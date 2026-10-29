"""Train and evaluate the on-device tabular risk model from the complete feature cohort.

The held-out test set is never used for tuning.  This is an AI-assisted risk
screening model, not a diagnostic system; it refuses to train when labels are
too sparse for meaningful validation.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import joblib
import pandas as pd
from sklearn.metrics import (average_precision_score, classification_report, confusion_matrix,
                             precision_recall_curve, roc_auc_score)
from sklearn.model_selection import StratifiedGroupKFold
from xgboost import XGBClassifier

FEATURES = ["hr_mean", "hr_std", "spo2_mean", "spo2_std", "temp_mean", "temp_std", "ecg_bpm", "ecg_rr_cv"]


def safe_metric(metric, y, score):
    return float(metric(y, score)) if len(set(y)) == 2 else None


def choose_threshold(y, probabilities):
    precision, recall, thresholds = precision_recall_curve(y, probabilities)
    # Screening: maintain at least 90% validation recall where possible, then maximise precision.
    candidates = [(p, r, t) for p, r, t in zip(precision[:-1], recall[:-1], thresholds) if r >= 0.90]
    return float(max(candidates, key=lambda item: item[0])[2]) if candidates else 0.50


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", default="data/vitaldb_complete_features.csv")
    parser.add_argument("--out", default="assets")
    args = parser.parse_args()
    data = pd.read_csv(args.data)
    required = set(FEATURES + ["adverse"])
    if missing := required - set(data):
        raise ValueError(f"Missing columns: {sorted(missing)}")
    data = data.dropna(subset=FEATURES + ["adverse"]).copy()
    data["adverse"] = data.adverse.astype(int)
    if len(data) < 1000 or data.adverse.sum() < 100:
        raise ValueError(f"Refusing unreliable training: {len(data)} rows and {data.adverse.sum()} positive labels. Build the full cohort first.")
    groups = data["subjectid"] if "subjectid" in data else data["caseid"]
    folds = StratifiedGroupKFold(n_splits=5, shuffle=True, random_state=2026)
    train_idx, validation_idx, test_idx = None, None, None
    # Three disjoint patient-level folds: 60/20/20.
    splits = list(folds.split(data[FEATURES], data.adverse, groups))
    test_idx = splits[0][1]
    remaining = data.drop(index=data.index[test_idx])
    remaining_groups = groups.drop(index=data.index[test_idx])
    sub_splits = list(StratifiedGroupKFold(n_splits=4, shuffle=True, random_state=2027).split(remaining[FEATURES], remaining.adverse, remaining_groups))
    validation_labels = remaining.index[sub_splits[0][1]]
    validation_idx = data.index.get_indexer(validation_labels)
    train_idx = data.index.get_indexer(remaining.index.difference(validation_labels))
    x_train, y_train = data.iloc[train_idx][FEATURES], data.iloc[train_idx].adverse
    x_val, y_val = data.iloc[validation_idx][FEATURES], data.iloc[validation_idx].adverse
    x_test, y_test = data.iloc[test_idx][FEATURES], data.iloc[test_idx].adverse
    positive_weight = (len(y_train) - y_train.sum()) / y_train.sum()
    model = XGBClassifier(n_estimators=500, max_depth=4, learning_rate=0.03, subsample=0.85,
        colsample_bytree=0.85, min_child_weight=5, reg_lambda=3.0, reg_alpha=0.1,
        objective="binary:logistic", eval_metric="logloss", scale_pos_weight=positive_weight,
        random_state=2026, n_jobs=4)
    model.fit(x_train, y_train, eval_set=[(x_val, y_val)], verbose=False)
    threshold = choose_threshold(y_val, model.predict_proba(x_val)[:, 1])
    probability = model.predict_proba(x_test)[:, 1]
    prediction = (probability >= threshold).astype(int)
    tn, fp, fn, tp = confusion_matrix(y_test, prediction, labels=[0, 1]).ravel()
    metrics = {
        "dataset_rows": int(len(data)), "positive_rows": int(data.adverse.sum()), "split": "patient-level 60/20/20",
        "threshold": threshold, "roc_auc": safe_metric(roc_auc_score, y_test, probability),
        "average_precision": safe_metric(average_precision_score, y_test, probability),
        "sensitivity": tp / (tp + fn) if tp + fn else None, "specificity": tn / (tn + fp) if tn + fp else None,
        "confusion_matrix": {"tn": int(tn), "fp": int(fp), "fn": int(fn), "tp": int(tp)},
        "classification_report": classification_report(y_test, prediction, output_dict=True, zero_division=0),
        "feature_importance_gain": dict(zip(FEATURES, map(float, model.feature_importances_))),
        "data_note": "VitalDB intra-operative monitoring cohort; not a home-care diagnostic model.",
    }
    out = Path(args.out); out.mkdir(parents=True, exist_ok=True)
    model.save_model(out / "tabular_risk_xgboost.json")
    (out / "tabular_risk_tree_dump.json").write_text(json.dumps([json.loads(tree) for tree in model.get_booster().get_dump(dump_format="json")]), encoding="utf-8")
    (out / "tabular_risk_metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    joblib.dump(model, out / "tabular_risk_xgboost.joblib")
    print(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
