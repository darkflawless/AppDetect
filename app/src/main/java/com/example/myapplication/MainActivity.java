package com.example.myapplication;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.ImageProxy;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.example.myapplication.model.BoundingBoxDto;
import com.example.myapplication.model.SomnolenceRecordRequest;
import com.example.myapplication.model.ViolationReportRequest;
import com.example.myapplication.network.ApiClient;
import com.example.myapplication.network.LocationService;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final long ALERT_COOLDOWN_MS = 15000L; // 15 giây cooldown chống spam API

    private CameraManager cameraManager;
    private DetectionPipeline detectionPipeline;
    private MainAlertUIManager uiManager;

    private LinearLayout startOverlay;
    private Button btnStartTrip;

    // Detector + Threading
    private YoloDetector detector;
    private DrowsinessDetector drowsinessDetector;
    private PersonDetector personDetector; // detect toàn thân người
    private ExecutorService cameraExecutor;
    private ExecutorService yoloExecutor;
    private ExecutorService drowsinessExecutor;
    private ExecutorService personExecutor; // luồng riêng cho person detect

    // Camera state
    private int lensFacing = CameraSelector.LENS_FACING_FRONT;
    private boolean isTripStarted = false;

    private long lastSomnolenceAlertTime = 0L;
    private long lastSeatbeltAlertTime = 0L;

    // Permissions launcher (Camera + Location)
    private final ActivityResultLauncher<String[]> permissionsLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
                Boolean cameraGranted = permissions.getOrDefault(Manifest.permission.CAMERA, false);
                Boolean locationGranted = permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false);

    // Kết quả mới nhất
    private List<YoloDetector.Detection> lastYoloDetections = new ArrayList<>();
    private DrowsinessDetector.DrowsinessResult lastDrowsinessResult = null;
    private List<android.graphics.RectF> lastPersonBboxes = new ArrayList<>();

    // Labels
    private String[] labels;

    // ── WebSocket + UDP Streaming ─────────────────────────────────────────────
    private static final String BACKEND_IP = "192.168.42.222";
    private static final int UDP_PORT = 9090;
    private static final long DRIVER_ID = 1L; // ← ĐỔI thành driverId đăng nhập

    private AppWebSocketClient wsClient;
    private UdpFrameSender udpSender;

    private final ActivityResultLauncher<String> cameraPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "App cần quyền camera để hoạt động!", Toast.LENGTH_LONG).show();
                }

                if (Boolean.TRUE.equals(locationGranted)) {
                    startLocationService();
                } else {
                    Toast.makeText(this, "Chưa cấp quyền vị trí GPS!", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Bind Views
        PreviewView cameraPreview = findViewById(R.id.camera_preview);
        BoundingBoxOverlay bboxOverlay = findViewById(R.id.bbox_overlay);
        android.view.View redFlashOverlay = findViewById(R.id.red_flash_overlay);
        TextView statusIcon = findViewById(R.id.status_icon);
        TextView statusText = findViewById(R.id.status_text);
        TextView confidenceText = findViewById(R.id.confidence_text);
        TextView fpsText = findViewById(R.id.fps_text);
        btnSwitchCamera = findViewById(R.id.btn_switch_camera);
        startOverlay = findViewById(R.id.start_overlay);
        btnStartTrip = findViewById(R.id.btn_start_trip);

        // Managers
        cameraManager = new CameraManager(this, cameraPreview);
        uiManager = new MainAlertUIManager(bboxOverlay, redFlashOverlay, statusIcon, statusText, confidenceText, fpsText);
        detectionPipeline = new DetectionPipeline(this);

        btnSwitchCamera.setOnClickListener(v -> {
            if (isTripStarted) {
                cameraManager.switchCamera(detectionPipeline.getCameraExecutor(), this::onAnalyzeFrame);
            }
        });

        btnStartTrip.setOnClickListener(v -> {
            isTripStarted = true;
            startOverlay.setVisibility(android.view.View.GONE);
            checkPermissionsAndStart();
        });

        labels = loadLabels();

        cameraExecutor = Executors.newSingleThreadExecutor();
        yoloExecutor = Executors.newSingleThreadExecutor();
        drowsinessExecutor = Executors.newSingleThreadExecutor();
        personExecutor = Executors.newSingleThreadExecutor();

        // Load tất cả models trên background thread
        cameraExecutor.execute(() -> {
            try {
                drowsinessDetector = new DrowsinessDetector(this);
                detector = new YoloDetector(this, labels);
                personDetector = new PersonDetector(this);

                runOnUiThread(() -> {
                    btnStartTrip.setEnabled(true);
                    Log.d(TAG, "All models loaded and ready");
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load models", e);
                runOnUiThread(() -> {
                    statusIcon.setText("❌");
                    statusText.setText("Lỗi load model: " + e.getMessage());
                });
            }
        });

        // ── ② Khởi tạo UDP sender + WebSocket client ─────────────────────────
        udpSender = new UdpFrameSender(BACKEND_IP, UDP_PORT, DRIVER_ID);

        wsClient = new AppWebSocketClient(BACKEND_IP, DRIVER_ID, new AppWebSocketClient.StreamCommandListener() {
            @Override
            public void onStartStream() {
                udpSender.startStreaming();
                Log.i(TAG, "✅ Nhận lệnh START_STREAM → bắt đầu gửi camera");
            }

            @Override
            public void onStopStream() {
                udpSender.stopStreaming();
                Log.i(TAG, "⏹ Nhận lệnh STOP_STREAM → dừng gửi camera");
            }

            @Override
            public void onConnected() {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Đã kết nối Backend", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onDisconnected() {
                Log.w(TAG, "Mất kết nối Backend, đang thử lại...");
            }
        });
        wsClient.connect();
    }

    private void checkPermissionsAndStart() {
        String[] reqPermissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            reqPermissions = new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        } else {
            reqPermissions = new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            };
        }

        boolean hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (hasCamera && hasLocation) {
            startCamera();
            startLocationService();
        } else {
            permissionsLauncher.launch(reqPermissions);
        }
    }

    private void startCamera() {
        cameraManager.startCamera(detectionPipeline.getCameraExecutor(), this::onAnalyzeFrame);
    }

    private long lastTimestampMs = -1;

    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        isProcessing = true;
        if (drowsinessDetector == null) {
            imageProxy.close();
            isProcessing = false;
            return;
        }

        try {
            Bitmap bitmap = imageProxyToBitmap(imageProxy);
            if (bitmap == null) {
                isProcessing = false;
                return;
            }

            // ── ③ Gửi frame lên Backend nếu Admin đang yêu cầu xem ───────────
            if (udpSender != null) {
                udpSender.sendFrame(bitmap);
            }

            long timestampMs = imageProxy.getImageInfo().getTimestamp() / 1000000;
            if (timestampMs <= lastTimestampMs) {
                timestampMs = lastTimestampMs + 1;
            }
            lastTimestampMs = timestampMs;

            // ── Thu kết quả person detection ─────────────────────────────────────
            if (pendingPersonFuture != null && pendingPersonFuture.isDone()) {
                try {
                    lastPersonBboxes = new ArrayList<>(pendingPersonFuture.get());
                } catch (Exception e) {
                    Log.w(TAG, "Person result error: " + e.getMessage());
                }
                pendingPersonFuture = null;
            }

            // ── Thu kết quả drowsiness ────────────────────────────────────────────
            if (pendingDrowsinessFuture != null && pendingDrowsinessFuture.isDone()) {
                try {
                    lastDrowsinessResult = pendingDrowsinessFuture.get();
                } catch (Exception e) {
                    Log.w(TAG, "Drowsiness result error: " + e.getMessage());
                }
                pendingDrowsinessFuture = null;
            }

            // ── Thu kết quả seatbelt ──────────────────────────────────────────────
            if (pendingYoloFuture != null && pendingYoloFuture.isDone()) {
                try {
                    lastYoloDetections = new ArrayList<>(pendingYoloFuture.get());
                } catch (Exception e) {
                    Log.w(TAG, "YOLO result error: " + e.getMessage());
                }
                pendingYoloFuture = null;
            }

            // ── Submit Person Detection (step 1) ──────────────────────────────────
            if (personDetector != null && pendingPersonFuture == null) {
                final Bitmap personBitmap = bitmap.copy(bitmap.getConfig(), false);
                pendingPersonFuture = personExecutor.submit(() -> {
                    List<android.graphics.RectF> persons = personDetector.detectPersons(personBitmap);
                    personBitmap.recycle();
                    return persons;
                });
            }

            // ── Submit Seatbelt Detection (step 2): crop từng người ───────────────
            // Chạy khi: personFuture xong VÀ yoloFuture rảnh VÀ có ít nhất 1 người
            if (detector != null && pendingYoloFuture == null && !lastPersonBboxes.isEmpty()) {
                final Bitmap fullBitmap = bitmap.copy(bitmap.getConfig(), false);
                final int bmpW = fullBitmap.getWidth();
                final int bmpH = fullBitmap.getHeight();
                final List<android.graphics.RectF> persons = new ArrayList<>(lastPersonBboxes);

                pendingYoloFuture = yoloExecutor.submit(() -> {
                    List<YoloDetector.Detection> allSb = new ArrayList<>();

                    for (android.graphics.RectF pBbox : persons) {
                        // Tính tọa độ pixel của người trong ảnh gốc
                        int px1 = Math.max(0, (int) (pBbox.left * bmpW));
                        int py1 = Math.max(0, (int) (pBbox.top * bmpH));
                        int px2 = Math.min(bmpW, (int) (pBbox.right * bmpW));
                        int py2 = Math.min(bmpH, (int) (pBbox.bottom * bmpH));
                        int pw = px2 - px1;
                        int ph = py2 - py1;

                        if (pw < 10 || ph < 10)
                            continue;

                        // Crop ảnh người
                        Bitmap personCrop = Bitmap.createBitmap(fullBitmap, px1, py1, pw, ph);

                        // Detect seatbelt trên crop
                        List<YoloDetector.Detection> sbOnCrop = detector.detect(personCrop);
                        personCrop.recycle();

                        // Map tọa độ bbox từ crop → full frame (normalized)
                        float personW = pBbox.width();
                        float personH = pBbox.height();
                        for (YoloDetector.Detection d : sbOnCrop) {
                            float gL = pBbox.left + d.bbox.left * personW;
                            float gT = pBbox.top + d.bbox.top * personH;
                            float gR = pBbox.left + d.bbox.right * personW;
                            float gB = pBbox.top + d.bbox.bottom * personH;
                            allSb.add(new YoloDetector.Detection(
                                    new android.graphics.RectF(gL, gT, gR, gB),
                                    d.classId, d.confidence, d.label));
                        }

                        // Thêm bbox người vào overlay (label = "Person")
                        allSb.add(new YoloDetector.Detection(
                                new android.graphics.RectF(pBbox), -2, 1.0f, "Person"));
                    }

                    fullBitmap.recycle();
                    return allSb;
                });
            } else if (detector != null && pendingYoloFuture == null && lastPersonBboxes.isEmpty()) {
                // Không detect được người → fallback: chạy trên full frame như cũ
                final Bitmap yoloBitmap = bitmap.copy(bitmap.getConfig(), false);
                pendingYoloFuture = yoloExecutor.submit(() -> {
                    List<YoloDetector.Detection> result = detector.detect(yoloBitmap);
                    yoloBitmap.recycle();
                    return result;
                });
            }

            // ── Submit Drowsiness ─────────────────────────────────────────────────
            if (pendingDrowsinessFuture == null) {
                final Bitmap drowsinessBitmap = bitmap.copy(bitmap.getConfig(), false);
                final long ts = timestampMs;
                pendingDrowsinessFuture = drowsinessExecutor.submit(() -> {
                    DrowsinessDetector.DrowsinessResult r = drowsinessDetector.detect(drowsinessBitmap, ts);
                    drowsinessBitmap.recycle();
                    return r;
                });
            }

            // ── Dùng kết quả mới nhất để vẽ UI ───────────────────────────────────
            DrowsinessDetector.DrowsinessResult drowsinessResult = (lastDrowsinessResult != null)
                    ? lastDrowsinessResult
                    : new DrowsinessDetector.DrowsinessResult();

            List<YoloDetector.Detection> seatbeltDetections = new ArrayList<>(lastYoloDetections);

            // ── Thêm bbox khuôn mặt vào overlay ──────────────────────────────────
            if (drowsinessResult.faceDetected && drowsinessResult.faceBbox != null) {
                String faceLabel = "Face";
                if (drowsinessResult.isDrowsy)
                    faceLabel = "Drowsy";
                else if (drowsinessResult.isDistracted)
                    faceLabel = "Distracted";
                else if (drowsinessResult.isYawning)
                    faceLabel = "Yawning";

                seatbeltDetections.add(new YoloDetector.Detection(
                        drowsinessResult.faceBbox, -1, 1.0f, faceLabel));
            }

            long now = System.currentTimeMillis();
            float fps = (lastFrameTime > 0) ? 1000f / (now - lastFrameTime) : 0f;
            lastFrameTime = now;

            runOnUiThread(() -> updateUI(seatbeltDetections, drowsinessResult, fps));

        } catch (Exception e) {
            Log.e(TAG, "Error analyzing frame", e);
        } finally {
            imageProxy.close();
            isProcessing = false;
        }
    }

    private Bitmap imageProxyToBitmap(@NonNull ImageProxy imageProxy) {
        Bitmap bitmap = imageProxy.toBitmap();
        if (bitmap == null)
            return null;

        Matrix matrix = new Matrix();
        matrix.postRotate(imageProxy.getImageInfo().getRotationDegrees());

        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            matrix.postScale(-1f, 1f, bitmap.getWidth() / 2f, bitmap.getHeight() / 2f);
        }

        // TỐI ƯU: Thu nhỏ ảnh xuống 480px để tăng tốc độ AI
        int targetSize = 480;
        float scale = (float) targetSize / Math.max(bitmap.getWidth(), bitmap.getHeight());
        matrix.postScale(scale, scale);

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private void updateUI(List<YoloDetector.Detection> detections, DrowsinessDetector.DrowsinessResult dResult,
            float fps) {
        fpsText.setText(String.format("%.1f FPS", fps));
        bboxOverlay.setDetections(detections);

        YoloDetector.Detection bestBelt = null;
        YoloDetector.Detection bestNoBelt = null;
        for (YoloDetector.Detection d : detections) {
            if (d.label.equalsIgnoreCase("seatbelt") || d.label.equalsIgnoreCase("belt")) {
                if (bestBelt == null || d.confidence > bestBelt.confidence)
                    bestBelt = d;
            } else if (d.label.equalsIgnoreCase("no-seatbelt") || d.label.equalsIgnoreCase("no_belt")) {
                if (bestNoBelt == null || d.confidence > bestNoBelt.confidence)
                    bestNoBelt = d;
            }
        }

        // Logic hiển thị (Ưu tiên các cảnh báo nguy hiểm trước)
        if (dResult.isFaceMissing) {
            redFlashOverlay.setVisibility(android.view.View.VISIBLE);
            statusIcon.setText("🫥");
            statusText.setText("CẢNH BÁO - KHÔNG THẤY TÀI XẾ!");
            statusText.setTextColor(Color.RED);
            confidenceText.setText("Hệ thống mất dấu người lái!");
        } else if (dResult.faceDetected && dResult.isDrowsy) {
            redFlashOverlay.setVisibility(android.view.View.VISIBLE);
            statusIcon.setText("😴");
            statusText.setText("NGUY HIỂM - ĐANG NGỦ GẬT!");
            statusText.setTextColor(Color.RED);
            confidenceText.setText(String.format("Hãy dừng xe! (EAR: %.2f)", dResult.ear));
        } else if (dResult.faceDetected && dResult.isDistracted) {
            redFlashOverlay.setVisibility(android.view.View.VISIBLE);
            statusIcon.setText("🫣");
            statusText.setText("CẢNH BÁO - MẤT TẬP TRUNG!");
            statusText.setTextColor(Color.RED);
            confidenceText.setText(String.format("Hãy nhìn thẳng! (Góc quay: %.0f°)", dResult.headEulerY));
        } else {
            startService(intent);
        }
        Log.d(TAG, "LocationService started from MainActivity");
    }

    private void stopLocationService() {
        Intent intent = new Intent(this, LocationService.class);
        stopService(intent);
    }

    private void onAnalyzeFrame(ImageProxy imageProxy) {
        detectionPipeline.processFrame(imageProxy, cameraManager, (detections, dResult, fps, imgW, imgH) -> {
            // 1. Cập nhật UI
            runOnUiThread(() -> uiManager.updateUI(detections, dResult, fps, imgW, imgH));

            // 2. Kiểm tra và gửi API cảnh báo nếu có sự cố
            checkAndSendAlerts(detections, dResult);
        });
    }

    private void checkAndSendAlerts(List<YoloDetector.Detection> detections, DrowsinessDetector.DrowsinessResult dResult) {
        long now = System.currentTimeMillis();

        // ── Xử lý cảnh báo Buồn ngủ / Mất tập trung / Không thấy mặt ──────────────────
        if (dResult != null && (dResult.isDrowsy || dResult.isDistracted || dResult.isFaceMissing)) {
            if (now - lastSomnolenceAlertTime >= ALERT_COOLDOWN_MS) {
                lastSomnolenceAlertTime = now;

                String eventType = "DROWSY";
                if (dResult.isFaceMissing) eventType = "FACE_MISSING";
                else if (dResult.isDistracted) eventType = "DISTRACTED";

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                String timestampStr = sdf.format(new Date());

                SomnolenceRecordRequest req = new SomnolenceRecordRequest(
                        eventType,
                        dResult.ear,
                        dResult.mar,
                        dResult.closedEyeDurationMs,
                        dResult.headEulerY,
                        timestampStr,
                        BoundingBoxDto.fromRectF(dResult.faceBbox),
                        null // Tùy chọn chuyển Bitmap sang Base64
                );

                ApiClient.getInstance().postSomnolenceRecord(req, null);
                Log.d(TAG, "Triggered Somnolence alert API: " + eventType);
            }
        }

        // ── Xử lý vi phạm Không đeo dây an toàn ──────────────────────────────────────
        if (detections != null) {
            for (YoloDetector.Detection d : detections) {
                if ((d.label.equalsIgnoreCase("no-seatbelt") || d.label.equalsIgnoreCase("no_belt")) && d.confidence > 0.6f) {
                    if (now - lastSeatbeltAlertTime >= ALERT_COOLDOWN_MS) {
                        lastSeatbeltAlertTime = now;

                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                        String timestampStr = sdf.format(new Date());

                        ViolationReportRequest req = new ViolationReportRequest(
                                "NO_SEATBELT",
                                d.confidence,
                                timestampStr,
                                BoundingBoxDto.fromRectF(d.bbox),
                                null
                        );

                        ApiClient.getInstance().postViolationReport(req, null);
                        Log.d(TAG, "Triggered Seatbelt violation alert API: NO_SEATBELT");
                    }
                    break;
                }
            }
        }
    }

    private String[] loadLabels() {
        List<String> labelList = new ArrayList<>();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(getAssets().open("labels.txt")));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty())
                    labelList.add(line);
            }
            reader.close();
        } catch (IOException e) {
            Log.e(TAG, "Error loading labels", e);
        }
        return labelList.toArray(new String[0]);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null)
            cameraExecutor.shutdown();
        if (yoloExecutor != null)
            yoloExecutor.shutdown();
        if (drowsinessExecutor != null)
            drowsinessExecutor.shutdown();
        if (personExecutor != null)
            personExecutor.shutdown();
        if (detector != null)
            detector.close();
        if (drowsinessDetector != null)
            drowsinessDetector.close();
        if (personDetector != null)
            personDetector.close();
        // ── ④ Dọn dẹp WebSocket + UDP ────────────────────────────────────────
        if (wsClient != null)
            wsClient.disconnect();
        if (udpSender != null)
            udpSender.close();
    }
}
