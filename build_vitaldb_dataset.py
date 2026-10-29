"""Build the complete, reproducible VitalDB sensor-feature cohort.

This downloads all eligible cases (never an arbitrary small subset), derives
features from the final monitored window, validates them, and checkpoints each
case so interrupted downloads can resume.  VitalDB is intra-operative data;
the output must not be represented as a home-care diagnostic dataset.
"""
from __future__ import annotations

import argparse
import json
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import numpy as np
import pandas as pd
import vitaldb

TRACKS = ["Solar8000/HR", "Solar8000/PLETH_SPO2", "Solar8000/BT"]
ECG_TRACK = "SNUADC/ECG_II"
FEATURE_COLUMNS = ["hr_mean", "hr_std", "spo2_mean", "spo2_std", "temp_mean", "temp_std", "ecg_bpm", "ecg_rr_cv"]


def ecg_features(signal: np.ndarray, sample_rate: int) -> tuple[float, float] | None:
    values = signal[np.isfinite(signal)]
    if len(values) < sample_rate * 10:
        return None
    mean, std = float(values.mean()), float(values.std())
    if std < 1e-6:
        return None
    threshold, gap = mean + 0.8 * std, int(sample_rate * 0.30)
    peaks, index = [], 1
    while index < len(values) - 1:
        if values[index] > threshold and values[index] >= values[index - 1] and values[index] >= values[index + 1]:
            peaks.append(index)
            index += gap
        else:
            index += 1
    if len(peaks) < 3:
        return None
    intervals = np.diff(peaks).astype(float)
    bpm = sample_rate * 60.0 / intervals.mean()
    rr_cv = intervals.std() / intervals.mean()
    return float(bpm), float(rr_cv)


def valid_feature_row(row: dict[str, float]) -> bool:
    bands = {
        "hr_mean": (25, 230), "hr_std": (0, 80), "spo2_mean": (50, 100), "spo2_std": (0, 25),
        "temp_mean": (25, 43), "temp_std": (0, 8), "ecg_bpm": (25, 230), "ecg_rr_cv": (0, 2),
    }
    return all(np.isfinite(row[name]) and low <= row[name] <= high for name, (low, high) in bands.items())


def feature_row(case_id: int, subject_id: int, adverse: int, sample_rate: int, window_seconds: int) -> dict[str, float] | None:
    numeric = vitaldb.load_case(case_id, TRACKS, interval=2.0)
    ecg = vitaldb.load_case(case_id, [ECG_TRACK], interval=1.0 / sample_rate)
    if numeric is None or ecg is None or len(numeric) == 0 or len(ecg) == 0:
        return None
    numeric = numeric[-max(1, window_seconds // 2):]
    def stats(column: np.ndarray) -> tuple[float, float] | None:
        column = column[np.isfinite(column)]
        return (float(column.mean()), float(column.std())) if len(column) >= 20 else None
    hr, spo2, temp = (stats(numeric[:, i]) for i in range(3))
    ecg_stats = ecg_features(ecg[-window_seconds * sample_rate:, 0], sample_rate)
    if not all((hr, spo2, temp, ecg_stats)):
        return None
    row = dict(caseid=case_id, subjectid=subject_id, hr_mean=hr[0], hr_std=hr[1], spo2_mean=spo2[0], spo2_std=spo2[1],
               temp_mean=temp[0], temp_std=temp[1], ecg_bpm=ecg_stats[0], ecg_rr_cv=ecg_stats[1], adverse=adverse)
    return row if valid_feature_row(row) else None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="data/vitaldb_complete_features.csv")
    parser.add_argument("--checkpoint", default="data/vitaldb_complete_features.checkpoint.jsonl")
    parser.add_argument("--window-seconds", type=int, default=1800)
    parser.add_argument("--ecg-sample-rate", type=int, default=100)
    parser.add_argument("--workers", type=int, default=4, help="Bounded concurrent case downloads.")
    parser.add_argument("--clinical-csv", default=str(Path(__file__).with_name("cases_debug.csv")))
    args = parser.parse_args()
    output, checkpoint = Path(args.output), Path(args.checkpoint)
    output.parent.mkdir(parents=True, exist_ok=True)
    completed: dict[int, dict] = {}
    if checkpoint.exists():
        for line in checkpoint.read_text(encoding="utf-8").splitlines():
            item = json.loads(line)
            completed[item["caseid"]] = item
    clinical_path = Path(args.clinical_csv)
    clinical = pd.read_csv(clinical_path) if clinical_path.exists() else vitaldb.load_clinical_data()
    if clinical.empty:
        raise RuntimeError("Clinical outcome table is empty. Provide a complete cases CSV with --clinical-csv.")
    clinical["caseid"] = clinical.caseid.astype(int)
    clinical["adverse"] = ((clinical.death_inhosp.fillna(0).eq(1)) | (clinical.icu_days.fillna(0).gt(0))).astype(int)
    labels = clinical.set_index("caseid").adverse.to_dict()
    subjects = clinical.set_index("caseid").subjectid.to_dict()
    for item in completed.values():
        if "adverse" in item and "subjectid" not in item:
            item["subjectid"] = int(subjects[item["caseid"]])
    candidate_ids = sorted(set(map(int, vitaldb.find_cases(",".join(TRACKS + [ECG_TRACK])))) & set(labels))
    print(f"Eligible four-sensor cases: {len(candidate_ids)}; resumed: {len(completed)}")
    pending = [case_id for case_id in candidate_ids if case_id not in completed]
    def extract(case_id: int) -> tuple[int, dict]:
        try:
            row = feature_row(case_id, int(subjects[case_id]), int(labels[case_id]), args.ecg_sample_rate, args.window_seconds)
            return case_id, row or {"caseid": case_id, "discarded": True}
        except Exception as error:
            return case_id, {"caseid": case_id, "error": str(error)[:200]}
    with checkpoint.open("a", encoding="utf-8") as stream, ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        futures = [pool.submit(extract, case_id) for case_id in pending]
        for number, future in enumerate(as_completed(futures), 1):
            case_id, result = future.result()
            completed[case_id] = result
            stream.write(json.dumps(result) + "\n")
            stream.flush()
            if "adverse" in result:
                completed[case_id] = result
            if number % 25 == 0:
                pd.DataFrame([row for row in completed.values() if "adverse" in row]).to_csv(output, index=False)
                print(f"{number}/{len(pending)} new cases checked; {sum('adverse' in row for row in completed.values())} valid rows")
    dataset = pd.DataFrame([row for row in completed.values() if "adverse" in row], columns=["caseid", "subjectid", *FEATURE_COLUMNS, "adverse"])
    dataset.to_csv(output, index=False)
    print(f"Saved {len(dataset)} valid cases to {output}; positives={int(dataset['adverse'].sum()) if len(dataset) else 0}")


if __name__ == "__main__":
    main()
