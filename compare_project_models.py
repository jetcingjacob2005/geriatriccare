"""Create a reproducible five-model comparison from held-out project results.

The three tabular models share the same VitalDB patient-level split and feature
set.  The ECG CNN is reported from its separate PhysioNet rhythm-classification
test set; it is not ranked head-to-head with the tabular outcome models.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, precision_recall_fscore_support, roc_auc_score
from sklearn.neural_network import MLPClassifier
from sklearn.model_selection import StratifiedGroupKFold
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler


FEATURES = [
    "hr_mean", "hr_std", "spo2_mean", "spo2_std",
    "temp_mean", "temp_std", "ecg_bpm", "ecg_rr_cv",
]


def split_indices(data: pd.DataFrame) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    groups = data["subjectid"] if "subjectid" in data else data["caseid"]
    outer = list(StratifiedGroupKFold(n_splits=5, shuffle=True, random_state=2026)
                 .split(data[FEATURES], data.adverse, groups))
    test_idx = outer[0][1]
    remaining = data.drop(index=data.index[test_idx])
    remaining_groups = groups.drop(index=data.index[test_idx])
    inner = list(StratifiedGroupKFold(n_splits=4, shuffle=True, random_state=2027)
                 .split(remaining[FEATURES], remaining.adverse, remaining_groups))
    validation_labels = remaining.index[inner[0][1]]
    validation_idx = data.index.get_indexer(validation_labels)
    train_idx = data.index.get_indexer(remaining.index.difference(validation_labels))
    return train_idx, validation_idx, test_idx


def sensitivity_threshold(labels: pd.Series, probabilities: np.ndarray) -> float:
    candidates = sorted(set(map(float, probabilities)))
    acceptable: list[tuple[float, float]] = []
    for threshold in candidates:
        predicted = probabilities >= threshold
        true_positive = int(((labels.to_numpy() == 1) & predicted).sum())
        false_negative = int(((labels.to_numpy() == 1) & ~predicted).sum())
        recall = true_positive / (true_positive + false_negative) if true_positive + false_negative else 0.0
        if recall >= 0.90:
            positive_predictions = int(predicted.sum())
            precision = true_positive / positive_predictions if positive_predictions else 0.0
            acceptable.append((precision, threshold))
    return max(acceptable)[1] if acceptable else 0.5


def tabular_result(name: str, model, x_validation, y_validation, x_test, y_test,
                    fixed_threshold: float | None = None) -> dict:
    validation_probability = model.predict_proba(x_validation)[:, 1]
    threshold = fixed_threshold if fixed_threshold is not None else sensitivity_threshold(y_validation, validation_probability)
    probability = model.predict_proba(x_test)[:, 1]
    prediction = (probability >= threshold).astype(int)
    precision, recall, f1, _ = precision_recall_fscore_support(
        y_test, prediction, average="macro", zero_division=0
    )
    return {
        "model": name,
        "task": "Multisensor adverse-risk screening",
        "test_records": int(len(y_test)),
        "accuracy": float(accuracy_score(y_test, prediction)),
        "precision_macro": float(precision),
        "recall_macro": float(recall),
        "f1_macro": float(f1),
        "auc": float(roc_auc_score(y_test, probability)),
        "decision_threshold": float(threshold),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", default="data/vitaldb_complete_features.csv")
    parser.add_argument("--xgboost-model", default="assets/xgboost/tabular_risk_xgboost.joblib")
    parser.add_argument("--xgboost-metrics", default="assets/xgboost/tabular_risk_metrics.json")
    parser.add_argument("--cnn-metrics", default="assets/ecg_cnn/metrics.json")
    parser.add_argument("--output", default="../docs/model_comparison.json")
    args = parser.parse_args()

    data = pd.read_csv(args.data).dropna(subset=FEATURES + ["adverse"]).copy()
    data["adverse"] = data.adverse.astype(int)
    train_idx, validation_idx, test_idx = split_indices(data)
    x_train, y_train = data.iloc[train_idx][FEATURES], data.iloc[train_idx].adverse
    x_validation, y_validation = data.iloc[validation_idx][FEATURES], data.iloc[validation_idx].adverse
    x_test, y_test = data.iloc[test_idx][FEATURES], data.iloc[test_idx].adverse

    logistic = make_pipeline(
        StandardScaler(),
        LogisticRegression(max_iter=3000, class_weight="balanced", random_state=2026),
    ).fit(x_train, y_train)
    forest = RandomForestClassifier(
        n_estimators=500, max_depth=8, min_samples_leaf=3,
        class_weight="balanced", random_state=2026, n_jobs=-1,
    ).fit(x_train, y_train)
    mlp = make_pipeline(
        StandardScaler(),
        MLPClassifier(
            hidden_layer_sizes=(64, 32), activation="relu", solver="adam",
            alpha=0.001, learning_rate_init=0.001, max_iter=500,
            early_stopping=True, validation_fraction=0.2,
            n_iter_no_change=30, random_state=2026,
        ),
    )
    positive_weight = (len(y_train) - int(y_train.sum())) / max(1, int(y_train.sum()))
    sample_weight = np.where(y_train.to_numpy() == 1, positive_weight, 1.0)
    mlp.fit(x_train, y_train, mlpclassifier__sample_weight=sample_weight)
    xgboost = joblib.load(args.xgboost_model)
    xgboost_threshold = float(json.loads(Path(args.xgboost_metrics).read_text(encoding="utf-8"))["threshold"])

    results = [
        tabular_result("Logistic Regression", logistic, x_validation, y_validation, x_test, y_test),
        tabular_result("Random Forest", forest, x_validation, y_validation, x_test, y_test),
        tabular_result("MLP", mlp, x_validation, y_validation, x_test, y_test),
        tabular_result("XGBoost", xgboost, x_validation, y_validation, x_test, y_test, xgboost_threshold),
    ]

    cnn = json.loads(Path(args.cnn_metrics).read_text(encoding="utf-8"))
    report = cnn["test_classification_report"]
    results.append({
        "model": "1D CNN",
        "task": "Single-lead ECG rhythm classification",
        "test_records": int(cnn["splits"]["test"]),
        "accuracy": float(report["accuracy"]),
        "precision_macro": float(report["macro avg"]["precision"]),
        "recall_macro": float(report["macro avg"]["recall"]),
        "f1_macro": float(report["macro avg"]["f1-score"]),
        "auc": float(cnn["test_macro_ovr_auc"]),
        "decision_threshold": None,
    })

    artifact = {
        "metric_definition": "Macro precision, recall and F1; ROC-AUC (macro one-vs-rest for CNN)",
        "tabular_split": "VitalDB patient-level 60/20/20; thresholds selected on validation data with screening recall priority",
        "cnn_split": "PhysioNet/CinC 2017 record-level 60/20/20 split saved by the ECG training pipeline",
        "warning": "The CNN and tabular models solve different tasks and their scores must not be used as a head-to-head ranking.",
        "models": results,
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(artifact, indent=2), encoding="utf-8")
    print(json.dumps(artifact, indent=2))


if __name__ == "__main__":
    main()
