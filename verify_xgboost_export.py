"""Verify that the portable JSON tree evaluator matches XGBoost probabilities."""
import json
from math import exp
from pathlib import Path

import joblib
import pandas as pd
import numpy as np

FEATURES = ["hr_mean", "hr_std", "spo2_mean", "spo2_std", "temp_mean", "temp_std", "ecg_bpm", "ecg_rr_cv"]


def evaluate(node, values):
    if "leaf" in node:
        return node["leaf"]
    child_id = node["yes"] if np.float32(values[node["split"]]) < np.float32(node["split_condition"]) else node["no"]
    return evaluate(next(child for child in node["children"] if child["nodeid"] == child_id), values)


root = Path(__file__).parent / "assets" / "xgboost"
trees = json.loads((root / "tabular_risk_tree_dump.json").read_text())
model = joblib.load(root / "tabular_risk_xgboost.joblib")
rows = pd.read_csv(Path(__file__).parent / "data" / "vitaldb_complete_features.csv").head(25)
portable = []
for _, row in rows.iterrows():
    score = sum(evaluate(tree, row) for tree in trees)
    portable.append(1 / (1 + exp(-score)))
native = model.predict_proba(rows[FEATURES])[:, 1]
maximum_difference = max(abs(a - b) for a, b in zip(portable, native))
print(f"portable evaluator maximum difference: {maximum_difference:.10f}")
print(f"first portable/native: {portable[0]:.10f} / {native[0]:.10f}")
if maximum_difference > 1e-3:
    raise SystemExit("Portable evaluator does not match XGBoost")
