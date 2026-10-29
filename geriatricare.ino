
/*
  GeriatriCare - ESP32-C3 Super Mini
  Sensors:
    AD8232   - ECG
    MAX30102 - Heart rate + SpO2
    DS18B20  - Temperature
  Communication:
    BLE UART-style service for Android app
  Example wiring (verify your board pinout):
    AD8232 OUTPUT -> GPIO 0
    AD8232 LO+    -> GPIO 3
    AD8232 LO-    -> GPIO 10

    MAX30102 SDA  -> GPIO 6
    MAX30102 SCL  -> GPIO 7

    DS18B20 DATA  -> GPIO 4
    4.7k resistor between DATA and 3.3V

  Libraries:
    - SparkFun MAX3010x Pulse and Proximity Sensor Library
    - OneWire
    - DallasTemperature
    - ESP32 BLE Arduino compatible with your ESP32 Arduino core

  Arduino IDE:
    Board: ESP32C3 Dev Module
    USB CDC On Boot: Enabled if using native USB serial
*/

#include <Arduino.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

#include <OneWire.h>
#include <DallasTemperature.h>
#include <Wire.h>
#include "MAX30105.h"
#include "spo2_algorithm.h"

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ---------------- Pin definitions ----------------

#define ECG_PIN       0
#define LO_PLUS       3
#define LO_MINUS      10
#define TEMP_PIN      4

#define I2C_SDA       6
#define I2C_SCL       7

// ---------------- BLE UUIDs ----------------

#define SERVICE_UUID \
  "0000ffe0-0000-1000-8000-00805f9b34fb"

#define CHARACTERISTIC_UUID_TX \
  "0000ffe1-0000-1000-8000-00805f9b34fb"

// ---------------- BLE objects ----------------

BLEServer *pServer = nullptr;
BLECharacteristic *pTxCharacteristic = nullptr;

volatile bool deviceConnected = false;
volatile bool restartAdvertising = false;
const char *BLE_DEVICE_NAME = "GeriatriCare_ESP32C3";

// ---------------- Sensor objects ----------------

OneWire oneWire(TEMP_PIN);
DallasTemperature tempSensor(&oneWire);
MAX30105 particleSensor;

// ---------------- ECG sampling ----------------

const uint32_t ECG_INTERVAL_MS = 4;
// Eight signed 16-bit samples plus a four-byte header fit in one default
// 20-byte BLE notification. This avoids fragmented JSON packets, which were
// easily dropped on phones using the default BLE MTU.
const int ECG_BATCH_SIZE = 8;

struct EcgSample {
  int value;
  uint32_t sequence;
  uint32_t timeMs;
  bool timing;
};

QueueHandle_t ecgQueue = nullptr;

// ---------------- MAX30102 buffers ----------------

const int32_t SAMPLES_LEN = 100;

uint32_t irBuffer[SAMPLES_LEN];
uint32_t redBuffer[SAMPLES_LEN];

int32_t spo2Value = 0;
int8_t validSpO2 = 0;

int32_t heartRateVal = 0;
int8_t validHR = 0;

bool maxSensorReady = false;
bool opticalContact = false;
uint8_t opticalBadSampleCount = 0;

enum OpticalMeasurementState : uint8_t {
  SENSOR_NOT_FOUND = 0,
  NO_FINGER_OR_WEAK_SIGNAL = 1,
  SIGNAL_UNSTABLE = 2,
  MEASUREMENT_IN_PROGRESS = 3,
  VALID_MEASUREMENT = 4
};

OpticalMeasurementState opticalState = SENSOR_NOT_FOUND;

unsigned int opticalSamplesCollected = 0;
unsigned long lastOpticalSampleMs = 0;
unsigned long lastMaxInitAttemptMs = 0;
uint32_t latestIr = 0;
uint32_t latestRed = 0;
uint32_t latestIrAverage = 0;
uint32_t latestIrSpan = 0;
uint32_t latestRedSpan = 0;
int32_t latestAlgorithmHR = 0;
int32_t latestRobustHR = 0;
int32_t latestAlgorithmSpO2 = 0;

const uint8_t OPTICAL_RESULT_HISTORY = 5;
// Publish the first algorithm result instead of blanking the UI for another
// two calculation cycles after the 100-sample window is ready. The rolling
// median below still becomes progressively steadier as more results arrive.
const uint8_t OPTICAL_RESULTS_BEFORE_PUBLISH = 1;
int32_t acceptedHr[OPTICAL_RESULT_HISTORY] = {0};
int32_t acceptedSpO2[OPTICAL_RESULT_HISTORY] = {0};
uint8_t acceptedResultCount = 0;
unsigned long lastValidOpticalResultMs = 0;

// The Maxim algorithm can mark an occasional one-second window invalid even
// while a finger remains firmly present. Keep the last verified result for a
// short grace period in that case only. Actual finger loss still clears the
// values immediately in clearOpticalMeasurement().
const unsigned long OPTICAL_RESULT_GRACE_MS = 5000;
// At the configured effective 25 Hz rate, three consecutive bad samples are
// about 120 ms. This rejects a one-sample optical/I2C glitch without retaining
// readings when the finger is genuinely removed.
const uint8_t OPTICAL_BAD_SAMPLES_BEFORE_RELEASE = 3;

// The reference Maxim routine accepts peaks only four samples apart. At the
// effective 25 Hz rate, a secondary (dicrotic) PPG peak can therefore be
// counted as another heartbeat and report almost exactly twice the real rate.
// A second detector uses a 7-sample refractory distance and consistent peak
// intervals. It is used only to correct a clearly supported 2x harmonic, so a
// genuine rapid pulse is not automatically divided by two.
const uint8_t PPG_REFRACTORY_SAMPLES = 7;
const int32_t HARMONIC_RAW_HR_MIN = 145;

// MAX30102 breakout boards vary considerably in LED optics and raw DC level.
// Use hysteresis plus waveform variation rather than the SparkFun example's
// fixed 50,000-count gate, which rejects valid fingers on lower-output boards.
const uint32_t FINGER_PRESENT_IR_THRESHOLD = 5000;
const uint32_t FINGER_RELEASE_IR_THRESHOLD = 3000;
const uint32_t MIN_PPG_SPAN = 300;
const uint32_t OPTICAL_ADC_MAX = 262143;

// ---------------- Cached vital signs ----------------

float currentTemp = 0.0f;
float currentSpO2 = 0.0f;
float currentHR = 0.0f;

const unsigned long TEMP_INTERVAL_MS = 2000;
const unsigned long TEMP_CONVERSION_MS = 750;
const unsigned long SPO2_CALC_INTERVAL_MS = 1000;
const unsigned long VITALS_TX_INTERVAL_MS = 500;

unsigned long lastTempReqMs = 0;
unsigned long lastSpO2CalcMs = 0;
unsigned long lastVitalsTxMs = 0;

bool tempConversionPending = false;

// ---------------- BLE callbacks ----------------

class MyServerCallbacks : public BLEServerCallbacks {

  void onConnect(BLEServer *server) override {
    deviceConnected = true;
    Serial.println("BLE client connected");
  }

  void onDisconnect(BLEServer *server) override {
    deviceConnected = false;

    Serial.println("BLE client disconnected");
    // Restart from loop after the stack finishes disconnect processing.
    restartAdvertising = true;
  }
};

// ---------------- ECG sampling task ----------------

void sampleEcgTask(void *parameter) {

  TickType_t nextWake = xTaskGetTickCount();

  uint32_t sequence = 0;
  uint32_t previousMs = 0;
  bool firstSample = true;

  for (;;) {

    const uint32_t nowMs = millis();
    const uint32_t delta = nowMs - previousMs;

    // ESP32-C3 is single-core. BLE/I2C interrupts can occasionally delay one
    // 4 ms sample without meaning that the AD8232 is disconnected. Packet
    // timestamps and sequence numbers still protect the Android time base.
    const bool timingOK =
      firstSample || (delta >= 1 && delta <= 20);

    int value;

    if (digitalRead(LO_PLUS) == HIGH ||
        digitalRead(LO_MINUS) == HIGH) {

      value = -1;

    } else {

      value = map(
        analogRead(ECG_PIN),
        0,
        4095,
        0,
        1023
      );
    }

    EcgSample sample = {
      value,
      sequence++,
      nowMs,
      timingOK
    };

    // Preserve the newest ECG sample if the queue is full.
    if (xQueueSend(ecgQueue, &sample, 0) != pdPASS) {

      EcgSample discarded;

      xQueueReceive(ecgQueue, &discarded, 0);
      xQueueSend(ecgQueue, &sample, 0);
    }

    previousMs = nowMs;
    firstSample = false;

    vTaskDelayUntil(
      &nextWake,
      pdMS_TO_TICKS(ECG_INTERVAL_MS)
    );
  }
}

// ---------------- Compact BLE transport ----------------

void notifyPacket(const uint8_t *data, size_t length) {

  if (deviceConnected && pTxCharacteristic != nullptr) {
    pTxCharacteristic->setValue((uint8_t *)data, length);
    pTxCharacteristic->notify();
    // Give the controller time to queue the notification. ECG now needs about
    // 31 notifications/second instead of 100+ fragmented JSON notifications.
    delay(2);
  }
}

// ---------------- Send ECG batches ----------------

void drainEcg() {

  for (int batch = 0;
       batch < 3 &&
       uxQueueMessagesWaiting(ecgQueue) >= ECG_BATCH_SIZE;
       batch++) {

    EcgSample samples[ECG_BATCH_SIZE];

    bool timingOK = true;

    for (int i = 0; i < ECG_BATCH_SIZE; i++) {

      if (xQueueReceive(ecgQueue, &samples[i], 0) != pdPASS) {
        return;
      }

      timingOK = timingOK && samples[i].timing;

      if (i > 0 &&
          samples[i].sequence != samples[i - 1].sequence + 1) {
        timingOK = false;
      }
    }

    if (!deviceConnected) {
      continue;
    }

    // Binary ECG packet (20 bytes):
    // magic, flags, uint16 sequence, 8 little-endian int16 ADC samples.
    uint8_t packet[20] = {0};
    packet[0] = 0xE1;
    packet[1] = timingOK ? 0x01 : 0x00;
    const uint16_t sequence16 = (uint16_t)samples[0].sequence;
    packet[2] = (uint8_t)(sequence16 & 0xff);
    packet[3] = (uint8_t)(sequence16 >> 8);

    for (int i = 0; i < ECG_BATCH_SIZE; i++) {
      const int16_t value = (int16_t)samples[i].value;
      packet[4 + i * 2] = (uint8_t)(value & 0xff);
      packet[5 + i * 2] = (uint8_t)((uint16_t)value >> 8);
    }

    notifyPacket(packet, sizeof(packet));
  }
}

// ---------------- Reset optical readings ----------------

void clearOpticalMeasurement() {

  opticalContact = false;
  opticalBadSampleCount = 0;
  opticalSamplesCollected = 0;

  currentHR = 0.0f;
  currentSpO2 = 0.0f;

  validHR = 0;
  validSpO2 = 0;
  acceptedResultCount = 0;
  lastValidOpticalResultMs = 0;
  opticalState = maxSensorReady
    ? NO_FINGER_OR_WEAK_SIGNAL
    : SENSOR_NOT_FOUND;
}

void invalidateOpticalResult(OpticalMeasurementState state) {
  const bool canHoldRecentResult =
    state == SIGNAL_UNSTABLE &&
    opticalContact &&
    lastValidOpticalResultMs > 0 &&
    millis() - lastValidOpticalResultMs <= OPTICAL_RESULT_GRACE_MS &&
    currentHR >= 25.0f && currentHR <= 230.0f &&
    currentSpO2 >= 50.0f && currentSpO2 <= 100.0f;

  if (!canHoldRecentResult) {
    currentHR = 0.0f;
    currentSpO2 = 0.0f;
    validHR = 0;
    validSpO2 = 0;
    acceptedResultCount = 0;
    lastValidOpticalResultMs = 0;
  }
  opticalState = state;
}

const char *opticalStateName(OpticalMeasurementState state) {
  switch (state) {
    case SENSOR_NOT_FOUND: return "SENSOR_NOT_FOUND";
    case NO_FINGER_OR_WEAK_SIGNAL: return "NO_FINGER_OR_WEAK_SIGNAL";
    case SIGNAL_UNSTABLE: return "SIGNAL_UNSTABLE";
    case MEASUREMENT_IN_PROGRESS: return "MEASUREMENT_IN_PROGRESS";
    case VALID_MEASUREMENT: return "VALID_MEASUREMENT";
    default: return "UNKNOWN";
  }
}

int32_t medianResult(const int32_t *values, uint8_t count) {
  int32_t sorted[OPTICAL_RESULT_HISTORY];
  for (uint8_t i = 0; i < count; i++) sorted[i] = values[i];
  for (uint8_t i = 1; i < count; i++) {
    const int32_t value = sorted[i];
    int8_t j = (int8_t)i - 1;
    while (j >= 0 && sorted[j] > value) {
      sorted[j + 1] = sorted[j];
      j--;
    }
    sorted[j + 1] = value;
  }
  return sorted[count / 2];
}

void addAcceptedOpticalResult(int32_t hr, int32_t spo2) {
  if (acceptedResultCount < OPTICAL_RESULT_HISTORY) {
    acceptedHr[acceptedResultCount] = hr;
    acceptedSpO2[acceptedResultCount] = spo2;
    acceptedResultCount++;
  } else {
    for (uint8_t i = 1; i < OPTICAL_RESULT_HISTORY; i++) {
      acceptedHr[i - 1] = acceptedHr[i];
      acceptedSpO2[i - 1] = acceptedSpO2[i];
    }
    acceptedHr[OPTICAL_RESULT_HISTORY - 1] = hr;
    acceptedSpO2[OPTICAL_RESULT_HISTORY - 1] = spo2;
  }
}

// Independently estimate pulse rate from prominent valleys in the IR PPG.
// This follows the same DC removal/inversion and four-point smoothing used by
// the Maxim reference algorithm, but rejects peaks that are too close together
// and uses the median interval to discard isolated motion/noise peaks.
int32_t calculateRobustPpgHeartRate() {
  int32_t filtered[SAMPLES_LEN];
  int32_t peakLocations[15] = {0};
  int32_t intervals[14] = {0};
  uint64_t irSum = 0;

  for (int i = 0; i < SAMPLES_LEN; i++) irSum += irBuffer[i];
  const int32_t irMean = (int32_t)(irSum / SAMPLES_LEN);

  for (int i = 0; i < SAMPLES_LEN; i++) {
    filtered[i] = -((int32_t)irBuffer[i] - irMean);
  }
  for (int i = 0; i < SAMPLES_LEN - 4; i++) {
    filtered[i] =
      (filtered[i] + filtered[i + 1] + filtered[i + 2] + filtered[i + 3]) / 4;
  }

  int32_t threshold = 0;
  for (int i = 0; i < SAMPLES_LEN; i++) threshold += filtered[i];
  threshold /= SAMPLES_LEN;
  threshold = constrain(threshold, 30, 60);

  int32_t peakCount = 0;
  maxim_find_peaks(
    peakLocations,
    &peakCount,
    filtered,
    SAMPLES_LEN,
    threshold,
    PPG_REFRACTORY_SAMPLES,
    15
  );
  if (peakCount < 2) return -1;

  int32_t intervalCount = 0;
  for (int32_t i = 1; i < peakCount; i++) {
    const int32_t interval = peakLocations[i] - peakLocations[i - 1];
    if (interval > PPG_REFRACTORY_SAMPLES && interval <= 50) {
      intervals[intervalCount++] = interval;
    }
  }
  if (intervalCount == 0) return -1;

  // Sort the tiny interval array to obtain a motion-resistant median.
  for (int32_t i = 1; i < intervalCount; i++) {
    const int32_t value = intervals[i];
    int32_t j = i - 1;
    while (j >= 0 && intervals[j] > value) {
      intervals[j + 1] = intervals[j];
      j--;
    }
    intervals[j + 1] = value;
  }
  const int32_t medianInterval = intervals[intervalCount / 2];

  // Average only intervals close to the median. This keeps one displaced peak
  // from changing the result while retaining sub-sample precision over the
  // complete four-second window.
  int32_t intervalSum = 0;
  int32_t consistentCount = 0;
  for (int32_t i = 0; i < intervalCount; i++) {
    if (abs(intervals[i] - medianInterval) * 4 <= medianInterval) {
      intervalSum += intervals[i];
      consistentCount++;
    }
  }
  if (consistentCount == 0 || intervalSum == 0) return -1;

  const float averageInterval = (float)intervalSum / (float)consistentCount;
  const int32_t bpm = (int32_t)roundf((25.0f * 60.0f) / averageInterval);
  return bpm >= 25 && bpm <= 230 ? bpm : -1;
}

int32_t correctedOpticalHeartRate(int32_t maximHeartRate, int32_t robustHeartRate) {
  if (maximHeartRate < HARMONIC_RAW_HR_MIN || robustHeartRate < 45 || robustHeartRate > 130) {
    return maximHeartRate;
  }

  const float ratio = (float)maximHeartRate / (float)robustHeartRate;
  if (ratio >= 1.70f && ratio <= 2.30f) {
    return robustHeartRate;
  }
  return maximHeartRate;
}

// ---------------- Initialize/recover MAX30102 ----------------

bool initializeMaxSensor() {

  lastMaxInitAttemptMs = millis();

  if (!particleSensor.begin(Wire, I2C_SPEED_STANDARD)) {
    maxSensorReady = false;
    Serial.println("MAX30102 not found at I2C address 0x57. Will retry.");
    return false;
  }

  const byte ledBrightness = 60;
  // spo2_algorithm.h is hard-coded for FreqS=25 and BUFFER_SIZE=100.
  // 100 sensor samples/s with FIFO averaging 4 produces the required 25 Hz.
  const byte sampleAverage = 4;
  const byte ledMode = 2;
  const int sampleRate = 100;
  const int pulseWidth = 411;
  const int adcRange = 4096;

  particleSensor.setup(
    ledBrightness,
    sampleAverage,
    ledMode,
    sampleRate,
    pulseWidth,
    adcRange
  );

  particleSensor.clearFIFO();
  maxSensorReady = true;
  lastOpticalSampleMs = millis();
  clearOpticalMeasurement();
  Serial.println("MAX30102 ready at 0x57 (25 FIFO samples/s, red + IR)");
  return true;
}

// ---------------- Calculate HR and SpO2 ----------------

void calculateSpO2HR() {

  if (!maxSensorReady) {
    invalidateOpticalResult(SENSOR_NOT_FOUND);
    return;
  }
  if (!opticalContact || millis() - lastOpticalSampleMs > 2000) {
    invalidateOpticalResult(NO_FINGER_OR_WEAK_SIGNAL);
    return;
  }
  if (opticalSamplesCollected < SAMPLES_LEN) {
    invalidateOpticalResult(MEASUREMENT_IN_PROGRESS);
    return;
  }

  uint64_t irSum = 0;
  uint32_t irMin = UINT32_MAX;
  uint32_t irMax = 0;
  uint32_t redMin = UINT32_MAX;
  uint32_t redMax = 0;

  for (int i = 0; i < SAMPLES_LEN; i++) {
    irSum += irBuffer[i];
    irMin = min(irMin, irBuffer[i]);
    irMax = max(irMax, irBuffer[i]);
    redMin = min(redMin, redBuffer[i]);
    redMax = max(redMax, redBuffer[i]);
  }

  latestIrAverage = irSum / SAMPLES_LEN;
  latestIrSpan = irMax - irMin;
  latestRedSpan = redMax - redMin;

  if (latestIrAverage < FINGER_RELEASE_IR_THRESHOLD) {
    invalidateOpticalResult(NO_FINGER_OR_WEAK_SIGNAL);
    return;
  }
  if (latestIrSpan < MIN_PPG_SPAN || latestRedSpan < MIN_PPG_SPAN) {
    invalidateOpticalResult(SIGNAL_UNSTABLE);
    return;
  }

  maxim_heart_rate_and_oxygen_saturation(
    irBuffer,
    SAMPLES_LEN,
    redBuffer,
    &spo2Value,
    &validSpO2,
    &heartRateVal,
    &validHR
  );

  latestAlgorithmHR = heartRateVal;
  latestAlgorithmSpO2 = spo2Value;
  latestRobustHR = calculateRobustPpgHeartRate();

  const int32_t correctedHeartRate = correctedOpticalHeartRate(
    heartRateVal,
    latestRobustHR
  );

  if (validSpO2 &&
      validHR &&
      spo2Value >= 50 &&
      spo2Value <= 100 &&
      correctedHeartRate >= 25 &&
      correctedHeartRate <= 230) {

    addAcceptedOpticalResult(correctedHeartRate, spo2Value);
    if (acceptedResultCount >= OPTICAL_RESULTS_BEFORE_PUBLISH) {
      currentHR = (float)medianResult(acceptedHr, acceptedResultCount);
      currentSpO2 = (float)medianResult(acceptedSpO2, acceptedResultCount);
      lastValidOpticalResultMs = millis();
      opticalState = VALID_MEASUREMENT;
    } else {
      currentHR = 0.0f;
      currentSpO2 = 0.0f;
      opticalState = MEASUREMENT_IN_PROGRESS;
    }
  } else {
    invalidateOpticalResult(SIGNAL_UNSTABLE);
  }
}

// ---------------- Transmit vital signs ----------------

void transmitVitals() {

  const bool leadOff =
    digitalRead(LO_PLUS) == HIGH ||
    digitalRead(LO_MINUS) == HIGH;

  // Binary vitals packet (12 bytes): magic, flags, HR x10, SpO2 x10,
  // temperature x10, latest IR. All integer fields are little-endian.
  uint8_t packet[12] = {0};
  packet[0] = 0xD1;
  packet[1] =
    (opticalContact ? 0x01 : 0x00) |
    (maxSensorReady ? 0x02 : 0x00) |
    (leadOff ? 0x04 : 0x00);

  const uint16_t hr10 = (uint16_t)roundf(currentHR * 10.0f);
  const uint16_t spo210 = (uint16_t)roundf(currentSpO2 * 10.0f);
  const int16_t temp10 = (int16_t)roundf(currentTemp * 10.0f);
  packet[2] = (uint8_t)(hr10 & 0xff);
  packet[3] = (uint8_t)(hr10 >> 8);
  packet[4] = (uint8_t)(spo210 & 0xff);
  packet[5] = (uint8_t)(spo210 >> 8);
  packet[6] = (uint8_t)((uint16_t)temp10 & 0xff);
  packet[7] = (uint8_t)((uint16_t)temp10 >> 8);
  packet[8] = (uint8_t)(latestIr & 0xff);
  packet[9] = (uint8_t)((latestIr >> 8) & 0xff);
  packet[10] = (uint8_t)((latestIr >> 16) & 0xff);
  packet[11] = (uint8_t)((latestIr >> 24) & 0xff);

  notifyPacket(packet, sizeof(packet));

  // A readable diagnostic line twice per second; ECG is deliberately not printed
  // at 250 Hz because serial back-pressure can disturb sensor timing.
  Serial.printf(
    "Vitals state=%s HR=%.1f SpO2=%.1f Temp=%.1f finger=%s max=%s IR=%lu RED=%lu IRavg=%lu IRspan=%lu REDspan=%lu algoHR=%ld robustHR=%ld algoSpO2=%ld validHR=%d validSpO2=%d leadOff=%s\n",
    opticalStateName(opticalState),
    currentHR, currentSpO2, currentTemp,
    opticalContact ? "true" : "false",
    maxSensorReady ? "true" : "false",
    (unsigned long)latestIr,
    (unsigned long)latestRed,
    (unsigned long)latestIrAverage,
    (unsigned long)latestIrSpan,
    (unsigned long)latestRedSpan,
    (long)latestAlgorithmHR,
    (long)latestRobustHR,
    (long)latestAlgorithmSpO2,
    (int)validHR,
    (int)validSpO2,
    leadOff ? "true" : "false"
  );
}

// Start discovery before probing sensors, so a sensor initialization problem
// does not prevent the phone from finding the board.
void setupBle() {
  Serial.println("Starting BLE...");
  BLEDevice::init(BLE_DEVICE_NAME);
  BLEDevice::setMTU(185);
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());
  BLEService *service = pServer->createService(SERVICE_UUID);
  pTxCharacteristic = service->createCharacteristic(
    CHARACTERISTIC_UUID_TX,
    BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ
  );
  pTxCharacteristic->addDescriptor(new BLE2902());
  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  // Flags (3) + 16-bit service (4) + full name (22) = 29 bytes, below
  // the 31-byte legacy advertisement limit. The name is not scan-response-only.
  BLEAdvertisementData advertisement;
  advertisement.setFlags(0x06); // General discoverable, BLE-only.
  advertisement.setCompleteServices(BLEUUID((uint16_t)0xFFE0));
  advertisement.setName(BLE_DEVICE_NAME);
  advertising->setAdvertisementData(advertisement);
  BLEAdvertisementData response;
  response.setName(BLE_DEVICE_NAME);
  advertising->setScanResponseData(response);
  advertising->setScanResponse(true);
  advertising->setMinInterval(0xA0); // 100 ms
  advertising->setMaxInterval(0xF0); // 150 ms
  BLEDevice::startAdvertising();
  Serial.printf("BLE advertising requested: %s; service FFE0\n", BLE_DEVICE_NAME);
}

// ---------------- Setup ----------------

void setup() {

  Serial.begin(115200);
  delay(1000); // Allow native USB CDC to enumerate; never wait indefinitely.
  Serial.println("GeriatriCare ESP32-C3 starting");

  setupBle();

  pinMode(LO_PLUS, INPUT);
  pinMode(LO_MINUS, INPUT);

  analogReadResolution(12);
  analogSetPinAttenuation(ECG_PIN, ADC_11db);

  // I2C
  Wire.begin(I2C_SDA, I2C_SCL);
  Wire.setTimeOut(50);
  // Standard-mode I2C is more reliable with common MAX30102 breakout boards
  // and breadboard jumper lengths than a forced 400 kHz bus.
  Wire.setClock(100000);

  // DS18B20
  tempSensor.begin();
  tempSensor.setWaitForConversion(false);

  Serial.println("DS18B20 initialized");

  // MAX30102 (failure is non-fatal; loop() retries automatically).
  initializeMaxSensor();

  // ECG queue
  ecgQueue = xQueueCreate(500, sizeof(EcgSample));

  if (ecgQueue == nullptr) {

    Serial.println("ERROR: ECG queue allocation failed");

    while (true) {
      delay(1000);
    }
  }

  // ESP32-C3 is single-core: do not pin task to core 1.
  if (xTaskCreate(
        sampleEcgTask,
        "ECG250",
        3072,
        nullptr,
        2,
        nullptr
      ) != pdPASS) {

    Serial.println("ERROR: ECG sampler task creation failed");

    while (true) {
      delay(1000);
    }
  }
}

// ---------------- Main loop ----------------

void loop() {

  if (restartAdvertising) {
    restartAdvertising = false;
    delay(150);
    if (!deviceConnected) {
      BLEDevice::startAdvertising();
      Serial.println("BLE advertising restarted");
    }
  }

  const unsigned long now = millis();

  if (!maxSensorReady &&
      now - lastMaxInitAttemptMs >= 5000) {
    initializeMaxSensor();
  }

  // Read MAX30102 samples.
  if (maxSensorReady) {
    particleSensor.check();
  }

  while (maxSensorReady && particleSensor.available()) {

    const uint32_t red = particleSensor.getRed();
    const uint32_t ir = particleSensor.getIR();

    particleSensor.nextSample();

    lastOpticalSampleMs = millis();
    latestRed = red;
    latestIr = ir;

    const bool signalAboveContactGate =
      opticalContact
      ? ir >= FINGER_RELEASE_IR_THRESHOLD
      : ir >= FINGER_PRESENT_IR_THRESHOLD;

    const bool saturatedOrMissing =
      red == 0 || ir == 0 ||
      ir >= OPTICAL_ADC_MAX ||
      red >= OPTICAL_ADC_MAX;

    if (!signalAboveContactGate || saturatedOrMissing) {
      opticalBadSampleCount++;
      if (opticalBadSampleCount >= OPTICAL_BAD_SAMPLES_BEFORE_RELEASE) {
        // Confirmed contact loss: never bridge this gap into a later algorithm
        // window or keep publishing a stale result.
        clearOpticalMeasurement();
        opticalState = saturatedOrMissing
          ? SIGNAL_UNSTABLE
          : NO_FINGER_OR_WEAK_SIGNAL;
      }
      continue;
    }

    opticalBadSampleCount = 0;
    opticalContact = true;
    opticalState = opticalSamplesCollected < SAMPLES_LEN
      ? MEASUREMENT_IN_PROGRESS
      : opticalState;

    // Shift rolling sample window.
    for (int i = 1; i < SAMPLES_LEN; i++) {

      redBuffer[i - 1] = redBuffer[i];
      irBuffer[i - 1] = irBuffer[i];
    }

    redBuffer[SAMPLES_LEN - 1] = red;
    irBuffer[SAMPLES_LEN - 1] = ir;

    if (opticalSamplesCollected < SAMPLES_LEN) {
      opticalSamplesCollected++;
    }
  }

  if (maxSensorReady &&
      millis() - lastOpticalSampleMs > 5000) {

    Serial.println("MAX30102 sample stream stalled; reinitializing.");
    maxSensorReady = false;
    clearOpticalMeasurement();

  } else if (!maxSensorReady ||
             millis() - lastOpticalSampleMs > 2000) {

    clearOpticalMeasurement();
  }

  // Recalculate HR and SpO2 every second.
  if (now - lastSpO2CalcMs >= SPO2_CALC_INTERVAL_MS) {

    lastSpO2CalcMs = now;

    calculateSpO2HR();
  }

  // DS18B20 non-blocking conversion.
  if (!tempConversionPending &&
      now - lastTempReqMs >= TEMP_INTERVAL_MS) {

    tempSensor.requestTemperatures();

    lastTempReqMs = now;
    tempConversionPending = true;

  } else if (tempConversionPending &&
             now - lastTempReqMs >= TEMP_CONVERSION_MS) {

    const float t = tempSensor.getTempCByIndex(0);

    // Original project's expected human-temperature range.
    // Invalid/disconnected readings are reported as 0.
    currentTemp =
      (isfinite(t) && t >= 25.0f && t <= 43.0f)
      ? t
      : 0.0f;

    tempConversionPending = false;
  }

  // Send ECG batches and vital signs.
  drainEcg();

  if (deviceConnected &&
      now - lastVitalsTxMs >= VITALS_TX_INTERVAL_MS) {

    lastVitalsTxMs = now;

    transmitVitals();
  }

  delay(1);
}
