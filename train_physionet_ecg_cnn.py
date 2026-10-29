"""Train an ECG-specific 1D CNN on real single-lead PhysioNet Challenge 2017 data.

The model classifies a short single-lead ECG as normal, AF, other rhythm, or
noisy. It must not be presented as a diagnosis and is not a substitute for
validation on the AD8232 hardware or the intended elderly-care population.

Usage:
  python train_physionet_ecg_cnn.py --data-dir data/physionet2017 --download
"""
from __future__ import annotations

import argparse
import csv
import json
import random
from pathlib import Path
from urllib.request import urlretrieve
from zipfile import BadZipFile, ZipFile

import numpy as np
import tensorflow as tf
import wfdb
from sklearn.metrics import classification_report, confusion_matrix, roc_auc_score
from sklearn.model_selection import train_test_split
from sklearn.utils.class_weight import compute_class_weight

RANDOM_SEED = 20260930
SAMPLE_RATE = 300
WINDOW_SECONDS = 10
WINDOW_SAMPLES = SAMPLE_RATE * WINDOW_SECONDS
LABELS = {"N": 0, "A": 1, "O": 2, "~": 3}
LABEL_NAMES = ["normal", "atrial_fibrillation", "other_rhythm", "noisy"]
TRAINING_ARCHIVE_URL = "https://physionet.org/files/challenge-2017/1.0.0/training2017.zip"


def set_seed() -> None:
    random.seed(RANDOM_SEED)
    np.random.seed(RANDOM_SEED)
    tf.keras.utils.set_random_seed(RANDOM_SEED)


def locate_reference(data_dir: Path) -> Path:
    candidates = sorted(data_dir.rglob("REFERENCE*.csv"))
    if not candidates:
        raise FileNotFoundError(f"No REFERENCE CSV under {data_dir}; run with --download first.")
    # PhysioNet's current archive supplies versioned references; v3 is preferred.
    return next((path for path in candidates if path.name == "REFERENCE-v3.csv"), candidates[-1])


def load_reference(data_dir: Path) -> tuple[Path, list[tuple[str, int]]]:
    reference = locate_reference(data_dir)
    rows: list[tuple[str, int]] = []
    with reference.open(newline="") as handle:
        for row in csv.reader(handle):
            if len(row) >= 2 and row[1] in LABELS:
                rows.append((row[0], LABELS[row[1]]))
    if len(rows) < 1_000:
        raise ValueError(f"Only {len(rows)} labelled records found; refusing to train on a subset.")
    return reference.parent, rows


def download_training_archive(data_dir: Path) -> None:
    """The Challenge archive has no WFDB RECORDS index, so download its official
    training ZIP rather than using wfdb.dl_database."""
    data_dir.mkdir(parents=True, exist_ok=True)
    archive = data_dir / "training2017.zip"
    if not archive.exists():
        print(f"Downloading official PhysioNet training archive to {archive}…")
        urlretrieve(TRAINING_ARCHIVE_URL, archive)
    else:
        print(f"Using existing archive {archive}; extracting it…")
    try:
        with ZipFile(archive) as zipped:
            if zipped.testzip() is not None:
                raise ValueError("archive CRC check failed")
            print("Extracting training archive…")
            zipped.extractall(data_dir)
    except (BadZipFile, ValueError, OSError) as error:
        raise RuntimeError(
            f"The archive is incomplete or damaged ({error}). Resume it with: "
            "curl.exe --retry 20 --retry-all-errors -L -C - -o "
            f"\"{archive}\" \"{TRAINING_ARCHIVE_URL}\""
        ) from error
    archive.unlink(missing_ok=True)


def standardize_window(signal: np.ndarray) -> np.ndarray:
    signal = np.asarray(signal, dtype=np.float32).reshape(-1)
    if signal.size == 0 or not np.isfinite(signal).all():
        raise ValueError("Invalid ECG waveform")
    if signal.size >= WINDOW_SAMPLES:
        start = (signal.size - WINDOW_SAMPLES) // 2
        window = signal[start:start + WINDOW_SAMPLES]
    else:
        window = np.pad(signal, (0, WINDOW_SAMPLES - signal.size), mode="edge")
    median = np.median(window)
    mad = np.median(np.abs(window - median))
    scale = max(float(mad * 1.4826), 1e-4)
    return np.clip((window - median) / scale, -8.0, 8.0)


def load_dataset(data_dir: Path, rows: list[tuple[str, int]]) -> tuple[np.ndarray, np.ndarray, list[str]]:
    features: list[np.ndarray] = []
    labels: list[int] = []
    record_ids: list[str] = []
    skipped = 0
    for index, (record_id, label) in enumerate(rows, start=1):
        try:
            record = wfdb.rdrecord(str(data_dir / record_id))
            features.append(standardize_window(record.p_signal[:, 0]))
            labels.append(label)
            record_ids.append(record_id)
        except Exception as error:  # keep audit trail; do not fabricate missing signals
            skipped += 1
            print(f"Skipping {record_id}: {error}")
        if index % 500 == 0:
            print(f"Loaded {index}/{len(rows)} records")
    if len(features) < 1_000:
        raise ValueError("Too few valid ECG records after validation")
    print(f"Validated {len(features)} records; skipped {skipped} invalid/missing records.")
    return np.stack(features)[..., np.newaxis], np.asarray(labels, dtype=np.int32), record_ids


def build_model() -> tf.keras.Model:
    inputs = tf.keras.Input(shape=(WINDOW_SAMPLES, 1), name="ecg")
    x = inputs
    for filters, kernel, stride in ((16, 11, 2), (32, 9, 2), (64, 7, 2), (96, 5, 2)):
        x = tf.keras.layers.Conv1D(filters, kernel, strides=stride, padding="same", use_bias=False)(x)
        x = tf.keras.layers.BatchNormalization()(x)
        x = tf.keras.layers.Activation("relu")(x)
        x = tf.keras.layers.SpatialDropout1D(0.10)(x)
    x = tf.keras.layers.GlobalAveragePooling1D()(x)
    x = tf.keras.layers.Dense(64, activation="relu")(x)
    x = tf.keras.layers.Dropout(0.25)(x)
    outputs = tf.keras.layers.Dense(len(LABEL_NAMES), activation="softmax", name="class_probability")(x)
    model = tf.keras.Model(inputs, outputs, name="physionet_single_lead_ecg_cnn")
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=1e-3),
        loss="sparse_categorical_crossentropy",
        # AUC requires one-hot labels; Keras receives sparse integer labels here.
        # Calculate multiclass ROC-AUC correctly after inference on the untouched
        # test set instead of using an incompatible training-time metric.
        metrics=["accuracy"],
    )
    return model


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-dir", type=Path, default=Path("data/physionet2017"))
    parser.add_argument("--output-dir", type=Path, default=Path("assets/ecg_cnn"))
    parser.add_argument("--download", action="store_true")
    parser.add_argument("--epochs", type=int, default=30)
    args = parser.parse_args()
    set_seed()
    if args.download:
        try:
            locate_reference(args.data_dir)
        except FileNotFoundError:
            download_training_archive(args.data_dir)

    record_dir, rows = load_reference(args.data_dir)
    X, y, record_ids = load_dataset(record_dir, rows)
    indices = np.arange(len(y))
    train_idx, test_idx = train_test_split(indices, test_size=0.20, stratify=y, random_state=RANDOM_SEED)
    train_idx, validation_idx = train_test_split(train_idx, test_size=0.25, stratify=y[train_idx], random_state=RANDOM_SEED)
    # 60% train, 20% validation, 20% completely unseen test.
    class_weights = compute_class_weight("balanced", classes=np.arange(len(LABEL_NAMES)), y=y[train_idx])
    output_dir = args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)
    model = build_model()
    callbacks = [
        tf.keras.callbacks.EarlyStopping(monitor="val_loss", patience=6, restore_best_weights=True),
        tf.keras.callbacks.ModelCheckpoint(str(output_dir / "best.keras"), monitor="val_loss", save_best_only=True),
    ]
    model.fit(
        X[train_idx], y[train_idx], validation_data=(X[validation_idx], y[validation_idx]),
        epochs=args.epochs, batch_size=64, class_weight=dict(enumerate(class_weights)), callbacks=callbacks, verbose=2,
    )
    probabilities = model.predict(X[test_idx], batch_size=128, verbose=0)
    predictions = probabilities.argmax(axis=1)
    report = classification_report(y[test_idx], predictions, target_names=LABEL_NAMES, output_dict=True, zero_division=0)
    metrics = {
        "source": "PhysioNet/Computing in Cardiology Challenge 2017 single-lead ECG",
        "source_records_validated": int(len(y)),
        "splits": {"train": int(len(train_idx)), "validation": int(len(validation_idx)), "test": int(len(test_idx))},
        "labels": LABEL_NAMES,
        "class_counts": {LABEL_NAMES[i]: int((y == i).sum()) for i in range(len(LABEL_NAMES))},
        "test_classification_report": report,
        "test_confusion_matrix": confusion_matrix(y[test_idx], predictions).tolist(),
        "test_macro_ovr_auc": float(roc_auc_score(tf.keras.utils.to_categorical(y[test_idx], len(LABEL_NAMES)), probabilities, multi_class="ovr", average="macro")),
        "limitations": [
            "Trained on AliveCor single-lead recordings, not the AD8232 hardware.",
            "This is ECG rhythm screening, not a diagnosis or a general health-risk predictor.",
            "External validation on held-out recordings from the intended device and setting is required before clinical deployment."
        ]
    }
    (output_dir / "metrics.json").write_text(json.dumps(metrics, indent=2))
    model.save(output_dir / "ecg_cnn.keras")
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    (output_dir / "ecg_cnn.tflite").write_bytes(converter.convert())
    (output_dir / "model_contract.json").write_text(json.dumps({
        "input_shape": [1, WINDOW_SAMPLES, 1], "sample_rate_hz": SAMPLE_RATE,
        "window_seconds": WINDOW_SECONDS, "labels": LABEL_NAMES,
        "preprocessing": "central 10-second window, median/MAD normalization, clipped [-8, 8]"
    }, indent=2))
    print(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
