# GeriatriCare model training

This directory builds an offline XGBoost screening model from VitalDB's public
intra-operative data. It is not a medical diagnostic model and must not be
presented as one.

## Rebuild the dataset

```powershell
python build_vitaldb_dataset.py --workers 4
```

The extractor resumes from `data/vitaldb_complete_features.checkpoint.jsonl`.
It accepts only cases with validated HR, SpO2, body temperature, and ECG-II
segments. The output includes case and subject identifiers so training can keep
subjects separate between train, validation, and test sets.

## Train and evaluate

```powershell
python train_xgboost_risk_model.py --data data/vitaldb_complete_features.csv --out assets/xgboost
python verify_xgboost_export.py
Copy-Item assets/xgboost/tabular_risk_tree_dump.json, assets/xgboost/tabular_risk_metrics.json ../app/src/main/assets/
```

The training script refuses to train if the feature cohort has fewer than 1,000
cases or fewer than 100 positive labels. It uses a patient-level 60/20/20
train/validation/test split, selects a validation threshold that prioritises
sensitivity, and saves the final held-out metrics. The Android app evaluates
the exported trees locally; it never retrains on startup.

## ECG limitation

VitalDB supplies ECG waveforms, but its checked outcome label is case-level
death/ICU outcome, not a rhythm annotation. The tabular model therefore uses
ECG-derived rate and RR-variability features. A 1D CNN must only be trained
after acquiring waveform data with compatible rhythm labels and a subject-wise
evaluation protocol.
