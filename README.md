# GeriatriCare

[![Android CI](https://github.com/jetcingjacob2005/geriatriccare/actions/workflows/android-ci.yml/badge.svg)](https://github.com/jetcingjacob2005/geriatriccare/actions/workflows/android-ci.yml)
[![ML unit tests](https://github.com/jetcingjacob2005/geriatriccare/actions/workflows/ml-tests.yml/badge.svg)](https://github.com/jetcingjacob2005/geriatriccare/actions/workflows/ml-tests.yml)

**AI-Powered Wearable Health Monitoring and Risk Analysis System**

GeriatriCare combines an ESP32 wearable sensor stream, an Android application, and an on-device screening model for elderly-care monitoring. The app displays heart rate, SpO2, body temperature, and a single-lead ECG waveform. Patient and doctor dashboards present readings and history. Screening is conditional on signal quality and a sufficient vital-sign window; it is not a medical diagnosis.

## Team

| Member | Roll number | GitHub | Responsibility |
| --- | --- | --- | --- |
| Abhinesh R | B23CS2105 | [Abhinesh-R](https://github.com/Abhinesh-R) | AI risk analysis and ML integration |
| Abiya George | B23CS2108 | [Abiya48](https://github.com/Abiya48) | Doctor dashboard |
| Arathy Krishna S B | B23CS2117 | [Arathy294](https://github.com/Arathy294) | Sensor monitoring and data visualization |
| Jetcin G Jacob | B23CS2134 | [jetcingjacob2005](https://github.com/jetcingjacob2005) | Patient dashboard |

These are project responsibilities. Git commit authorship and PR reviews must be read from GitHub records rather than inferred from this table.

## Repository layout

- `app/`: Kotlin Android application using Jetpack Compose, BLE, Firebase, Room, and Hilt.
- `app/src/test/`: JUnit tests for BLE packets, ECG windows, screening, location, and explanations.
- `geriatricare.ino`: ESP32 firmware snapshot from the original upload.
- Root Python scripts: VitalDB feature extraction, XGBoost training/export, ECG CNN research, and verification.
- `.github/workflows/`: repository and Android checks.

## Run the Android application

1. Install Android Studio with Android SDK 35 and JDK 17. Use Gradle 8.7, as specified in `gradle/wrapper/gradle-wrapper.properties`.
2. Supply your own `app/google-services.json` from your Firebase project. This file is ignored by Git. For Maps, set `MAPS_API_KEY` in ignored `local.properties`.
3. Open the repository in Android Studio, sync Gradle, and run the `app` configuration on Android 8.0 or later.
4. Flash the ESP32 firmware and connect the wearable over BLE from the patient dashboard. The firmware and Android source must be validated together on the target hardware.

The repository does not include Firebase credentials, signing keys, raw datasets, or trained model binaries. A development build can use a local Firebase configuration; the CI workflow writes a clearly fake configuration only to exercise compilation and unit tests.

## Test and CI

Android tests: `gradle testDebugUnitTest`. Debug build: `gradle assembleDebug`. The `Android CI` workflow runs both on pull requests when the source is present. The `Repository checks` workflow compiles the public Python scripts and verifies documentation files. Check the Actions page for actual run conclusions; a successful Python syntax check is not evidence of an Android build.

## ML training and limitations

The XGBoost screening model uses 30-minute summaries of heart rate, SpO2, temperature, ECG-derived rate, and RR variability. Build the complete VitalDB feature cohort with `python build_vitaldb_dataset.py --workers 4`, then train with `python train_xgboost_risk_model.py --data data/vitaldb_complete_features.csv --out assets/xgboost`. `python verify_xgboost_export.py` compares the portable tree evaluator with the native model when the private/generated artifacts are available. The training script requires at least 1,000 cases and 100 positive labels and uses patient-level train, validation, and test separation.

An experimental 1D ECG CNN exists in the research scripts. Its status should not be confused with the Android app's current heuristic ECG timing path. The system is for screening and project validation, not clinical diagnosis.

## Repository hygiene

`.gitignore` excludes future local configuration, credentials, datasets, build outputs, and model artifacts. Ignore rules do not remove files already tracked in Git history. Review the earlier upload of `local.properties` and CSV files separately and rotate any exposed key if one was committed.
