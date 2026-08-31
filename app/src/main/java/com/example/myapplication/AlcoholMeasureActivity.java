package com.example.myapplication;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.gson.Gson;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AlcoholMeasureActivity extends AppCompatActivity {

    private static final String TAG = "AlcoholMeasure";
    private static final int PERMISSION_REQUEST_CODE = 200;

    // UUIDs match ESP32 code exactly
    private static final UUID SERVICE_UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB");
    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB");
    private static final UUID COMMAND_CHARACTERISTIC_UUID = UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private TextView tvDriverInfo, tvBleStatus, tvAlcoholLevel;
    private Button btnMeasure, btnSubmit;
    private ProgressBar progressBar;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private String driverId;
    private double currentAlcoholLevel = 0.0;
    private boolean isScanning = false;

    private final OkHttpClient httpClient = new OkHttpClient();
    private final Gson gson = new Gson();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alcohol_measure);

        tvDriverInfo = findViewById(R.id.tvDriverInfo);
        tvBleStatus = findViewById(R.id.tvBleStatus);
        tvAlcoholLevel = findViewById(R.id.tvAlcoholLevel);
        btnMeasure = findViewById(R.id.btnMeasure);
        btnSubmit = findViewById(R.id.btnSubmit);
        progressBar = findViewById(R.id.progressBar);

        driverId = getIntent().getStringExtra("DRIVER_ID");
        tvDriverInfo.setText("Driver ID: " + (driverId != null ? driverId : "N/A"));

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        btnMeasure.setOnClickListener(v -> startScanning());
        btnSubmit.setOnClickListener(v -> submitRecord());

        btnMeasure.setEnabled(false);
        checkPermissions();
    }

    private void checkPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);

        List<String> listNeeded = new ArrayList<>();
        for (String p : permissionsNeeded) {
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listNeeded.add(p);
            }
        }

        if (!listNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, listNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        } else {
            btnMeasure.setEnabled(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean granted = true;
            for (int r : grantResults) { if (r != PackageManager.PERMISSION_GRANTED) granted = false; }
            if (granted) {
                btnMeasure.setEnabled(true);
            } else {
                Toast.makeText(this, "Hãy cấp quyền Bluetooth và Vị trí để quét thiết bị", Toast.LENGTH_LONG).show();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void startScanning() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Vui lòng bật Bluetooth", Toast.LENGTH_SHORT).show();
            return;
        }

        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "HÃY BẬT VỊ TRÍ (GPS) TRÊN ĐIỆN THOẠI!", Toast.LENGTH_LONG).show();
            return;
        }

        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            tvBleStatus.setText("Lỗi khởi tạo Scanner");
            return;
        }

        tvBleStatus.setText("Đang tìm thiết bị đo...");
        progressBar.setVisibility(View.VISIBLE);
        btnMeasure.setEnabled(false);
        isScanning = true;

        Log.d(TAG, "Bắt đầu quét BLE (Quét tất cả để tránh lỗi filter)...");
        
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        // Quét không filter để tránh lỗi một số device không tìm thấy ESP32
        bluetoothLeScanner.startScan(null, settings, scanCallback);

        // Timeout 15s
        mainHandler.postDelayed(() -> {
            if (isScanning && bluetoothGatt == null) {
                stopScanning();
                runOnUiThread(() -> {
                    tvBleStatus.setText("Không tìm thấy thiết bị! Hãy thử lại.");
                    btnMeasure.setEnabled(true);
                    progressBar.setVisibility(View.GONE);
                });
            }
        }, 15000);
    }

    @SuppressLint("MissingPermission")
    private void stopScanning() {
        if (bluetoothLeScanner != null && isScanning) {
            isScanning = false;
            bluetoothLeScanner.stopScan(scanCallback);
            Log.d(TAG, "Đã dừng quét");
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            @SuppressLint("MissingPermission") String name = device.getName();
            
            Log.d(TAG, "Thấy: " + (name != null ? name : "N/A") + " [" + device.getAddress() + "]");

            // Lọc theo tên HOẶC Service UUID trong gói Advertisement
            boolean isTarget = false;
            if (name != null && name.contains("Alcohol")) {
                isTarget = true;
            } else if (result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null) {
                for (ParcelUuid uuid : result.getScanRecord().getServiceUuids()) {
                    if (uuid.getUuid().equals(SERVICE_UUID)) {
                        isTarget = true;
                        break;
                    }
                }
            }

            if (isTarget) {
                Log.i(TAG, "Đã tìm thấy ESP32! Dừng quét và kết nối...");
                stopScanning();
                connectToDevice(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "Quét thất bại! Mã lỗi: " + errorCode);
            isScanning = false;
            runOnUiThread(() -> {
                tvBleStatus.setText("Lỗi quét BLE: " + errorCode);
                btnMeasure.setEnabled(true);
                progressBar.setVisibility(View.GONE);
            });
        }
    };

    @SuppressLint("MissingPermission")
    private void connectToDevice(BluetoothDevice device) {
        runOnUiThread(() -> tvBleStatus.setText("Đang kết nối: " + device.getAddress()));
        Log.d(TAG, "Kết nối GATT (TRANSPORT_LE) tới: " + device.getAddress());
        
        // Sử dụng TRANSPORT_LE cực kỳ quan trọng cho các máy đời mới để tránh lỗi GATT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "GATT State Change: " + newState + " | status: " + status);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread(() -> tvBleStatus.setText("Đã kết nối. Đang lấy dịch vụ..."));
                // Delay 1 giây để GATT ổn định trước khi tìm dịch vụ
                mainHandler.postDelayed(gatt::discoverServices, 1000);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread(() -> {
                    tvBleStatus.setText("Đã ngắt kết nối");
                    btnMeasure.setEnabled(true);
                    progressBar.setVisibility(View.GONE);
                    btnSubmit.setVisibility(View.GONE);
                });
                if (bluetoothGatt != null) {
                    bluetoothGatt.close();
                    bluetoothGatt = null;
                }
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "onServicesDiscovered status: " + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service != null) {
                    BluetoothGattCharacteristic notifyChar = service.getCharacteristic(CHARACTERISTIC_UUID);
                    if (notifyChar != null) {
                        Log.i(TAG, "Tìm thấy Characteristic FFE1. Bật Notify...");
                        gatt.setCharacteristicNotification(notifyChar, true);

                        BluetoothGattDescriptor descriptor = notifyChar.getDescriptor(CCCD_UUID);
                        if (descriptor != null) {
                            mainHandler.postDelayed(() -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                } else {
                                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                    gatt.writeDescriptor(descriptor);
                                }
                                Log.d(TAG, "Đã gửi lệnh ghi descriptor bật thông báo...");
                            }, 1000);
                        }
                    }
                    // QUAN TRỌNG: LUÔN gửi lệnh đo sau 2.5 giây, kể cả khi bật notification bị lỗi status 3
                    mainHandler.postDelayed(() -> sendStartCommand(gatt, service), 2500);
                } else {
                    Log.e(TAG, "Không tìm thấy Service FFE0!");
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data != null && data.length > 0) {
                String valueStr = new String(data).trim();
                Log.i(TAG, "DỮ LIỆU TỪ ESP32: " + valueStr);
                try {
                    currentAlcoholLevel = Double.parseDouble(valueStr);
                    runOnUiThread(() -> {
                        tvAlcoholLevel.setText(String.format("%.2f mg/L", currentAlcoholLevel));
                        btnSubmit.setVisibility(View.VISIBLE);
                        progressBar.setVisibility(View.GONE);
                        tvBleStatus.setText("Đo hoàn tất!");
                    });
                } catch (Exception e) { Log.e(TAG, "Lỗi format dữ liệu: " + valueStr); }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "Descriptor write status: " + status);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "Command write status: " + status);
        }
    };

    @SuppressLint("MissingPermission")
    private void sendStartCommand(BluetoothGatt gatt, BluetoothGattService service) {
        BluetoothGattCharacteristic cmdChar = service.getCharacteristic(COMMAND_CHARACTERISTIC_UUID);
        if (cmdChar != null) {
            runOnUiThread(() -> tvBleStatus.setText("HÃY THỔI NGAY!"));
            Log.i(TAG, "Gửi lệnh đo '1' xuống ESP32...");
            byte[] value = "1".getBytes();
            cmdChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(cmdChar, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                cmdChar.setValue(value);
                gatt.writeCharacteristic(cmdChar);
            }
        }
    }

    private void submitRecord() {
        if (driverId == null) return;

        long driverIdLong;
        try {
            driverIdLong = Long.parseLong(driverId);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Driver ID không hợp lệ: " + driverId, Toast.LENGTH_LONG).show();
            return;
        }

        AlcoholRecordRequest record = new AlcoholRecordRequest();
        record.driverId = Long.parseLong(driverId);
        record.alcoholLevel = new BigDecimal(currentAlcoholLevel);
        record.notes = "BLE Alcohol Measure";

        RequestBody body = RequestBody.create(gson.toJson(record), MediaType.parse("application/json"));
        Request request = new Request.Builder().url(DriverVerifyActivity.BASE_URL + "/alcohol-records").post(body).build();

        tvBleStatus.setText("Đang gửi dữ liệu...");
        btnSubmit.setEnabled(false);

        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> {
                    Toast.makeText(AlcoholMeasureActivity.this, "Lỗi gửi: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    btnSubmit.setEnabled(true);
                });
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                runOnUiThread(() -> {
                    if (response.isSuccessful()) {
                        Toast.makeText(AlcoholMeasureActivity.this, "Lưu thành công!", Toast.LENGTH_SHORT).show();
                        startActivity(new Intent(AlcoholMeasureActivity.this, MainActivity.class));
                        finish();
                    } else {
                        Toast.makeText(AlcoholMeasureActivity.this, "Lỗi server: " + response.code(), Toast.LENGTH_SHORT).show();
                        btnSubmit.setEnabled(true);
                    }
                });
            }
        });
    }

    static class AlcoholRecordRequest { Long driverId; BigDecimal alcoholLevel; String notes; }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            if (bluetoothGatt != null) {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            }
        }
    }
}
