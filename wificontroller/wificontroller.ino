#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>

const char* ssid = ".";
const char* password = "gowtham1";

ESP8266WebServer server(80);

void handleRoot() {
  String html = "<html><body style='text-align:center;'>";
  html += "<h2>Motor Control</h2>";
  html += "<button onclick=\"sendCommand('F')\">Forward</button>";
  html += "<button onclick=\"sendCommand('B')\">Backward</button><br>";
  html += "<button onclick=\"sendCommand('L')\">Left</button>";
  html += "<button onclick=\"sendCommand('S')\">Stop</button>";
  html += "<button onclick=\"sendCommand('R')\">Right</button><br>";
  html += "<button onclick=\"sendCommand('I')\">Speed +</button>";
  html += "<button onclick=\"sendCommand('-')\">Speed -</button><br>";
  html += "<script>function sendCommand(cmd) { fetch('/cmd?val=' + cmd); }</script>";
  html += "</body></html>";

  server.send(200, "text/html", html);
}

void handleCommand() {
  if (server.hasArg("val")) {
    String command = server.arg("val");
    command.trim();  // Remove any spaces

    if (command == "%2B") command = "+";  // Decode URL-encoded `+`

    Serial.print("Command Received: ");
    Serial.println(command);  // Debugging

    Serial.print(command);  // Send to Serial
  }
  server.send(200, "text/plain", "OK");
}


void setup() {
  Serial.begin(115200);
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("");
  Serial.print("Connected! IP address: ");
  Serial.println(WiFi.localIP());

  server.on("/", handleRoot);
  server.on("/cmd", handleCommand);
  server.begin();
  Serial.println("Web server started!");
}

void loop() {
  server.handleClient();
}