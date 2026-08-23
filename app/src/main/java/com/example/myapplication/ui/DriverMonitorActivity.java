package com.example.myapplication.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.myapplication.R;
import com.example.myapplication.ai.DetectionPipeline;
import com.example.myapplication.ai.DrowsinessDetector;
import com.example.myapplication.ai.SeatBeltDetector;
import com.example.myapplication.camera.CameraManager;
import com.example.myapplication.data.model.SomnolenceRecordDto;
import com.example.myapplication.network.api.AppRestClient;
import com.example.myapplication.network.stream.UdpFrameSender;
import com.example.myapplication.network.websocket.AppWebSocketClient;
import com.example.myapplication.service.LocationTrackingService;
import com.example.myapplication.utils.AppConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * DriverMonitorActivity - Màn hình chính giám sát buồng lái (Presenter / Controller):
 * Đạt chuẩn Đơn Trách Nhiệm (SRP): Chỉ quản lý vòng đời Activity và điều phối giao tiếp giữa các module:
 * - CameraManager (Thu nhận hình ảnh)
 * - DetectionPipeline (Bộ máy AI on-device)
 * - UdpFrameSender & AppWebSocketClient (Mạng truyền dẫn)
 * - LocationTrackingService (Định vị GPS nền)
 * - UiManager (Hiển thị giao diện & cảnh báo)
 */
public class DriverMonitorActivity extends AppCompatActivity {

    private static final String TAG = "DriverMonitor";

    // Modules
    private UiManager uiManager;
    private CameraManager cameraManager;
    private DetectionPipeline detectionPipeline;
    private UdpFrameSender udpSender;
    private AppWebSocketClient wsClient;

    private boolean isTripStarted = false;
    private long lastDrowsyAlertTime = 0;
    private long lastSeatbeltAlertTime = 0;
    private long lastDistractedAlertTime = 0;
    private static final long ALERT_COOLDOWN_MS = 10000L; // 10 giây giữa các lần bắn vi phạm

    private final ActivityResultLauncher<String[]> permissionsLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Boolean cameraGranted = result.getOrDefault(Manifest.permission.CAMERA, false);
                Boolean locationGranted = result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false);

                if (cameraGranted) {
                    startCameraStream();
                } else {
                    Toast.makeText(this, "App cần quyền camera để hoạt động!", Toast.LENGTH_LONG).show();
                    uiManager.statusText.setText("Cần cấp quyền camera");
                    uiManager.statusIcon.setText("🚫");
                }

                if (locationGranted && isTripStarted) {
                    LocationTrackingService.start(this);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Giữ màn hình luôn sáng khi lái xe
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        // 2. Khởi tạo UI & Camera Modules
        uiManager = new UiManager(this);
        cameraManager = new CameraManager(this);

        uiManager.btnSwitchCamera.setOnClickListener(v -> {
            if (isTripStarted) {
                cameraManager.toggleCamera(this, uiManager.cameraPreview,
                        detectionPipeline.getCameraExecutor(), this::onFrameCaptured);
            }
        });

        uiManager.btnStartTrip.setOnClickListener(v -> {
            isTripStarted = true;
            uiManager.startOverlay.setVisibility(android.view.View.GONE);
            checkAndStartServices();
        });

        // 3. Khởi tạo AI Detection Pipeline
        detectionPipeline = new DetectionPipeline();
        String[] labels = loadLabels();
        detectionPipeline.loadModelsAsync(this, labels, new DetectionPipeline.InitCallback() {
            @Override
            public void onSuccess() {
                uiManager.enableStartButton();
                Log.d(TAG, "AI models ready");
            }

            @Override
            public void onError(Exception e) {
                uiManager.showError("Lỗi load model: " + e.getMessage());
            }
        });

        // 4. Khởi tạo Network Streaming (UDP + WebSocket)
        initNetworkStreaming();
    }

    private void checkAndStartServices() {
        boolean hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (hasCamera && hasLocation) {
            startCameraStream();
            LocationTrackingService.start(this);
        } else {
            permissionsLauncher.launch(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    private void startCameraStream() {
        cameraManager.startCamera(this, uiManager.cameraPreview,
                detectionPipeline.getCameraExecutor(), this::onFrameCaptured);
    }

    /**
     * Callback nhận Frame ảnh Bitmap từ CameraManager:
     * Tách làm 2 nhánh độc lập: Gửi UDP Stream và Chạy AI Detection Pipeline
     */
    private void onFrameCaptured(Bitmap bitmap, long timestampMs) {
        // Nhánh 1: Gửi UDP video frame
        if (udpSender != null) {
            udpSender.sendFrame(bitmap);
        }

        // Nhánh 2: Chạy AI On-Device
        detectionPipeline.processFrame(bitmap, timestampMs, (detections, dResult, fps, imgW, imgH) -> {
            runOnUiThread(() -> uiManager.updateUI(detections, dResult, fps, imgW, imgH));
            checkAndSendAlerts(bitmap, detections, dResult);
        });
    }

    /**
     * Kiểm tra kết quả AI và gửi cảnh báo WebSocket + POST /violations (Multipart)
     */
    private void checkAndSendAlerts(Bitmap currentFrame, List<SeatBeltDetector.Detection> detections, DrowsinessDetector.DrowsinessResult dResult) {
        long now = System.currentTimeMillis();

        // 1. Cảnh báo buồn ngủ
        if (dResult != null && dResult.isDrowsy) {
            if (now - lastDrowsyAlertTime > ALERT_COOLDOWN_MS) {
                lastDrowsyAlertTime = now;
                if (wsClient != null) {
                    wsClient.sendRealtimeAlert(AppConfig.DEFAULT_DRIVER_ID, "DROWSY", "Tài xế có dấu hiệu ngủ gật (nhắm mắt > 3s)!");
                }
                AppRestClient.getInstance().sendViolation(AppConfig.DEFAULT_DRIVER_ID, "DROWSY", currentFrame, 0.0, 0.0, null);
                SomnolenceRecordDto somnolenceDto = new SomnolenceRecordDto(
                        AppConfig.DEFAULT_DRIVER_ID,
                        dResult.ear,
                        dResult.mar,
                        dResult.closedEyeDurationMs
                );
                AppRestClient.getInstance().sendSomnolenceRecord(somnolenceDto, null);
            }
        }

        // 2. Cảnh báo mất tập trung (quay đầu)
        if (dResult != null && dResult.isDistracted) {
            if (now - lastDistractedAlertTime > ALERT_COOLDOWN_MS) {
                lastDistractedAlertTime = now;
                if (wsClient != null) {
                    wsClient.sendRealtimeAlert(AppConfig.DEFAULT_DRIVER_ID, "DISTRACTED", "Tài xế quay đầu mất tập trung!");
                }
                AppRestClient.getInstance().sendViolation(AppConfig.DEFAULT_DRIVER_ID, "DISTRACTED", currentFrame, 0.0, 0.0, null);
            }
        }

        // 3. Cảnh báo không đeo dây an toàn
        if (detections != null) {
            for (SeatBeltDetector.Detection d : detections) {
                if (d.classId == 0 || "no-seatbelt".equalsIgnoreCase(d.label)) {
                    if (now - lastSeatbeltAlertTime > ALERT_COOLDOWN_MS) {
                        lastSeatbeltAlertTime = now;
                        if (wsClient != null) {
                            wsClient.sendRealtimeAlert(AppConfig.DEFAULT_DRIVER_ID, "NO_SEATBELT", "Phát hiện tài xế không cài dây an toàn!");
                        }
                        AppRestClient.getInstance().sendViolation(AppConfig.DEFAULT_DRIVER_ID, "NO_SEATBELT", currentFrame, 0.0, 0.0, null);
                    }
                    break;
                }
            }
        }
    }

    private void initNetworkStreaming() {
        udpSender = new UdpFrameSender(AppConfig.SERVER_IP, AppConfig.UDP_PORT, AppConfig.DEFAULT_DRIVER_ID);
        wsClient = new AppWebSocketClient(AppConfig.SERVER_IP, AppConfig.DEFAULT_DRIVER_ID, new AppWebSocketClient.StreamCommandListener() {
            @Override
            public void onStartStream() {
                udpSender.startStreaming();
                Log.i(TAG, "✅ START_STREAM từ Backend");
            }

            @Override
            public void onStopStream() {
                udpSender.stopStreaming();
                Log.i(TAG, "⏹ STOP_STREAM từ Backend");
            }

            @Override
            public void onConnected() {
                runOnUiThread(() -> Toast.makeText(DriverMonitorActivity.this, "Đã kết nối Backend", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onDisconnected() {
                Log.w(TAG, "Mất kết nối Backend");
            }
        });
        wsClient.connect();
    }

    private String[] loadLabels() {
        List<String> labelList = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(getAssets().open("labels.txt")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) labelList.add(line);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error loading labels", e);
        }
        return labelList.toArray(new String[0]);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraManager != null) cameraManager.stopCamera();
        if (detectionPipeline != null) detectionPipeline.close();
        if (wsClient != null) wsClient.disconnect();
        if (udpSender != null) udpSender.close();
        LocationTrackingService.stop(this);
    }
}
