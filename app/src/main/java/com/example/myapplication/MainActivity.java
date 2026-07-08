package com.example.myapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AppDetectMain";

    // UI Views
    private PreviewView cameraPreview;
    private BoundingBoxOverlay bboxOverlay;
    private android.view.View redFlashOverlay;
    private TextView statusIcon;
    private TextView statusText;
    private TextView confidenceText;
    private TextView fpsText;
    private ImageButton btnSwitchCamera;

    // Start Trip UI
    private LinearLayout startOverlay;
    private Button btnStartTrip;

    // Detector + Threading
    private YoloDetector detector;
    private DrowsinessDetector drowsinessDetector;
    private ExecutorService cameraExecutor;
    private ExecutorService yoloExecutor; // Luồng riêng biệt cho YOLO inference
    private ExecutorService drowsinessExecutor; // Luồng riêng biệt cho Drowsiness inference

    // Camera state
    private int lensFacing = CameraSelector.LENS_FACING_FRONT;
    private boolean isTripStarted = false;

    // FPS tracking
    private long lastFrameTime = 0L;
    private boolean isProcessing = false; // Cờ chặn chồng chéo frame

    // Pending futures (fire-and-forget): submit rồi đi tiếp, lấy kết quả ở frame
    // sau
    private java.util.concurrent.Future<List<YoloDetector.Detection>> pendingYoloFuture = null;
    private java.util.concurrent.Future<DrowsinessDetector.DrowsinessResult> pendingDrowsinessFuture = null;

    // Kết quả mới nhất (dùng khi future chưa xong)
    private List<YoloDetector.Detection> lastYoloDetections = new ArrayList<>();
    private DrowsinessDetector.DrowsinessResult lastDrowsinessResult = null;

    // Labels
    private String[] labels;

    private final ActivityResultLauncher<String> cameraPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "App cần quyền camera để hoạt động!", Toast.LENGTH_LONG).show();
                    statusText.setText("Cần cấp quyền camera");
                    statusIcon.setText("🚫");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Bind views
        cameraPreview = findViewById(R.id.camera_preview);
        bboxOverlay = findViewById(R.id.bbox_overlay);
        redFlashOverlay = findViewById(R.id.red_flash_overlay);
        statusIcon = findViewById(R.id.status_icon);
        statusText = findViewById(R.id.status_text);
        confidenceText = findViewById(R.id.confidence_text);
        fpsText = findViewById(R.id.fps_text);
        btnSwitchCamera = findViewById(R.id.btn_switch_camera);

        // Start Trip views
        startOverlay = findViewById(R.id.start_overlay);
        btnStartTrip = findViewById(R.id.btn_start_trip);

        btnSwitchCamera.setOnClickListener(v -> {
            lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK)
                    ? CameraSelector.LENS_FACING_FRONT
                    : CameraSelector.LENS_FACING_BACK;
            if (isTripStarted)
                startCamera();
        });

        btnStartTrip.setOnClickListener(v -> {
            isTripStarted = true;
            startOverlay.setVisibility(android.view.View.GONE);
            checkAndStartCamera();
        });

        labels = loadLabels();

        cameraExecutor = Executors.newSingleThreadExecutor();
        yoloExecutor = Executors.newSingleThreadExecutor();
        drowsinessExecutor = Executors.newSingleThreadExecutor();

        // Load model trước để sẵn sàng
        cameraExecutor.execute(() -> {
            try {
                // Load cả 2 model trên background thread
                drowsinessDetector = new DrowsinessDetector(this);
                detector = new YoloDetector(this, labels);

                runOnUiThread(() -> {
                    btnStartTrip.setEnabled(true);
                    Log.d(TAG, "Models loaded and ready");
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load models", e);
                runOnUiThread(() -> {
                    statusIcon.setText("❌");
                    statusText.setText("Lỗi load model: " + e.getMessage());
                });
            }
        });
    }

    private void checkAndStartCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    if (isProcessing) {
                        imageProxy.close();
                        return;
                    }
                    analyzeFrame(imageProxy);
                });

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera start failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
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

            long timestampMs = imageProxy.getImageInfo().getTimestamp() / 1000000;
            if (timestampMs <= lastTimestampMs) {
                timestampMs = lastTimestampMs + 1;
            }
            lastTimestampMs = timestampMs;

            // ── Thu thập kết quả nếu future đã xong (KHÔNG block) ───────────────
            if (pendingYoloFuture != null && pendingYoloFuture.isDone()) {
                try {
                    lastYoloDetections = new ArrayList<>(pendingYoloFuture.get());
                } catch (Exception e) {
                    Log.w(TAG, "YOLO result error: " + e.getMessage());
                }
                pendingYoloFuture = null;
            }

            if (pendingDrowsinessFuture != null && pendingDrowsinessFuture.isDone()) {
                try {
                    lastDrowsinessResult = pendingDrowsinessFuture.get();
                } catch (Exception e) {
                    Log.w(TAG, "Drowsiness result error: " + e.getMessage());
                }
                pendingDrowsinessFuture = null;
            }

            // ── Submit job mới nếu executor đang rảnh (fire-and-forget) ───────────
            // YOLO: chỉ submit khi future cũ đã hoàn thành (pendingYoloFuture == null)
            if (detector != null && pendingYoloFuture == null) {
                final Bitmap yoloBitmap = bitmap.copy(bitmap.getConfig(), false);
                pendingYoloFuture = yoloExecutor.submit(() -> {
                    List<YoloDetector.Detection> result = detector.detect(yoloBitmap);
                    yoloBitmap.recycle();
                    return result;
                });
            }

            // Drowsiness: chỉ submit khi future cũ đã hoàn thành
            if (pendingDrowsinessFuture == null) {
                final Bitmap drowsinessBitmap = bitmap.copy(bitmap.getConfig(), false);
                final long ts = timestampMs;
                pendingDrowsinessFuture = drowsinessExecutor.submit(() -> {
                    DrowsinessDetector.DrowsinessResult r = drowsinessDetector.detect(drowsinessBitmap, ts);
                    drowsinessBitmap.recycle();
                    return r;
                });
            }

            // ── Dùng kết quả cũ nhất để vẽ UI (camera thread không bị block) ──────
            DrowsinessDetector.DrowsinessResult drowsinessResult = (lastDrowsinessResult != null)
                    ? lastDrowsinessResult
                    : new DrowsinessDetector.DrowsinessResult();

            List<YoloDetector.Detection> seatbeltDetections = new ArrayList<>(lastYoloDetections);

            // ── Thêm Bbox khuôn mặt vào danh sách hiển thị ──────────────────────
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
            redFlashOverlay.setVisibility(android.view.View.GONE);

            if (dResult.faceDetected && dResult.isYawning) {
                statusIcon.setText("🥱");
                statusText.setText("CẢNH BÁO - Đang ngáp!");
                statusText.setTextColor(Color.parseColor("#FF9100"));
                confidenceText.setText("Bạn có vẻ đang mệt mỏi.");
            } else if (bestNoBelt != null) {
                statusIcon.setText("⚠️");
                statusText.setText("CẢNH BÁO - Không đeo dây!");
                statusText.setTextColor(Color.RED);
                confidenceText.setText(String.format("Độ tin cậy: %.1f%%", bestNoBelt.confidence * 100f));
            } else if (bestBelt != null) {
                statusIcon.setText("✅");
                statusText.setText("AN TOÀN - Đang đeo dây");
                statusText.setTextColor(Color.GREEN);
                confidenceText.setText(String.format("Độ tin cậy: %.1f%%", bestBelt.confidence * 100f));
            } else {
                statusIcon.setText("🔍");
                statusText.setText(
                        dResult.faceDetected ? "Đã thấy mặt - Đang theo dõi..." : "Đang tìm kiếm khuôn mặt...");
                statusText.setTextColor(Color.WHITE);

                if (dResult.faceDetected) {
                    // Hiện trạng thái bình thường
                    confidenceText.setText(String.format("EAR: %.2f | Yaw: %.0f°",
                            dResult.ear, dResult.headEulerY));
                } else {
                    confidenceText.setText("");
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
        if (detector != null)
            detector.close();
        if (drowsinessDetector != null)
            drowsinessDetector.close();
    }
}
