#include <SoftwareSerial.h>

// Define motor control pins
#define IN1  7   // Motor 1
#define IN2  6
#define IN3  5   // Motor 2
#define IN4  4
#define ENA  9   // Enable pin for Motor 1
#define ENB  10  // Enable pin for Motor 2

#define ENCODER_PIN 2
#define ENCODER_N   20  // Number of obstacles

volatile unsigned long T1 = 0, T2 = 0;
volatile unsigned long pulseTime = 0;
volatile int pulseCount = 0;
const int pulsesToMeasure = 10; // Increase for better averaging
bool hasReachedThreshold = false;
bool rpmDecreased = false;

char command;

#ifdef ESP32  // Only use IRAM_ATTR for ESP32
void IRAM_ATTR INT0_ISR()
#else
void INT0_ISR()
#endif
{
  T2 = micros();
  pulseTime += (T2 - T1);
  T1 = T2;
  pulseCount++;

  if (pulseCount >= pulsesToMeasure)
  {
    pulseCount = 0;
  }
}

void setup()
{
  Serial.begin(115200);
  pinMode(IN1, OUTPUT);
  pinMode(IN2, OUTPUT);
  pinMode(IN3, OUTPUT);
  pinMode(IN4, OUTPUT);
  pinMode(ENA, OUTPUT);
  pinMode(ENB, OUTPUT);
  pinMode(ENCODER_PIN, INPUT);
  attachInterrupt(digitalPinToInterrupt(ENCODER_PIN), INT0_ISR, RISING);

  // Gradually ramp up motor speed
  for (int speed = 0; speed <= 75; speed += 25)
  {
    analogWrite(ENA, speed);
    analogWrite(ENB, speed);
    delay(50); // Small delay for ramp-up
  }

  Serial.println("Motor Control Ready...");
}


void loop()
{
  if (Serial.available()) {  
    command = Serial.read(); 
    Serial.print("Received Command: ");
    Serial.println(command);

    switch(command) {
      case 'F': moveForward(); break;
      case 'B': moveBackward(); break;
      case 'S': stopMotor(); break;
      case '1': moveMotor1Forward(); break;
      case '2': moveMotor1Backward(); break;
      case '3': stopMotor1(); break;
      case '4': moveMotor2Forward(); break;
      case '5': moveMotor2Backward(); break;
      case '6': stopMotor2(); break;
      default: Serial.println("Invalid Command"); break;
    }
  }
  static unsigned long lastUpdate = 0;

  if (pulseCount == 0 && pulseTime > 0)
  {
    noInterrupts();
    unsigned long avgPulseTime = pulseTime / pulsesToMeasure;
    pulseTime = 0;
    interrupts();

    if (avgPulseTime > 0)
    {
      int Motor_RPM = (60000000) / (avgPulseTime * ENCODER_N);
      Serial.print("Motor RPM: ");
      Serial.println(Motor_RPM);
      
      if (Motor_RPM >= 4500)
      {
        hasReachedThreshold = true;
        rpmDecreased = false;
      }
      else if (hasReachedThreshold)
      {
        rpmDecreased = true;
      }
    }
  }

  if (rpmDecreased)
  {
    int currentPWM = analogRead(ENB);
    int newPWM = currentPWM + 20; // Increase power when RPM drops below 1000 after reaching it
    if (newPWM > 255) newPWM = 255;
    analogWrite(ENB, newPWM);
    Serial.println("RPM Dropped Below 1000 Again - Increasing Power");
    rpmDecreased = false; // Reset flag to prevent continuous increase
  }
  
  if (millis() - lastUpdate > 250)
  {
    lastUpdate = millis();
  }
}

void moveForward() { Serial.println("Moving Forward"); digitalWrite(IN1, HIGH); digitalWrite(IN2, LOW); digitalWrite(IN3, HIGH); digitalWrite(IN4, LOW); }
void moveBackward() { Serial.println("Moving Backward"); digitalWrite(IN1, LOW); digitalWrite(IN2, HIGH); digitalWrite(IN3, LOW); digitalWrite(IN4, HIGH); }
void stopMotor() { Serial.println("Stopping Motors"); digitalWrite(IN1, LOW); digitalWrite(IN2, LOW); digitalWrite(IN3, LOW); digitalWrite(IN4, LOW); }
void moveMotor1Forward() { Serial.println("Moving Motor 1 Forward"); digitalWrite(IN1, HIGH); digitalWrite(IN2, LOW); }
void moveMotor1Backward() { Serial.println("Moving Motor 1 Backward"); digitalWrite(IN1, LOW); digitalWrite(IN2, HIGH); }
void stopMotor1() { Serial.println("Stopping Motor 1"); digitalWrite(IN1, LOW); digitalWrite(IN2, LOW); }
void moveMotor2Forward() { Serial.println("Moving Motor 2 Forward"); digitalWrite(IN3, HIGH); digitalWrite(IN4, LOW); }
void moveMotor2Backward() { Serial.println("Moving Motor 2 Backward"); digitalWrite(IN3, LOW); digitalWrite(IN4, HIGH); }
void stopMotor2() { Serial.println("Stopping Motor 2"); digitalWrite(IN3, LOW); digitalWrite(IN4, LOW); }
