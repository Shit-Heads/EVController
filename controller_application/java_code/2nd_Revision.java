package com.example.motorcontroller;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_BLUETOOTH_CONNECT = 1;
    // Set the maximum optimal RPM to 350 (as the motor can achieve between 300-350 RPM)
    private static final int EXPECTED_MAX_RPM = 350;
    // Tolerance of 10%
    private static final double TOLERANCE_PERCENT = 0.1;

    // Bluetooth objects
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;
    private InputStream inputStream;

    // Standard SPP UUID for Bluetooth modules
    private final UUID BT_MODULE_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // UI elements
    private Button btnConnect, btnDisconnect, btnMotor1Forward, btnMotor1Reverse, btnMotor1Stop,
            btnMotor2Forward, btnMotor2Reverse, btnMotor2Stop;
    private SeekBar speedSeekBar;
    private TextView speedValueTextView, connectedDeviceTextView, rpmValueTextView,
            statusValueTextView, optimalRpmValueTextView;

    // Store the current speed percentage (0-100)
    private int currentSpeed = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI elements
        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnMotor1Forward = findViewById(R.id.btnMotor1Forward);
        btnMotor1Reverse = findViewById(R.id.btnMotor1Reverse);
        btnMotor1Stop = findViewById(R.id.btnMotor1Stop);
        btnMotor2Forward = findViewById(R.id.btnMotor2Forward);
        btnMotor2Reverse = findViewById(R.id.btnMotor2Reverse);
        btnMotor2Stop = findViewById(R.id.btnMotor2Stop);
        speedSeekBar = findViewById(R.id.speedSeekBar);
        speedValueTextView = findViewById(R.id.speedValueTextView);
        connectedDeviceTextView = findViewById(R.id.connectedDeviceTextView);
        rpmValueTextView = findViewById(R.id.rpmValueTextView);
        statusValueTextView = findViewById(R.id.statusValueTextView);
        optimalRpmValueTextView = findViewById(R.id.optimalRpmValueTextView); // New UI element

        // Set initial connection state
        connectedDeviceTextView.setText("Not Connected");
        rpmValueTextView.setText("RPM: 0");
        statusValueTextView.setText("Status: N/A");
        optimalRpmValueTextView.setText("Optimal RPM: 0");

        // Initialize Bluetooth adapter
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_SHORT).show();
            finish();
        }
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, 1);
        }

        // Request Bluetooth permission if needed (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                        REQUEST_BLUETOOTH_CONNECT);
            }
        }

        // Set up connect button to show a list of paired devices
        btnConnect.setOnClickListener(v -> showPairedDevicesList());

        // Set up disconnect button to disconnect from device
        btnDisconnect.setOnClickListener(v -> disconnectDevice());

        // Set up the SeekBar to send speed commands and update currentSpeed
        speedSeekBar.setMax(100);
        speedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentSpeed = progress;
                speedValueTextView.setText("Speed: " + progress);
                if (outputStream != null) {
                    sendCommand("SPEED" + progress + "\n");
                }
                // Update optimal RPM display based on current speed
                int expectedRpm = (currentSpeed * EXPECTED_MAX_RPM) / 100;
                optimalRpmValueTextView.setText("Optimal RPM: " + expectedRpm);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        // Set up Motor 1 control buttons
        btnMotor1Forward.setOnClickListener(v -> sendCommand("DIR1F\n"));
        btnMotor1Reverse.setOnClickListener(v -> sendCommand("DIR1R\n"));
        btnMotor1Stop.setOnClickListener(v -> sendCommand("DIR1S\n"));

        // Set up Motor 2 control buttons
        btnMotor2Forward.setOnClickListener(v -> sendCommand("DIR2F\n"));
        btnMotor2Reverse.setOnClickListener(v -> sendCommand("DIR2R\n"));
        btnMotor2Stop.setOnClickListener(v -> sendCommand("DIR2S\n"));
    }

    // Helper method to check if Bluetooth permission is granted
    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    // Show a dialog with a list of paired devices
    private void showPairedDevicesList() {
        if (!hasBluetoothPermission()) {
            Toast.makeText(this, "Bluetooth permission not granted", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices.size() > 0) {
            final ArrayList<BluetoothDevice> devicesList = new ArrayList<>(pairedDevices);
            ArrayList<String> deviceNames = new ArrayList<>();
            for (BluetoothDevice device : devicesList) {
                deviceNames.add(device.getName() + "\n" + device.getAddress());
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Select Bluetooth Device");
            builder.setItems(deviceNames.toArray(new CharSequence[0]), (dialog, which) -> {
                BluetoothDevice selectedDevice = devicesList.get(which);
                connectToDevice(selectedDevice);
            });
            builder.show();
        } else {
            Toast.makeText(this, "No paired devices found", Toast.LENGTH_SHORT).show();
        }
    }

    // Connect to the selected device in a background thread
    private void connectToDevice(BluetoothDevice device) {
        new Thread(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this, "Bluetooth permission not granted", Toast.LENGTH_SHORT).show());
                    return;
                }
            }
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(BT_MODULE_UUID);
                bluetoothSocket.connect();
                outputStream = bluetoothSocket.getOutputStream();
                inputStream = bluetoothSocket.getInputStream();
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Connected to " + device.getName(), Toast.LENGTH_SHORT).show();
                    connectedDeviceTextView.setText("Connected to: " + device.getName());
                    speedSeekBar.setProgress(0);
                    speedValueTextView.setText("Speed: 0");
                    sendCommand("SPEED0\n");
                    optimalRpmValueTextView.setText("Optimal RPM: 0");
                });
                startListeningForData(); // Start the data listener thread
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Connection failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            } catch (SecurityException e) {
                e.printStackTrace();
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Security exception: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // Disconnect from the connected device
    private void disconnectDevice() {
        new Thread(() -> {
            if (bluetoothSocket != null) {
                try {
                    bluetoothSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            bluetoothSocket = null;
            outputStream = null;
            inputStream = null;
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "Disconnected", Toast.LENGTH_SHORT).show();
                connectedDeviceTextView.setText("Not Connected");
                rpmValueTextView.setText("RPM: 0");
                statusValueTextView.setText("Status: N/A");
                optimalRpmValueTextView.setText("Optimal RPM: 0");
            });
        }).start();
    }

    // Send a command string over Bluetooth
    private void sendCommand(String command) {
        if (outputStream != null) {
            try {
                outputStream.write(command.getBytes());
            } catch (IOException e) {
                e.printStackTrace();
                Toast.makeText(this, "Failed to send command", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Not connected to any device", Toast.LENGTH_SHORT).show();
        }
    }

    // Listen for incoming data from the ESP32 using a BufferedReader for complete line reads.
    // Expected data format: "RPM<value>" (e.g., "RPM1200")
    private void startListeningForData() {
        new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("RPM")) {
                        String rpmStr = line.substring(3).trim();
                        try {
                            int measuredRpm = Integer.parseInt(rpmStr);
                            runOnUiThread(() -> {
                                rpmValueTextView.setText("RPM: " + measuredRpm);
                                // Compute expected RPM based on currentSpeed.
                                int expectedRpm = (currentSpeed * EXPECTED_MAX_RPM) / 100;
                                optimalRpmValueTextView.setText("Optimal RPM: " + expectedRpm);

                                String status;
                                if (currentSpeed == 0) {
                                    status = (measuredRpm == 0) ? "Idle" : "Not Optimal";
                                } else {
                                    double lowerBound = expectedRpm * (1 - TOLERANCE_PERCENT);
                                    double upperBound = expectedRpm * (1 + TOLERANCE_PERCENT);
                                    status = (measuredRpm >= lowerBound && measuredRpm <= upperBound)
                                            ? "Optimal" : "Not Optimal";
                                }
                                statusValueTextView.setText("Status: " + status);
                            });
                        } catch (NumberFormatException e) {
                            e.printStackTrace();
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectDevice();
    }
}
