/*

  ESP32 Elderly Fall-Detection Wristband (App-Compatible Version)


  Changes from previous version:

    - Added logic to listen for a "CANCEL_ALARM" command from a connected

      Bluetooth device (the mobile app) to remotely cancel an active alarm.


  Hardware:

    - ESP32 (any dev board with built-in Bluetooth)

    - MPU6050 (I2C)

    - Buzzer (active or passive)

    - Button (override / cancel alarm)

    - Optional LED for status


  Libraries required:

    - Adafruit_MPU6050

    - Adafruit_Sensor

    - Wire

    - BluetoothSerial (built into ESP32 core)

*/


#include <Wire.h>

#include <Adafruit_MPU6050.h>

#include <Adafruit_Sensor.h>

#include "BluetoothSerial.h"


// ----- CONFIG -----

const int BUZZER_PIN = 25;      // change to the pin you wired the buzzer to

const int BUTTON_PIN = 27;      // Supports internal pull-up

const int LED_PIN = 2;          // optional status LED


// threshold values (tune for your setup)

const float FREE_FALL_THRESHOLD_G = 0.6f;

const float IMPACT_THRESHOLD_G = 2.5f;

const unsigned long IMPACT_WINDOW_MS = 1200;

const unsigned long CANCEL_WINDOW_MS = 10000; // Time user has to press button

const unsigned long ALARM_REPEAT_MS = 3000;


Adafruit_MPU6050 mpu;

BluetoothSerial SerialBT;


// state variables

bool possibleFreeFall = false;

unsigned long freeFallTime = 0;

bool alarmActive = false;

unsigned long alarmTriggeredTime = 0;

unsigned long lastAlarmMsg = 0;


// button debounce

unsigned long lastButtonChange = 0;

const unsigned long BUTTON_DEBOUNCE_MS = 50;

int lastButtonState = HIGH;


void setup() {

  Serial.begin(115200);

  delay(100);

  Serial.println("ESP32 Fall Detection - startup");


  if (!SerialBT.begin("ESP32-FallBand")) {

    Serial.println("Error starting Bluetooth");

  } else {

    Serial.println("Bluetooth started: ESP32-FallBand");

  }


  pinMode(BUZZER_PIN, OUTPUT);

  digitalWrite(BUZZER_PIN, LOW);

  pinMode(LED_PIN, OUTPUT);

  digitalWrite(LED_PIN, LOW);

  pinMode(BUTTON_PIN, INPUT_PULLUP);

  lastButtonState = digitalRead(BUTTON_PIN);


  // explicitly initialize I2C on default ESP32 pins

  Wire.begin(21, 22);


  if (!mpu.begin(0x68, &Wire)) {

    Serial.println("Failed to find MPU6050 chip");

    while (1) delay(10);

  }


  mpu.setAccelerometerRange(MPU6050_RANGE_8_G);

  mpu.setGyroRange(MPU6050_RANGE_500_DEG);

  mpu.setFilterBandwidth(MPU6050_BAND_21_HZ);

  Serial.println("MPU6050 found and configured");

}


void loop() {

  sensors_event_t a, g, temp;

  mpu.getEvent(&a, &g, &temp);


  float ax = a.acceleration.x;

  float ay = a.acceleration.y;

  float az = a.acceleration.z;

  const float G = 9.80665f;

  float mag_g = sqrt(ax*ax + ay*ay + az*az) / G;


  unsigned long now = millis();


  // Listen for commands from the app

  handleBluetoothCommands();


  handleButton(now);


  if (!alarmActive) {

    if (!possibleFreeFall) {

      if (mag_g < FREE_FALL_THRESHOLD_G) {

        possibleFreeFall = true;

        freeFallTime = now;

        SerialBT.println("POSSIBLE FREE FALL detected");

        Serial.println("POSSIBLE FREE FALL detected");

      }

    } else {

      if (mag_g > IMPACT_THRESHOLD_G) {

        if (now - freeFallTime <= IMPACT_WINDOW_MS) {

          triggerAlarm(now, mag_g);

        } else {

          possibleFreeFall = false;

        }

      } else if (now - freeFallTime > IMPACT_WINDOW_MS) {

        possibleFreeFall = false;

      }

    }

  } else {

    // If CANCEL_WINDOW_MS has passed, the alarm is confirmed.

    if (now - alarmTriggeredTime > CANCEL_WINDOW_MS) {

       if (now - lastAlarmMsg >= ALARM_REPEAT_MS) {

            sendAlarmMessage(mag_g);

            lastAlarmMsg = now;

       }

    }

    // Keep buzzer and LED on during the alarm state

    digitalWrite(BUZZER_PIN, HIGH);

    digitalWrite(LED_PIN, HIGH);

  }


  // Debug printing to Serial Monitor

  static unsigned long lastDbg = 0;

  if (now - lastDbg > 500) {

    Serial.print("acc_g="); Serial.print(mag_g, 2);

    Serial.print(" freeFall="); Serial.print(possibleFreeFall);

    Serial.print(" alarm="); Serial.println(alarmActive);

    lastDbg = now;

  }


  delay(10);

}


void triggerAlarm(unsigned long now, float mag_g) {

  alarmActive = true;

  alarmTriggeredTime = now;

  lastAlarmMsg = now;

  digitalWrite(BUZZER_PIN, HIGH);

  digitalWrite(LED_PIN, HIGH);


  String msg = "FALL DETECTED! acc_g=" + String(mag_g, 2);

  Serial.println(msg);

  SerialBT.println(msg);


  String cancelMsg = "You have " + String(CANCEL_WINDOW_MS/1000) + "s to cancel.";

  Serial.println(cancelMsg);

  SerialBT.println(cancelMsg);

}


void sendAlarmMessage(float mag_g) {

  String msg = "ALARM ACTIVE - FALL DETECTED! acc_g=" + String(mag_g, 2);

  Serial.println(msg);

  SerialBT.println(msg);

}


void handleButton(unsigned long now) {

  int state = digitalRead(BUTTON_PIN);

  if (state != lastButtonState) {

    lastButtonChange = now;

    lastButtonState = state;

  }

  if (now - lastButtonChange > BUTTON_DEBOUNCE_MS) {

    if (state == LOW) {

      if (alarmActive) {

        cancelAlarm();

      } else {

        SerialBT.println("Button pressed (no alarm)");

        Serial.println("Button pressed (no alarm)");

        beep(100);

      }

      delay(200); // Prevent multiple presses

    }

  }

}


void handleBluetoothCommands() {

    if (SerialBT.available()) {

        String command = SerialBT.readStringUntil('\n');

        command.trim();

        if (command == "CANCEL_ALARM" && alarmActive) {

            Serial.println("Alarm cancelled via Bluetooth command.");

            SerialBT.println("Alarm cancelled via app command.");

            cancelAlarm();

        }

    }

}



void cancelAlarm() {

  alarmActive = false;

  possibleFreeFall = false;

  digitalWrite(BUZZER_PIN, LOW);

  digitalWrite(LED_PIN, LOW);

  Serial.println("Alarm CANCELLED by user");

  SerialBT.println("Alarm CANCELLED by user");

}


void beep(unsigned int ms) {

  digitalWrite(BUZZER_PIN, HIGH);

  delay(ms);

  digitalWrite(BUZZER_PIN, LOW);

} 