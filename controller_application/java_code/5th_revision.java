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
import android.os.Handler;
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

    // Bluetooth objects
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;
    private InputStream inputStream;
    // Standard SPP UUID for Bluetooth modules
    private final UUID BT_MODULE_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // UI elements
    private Button btnConnect, btnDisconnect;
    private Button btnMotor1Forward, btnMotor1Reverse, btnMotor1Stop,
            btnMotor2Forward, btnMotor2Reverse, btnMotor2Stop;
    private SeekBar speedSeekBar;
    private TextView speedValueTextView, connectedDeviceTextView,
            rpmValueTextView, statusValueTextView, consistencyRpmTextView;

    // Configurable thresholds
    private static final int DROP_THRESHOLD = 50;      // "sudden drop" threshold for compensation
    private static final int CONSISTENCY_TOLERANCE = 30;      // ±30 is considered consistent
    private static final int COMPENSATION_INCREMENT = 10;       // increase speed by this during compensation
    private static final int CONSISTENT_READINGS_TARGET = 3;   // revert after these many consistent RPM readings

    // Handling speed changes
    private int currentSpeed = 0;
    private int originalSpeed = 0;     // speed before first compensation
    private boolean isCompensating = false;

    // Track RPM
    private Integer lastRpm = null;
    private int consecutiveConsistentReadings = 0;

    // For ignoring quick manual speed changes
    private boolean isManualSpeedChange = false;
    private static final long MANUAL_SPEED_CHANGE_WINDOW = 1500; // ms to ignore compensation attempts
    private long lastManualChangeTimestamp = 0;

    // Handlers for scheduling
    private Handler handler = new Handler();

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
        consistencyRpmTextView = findViewById(R.id.optimalRpmValueTextView);

        // Assign initial text
        connectedDeviceTextView.setText("Not Connected");
        rpmValueTextView.setText("RPM: 0");
        statusValueTextView.setText("Status: N/A");
        consistencyRpmTextView.setText("Consistency: Unknown");

        // Initialize Bluetooth
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_SHORT).show();
            finish();
        }
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, 1);
        }

        // Request Bluetooth permissions (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                        REQUEST_BLUETOOTH_CONNECT
                );
            }
        }

        // Set up connect / disconnect
        btnConnect.setOnClickListener(view -> showPairedDevicesList());
        btnDisconnect.setOnClickListener(view -> disconnectDevice());

        // SeekBar to set speed
        speedSeekBar.setMax(100);
        speedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentSpeed = progress;
                speedValueTextView.setText("Speed: " + progress);

                // If user is changing speed manually, ignore compensation
                if (fromUser) {
                    isManualSpeedChange = true;
                    lastManualChangeTimestamp = System.currentTimeMillis();
                }

                // If user changes speed while in compensation, revert it
                if (fromUser && isCompensating) {
                    revertToOriginalSpeed();
                }

                // Send speed command to device
                if (outputStream != null) {
                    sendCommand("SPEED" + progress + "\n");
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Not used
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Not used
            }
        });

        // Motor 1 controls
        btnMotor1Forward.setOnClickListener(view -> sendCommand("DIR1F\n"));
        btnMotor1Reverse.setOnClickListener(view -> sendCommand("DIR1R\n"));
        btnMotor1Stop.setOnClickListener(view -> sendCommand("DIR1S\n"));

        // Motor 2 controls
        btnMotor2Forward.setOnClickListener(view -> sendCommand("DIR2F\n"));
        btnMotor2Reverse.setOnClickListener(view -> sendCommand("DIR2R\n"));
        btnMotor2Stop.setOnClickListener(view -> sendCommand("DIR2S\n"));
    }

    /**
     * Shows paired devices in a dialog and connects when selected.
     */
    private void showPairedDevicesList() {
        if (!hasBluetoothPermission()) {
            Toast.makeText(this, "Bluetooth permission not granted", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
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

    /**
     * Connect to the chosen device.
     */
    private void connectToDevice(BluetoothDevice device) {
        new Thread(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this,
                                    "Bluetooth permission not granted",
                                    Toast.LENGTH_SHORT).show()
                    );
                    return;
                }
            }
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(BT_MODULE_UUID);
                bluetoothSocket.connect();
                outputStream = bluetoothSocket.getOutputStream();
                inputStream = bluetoothSocket.getInputStream();

                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this,
                            "Connected to " + device.getName(),
                            Toast.LENGTH_SHORT
                    ).show();
                    connectedDeviceTextView.setText("Connected to: " + device.getName());
                    speedSeekBar.setProgress(0);
                    speedValueTextView.setText("Speed: 0");
                    sendCommand("SPEED0\n");
                    consistencyRpmTextView.setText("Consistency: Unknown");
                    lastRpm = null;
                });

                // Start listening for data
                startListeningForData();

            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this,
                                "Connection failed: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show()
                );
            } catch (SecurityException e) {
                e.printStackTrace();
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this,
                                "Security exception: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show()
                );
            }
        }).start();
    }

    /**
     * Disconnect from the current device.
     */
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
                Toast.makeText(MainActivity.this,
                        "Disconnected", Toast.LENGTH_SHORT).show();
                connectedDeviceTextView.setText("Not Connected");
                rpmValueTextView.setText("RPM: 0");
                statusValueTextView.setText("Status: N/A");
                consistencyRpmTextView.setText("Consistency: Unknown");
                lastRpm = null;
                revertToOriginalSpeed();
            });
        }).start();
    }

    /**
     * Send a command string over Bluetooth if connected.
     */
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

    /**
     * Start listening for lines of data, e.g. "RPM<number>".
     */
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
                            runOnUiThread(() -> handleRpmReading(measuredRpm));
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

    /**
     * Decide how to handle new RPM readings, including compensation for sudden drops.
     */
    private void handleRpmReading(int measuredRpm) {
        // Update the UI
        rpmValueTextView.setText("RPM: " + measuredRpm);

        // If the user just changed speed, ignore compensation briefly
        if (isManualSpeedChange && (System.currentTimeMillis() - lastManualChangeTimestamp < MANUAL_SPEED_CHANGE_WINDOW)) {
            // Reset consistency counters in this window, but do not compensate
            consistencyRpmTextView.setText("Consistency: Ignoring (Recent Manual Change)");
            consecutiveConsistentReadings = 0;
        } else {
            // Past the quick-change window
            isManualSpeedChange = false; // no longer ignoring

            String consistencyStatus;
            if (lastRpm == null) {
                consistencyStatus = "No Previous Reading";
                consecutiveConsistentReadings = 0;
            } else {
                int diff = Math.abs(measuredRpm - lastRpm);
                if (diff <= CONSISTENCY_TOLERANCE) {
                    consistencyStatus = "Consistent (±" + CONSISTENCY_TOLERANCE + ")";
                    consecutiveConsistentReadings++;
                } else {
                    consistencyStatus = "Not Consistent (±" + CONSISTENCY_TOLERANCE + ")";
                    consecutiveConsistentReadings = 0;

                    // If there's a sudden drop, apply compensation
                    if ((measuredRpm < lastRpm) && (lastRpm - measuredRpm >= DROP_THRESHOLD)) {
                        applySpeedCompensation();
                    }
                }
            }
            consistencyRpmTextView.setText("Consistency: " + consistencyStatus);

            // If in compensation mode, check if we have enough consecutive consistent readings
            if (isCompensating && consecutiveConsistentReadings >= CONSISTENT_READINGS_TARGET) {
                revertToOriginalSpeed();
            }
        }

        // Update status
        String status = (currentSpeed == 0)
                ? (measuredRpm == 0 ? "Idle" : "Running")
                : "Running";
        statusValueTextView.setText("Status: " + status);

        // Store last reading
        lastRpm = measuredRpm;
    }

    /**
     * Increase speed by COMPENSATION_INCREMENT, or further if already compensating.
     */
    private void applySpeedCompensation() {
        if (!isCompensating) {
            // First time we compensate: remember the speed
            originalSpeed = currentSpeed;
            isCompensating = true;
        }
        int newSpeed = Math.min(currentSpeed + COMPENSATION_INCREMENT, 100);
        currentSpeed = newSpeed;
        speedSeekBar.setProgress(newSpeed);
        sendCommand("SPEED" + newSpeed + "\n");
    }

    /**
     * Restore speed to original and reset compensation flags.
     */
    private void revertToOriginalSpeed() {
        if (isCompensating) {
            isCompensating = false;
            consecutiveConsistentReadings = 0;
            speedSeekBar.setProgress(originalSpeed);
            currentSpeed = originalSpeed;
            sendCommand("SPEED" + originalSpeed + "\n");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectDevice();
        handler.removeCallbacksAndMessages(null);
    }

    /**
     * Return whether we have the required Bluetooth permission for connecting to devices.
     */
    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }
}