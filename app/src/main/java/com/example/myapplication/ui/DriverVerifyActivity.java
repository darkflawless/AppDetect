package com.example.myapplication.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.myapplication.R;
import com.example.myapplication.data.model.VerifyResponseDto;
import com.example.myapplication.network.api.AppRestClient;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DriverVerifyActivity extends AppCompatActivity {

    private static final String TAG = "DriverVerify";
    private static final int REQUEST_CAMERA = 100;
    private static final long VERIFY_INTERVAL_MS = 2000L;

    private PreviewView previewView;
    private TextView tvStatus;
    private Button btnVerify;

    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isVerifying = false;
    private boolean hasNavigated = false; // Cờ ngăn chặn chuyển màn hình nhiều lần

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_driver_verify);

        previewView = findViewById(R.id.previewView);
        tvStatus    = findViewById(R.id.tvStatus);
        btnVerify   = findViewById(R.id.btnVerify);

        cameraExecutor = Executors.newSingleThreadExecutor();

        btnVerify.setOnClickListener(v -> {
            if (isVerifying) stopVerifying();
            else startVerifying();
        });

        if (hasCameraPermission()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA
            );
        }
    }

    private void startVerifying() {
        isVerifying = true;
        btnVerify.setText("Dừng nhận diện");
        tvStatus.setText("Đang nhận diện...");
        tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        scheduleNextVerify(0);
    }

    private void stopVerifying() {
        isVerifying = false;
        handler.removeCallbacksAndMessages(null);
        btnVerify.setText("Bắt đầu nhận diện");
    }

    private void scheduleNextVerify(long delayMs) {
        if (!isVerifying) return;
        handler.postDelayed(() -> {
            if (isVerifying) {
                captureAndVerify();
                scheduleNextVerify(VERIFY_INTERVAL_MS);
            }
        }, delayMs);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageCapture
                );

            } catch (Exception e) {
                Log.e(TAG, "Camera error: " + e.getMessage());
                tvStatus.setText("Lỗi khởi động camera");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void captureAndVerify() {
        if (imageCapture == null || hasNavigated) return;

        imageCapture.takePicture(
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                        Bitmap bitmap = imageProxy.toBitmap();
                        imageProxy.close();
                        sendToApi(bitmap);
                    }
                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Capture error: " + exception.getMessage());
                    }
                }
        );
    }

    private void sendToApi(Bitmap bitmap) {
        if (hasNavigated || bitmap == null) return;

        AppRestClient.getInstance().verifyDriver(bitmap, new AppRestClient.ApiCallback<VerifyResponseDto>() {
            @Override
            public void onSuccess(VerifyResponseDto response) {
                handleVerifyResult(response);
            }

            @Override
            public void onError(String errorMessage) {
                if (!hasNavigated) {
                    runOnUiThread(() -> tvStatus.setText("⚠ " + errorMessage));
                }
            }
        });
    }

    private void handleVerifyResult(VerifyResponseDto response) {
        runOnUiThread(() -> {
            if (hasNavigated) return;

            if (response.isVerified()) {
                hasNavigated = true;
                stopVerifying();

                Toast.makeText(this, "Xác thực thành công!", Toast.LENGTH_SHORT).show();

                Intent intent = new Intent(DriverVerifyActivity.this, DriverMonitorActivity.class);
                startActivity(intent);
                finish();
            } else {
                tvStatus.setText("✗ " + response.getMessage());
                tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
            }
        });
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Cần quyền camera để nhận diện!", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopVerifying();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}
