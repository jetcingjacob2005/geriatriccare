"""Check deployed reference provenance, portable parity, and SHAP additivity.
No data is used to retrain or change the existing model or test metrics.
"""
import json
from math import comb, exp
from pathlib import Path
import joblib
import pandas as pd
import numpy as np
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from export_explanation_reference import reference, FEATURES

root = Path(__file__).resolve().parent
deployed = json.loads((root.parent / "app/src/main/assets/screening_explanation_reference.json").read_text())
assert deployed["reference"] == reference()["reference"], "Reference differs from training-only medians"
trees = json.loads((root / "assets/xgboost/tabular_risk_tree_dump.json").read_text())
model = joblib.load(root / "assets/xgboost/tabular_risk_xgboost.joblib")
baseline = [deployed["reference"][f] for f in FEATURES]

def node_value(node, inputs):
    if "leaf" in node:
        return node["leaf"]
    branch = node["yes"] if np.float32(inputs[FEATURES.index(node["split"])]) < np.float32(node["split_condition"]) else node["no"]
    return node_value(next(child for child in node["children"] if child["nodeid"] == branch), inputs)

def margin(inputs):
    return sum(node_value(tree, inputs) for tree in trees)

rows = pd.read_csv(root / "data/vitaldb_complete_features.csv")[FEATURES].dropna().iloc[::200].head(6)
max_error = 0.0
max_parity = 0.0
for index, (_, row) in enumerate(rows.iterrows()):
    values = list(row)
    coalition = [margin([values[i] if mask & (1 << i) else baseline[i] for i in range(8)]) for mask in range(256)]
    phi = [sum((coalition[mask | (1 << i)] - coalition[mask]) / (8 * comb(7, mask.bit_count()))
               for mask in range(256) if not mask & (1 << i)) for i in range(8)]
    error = abs(coalition[0] + sum(phi) - coalition[255])
    probability = 1 / (1 + exp(-coalition[255]))
    native = model.predict_proba(row.to_frame().T)[0, 1]
    max_error = max(error, max_error)
    max_parity = max(abs(float(native) - probability), max_parity)
assert max_error < 1e-8
assert max_parity < 1e-3, f"Native probability mismatch: {max_parity}"
print(f"Verified {len(rows)} real input rows: margin reconstruction error {max_error:.12g}, native probability difference {max_parity:.12g}")
