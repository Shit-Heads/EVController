// Motor control pins
#define IN1  7   // Motor 1
#define IN2  6
#define IN3  5   // Motor 2
#define IN4  4
#define ENA  9   // PWM Speed Control for Motor 1
#define ENB  10  // PWM Speed Control for Motor 2

// RPM sensor pin
#define RPM_SENSOR_PIN 2  // Adjust if needed

// Debounce configuration (in microseconds)
#define DEBOUNCE_MICROS 5000  

// Configurable thresholds
const int DROP_THRESHOLD = 50;
const int CONSISTENCY_TOLERANCE = 30;
const int COMPENSATION_INCREMENT = 10;
const int CONSISTENT_READINGS_TARGET = 3;

volatile unsigned long pulseCount = 0;
volatile unsigned long lastPulseTime = 0;
unsigned long lastRpmTime = 0;

int currentSpeed = 0;
int originalSpeed = 0;
bool isCompensating = false;

int lastRpm = -1;
int consecutiveConsistentReadings = 0;

bool isManualSpeedChange = false;
const unsigned long MANUAL_SPEED_CHANGE_WINDOW = 1500;
unsigned long lastManualChangeTimestamp = 0;

void onPulse() {
  unsigned long now = micros();
  if (now - lastPulseTime > DEBOUNCE_MICROS) {
    pulseCount++;
    lastPulseTime = now;
  }
}

void handleSerialCommand(char command) {
  int speed = currentSpeed;  // Use the currentSpeed variable for consistency

  switch (command) {
    case 'F':  // Forward
      moveForward(speed);
      Serial.println("Moving Forward");
      break;
    case 'B':  // Backward
      moveBackward(speed);
      Serial.println("Moving Backward");
      break;
    case 'L':  // Left
      turnLeft(speed);
      Serial.println("Turning Left");
      break;
    case 'R':  // Right
      turnRight(speed);
      Serial.println("Turning Right");
      break;
    case 'S':  // Stop
      stopMotors();
      Serial.println("Stopping");
      break;
    case '+':  // Increase speed
      if (currentSpeed < 100) currentSpeed += 10;
      sendMotorSpeed(currentSpeed);
      Serial.print("Increasing Speed: ");
      Serial.println(currentSpeed);
      break;
    case '-':  // Decrease speed
      if (currentSpeed > 0) currentSpeed -= 10;
      sendMotorSpeed(currentSpeed);
      Serial.print("Decreasing Speed: ");
      Serial.println(currentSpeed);
      break;
    default:
      Serial.println("Invalid Command! Use F, B, L, R, S, +, -");
      break;
  }
}

void moveForward(int speed) {
  analogWrite(ENA, speed);
  analogWrite(ENB, speed);
  digitalWrite(IN1, HIGH);
  digitalWrite(IN2, LOW);
  digitalWrite(IN3, HIGH);
  digitalWrite(IN4, LOW);
}

void moveBackward(int speed) {
  analogWrite(ENA, speed);
  analogWrite(ENB, speed);
  digitalWrite(IN1, LOW);
  digitalWrite(IN2, HIGH);
  digitalWrite(IN3, LOW);
  digitalWrite(IN4, HIGH);
}

void turnLeft(int speed) {
  analogWrite(ENA, speed);
  analogWrite(ENB, speed);
  digitalWrite(IN1, LOW);
  digitalWrite(IN2, LOW);
  digitalWrite(IN3, HIGH);
  digitalWrite(IN4, LOW);
}

void turnRight(int speed) {
  analogWrite(ENA, speed);
  analogWrite(ENB, speed);
  digitalWrite(IN1, HIGH);
  digitalWrite(IN2, LOW);
  digitalWrite(IN3, LOW);
  digitalWrite(IN4, LOW);
}

void stopMotors() {
  analogWrite(ENA, 0);
  analogWrite(ENB, 0);
  digitalWrite(IN1, LOW);
  digitalWrite(IN2, LOW);
  digitalWrite(IN3, LOW);
  digitalWrite(IN4, LOW);
}

void setup() {
  pinMode(RPM_SENSOR_PIN, INPUT_PULLUP);
  pinMode(ENA, OUTPUT);
  pinMode(ENB, OUTPUT);
  pinMode(IN1, OUTPUT);
  pinMode(IN2, OUTPUT);
  pinMode(IN3, OUTPUT);
  pinMode(IN4, OUTPUT);
  attachInterrupt(digitalPinToInterrupt(RPM_SENSOR_PIN), onPulse, RISING);
  Serial.begin(115200);
  Serial.println("RPM compensation started.");
}

void loop() {
  // Handle RPM calculation and compensation every second
  unsigned long currentTime = millis();
  if (currentTime - lastRpmTime >= 1000) {
    noInterrupts();
    unsigned long pulses = pulseCount;
    pulseCount = 0;
    interrupts();
    
    int rawRpm = pulses * 3;
    int offset = 0;
    int rpm = (rawRpm > offset) ? (rawRpm - offset) : 0;
    
    Serial.print("Raw RPM: ");
    Serial.print(rawRpm);
    Serial.print("  Adjusted RPM: ");
    Serial.println(rpm);
    
    handleRpmReading(rpm);
    lastRpmTime = currentTime;
  }

  // Check for Serial input
  if (Serial.available() > 0) {
   char command = Serial.read();
command = char(toupper(command)); // Convert to uppercase if needed

if (command == '\n' || command == '\r') return; // Ignore newline characters

    // Execute movement based on input
    handleSerialCommand(command);
  }
}


void handleRpmReading(int measuredRpm) {
  if (isManualSpeedChange && (millis() - lastManualChangeTimestamp < MANUAL_SPEED_CHANGE_WINDOW)) {
    Serial.println("Consistency: Ignoring (Recent Manual Change)");
    consecutiveConsistentReadings = 0;
  } else {
    isManualSpeedChange = false;
    
    String consistencyStatus;
    if (lastRpm == -1) {
      consistencyStatus = "No Previous Reading";
      consecutiveConsistentReadings = 0;
    } else {
      int diff = abs(measuredRpm - lastRpm);
      if (diff <= CONSISTENCY_TOLERANCE) {
        consistencyStatus = "Consistent (±" + String(CONSISTENCY_TOLERANCE) + ")";
        consecutiveConsistentReadings++;
      } else {
        consistencyStatus = "Not Consistent (±" + String(CONSISTENCY_TOLERANCE) + ")";
        consecutiveConsistentReadings = 0;

        if ((measuredRpm < lastRpm) && (lastRpm - measuredRpm >= DROP_THRESHOLD)) {
          applySpeedCompensation();
        }
      }
    }
    Serial.println("Consistency: " + consistencyStatus);
    
    if (isCompensating && consecutiveConsistentReadings >= CONSISTENT_READINGS_TARGET) {
      revertToOriginalSpeed();
    }
  }
  lastRpm = measuredRpm;
}

void applySpeedCompensation() {
  if (!isCompensating) {
    originalSpeed = currentSpeed;
    isCompensating = true;
  }
  int newSpeed = min(currentSpeed + COMPENSATION_INCREMENT, 100);
  currentSpeed = newSpeed;
  Serial.print("Applying compensation, new speed: ");
  Serial.println(newSpeed);
  sendMotorSpeed(newSpeed);
}

void revertToOriginalSpeed() {
  if (isCompensating) {
    isCompensating = false;
    consecutiveConsistentReadings = 0;
    currentSpeed = originalSpeed;
    Serial.print("Reverting to original speed: ");
    Serial.println(originalSpeed);
    sendMotorSpeed(originalSpeed);
  }
}

void sendMotorSpeed(int speed) {
  Serial.print("Setting motor speed to: ");
  Serial.println(speed);
  analogWrite(ENA, map(speed, 0, 100, 0, 255));
  analogWrite(ENB, map(speed, 0, 100, 0, 255));
}
