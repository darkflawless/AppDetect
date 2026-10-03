package com.example.myapplication.ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * DetectionPipeline - Quản lý mô hình AI Drowsiness và luồng xử lý bất đồng bộ.
 */
public class DetectionPipeline {

    private static final String TAG = "DetectionPipeline";

    // Detectors
    private DrowsinessDetector drowsinessDetector;

    // Executors
    private final ExecutorService cameraExecutor;
    private final ExecutorService drowsinessExecutor;

    // Pending futures
    private Future<DrowsinessDetector.DrowsinessResult> pendingDrowsinessFuture = null;

    // Last results
    private DrowsinessDetector.DrowsinessResult lastDrowsinessResult = null;

    private long lastFrameTime = 0L;

    public interface PipelineCallback {
        void onPipelineResult(List<Detection> detections,
                DrowsinessDetector.DrowsinessResult drowsinessResult,
                float fps, int imgW, int imgH);
    }

    public interface InitCallback {
        void onSuccess();

        void onError(Exception e);
    }

    public DetectionPipeline() {
        cameraExecutor = Executors.newSingleThreadExecutor();
        drowsinessExecutor = Executors.newSingleThreadExecutor();
    }

    public ExecutorService getCameraExecutor() {
        return cameraExecutor;
    }

    public void loadModelsAsync(Context context, InitCallback callback) {
        cameraExecutor.execute(() -> {
            try {
                // Nạp mô hình nhận diện buồn ngủ (LSTM + Face Detector)
                drowsinessDetector = new DrowsinessDetector(context);

                Log.d(TAG, "Drowsiness models loaded successfully");
                if (callback != null)
                    callback.onSuccess();
            } catch (Exception e) {
                Log.e(TAG, "Error loading models", e);
                if (callback != null)
                    callback.onError(e);
            }
        });
    }

    public void loadModelsAsync(Context context, String[] labels, InitCallback callback) {
        loadModelsAsync(context, callback);
    }

    public void processFrame(Bitmap bitmap, long timestampMs, PipelineCallback callback) {
        if (drowsinessDetector == null || bitmap == null)
            return;

        // 1. Thu kết quả drowsiness
        if (pendingDrowsinessFuture != null && pendingDrowsinessFuture.isDone()) {
            try {
                lastDrowsinessResult = pendingDrowsinessFuture.get();
            } catch (Exception e) {
                Log.w(TAG, "Drowsiness result error: " + e.getMessage());
            }
            pendingDrowsinessFuture = null;
        }

        // 2. Submit Drowsiness
        if (pendingDrowsinessFuture == null) {
            final Bitmap drowsinessBitmap = bitmap.copy(bitmap.getConfig(), false);
            final long ts = timestampMs;
            pendingDrowsinessFuture = drowsinessExecutor.submit(() -> {
                try {
                    return drowsinessDetector.detect(drowsinessBitmap, ts);
                } finally {
                    drowsinessBitmap.recycle();
                }
            });
        }

        // 3. Tổng hợp kết quả mới nhất
        DrowsinessDetector.DrowsinessResult drowsinessResult = (lastDrowsinessResult != null)
                ? lastDrowsinessResult
                : new DrowsinessDetector.DrowsinessResult();

        List<Detection> detections = new ArrayList<>();

        if (drowsinessResult.faceDetected && drowsinessResult.faceBbox != null) {
            String faceLabel = "Face";
            if (drowsinessResult.isDrowsy)
                faceLabel = "Drowsy";
            else if (drowsinessResult.isDistracted)
                faceLabel = "Distracted";
            else if (drowsinessResult.isYawning)
                faceLabel = "Yawning";

            detections.add(new Detection(
                    drowsinessResult.faceBbox, -1, 1.0f, faceLabel));
        }

        long now = System.currentTimeMillis();
        float fps = (lastFrameTime > 0) ? 1000f / (now - lastFrameTime) : 0f;
        lastFrameTime = now;

        if (callback != null) {
            callback.onPipelineResult(detections, drowsinessResult, fps,
                    bitmap.getWidth(), bitmap.getHeight());
        }
    }

    public void close() {
        if (cameraExecutor != null)
            cameraExecutor.shutdown();
        if (drowsinessExecutor != null)
            drowsinessExecutor.shutdown();

        if (drowsinessDetector != null)
            drowsinessDetector.close();
    }
}
