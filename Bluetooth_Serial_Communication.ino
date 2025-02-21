#include <BluetoothSerial.h>

BluetoothSerial SerialBT;

void setup() {
  Serial.begin(9600);  // Initialize hardware serial communication at 9600 baud rate
  SerialBT.begin("ESP32_LED_Controller");  // Initialize Bluetooth serial with a name
  Serial.println("The device started, now you can pair it with Bluetooth!");
}

void loop() {
  if (SerialBT.available()) {
    String command = SerialBT.readStringUntil('\n'); // Read command from Bluetooth
    Serial.println(command);  // Send command to Arduino via hardware serial
  }
}