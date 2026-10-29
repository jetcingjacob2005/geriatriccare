"""Fast synthetic-data checks for the tabular screening training contract."""

import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import pandas as pd
from sklearn.metrics import roc_auc_score

from train_xgboost_risk_model import FEATURES, choose_threshold, main, safe_metric


class TrainingContractTests(unittest.TestCase):
    def test_feature_contract_contains_four_vital_groups(self):
        self.assertEqual(
            FEATURES,
            ["hr_mean", "hr_std", "spo2_mean", "spo2_std",
             "temp_mean", "temp_std", "ecg_bpm", "ecg_rr_cv"],
        )

    def test_single_class_metric_is_not_reported_as_auc(self):
        self.assertIsNone(safe_metric(roc_auc_score, [0, 0], [0.1, 0.2]))

    def test_two_class_metric_is_computed(self):
        self.assertAlmostEqual(
            safe_metric(roc_auc_score, [0, 1], [0.1, 0.9]), 1.0
        )

    def test_threshold_prioritises_high_recall(self):
        threshold = choose_threshold([0, 0, 1, 1], [0.1, 0.2, 0.8, 0.9])
        self.assertAlmostEqual(threshold, 0.8)

    def test_training_rejects_missing_feature_columns(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "incomplete.csv"
            pd.DataFrame({"hr_mean": [75], "adverse": [0]}).to_csv(source, index=False)
            with patch.object(sys, "argv", ["train", "--data", str(source)]):
                with self.assertRaisesRegex(ValueError, "Missing columns"):
                    main()

    def test_training_rejects_tiny_cohort(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "tiny.csv"
            rows = [{**{name: 1.0 for name in FEATURES},
                     "adverse": label, "caseid": case}
                    for case, label in enumerate([0, 1])]
            pd.DataFrame(rows).to_csv(source, index=False)
            with patch.object(sys, "argv", ["train", "--data", str(source)]):
                with self.assertRaisesRegex(ValueError, "Refusing unreliable training"):
                    main()


if __name__ == "__main__":
    unittest.main()
