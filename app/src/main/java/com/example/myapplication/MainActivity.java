package com.example.myapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
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

    private static final String TAG = "SeatbeltApp";

    // UI Views
    private PreviewView cameraPreview;
    private BoundingBoxOverlay bboxOverlay;
    private TextView statusIcon;
    private TextView statusText;
    private TextView confidenceText;
    private TextView fpsText;

    // Detector + Threading
    private YoloDetector detector;
    private ExecutorService cameraExecutor;

    // FPS tracking
    private long lastFrameTime = 0L;

    // Labels từ file assets/labels.txt
    private String[] labels;

    // --------------------------------------------------------
    // Launcher xin quyền camera
    // --------------------------------------------------------
    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this,
                            "App cần quyền camera để hoạt động!", Toast.LENGTH_LONG).show();
                    statusText.setText("Cần cấp quyền camera");
                    statusIcon.setText("🚫");
                }
            });

    // --------------------------------------------------------
    // onCreate
    // --------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Bind views
        cameraPreview   = findViewById(R.id.camera_preview);
        bboxOverlay     = findViewById(R.id.bbox_overlay);
        statusIcon      = findViewById(R.id.status_icon);
        statusText      = findViewById(R.id.status_text);
        confidenceText  = findViewById(R.id.confidence_text);
        fpsText         = findViewById(R.id.fps_text);

        // Load labels
        labels = loadLabels();
        Log.d(TAG, "Labels loaded: " + java.util.Arrays.toString(labels));

        // Load model (chạy trên background thread để không block UI)
        cameraExecutor = Executors.newSingleThreadExecutor();
        cameraExecutor.execute(() -> {
            try {
                detector = new YoloDetector(this, labels);
                Log.d(TAG, "Model loaded successfully");
                runOnUiThread(() -> {
                    statusText.setText("Model sẵn sàng!");
                    checkAndStartCamera();
                });
            } catch (IOException e) {
                Log.e(TAG, "Failed to load model", e);
                runOnUiThread(() -> {
                    statusIcon.setText("❌");
                    statusText.setText("Lỗi load model: " + e.getMessage());
                });
            }
        });
    }

    // --------------------------------------------------------
    // Kiểm tra quyền camera
    // --------------------------------------------------------
    private void checkAndStartCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    // --------------------------------------------------------
    // Khởi động CameraX
    // --------------------------------------------------------
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // Preview use case
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

                // ImageAnalysis use case - nhận frame để chạy model
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                // Dùng camera sau
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                // Unbind trước rồi bind lại
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

                Log.d(TAG, "Camera started");

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera start failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // --------------------------------------------------------
    // Phân tích từng frame từ camera
    // --------------------------------------------------------
    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        if (detector == null) {
            imageProxy.close();
            return;
        }

        try {
            // Chuyển ImageProxy → Bitmap
            Bitmap bitmap = imageProxyToBitmap(imageProxy);
            if (bitmap == null) {
                imageProxy.close();
                return;
            }

            // Chạy inference
            List<YoloDetector.Detection> detections = detector.detect(bitmap);

            // Tính FPS
            long now = System.currentTimeMillis();
            float fps = (lastFrameTime > 0) ? 1000f / (now - lastFrameTime) : 0f;
            lastFrameTime = now;

            // Cập nhật UI trên main thread
            runOnUiThread(() -> updateUI(detections, fps));

        } finally {
            imageProxy.close();
        }
    }

    // --------------------------------------------------------
    // Chuyển ImageProxy (YUV_420_888) → Bitmap
    // --------------------------------------------------------
    private Bitmap imageProxyToBitmap(@NonNull ImageProxy imageProxy) {
        ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
        if (planes == null || planes.length < 3) return null;

        ByteBuffer yBuffer  = planes[0].getBuffer();
        ByteBuffer uBuffer  = planes[1].getBuffer();
        ByteBuffer vBuffer  = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(
                nv21, ImageFormat.NV21,
                imageProxy.getWidth(), imageProxy.getHeight(), null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(
                new Rect(0, 0, imageProxy.getWidth(), imageProxy.getHeight()), 85, out);

        byte[] jpegBytes = out.toByteArray();
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
    }

    // --------------------------------------------------------
    // Cập nhật giao diện sau mỗi frame
    // --------------------------------------------------------
    private void updateUI(List<YoloDetector.Detection> detections, float fps) {
        // Cập nhật FPS
        fpsText.setText(String.format("%.1f FPS", fps));

        // Cập nhật overlay
        bboxOverlay.setDetections(detections);

        if (detections.isEmpty()) {
            // Không detect được gì
            statusIcon.setText("🔍");
            statusText.setText("Đang tìm kiếm...");
            statusText.setTextColor(Color.WHITE);
            confidenceText.setText("");
            return;
        }

        // Kiểm tra xem có detection nào là "belt" không
        YoloDetector.Detection bestBelt   = null;
        YoloDetector.Detection bestNoBelt = null;

        for (YoloDetector.Detection d : detections) {
            boolean isBelt = d.label.equalsIgnoreCase("belt")
                          || d.label.equalsIgnoreCase("seatbelt");
            if (isBelt) {
                if (bestBelt == null || d.confidence > bestBelt.confidence) {
                    bestBelt = d;
                }
            } else {
                if (bestNoBelt == null || d.confidence > bestNoBelt.confidence) {
                    bestNoBelt = d;
                }
            }
        }

        if (bestBelt != null) {
            // CÓ DÂY AN TOÀN
            statusIcon.setText("✅");
            statusText.setText("AN TOÀN - Đang đeo dây");
            statusText.setTextColor(Color.parseColor("#00E676"));
            confidenceText.setText(String.format("Độ chắc chắn: %.1f%%", bestBelt.confidence * 100f));
        } else if (bestNoBelt != null) {
            // KHÔNG ĐEO DÂY
            statusIcon.setText("⚠️");
            statusText.setText("NGUY HIỂM - Không đeo dây!");
            statusText.setTextColor(Color.parseColor("#FF1744"));
            confidenceText.setText(String.format("Độ chắc chắn: %.1f%%", bestNoBelt.confidence * 100f));
        }
    }

    // --------------------------------------------------------
    // Đọc labels từ assets/labels.txt
    // --------------------------------------------------------
    private String[] loadLabels() {
        List<String> labelList = new ArrayList<>();
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(getAssets().open("labels.txt")));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    labelList.add(line);
                }
            }
            reader.close();
        } catch (IOException e) {
            Log.e(TAG, "Failed to read labels.txt", e);
            // Fallback labels nếu file không đọc được
            return new String[]{"no-seatbelt", "seatbelt"};
        }
        return labelList.toArray(new String[0]);
    }

    // --------------------------------------------------------
    // Lifecycle
    // --------------------------------------------------------
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (detector != null) {
            detector.close();
        }
    }
}