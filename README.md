Elderly Fall Detection System with ESP32 & Android

A comprehensive system designed to provide safety and peace of mind for the elderly. This project combines a wearable ESP32-based wristband with an Android application to detect falls and automatically alert emergency contacts via SMS, including the user's last known GPS location.
🌟 Key Features
ESP32 Wearable Device

    Real-time Motion Sensing: Uses an MPU6050 accelerometer to constantly monitor the user's movements.

    On-device Fall Detection: A two-stage algorithm detects a state of free-fall followed by a sharp impact to accurately identify a potential fall.

    Local Alerts: An onboard buzzer provides immediate audible feedback when a fall is detected.

    Physical Cancel Button: Allows the user to cancel a false alarm directly from the wristband within a 10-second window.

    Bluetooth Communication: Securely communicates with the companion Android app via Bluetooth Classic.

Android Application

    Bluetooth Connectivity: Easily scans for and connects to the ESP32 wristband.

    Status Monitoring: Displays the real-time connection and fall detection status.

    Emergency Contact Management: Users can add and remove emergency contacts directly from their phone's address book.

    Automated SMS Alerts: In the event of a confirmed fall, the app automatically sends an urgent SMS to all emergency contacts.

    GPS Location Sharing: The SMS alert includes the user's last known latitude and longitude, with a convenient Google Maps link.

    Follow-up Alerts: If an alarm is not canceled, a second follow-up SMS is sent after 60 seconds to ensure the alert is noticed.

    Remote Alarm Cancellation: An active alarm can be canceled from within the app.

🛠️ Hardware & Software Requirements
Hardware

    ESP32 Development Board

    MPU6050 Accelerometer & Gyroscope Module

    Active or Passive Buzzer

    Push Button (for manual cancellation)

    Connecting Wires & Breadboard/PCB

    5V Power Source (e.g., LiPo battery and charging circuit)

Software

    Arduino IDE or PlatformIO (for flashing the ESP32)

        Adafruit MPU6050 Library

        Adafruit Unified Sensor Library

        Wire Library

    Android Studio (for building the Android app)

    An Android Smartphone (with Bluetooth and SMS capabilities) for testing and use.

⚙️ Setup and Installation
1. ESP32 Wristband

    Wiring: Connect the MPU6050, buzzer, and button to your ESP32 according to the pin definitions at the top of the .ino file.

    Libraries: In the Arduino IDE, go to Sketch > Include Library > Manage Libraries and install the required Adafruit libraries.

    Flashing: Open the ESP32_FallDetector.ino file in the Arduino IDE. Select your ESP32 board and the correct COM port.

    Upload: Click the "Upload" button to flash the firmware onto the ESP32.

2. Android Application

    Open Project: Open the project folder in Android Studio.

    Build Gradle: Allow Gradle to sync and build the project. This may take a few minutes.

    Run on Device:

        Enable "Developer Options" and "USB Debugging" on your Android phone.

        Connect your phone to your computer via USB.

        Select your device in Android Studio and click the "Run" button.

    Permissions: Once installed, launch the app and grant all requested permissions (Bluetooth, SMS, Location, Contacts). This is critical for the app to function.

🚀 How to Use

    Power On: Power on the ESP32 wristband.

    Pair Bluetooth: On your phone, go to Bluetooth settings and pair with the device named ESP32-FallBand.

    Launch App: Open the Fall Detection Monitor app.

    Add Contacts: Click "Add New Contact" to select one or more emergency contacts from your phone.

    Connect: Click the "Connect" button. The status should change to "Connected to ESP32-FallBand".

    Monitor: The app is now monitoring the device. If a fall is detected, the alarm will sound on the wristband and the app's status card will turn red.

    Cancellation:

        The user has 10 seconds to cancel the alarm by pressing the button on the wristband.

        Alternatively, the alarm can be canceled from the app by pressing the "CANCEL ALARM" button.

    Alerts: If not canceled, the app will proceed to send the SMS alerts to the saved contacts.

📄 License

This project is licensed under the MIT License. See the LICENSE.md file for details.
