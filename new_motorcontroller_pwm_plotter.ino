#include "BluetoothSerial.h"
BluetoothSerial SerialBT;

// Motor control pin definitions
#define IN1  16
#define IN2  17
#define IN3  18
#define IN4  19
#define ENA  22
#define ENB  23

// RPM sensor pin definition (adjust as needed)
#define RPM_SENSOR_PIN 4

// LEDC (PWM) configuration
#define LEDC_BASE_FREQ 5000
#define LEDC_TIMER_BITS 12
#define MOTOR_PWM_MAX ((1 << LEDC_TIMER_BITS) - 1)

// Debounce configuration (in microseconds)
// You may adjust DEBOUNCE_MICROS if needed for noise rejection
#define DEBOUNCE_MICROS 5000  

#define MOTOR_RESISTANCE 1.2  // Approximate motor winding resistance in ohms
#define SUPPLY_VOLTAGE 12.0   // Motor supply voltage in volts

// Function to estimate current based on PWM duty cycle
float estimateCurrent(int duty) {
  float motorVoltage = (duty / (float)MOTOR_PWM_MAX) * SUPPLY_VOLTAGE;
  float estimatedCurrent = motorVoltage / MOTOR_RESISTANCE; // Ohm's Law: I = V/R
  return estimatedCurrent;
}

volatile unsigned long pulseCount = 0;
volatile unsigned long lastPulseTime = 0;
unsigned long lastRpmTime = 0;

// Interrupt service routine for RPM sensor pulses with debounce
void IRAM_ATTR onPulse() {
  unsigned long now = micros();
  if (now - lastPulseTime > DEBOUNCE_MICROS) {
    pulseCount++;
    lastPulseTime = now;
  }
}

// Function to set motor direction (Motor 1)
void setMotor1Direction(char direction) {
  if (direction == 'F') { // Forward for Motor 1
    digitalWrite(IN1, HIGH);
    digitalWrite(IN2, LOW);
  } else if (direction == 'R') { // Reverse for Motor 1
    digitalWrite(IN1, LOW);
    digitalWrite(IN2, HIGH);
  } else if (direction == 'S') { // Stop for Motor 1
    digitalWrite(IN1, LOW);
    digitalWrite(IN2, LOW);
  }
}

// Function to set motor direction (Motor 2)
void setMotor2Direction(char direction) {
  if (direction == 'R') { // Forward for Motor 2
    digitalWrite(IN3, HIGH);
    digitalWrite(IN4, LOW);
  } else if (direction == 'F') { // Reverse for Motor 2
    digitalWrite(IN3, LOW);
    digitalWrite(IN4, HIGH);
  } else if (direction == 'S') { // Stop for Motor 2
    digitalWrite(IN3, LOW);
    digitalWrite(IN4, LOW);
  }
}

void setMotorSpeed(int speedPercentage) {
  int pwm8bitVal = map(speedPercentage, 0, 100, 0, 255);
  int duty = (pwm8bitVal * MOTOR_PWM_MAX) / 255;
  ledcWrite(ENA, duty);
  ledcWrite(ENB, duty);

  // Estimate current
  float motorCurrent = estimateCurrent(duty);

  // Print only the numeric value for Serial Plotter
  Serial.println(motorCurrent);  // Ensure only the number is printed

  // Bluetooth Output (if needed)
  SerialBT.print("CURRENT");
  SerialBT.println(motorCurrent);
}

void setup() {
  // Set motor control pins as outputs
  pinMode(IN1, OUTPUT);
  pinMode(IN2, OUTPUT);
  pinMode(IN3, OUTPUT);
  pinMode(IN4, OUTPUT);

  // Set RPM sensor pin as input with pull-up and attach interrupt
  pinMode(RPM_SENSOR_PIN, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(RPM_SENSOR_PIN), onPulse, RISING);

  // Attach PWM channels to motor enable pins using the LEDC API
  ledcAttach(ENA, LEDC_BASE_FREQ, LEDC_TIMER_BITS);
  ledcAttach(ENB, LEDC_BASE_FREQ, LEDC_TIMER_BITS);

  // Initialize Serial for debugging and Bluetooth Serial
  Serial.begin(115200);
  SerialBT.begin("MotorController");
  Serial.println("Bluetooth device started, now you can pair it with your phone!");
  
  setMotor1Direction('S');
  setMotor2Direction('S');
  setMotorSpeed(0);
}

void loop() {
  // Handle incoming Bluetooth commands
  if (SerialBT.available()) {
    String command = SerialBT.readStringUntil('\n');
    command.trim();
    Serial.print("Received command: ");
    Serial.println(command);

    if (command.startsWith("SPEED")) {
      int speedValue = command.substring(5).toInt();
      if (speedValue >= 0 && speedValue <= 100) {
        setMotorSpeed(speedValue);
        SerialBT.print("Speed set to: ");
        SerialBT.println(speedValue);
      } else {
        SerialBT.println("Invalid speed value (0-100)");
      }
    } else if (command.startsWith("DIR1")) {
      char direction1 = command.charAt(4);
      if (direction1 == 'F' || direction1 == 'R' || direction1 == 'S') {
        setMotor1Direction(direction1);
        SerialBT.print("Motor 1 direction: ");
        SerialBT.println(direction1);
      } else {
        SerialBT.println("Invalid direction for Motor 1 (F, R, S)");
      }
    } else if (command.startsWith("DIR2")) {
      char direction2 = command.charAt(4);
      if (direction2 == 'F' || direction2 == 'R' || direction2 == 'S') {
        setMotor2Direction(direction2);
        SerialBT.print("Motor 2 direction: ");
        SerialBT.println(direction2);
      } else {
        SerialBT.println("Invalid direction for Motor 2 (F, R, S)");
      }
    } else {
      SerialBT.println("Unknown command");
    }
  }
  
  // Calculate and send RPM every second
  unsigned long currentTime = millis();
  if (currentTime - lastRpmTime >= 1000) { // 1-second interval
    noInterrupts();
    unsigned long pulses = pulseCount;
    pulseCount = 0;
    interrupts();
    
    // Compute the raw RPM value.
    // With 20 holes, each re volution gives 20 pulses so:
    // RPM = (pulses per second * 60) / 20 = pulses * 3
    int rawRpm = pulses * 3;
    
    // Remove baseline noise: subtract offset (300 here) so that idle becomes 0 RPM.
    int offset = 0; // Adjust this offset based on your sensor's noise characteristics
    int rpm = (rawRpm > offset) ? (rawRpm - offset) : 0;
    
    Serial.print("Raw RPM: ");
    Serial.print(rawRpm);
    Serial.print("  Adjusted RPM: ");
    Serial.println(rpm);
    SerialBT.print("RPM");
    SerialBT.println(rpm);
    lastRpmTime = currentTime;
  }
}
