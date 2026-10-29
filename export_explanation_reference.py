"""Export training-only reference inputs for exact, single-reference Shapley values.

Reconstructs the original patient split; never uses validation/test rows to set
the background. This explains model differences, not causal medical effects.
"""
import json
from pathlib import Path
import pandas as pd
from sklearn.model_selection import StratifiedGroupKFold

FEATURES = ["hr_mean", "hr_std", "spo2_mean", "spo2_std", "temp_mean", "temp_std", "ecg_bpm", "ecg_rr_cv"]

def reference():
    root = Path(__file__).resolve().parent
    data = pd.read_csv(root / "data/vitaldb_complete_features.csv").dropna(subset=FEATURES + ["adverse"]).copy()
    groups = data["subjectid"] if "subjectid" in data else data["caseid"]
    test = list(StratifiedGroupKFold(5, shuffle=True, random_state=2026).split(data[FEATURES], data.adverse, groups))[0][1]
    remaining = data.drop(index=data.index[test])
    validation = list(StratifiedGroupKFold(4, shuffle=True, random_state=2027).split(
        remaining[FEATURES], remaining.adverse, groups.loc[remaining.index]))[0][1]
    train = remaining.drop(index=remaining.index[validation])
    return {"method": "exact_single_reference_shapley", "units": "log_odds",
            "reference_source": "Feature medians from the original training partition only",
            "training_rows": len(train), "features": FEATURES,
            "reference": {name: float(train[name].median()) for name in FEATURES}}

if __name__ == "__main__":
    print(json.dumps(reference(), indent=2))
