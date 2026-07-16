package com.example.myapplication;

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

import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;

public class DriverVerifyActivity extends AppCompatActivity {

    private static final String TAG = "DriverVerify";
    private static final int REQUEST_CAMERA = 100;
    private static final long VERIFY_INTERVAL_MS = 2000L; 

    private static final String BASE_URL = "http://10.32.238.110:8000";

    private PreviewView previewView;
    private TextView tvStatus;
    private Button btnVerify;

    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isVerifying = false;
    private boolean hasNavigated = false; // Cờ ngăn chặn chuyển màn hình nhiều lần

    private OkHttpClient httpClient;
    private final Gson gson = new Gson();

    static class VerifyResponse {
        @SerializedName("verified")   boolean verified;
        @SerializedName("driver_id")  String driverId;
        @SerializedName("similarity") float similarity;
        @SerializedName("message")    String message;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_driver_verify);

        previewView = findViewById(R.id.previewView);
        tvStatus    = findViewById(R.id.tvStatus);
        btnVerify   = findViewById(R.id.btnVerify);

        cameraExecutor = Executors.newSingleThreadExecutor();

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build();

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

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        imageCapture
                );
            } catch (Exception e) {
                Log.e(TAG, "Camera bind failed: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private final Runnable verifyRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isVerifying || hasNavigated) return;
            captureAndVerify();
            handler.postDelayed(this, VERIFY_INTERVAL_MS);
        }
    };

    private void startVerifying() {
        isVerifying = true;
        hasNavigated = false;
        btnVerify.setText("Dừng xác thực");
        tvStatus.setText("Đang xác thực...");
        handler.post(verifyRunnable);
    }

    private void stopVerifying() {
        isVerifying = false;
        handler.removeCallbacks(verifyRunnable);
        btnVerify.setText("Bắt đầu xác thực");
        tvStatus.setText("Chưa xác thực");
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
                        byte[] jpegBytes = bitmapToJpeg(bitmap, 85);
                        sendToApi(jpegBytes);
                    }
                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Capture error: " + exception.getMessage());
                    }
                }
        );
    }

    private void sendToApi(byte[] jpegBytes) {
        if (hasNavigated) return;

        RequestBody imageBody = RequestBody.create(jpegBytes, MediaType.parse("image/jpeg"));
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "frame.jpg", imageBody)
                .build();

        Request request = new Request.Builder()
                .url(BASE_URL + "/api/verify")
                .post(requestBody)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (hasNavigated) return;
                Log.e(TAG, "API error: " + e.getMessage());
                runOnUiThread(() -> tvStatus.setText("⚠ Lỗi kết nối server"));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (hasNavigated) {
                    response.close();
                    return;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    runOnUiThread(() -> tvStatus.setText("⚠ Server lỗi: " + response.code()));
                    return;
                }
                String json = response.body().string();
                VerifyResponse result = gson.fromJson(json, VerifyResponse.class);
                handleVerifyResult(result);
            }
        });
    }

    private void handleVerifyResult(VerifyResponse response) {
        runOnUiThread(() -> {
            if (hasNavigated) return; // Nếu đã chuyển màn hình rồi thì bỏ qua các kết quả sau

            if (response.verified) {
                hasNavigated = true; // Đánh dấu đã bắt đầu chuyển màn hình
                stopVerifying();

                Toast.makeText(this, "Xác thực thành công!", Toast.LENGTH_SHORT).show();
                
                Intent intent = new Intent(DriverVerifyActivity.this, MainActivity.class);
                startActivity(intent);
                finish(); 
            } else {
                tvStatus.setText("✗ " + response.message);
                tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
            }
        });
    }

    private byte[] bitmapToJpeg(Bitmap bitmap, int quality) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream);
        return stream.toByteArray();
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        stopVerifying();
    }
}
