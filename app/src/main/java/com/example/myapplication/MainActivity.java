package com.example.myapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
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
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * MainActivity - Controller chính quản lý ứng dụng, điều phối Camera, AI Pipeline và UI.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AppDetectMain";

    // Modules
    private UiManager uiManager;
    private DetectionPipeline detectionPipeline;

    // Camera state
    private int lensFacing = CameraSelector.LENS_FACING_FRONT;
    private boolean isTripStarted = false;
    private boolean isProcessing = false;
    private long lastTimestampMs = -1;

    // WebSocket + UDP Streaming
    private static final String BACKEND_IP = "192.168.42.222";
    private static final int UDP_PORT = 9090;
    private static final long DRIVER_ID = 1L;

    private AppWebSocketClient wsClient;
    private UdpFrameSender udpSender;

    private final ActivityResultLauncher<String> cameraPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "App cần quyền camera để hoạt động!", Toast.LENGTH_LONG).show();
                    uiManager.statusText.setText("Cần cấp quyền camera");
                    uiManager.statusIcon.setText("🚫");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Init UI Module
        uiManager = new UiManager(this);

        uiManager.btnSwitchCamera.setOnClickListener(v -> {
            lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK)
                    ? CameraSelector.LENS_FACING_FRONT
                    : CameraSelector.LENS_FACING_BACK;
            if (isTripStarted) startCamera();
        });

        uiManager.btnStartTrip.setOnClickListener(v -> {
            isTripStarted = true;
            uiManager.startOverlay.setVisibility(android.view.View.GONE);
            checkAndStartCamera();
        });

        // 2. Init AI Detection Pipeline Module
        detectionPipeline = new DetectionPipeline();
        String[] labels = loadLabels();

        detectionPipeline.loadModelsAsync(this, labels, new DetectionPipeline.InitCallback() {
            @Override
            public void onSuccess() {
                uiManager.enableStartButton();
                Log.d(TAG, "All AI models ready");
            }

            @Override
            public void onError(Exception e) {
                uiManager.showError("Lỗi load model: " + e.getMessage());
            }
        });

        // 3. Init Streaming Services
        initNetworkStreaming();
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
                preview.setSurfaceProvider(uiManager.cameraPreview.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();

                imageAnalysis.setAnalyzer(detectionPipeline.getCameraExecutor(), imageProxy -> {
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

    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        isProcessing = true;
        try {
            Bitmap bitmap = ImageUtils.imageProxyToBitmap(imageProxy, lensFacing);
            if (bitmap == null) {
                isProcessing = false;
                return;
            }

            if (udpSender != null) {
                udpSender.sendFrame(bitmap);
            }

            long timestampMs = imageProxy.getImageInfo().getTimestamp() / 1000000;
            if (timestampMs <= lastTimestampMs) {
                timestampMs = lastTimestampMs + 1;
            }
            lastTimestampMs = timestampMs;

            detectionPipeline.processFrame(bitmap, timestampMs, (detections, dResult, fps, imgW, imgH) ->
                    runOnUiThread(() -> uiManager.updateUI(detections, dResult, fps, imgW, imgH)));

        } catch (Exception e) {
            Log.e(TAG, "Error analyzing frame", e);
        } finally {
            imageProxy.close();
            isProcessing = false;
        }
    }

    private void initNetworkStreaming() {
        udpSender = new UdpFrameSender(BACKEND_IP, UDP_PORT, DRIVER_ID);
        wsClient = new AppWebSocketClient(BACKEND_IP, DRIVER_ID, new AppWebSocketClient.StreamCommandListener() {
            @Override
            public void onStartStream() {
                udpSender.startStreaming();
                Log.i(TAG, "✅ START_STREAM");
            }

            @Override
            public void onStopStream() {
                udpSender.stopStreaming();
                Log.i(TAG, "⏹ STOP_STREAM");
            }

            @Override
            public void onConnected() {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Đã kết nối Backend", Toast.LENGTH_SHORT).show());
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
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(getAssets().open("labels.txt")));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) labelList.add(line);
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
        if (detectionPipeline != null) detectionPipeline.close();
        if (wsClient != null) wsClient.disconnect();
        if (udpSender != null) udpSender.close();
    }
}
