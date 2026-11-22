package com.example.myapplication.ui;

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
import android.os.CountDownTimer;
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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.example.myapplication.R;
import com.example.myapplication.data.model.AlcoholRecordRequest;
import com.example.myapplication.data.model.AlcoholRecordResponse;
import com.example.myapplication.network.api.RetrofitClient;
import com.example.myapplication.utils.SecureTokenManager;
import com.google.gson.Gson;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * AlcoholMeasureActivity – Bước 2: Đo nồng độ cồn qua BLE (ESP32_Alcohol).
 *
 * Quy trình:
 *  1. Nhấn nút "Kết nối thiết bị" -> Quét và kết nối BLE.
 *  2. Kết nối thành công -> Hiển thị thông báo "Kết nối thiết bị thành công" rồi tự động ẩn sau 2s.
 *  3. Nút "Bắt đầu đo" kích hoạt -> Người dùng nhấn -> Bắt đầu đếm ngược 3s "Hãy thổi trong: 3s...".
 *  4. Đồng thời gửi lệnh đo '1' xuống BLE (FFE2).
 *  5. Nhận kết quả từ BLE (FFE1) -> Hiển thị kết quả mg/L.
 *  6. Bấm "Gửi kết quả" -> POST /alcohol-records lên Backend.
 *     - Nếu <= 0.0 mg/L -> Hợp lệ -> Vào buồng lái (DriverMonitorActivity).
 *     - Nếu > 0.0 mg/L -> Cảnh báo không được phép lái xe -> Quay về Dashboard.
 */
public class AlcoholMeasureActivity extends AppCompatActivity {

    private static final String TAG = "AlcoholMeasure";
    private static final int PERMISSION_REQ_CODE = 300;

    // UUIDs khớp với firmware ESP32
    private static final UUID SERVICE_UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB");
    private static final UUID NOTIFY_UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB");
    private static final UUID COMMAND_UUID = UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final double ALCOHOL_THRESHOLD = 0.0;

    // Views
    private TextView tvDriverInfo, tvCountdown, tvAlcoholLevel, tvAlcoholUnit, tvSuccessPill, tvBleStatus, tvGuideMsg;
    private Button btnConnect, btnMeasure, btnSubmit;
    private ProgressBar progressBar;

    // BLE Objects
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattService alcoholService;
    private boolean isScanning = false;
    private boolean isConnected = false;

    // Dữ liệu đo & Intent
    private double currentAlcoholLevel = -1.0;
    private boolean measurementDone = false;
    private String driverId;
    private String tripId;
    private String vehicleId;
    private String vehiclePlate;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private CountDownTimer countDownTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_alcohol_measure);

        Intent intent = getIntent();
        if (intent != null) {
            driverId = intent.getStringExtra("DRIVER_ID");
            tripId = intent.getStringExtra("TRIP_ID");
            vehicleId = intent.getStringExtra("VEHICLE_ID");
            vehiclePlate = intent.getStringExtra("VEHICLE_PLATE");
        }

        // Ưu tiên lấy driverId UUID từ SecureTokenManager nếu Intent truyền vào bị thiếu
        String savedDriverId = SecureTokenManager.getInstance(getApplicationContext()).getDriverId();
        if (savedDriverId != null && !savedDriverId.trim().isEmpty()) {
            driverId = savedDriverId.trim();
        }

        bindViews();
        updateDriverInfoDisplay();

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            bluetoothAdapter = bm.getAdapter();
        }

        btnConnect.setOnClickListener(v -> checkPermissionsAndConnect());
        btnMeasure.setOnClickListener(v -> startMeasurementWithCountdown());
        btnSubmit.setOnClickListener(v -> submitRecord());

        checkPermissions();
    }

    private void bindViews() {
        tvDriverInfo = findViewById(R.id.tvAlcoholDriverInfo);
        tvCountdown = findViewById(R.id.tvCountdown);
        tvAlcoholLevel = findViewById(R.id.tvAlcoholLevel);
        tvAlcoholUnit = findViewById(R.id.tvAlcoholUnit);
        tvSuccessPill = findViewById(R.id.tvSuccessPill);
        tvBleStatus = findViewById(R.id.tvBleStatus);
        tvGuideMsg = findViewById(R.id.tvGuideMsg);

        btnConnect = findViewById(R.id.btnConnect);
        btnMeasure = findViewById(R.id.btnMeasure);
        btnSubmit = findViewById(R.id.btnSubmit);
        progressBar = findViewById(R.id.progressBar);
    }

    private void updateDriverInfoDisplay() {
        StringBuilder sb = new StringBuilder();
        SecureTokenManager tokenManager = SecureTokenManager.getInstance(getApplicationContext());
        String username = tokenManager.getUsername();
        String fullName = tokenManager.getFullName();

        if (username != null && !username.trim().isEmpty()) {
            sb.append("Tài xế: ").append(username.trim());
        } else if (fullName != null && !fullName.trim().isEmpty()) {
            sb.append("Tài xế: ").append(fullName.trim());
        } else if (driverId != null && !driverId.isEmpty()) {
            String shortCode = driverId.length() > 8 ? driverId.substring(0, 8).toUpperCase() : driverId.toUpperCase();
            sb.append("Tài xế: DRV-").append(shortCode);
        }

        if (vehiclePlate != null && !vehiclePlate.isEmpty()) {
            if (sb.length() > 0) sb.append("  |  ");
            sb.append("Xe: ").append(vehiclePlate);
        }
        tvDriverInfo.setText(sb.length() > 0 ? sb.toString() : "Thông tin tài xế");
    }

    private void setStatus(String msg) {
        runOnUiThread(() -> tvBleStatus.setText(msg));
    }

    // ── Quyền Bluetooth & Location ──────────────────────────────────────────
    private void checkPermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN);
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        needed.add(Manifest.permission.ACCESS_FINE_LOCATION);

        List<String> toRequest = new ArrayList<>();
        for (String p : needed) {
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), PERMISSION_REQ_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            boolean ok = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            }
            if (!ok) {
                Toast.makeText(this, "Cần cấp quyền Bluetooth và Vị trí để quét thiết bị!", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void checkPermissionsAndConnect() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Vui lòng bật Bluetooth trên điện thoại!", Toast.LENGTH_SHORT).show();
            return;
        }

        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Vui lòng bật Định vị (GPS) để quét thiết bị!", Toast.LENGTH_LONG).show();
            return;
        }

        startScanning();
    }

    // ── Quét BLE ────────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private void startScanning() {
        disconnectGatt();

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            setStatus("Lỗi khởi tạo bộ quét Bluetooth!");
            return;
        }

        setStatus("Đang tìm kiếm thiết bị đo nồng độ cồn...");
        progressBar.setVisibility(View.VISIBLE);
        btnConnect.setEnabled(false);
        isScanning = true;

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        bluetoothLeScanner.startScan(null, settings, scanCallback);
        Log.d(TAG, "Bắt đầu quét BLE...");

        // Timeout 15s
        mainHandler.postDelayed(() -> {
            if (isScanning && !isConnected) {
                stopScanning();
                runOnUiThread(() -> {
                    setStatus("Không tìm thấy thiết bị! Vui lòng thử lại.");
                    btnConnect.setEnabled(true);
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
            Log.d(TAG, "Đã dừng quét BLE");
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            @SuppressLint("MissingPermission") String name = device.getName();

            boolean isTarget = false;
            if (name != null && (name.contains("Alcohol") || name.contains("ESP32_Alcohol"))) {
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
                Log.i(TAG, "Tìm thấy thiết bị mục tiêu: " + name + " [" + device.getAddress() + "]");
                stopScanning();
                connectToDevice(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "Quét BLE thất bại, lỗi: " + errorCode);
            isScanning = false;
            runOnUiThread(() -> {
                setStatus("Lỗi quét BLE: " + errorCode);
                btnConnect.setEnabled(true);
                progressBar.setVisibility(View.GONE);
            });
        }
    };

    // ── Kết nối GATT ────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private void connectToDevice(BluetoothDevice device) {
        setStatus("Đang kết nối: " + (device.getName() != null ? device.getName() : device.getAddress()));
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
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                setStatus("Đã kết nối thiết bị. Đang thiết lập dịch vụ...");
                mainHandler.postDelayed(gatt::discoverServices, 1000);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                runOnUiThread(() -> {
                    if (!measurementDone) {
                        setStatus("Đã ngắt kết nối thiết bị!");
                        btnConnect.setVisibility(View.VISIBLE);
                        btnConnect.setEnabled(true);
                        btnMeasure.setVisibility(View.GONE);
                        progressBar.setVisibility(View.GONE);
                    }
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
            if (status != BluetoothGatt.GATT_SUCCESS) return;

            alcoholService = gatt.getService(SERVICE_UUID);
            if (alcoholService == null) {
                setStatus("Không tìm thấy dịch vụ đo trên thiết bị!");
                return;
            }

            // Đăng ký Notify FFE1
            BluetoothGattCharacteristic notifyChar = alcoholService.getCharacteristic(NOTIFY_UUID);
            if (notifyChar != null) {
                gatt.setCharacteristicNotification(notifyChar, true);
                BluetoothGattDescriptor desc = notifyChar.getDescriptor(CCCD_UUID);
                if (desc != null) {
                    mainHandler.postDelayed(() -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        } else {
                            desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            gatt.writeDescriptor(desc);
                        }
                    }, 500);
                }
            }

            // Kết nối thành công -> Hiển thị thông báo thành công rồi ẩn sau 2s, hiện nút "Bắt đầu đo"
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                tvSuccessPill.setVisibility(View.VISIBLE);
                tvSuccessPill.setText("✓ Kết nối thiết bị thành công!");
                setStatus("Thiết bị đã sẵn sàng. Hãy bấm \"Bắt đầu đo\".");

                btnConnect.setVisibility(View.GONE);
                btnMeasure.setVisibility(View.VISIBLE);
                btnMeasure.setEnabled(true);

                // Tự động ẩn thông báo thành công sau 2 giây
                mainHandler.postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        tvSuccessPill.setVisibility(View.GONE);
                    }
                }, 2000);
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data == null || data.length == 0) return;

            String raw = new String(data).trim();
            Log.i(TAG, "Dữ liệu trả về từ ESP32: " + raw);

            try {
                currentAlcoholLevel = Double.parseDouble(raw);
                measurementDone = true;

                runOnUiThread(() -> {
                    if (countDownTimer != null) {
                        countDownTimer.cancel();
                    }
                    tvCountdown.setText("Hoàn tất đo!");
                    tvAlcoholLevel.setText(String.format(Locale.US, "%.2f", currentAlcoholLevel));
                    tvAlcoholUnit.setVisibility(View.VISIBLE);
                    progressBar.setVisibility(View.GONE);

                    btnSubmit.setVisibility(View.VISIBLE);
                    btnSubmit.setEnabled(true);
                    btnMeasure.setEnabled(true);
                    btnMeasure.setText("Đo lại");

                    if (currentAlcoholLevel > ALCOHOL_THRESHOLD) {
                        setStatus("⚠ Phát hiện nồng độ cồn! Nhấn \"Gửi kết quả\" để xác nhận.");
                        tvAlcoholLevel.setTextColor(0xFFE53935); // Đỏ
                    } else {
                        setStatus("✅ Không có cồn. Nhấn \"Gửi kết quả\" để tiếp tục.");
                        tvAlcoholLevel.setTextColor(0xFF43A047); // Xanh
                    }
                });
            } catch (NumberFormatException e) {
                Log.e(TAG, "Lỗi định dạng dữ liệu từ ESP32: " + raw);
            }
        }
    };

    // ── Bắt đầu đo với đếm ngược 3s ─────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private void startMeasurementWithCountdown() {
        if (alcoholService == null || bluetoothGatt == null) {
            Toast.makeText(this, "Chưa kết nối với thiết bị!", Toast.LENGTH_SHORT).show();
            return;
        }

        btnMeasure.setEnabled(false);
        btnSubmit.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        tvAlcoholUnit.setVisibility(View.GONE);
        tvAlcoholLevel.setText("--");
        tvAlcoholLevel.setTextColor(0xFFFFFFFF);

        // Gửi lệnh '1' xuống Characteristic FFE2
        BluetoothGattCharacteristic cmd = alcoholService.getCharacteristic(COMMAND_UUID);
        if (cmd != null) {
            byte[] value = "1".getBytes();
            cmd.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt.writeCharacteristic(cmd, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                cmd.setValue(value);
                bluetoothGatt.writeCharacteristic(cmd);
            }
            Log.i(TAG, "Đã gửi lệnh đo '1' tới ESP32");
        }

        setStatus("Vui lòng thổi vào cảm biến ngay bây giờ!");

        // Đếm ngược 3 giây cho tài xế thổi
        if (countDownTimer != null) countDownTimer.cancel();
        countDownTimer = new CountDownTimer(3000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int sec = (int) Math.ceil(millisUntilFinished / 1000.0);
                tvCountdown.setText("Hãy thổi liên tục: " + sec + "s");
            }

            @Override
            public void onFinish() {
                tvCountdown.setText("Đang phân tích kết quả...");
                setStatus("Đang đọc nồng độ cồn từ thiết bị...");
            }
        }.start();
    }

    // ── Gửi kết quả lên Backend ─────────────────────────────────────────────
    private void submitRecord() {
        if (currentAlcoholLevel < 0) {
            Toast.makeText(this, "Chưa có kết quả đo!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (driverId == null || driverId.trim().isEmpty()) {
            Toast.makeText(this, "Lỗi: Không tìm thấy ID tài xế!", Toast.LENGTH_LONG).show();
            return;
        }

        // Tạo format ISO-8601 measurementTime (ví dụ: 2026-11-21T01:00:00)
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault());
        String currentTimeIso = sdf.format(new Date());

        AlcoholRecordRequest request = new AlcoholRecordRequest(
                driverId.trim(),
                tripId != null && !tripId.trim().isEmpty() ? tripId.trim() : null,
                currentAlcoholLevel,
                currentTimeIso,
                "Kiểm tra nồng độ cồn trước khi bắt đầu ca lái"
        );

        String token = SecureTokenManager.getInstance(getApplicationContext()).getToken();
        String bearerToken = (token != null && !token.isEmpty()) ? "Bearer " + token.trim() : "";

        setStatus("Đang gửi kết quả lên hệ thống...");
        btnSubmit.setEnabled(false);

        Log.i(TAG, "POST /alcohol-records Body: " + new Gson().toJson(request));

        RetrofitClient.getApiService()
                .submitAlcoholRecord(bearerToken, request)
                .enqueue(new Callback<AlcoholRecordResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<AlcoholRecordResponse> call,
                                           @NonNull Response<AlcoholRecordResponse> response) {
                        btnSubmit.setEnabled(true);
                        if (response.isSuccessful()) {
                            Log.i(TAG, "Gửi kết quả đo thành công! HTTP " + response.code());
                            AlcoholRecordResponse recordResponse = response.body();
                            handleAlcoholResult(recordResponse);
                        } else {
                            String errBody = "";
                            try {
                                if (response.errorBody() != null) {
                                    errBody = response.errorBody().string();
                                }
                            } catch (Exception ignored) {}
                            Log.w(TAG, "Server lỗi: HTTP " + response.code() + " | Body: " + errBody);
                            
                            // Parse message lỗi nếu có
                            String msg = "Lỗi từ máy chủ (" + response.code() + ")";
                            try {
                                AlcoholRecordResponse errDto = new Gson().fromJson(errBody, AlcoholRecordResponse.class);
                                if (errDto != null && errDto.getMessage() != null) {
                                    msg = errDto.getMessage();
                                }
                            } catch (Exception ignored) {}

                            final String finalMsg = msg;
                            runOnUiThread(() -> {
                                Toast.makeText(AlcoholMeasureActivity.this, finalMsg, Toast.LENGTH_LONG).show();
                                setStatus("Lỗi gửi dữ liệu: " + finalMsg);
                            });
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<AlcoholRecordResponse> call, @NonNull Throwable t) {
                        btnSubmit.setEnabled(true);
                        Log.e(TAG, "Lỗi kết nối khi gửi kết quả: " + t.getMessage());
                        runOnUiThread(() -> {
                            Toast.makeText(AlcoholMeasureActivity.this, "Lỗi kết nối mạng: " + t.getMessage(), Toast.LENGTH_LONG).show();
                            setStatus("Lỗi mạng! Vui lòng thử lại.");
                        });
                    }
                });
    }

    private void handleAlcoholResult(AlcoholRecordResponse response) {
        runOnUiThread(() -> {
            boolean isViolation = false;
            if (response != null && response.getData() != null && response.getData().getViolation() != null) {
                isViolation = response.getData().isViolation();
            } else {
                isViolation = (currentAlcoholLevel > ALCOHOL_THRESHOLD);
            }

            if (isViolation) {
                // Vi phạm nồng độ cồn (data.violation == true)
                showAlcoholFailedDialog();
            } else {
                // Đủ điều kiện lái xe
                Toast.makeText(this, "Xác nhận đủ điều kiện an toàn lái xe!", Toast.LENGTH_SHORT).show();
                proceedToMonitor();
            }
        });
    }

    private void showAlcoholFailedDialog() {
        new AlertDialog.Builder(this)
                .setTitle("🚫 Không được phép lái xe")
                .setMessage("Nồng độ cồn vượt ngưỡng cho phép. Chuyến đi đã bị hủy và chuyển cho điều hành xử lý.")
                .setCancelable(false)
                .setPositiveButton("Đã hiểu, quay về", (dialog, which) -> {
                    dialog.dismiss();
                    disconnectGatt();
                    Intent intent = new Intent(this, DashboardActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                    finish();
                })
                .show();
    }

    private void proceedToMonitor() {
        disconnectGatt();
        Intent intent = new Intent(this, DriverMonitorActivity.class);
        if (tripId != null) intent.putExtra("TRIP_ID", tripId);
        if (vehicleId != null) intent.putExtra("VEHICLE_ID", vehicleId);
        if (vehiclePlate != null) intent.putExtra("VEHICLE_PLATE", vehiclePlate);
        startActivity(intent);
        finish();
    }

    @SuppressLint("MissingPermission")
    private void disconnectGatt() {
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (Exception ignored) {}
            bluetoothGatt = null;
        }
        isConnected = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        disconnectGatt();
    }
}
