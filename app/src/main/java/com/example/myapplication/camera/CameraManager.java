package com.example.myapplication.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.example.myapplication.utils.ImageUtils;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

/**
 * CameraManager - Quản lý độc lập toàn bộ vòng đời CameraX:
 * Bật/tắt camera, chuyển đổi camera trước/sau, cấu hình phân tích khung hình (ImageAnalysis)
 * và chuyển đổi ImageProxy sang Bitmap chuẩn để bắn qua FrameListener.
 */
public class CameraManager {

    private static final String TAG = "CameraManager";

    public interface FrameListener {
        void onFrameCaptured(Bitmap bitmap, long timestampMs);
    }

    private final Context context;
    private int lensFacing = CameraSelector.LENS_FACING_FRONT;
    private ProcessCameraProvider cameraProvider;
    private boolean isProcessing = false;
    private long lastTimestampMs = -1;

    public CameraManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public int getLensFacing() {
        return lensFacing;
    }

    public void setLensFacing(int lensFacing) {
        this.lensFacing = lensFacing;
    }

    public void toggleCamera(LifecycleOwner lifecycleOwner, PreviewView previewView,
                             ExecutorService executor, FrameListener frameListener) {
        this.lensFacing = (this.lensFacing == CameraSelector.LENS_FACING_BACK)
                ? CameraSelector.LENS_FACING_FRONT
                : CameraSelector.LENS_FACING_BACK;
        startCamera(lifecycleOwner, previewView, executor, frameListener);
    }

    public void startCamera(LifecycleOwner lifecycleOwner, PreviewView previewView,
                            ExecutorService executor, FrameListener frameListener) {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(context);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();

                imageAnalysis.setAnalyzer(executor, imageProxy -> {
                    if (isProcessing) {
                        imageProxy.close();
                        return;
                    }
                    analyzeFrame(imageProxy, frameListener);
                });

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis);
                Log.i(TAG, "🟢 Camera đã khởi động thành công với lens: " + lensFacing);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "❌ Lỗi khởi động CameraX: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void analyzeFrame(@NonNull ImageProxy imageProxy, FrameListener frameListener) {
        isProcessing = true;
        try {
            Bitmap bitmap = ImageUtils.imageProxyToBitmap(imageProxy, lensFacing);
            if (bitmap == null) {
                isProcessing = false;
                return;
            }

            long timestampMs = imageProxy.getImageInfo().getTimestamp() / 1000000;
            if (timestampMs <= lastTimestampMs) {
                timestampMs = lastTimestampMs + 1;
            }
            lastTimestampMs = timestampMs;

            if (frameListener != null) {
                frameListener.onFrameCaptured(bitmap, timestampMs);
            }

        } catch (Exception e) {
            Log.e(TAG, "Lỗi phân tích frame camera: " + e.getMessage());
        } finally {
            imageProxy.close();
            isProcessing = false;
        }
    }

    public void stopCamera() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            Log.i(TAG, "🔴 Camera đã dừng");
        }
    }
}
