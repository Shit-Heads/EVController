// Motor control pins
#define IN1  7   
#define IN2  6
#define IN3  5   
#define IN4  4
#define ENA  9   
#define ENB  10  

// RPM sensor
#define RPM_SENSOR_PIN 2  

#define DEBOUNCE_MICROS 5000  
const int DROP_THRESHOLD = 50;
const int CONSISTENCY_TOLERANCE = 30;
const int COMPENSATION_INCREMENT = 10;
const int CONSISTENT_READINGS_TARGET = 3;

volatile unsigned long pulseCount = 0;
volatile unsigned long lastPulseTime = 0;
unsigned long lastRpmTime = 0;
int currentSpeed = 50;
int originalSpeed = 50;
bool isCompensating = false;
int lastRpm = -1;
int consecutiveConsistentReadings = 0;

void onPulse() {
  unsigned long now = micros();
  if (now - lastPulseTime > DEBOUNCE_MICROS) {
    pulseCount++;
    lastPulseTime = now;
  }
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
  Serial.println("Arduino Motor Control Ready!");
}

void loop() {
  if (Serial.available() > 0) {
    char command = Serial.read();
    command = char(toupper(command)); 
    handleSerialCommand(command);
  }

  if (millis() - lastRpmTime >= 1000) {
    noInterrupts();
    unsigned long pulses = pulseCount;
    pulseCount = 0;
    interrupts();
    
    int rawRpm = pulses * 3;
    int offset = 0;
    int rpm = (rawRpm > offset) ? (rawRpm - offset) : 0;
    
    Serial.print("RPM: ");
    Serial.println(rpm);
    
    handleRpmReading(rpm);
    lastRpmTime = millis();
  }
}

void handleSerialCommand(char command) {
  Serial.print("Received: ");
  Serial.println(command);  // Debugging to check what is received

  switch (command) {
    case 'F': moveForward(currentSpeed); Serial.println("Forward"); break;
    case 'B': moveBackward(currentSpeed); Serial.println("Backward"); break;
    case 'L': turnLeft(currentSpeed); Serial.println("Left"); break;
    case 'R': turnRight(currentSpeed); Serial.println("Right"); break;
    case 'S': stopMotors(); Serial.println("Stop"); break;
    case 'I': 
      if (currentSpeed < 100) currentSpeed += 10;
      Serial.println("Speed Up");
      break;
    case '-': 
      if (currentSpeed > 0) currentSpeed -= 10;
      Serial.println("Speed Down");
      break;
    default: Serial.println("Invalid Command!"); break;
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

void handleRpmReading(int measuredRpm) {
  if (lastRpm == -1) {
    consecutiveConsistentReadings = 0;
  } else {
    int diff = abs(measuredRpm - lastRpm);
    if (diff <= CONSISTENCY_TOLERANCE) {
      consecutiveConsistentReadings++;
    } else {
      consecutiveConsistentReadings = 0;

      if ((measuredRpm < lastRpm) && (lastRpm - measuredRpm >= DROP_THRESHOLD)) {
        applySpeedCompensation();
      }
    }
  }
  
  if (isCompensating && consecutiveConsistentReadings >= CONSISTENT_READINGS_TARGET) {
    revertToOriginalSpeed();
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
  Serial.print("Compensating: New speed ");
  Serial.println(newSpeed);
  analogWrite(ENA, map(newSpeed, 0, 100, 0, 255));
  analogWrite(ENB, map(newSpeed, 0, 100, 0, 255));
}

void revertToOriginalSpeed() {
  if (isCompensating) {
    isCompensating = false;
    consecutiveConsistentReadings = 0;
    currentSpeed = originalSpeed;
    Serial.print("Reverting to speed: ");
    Serial.println(originalSpeed);
    analogWrite(ENA, map(originalSpeed, 0, 100, 0, 255));
    analogWrite(ENB, map(originalSpeed, 0, 100, 0, 255));
  }
}
