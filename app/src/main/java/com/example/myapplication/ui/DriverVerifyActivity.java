package com.example.myapplication.ui;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
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
import com.example.myapplication.utils.SecureTokenManager;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DriverVerifyActivity - Bước 1: Xác thực khuôn mặt tài xế.
 *
 * Luồng hoạt động:
 *  1. Camera trước mở, hiển thị preview.
 *  2. Người dùng bấm nút "Chụp ảnh".
 *  3. Đếm ngược 3 → 2 → 1 trên màn hình.
 *  4. Chụp ảnh, resize + xoay đúng hướng, gửi lên AppRestClient.verifyDriver().
 *  5. Nếu verified == true  → chuyển sang DriverMonitorActivity.
 *     Nếu thất bại           → thông báo lỗi, cho phép thử lại.
 *  6. Nút "Bỏ qua bước này" → bỏ qua bước 1, vào thẳng DriverMonitorActivity.
 */
public class DriverVerifyActivity extends AppCompatActivity {

    private static final String TAG           = "DriverVerify";
    private static final int    REQUEST_CAMERA = 100;
    private static final int    COUNTDOWN_SEC  = 3;   // số giây đếm ngược

    // ── Views ───────────────────────────────────────────────────────────────
    private PreviewView  previewView;
    private TextView     tvStatus;
    private TextView     tvCountdown;
    private FrameLayout  layoutCountdown;
    private Button       btnCapture;
    private Button       btnSkipVerify;

    // ── Camera ──────────────────────────────────────────────────────────────
    private ImageCapture  imageCapture;
    private ExecutorService cameraExecutor;

    // ── State ────────────────────────────────────────────────────────────────
    private final Handler handler     = new Handler(Looper.getMainLooper());
    private boolean isCounting        = false; // đang đếm ngược
    private boolean isUploading       = false; // đang gửi API
    private boolean hasNavigated      = false; // đã chuyển màn hình
    private CountDownTimer countDownTimer;

    // ── Trip data (truyền qua Intent) ────────────────────────────────────────
    private String tripId;
    private String vehicleId;
    private String vehiclePlate;

    // ═══════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_driver_verify);

        // Nhận thông tin chuyến đi
        Intent intent = getIntent();
        if (intent != null) {
            tripId       = intent.getStringExtra("TRIP_ID");
            vehicleId    = intent.getStringExtra("VEHICLE_ID");
            vehiclePlate = intent.getStringExtra("VEHICLE_PLATE");
        }

        bindViews();

        cameraExecutor = Executors.newSingleThreadExecutor();

        // Nút Chụp ảnh → kích hoạt đếm ngược
        btnCapture.setOnClickListener(v -> {
            if (!isCounting && !isUploading) {
                startCountdownThenCapture();
            }
        });

        // Nút Bỏ qua
        btnSkipVerify.setOnClickListener(v -> {
            Toast.makeText(this, "Đã bỏ qua bước xác thực khuôn mặt", Toast.LENGTH_SHORT).show();
            proceedToMonitor();
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

    @Override
    protected void onPause() {
        super.onPause();
        cancelCountdown();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelCountdown();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Camera
    // ═══════════════════════════════════════════════════════════════════════

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

                setStatus("Nhìn thẳng vào camera và nhấn \"Chụp ảnh\"", "#FFFFFF");
                btnCapture.setEnabled(true);

            } catch (Exception e) {
                Log.e(TAG, "Camera error: " + e.getMessage());
                setStatus("⚠ Lỗi khởi động camera", "#FF9800");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Countdown → Capture
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Khi bấm "Chụp ảnh", hiện vòng tròn đếm ngược 3-2-1 rồi chụp ảnh.
     */
    private void startCountdownThenCapture() {
        if (hasNavigated) return;

        isCounting = true;
        setBtnCaptureEnabled(false);
        btnSkipVerify.setEnabled(false);

        // Hiện overlay đếm ngược
        layoutCountdown.setVisibility(View.VISIBLE);
        setStatus("Chuẩn bị...", "#FFFFFF");

        countDownTimer = new CountDownTimer(COUNTDOWN_SEC * 1000L, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                int secondsLeft = (int) Math.ceil(millisUntilFinished / 1000.0);
                tvCountdown.setText(String.valueOf(secondsLeft));

                // Hiệu ứng pulse nhẹ
                ObjectAnimator scaleX = ObjectAnimator.ofFloat(tvCountdown, "scaleX", 1.4f, 1.0f);
                ObjectAnimator scaleY = ObjectAnimator.ofFloat(tvCountdown, "scaleY", 1.4f, 1.0f);
                scaleX.setDuration(600);
                scaleY.setDuration(600);
                scaleX.start();
                scaleY.start();
            }

            @Override
            public void onFinish() {
                // Ẩn đếm ngược, chụp ảnh thực
                layoutCountdown.setVisibility(View.GONE);
                isCounting = false;
                setStatus("📷 Đang chụp ảnh...", "#FFFFFF");
                doCapture();
            }
        }.start();
    }

    private void cancelCountdown() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
        isCounting = false;
        if (layoutCountdown != null) layoutCountdown.setVisibility(View.GONE);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Chụp ảnh & Gửi API
    // ═══════════════════════════════════════════════════════════════════════

    private void doCapture() {
        if (imageCapture == null || hasNavigated) {
            resetCaptureButton();
            return;
        }

        isUploading = true;

        imageCapture.takePicture(
                cameraExecutor,
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                        try {
                            int rotation = imageProxy.getImageInfo().getRotationDegrees();
                            Bitmap rawBitmap = imageProxy.toBitmap();
                            Bitmap processed = processBitmap(rawBitmap, rotation, 640);
                            sendToApi(processed);
                        } catch (Exception e) {
                            Log.e(TAG, "Lỗi xử lý ảnh: " + e.getMessage());
                            runOnUiThread(() -> {
                                setStatus("⚠ Lỗi xử lý ảnh, thử lại", "#FF9800");
                                resetCaptureButton();
                            });
                        } finally {
                            imageProxy.close();
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Lỗi chụp ảnh: " + exception.getMessage());
                        runOnUiThread(() -> {
                            setStatus("⚠ Lỗi chụp ảnh, thử lại", "#FF9800");
                            resetCaptureButton();
                        });
                    }
                }
        );
    }

    private void sendToApi(Bitmap bitmap) {
        if (hasNavigated || bitmap == null) {
            isUploading = false;
            resetCaptureButton();
            return;
        }

        runOnUiThread(() -> setStatus("⏳ Đang xác thực...", "#FFFFFF"));

        AppRestClient.getInstance().verifyDriver(bitmap, new AppRestClient.ApiCallback<VerifyResponseDto>() {
            @Override
            public void onSuccess(VerifyResponseDto response) {
                isUploading = false;
                handleVerifyResult(response);
            }

            @Override
            public void onError(String errorMessage) {
                isUploading = false;
                runOnUiThread(() -> {
                    setStatus("⚠ " + errorMessage, "#FF9800");
                    resetCaptureButton();
                });
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Xử lý kết quả xác thực
    // ═══════════════════════════════════════════════════════════════════════

    private void handleVerifyResult(VerifyResponseDto response) {
        runOnUiThread(() -> {
            if (hasNavigated) return;

            if (response == null) {
                setStatus("✗ Không nhận được phản hồi từ server AI\nNhấn \"Chụp ảnh\" để thử lại", "#F44336");
                resetCaptureButton();
                return;
            }

            // Lấy ID tài xế đang đăng nhập từ SecureTokenManager
            SecureTokenManager tokenManager = SecureTokenManager.getInstance(getApplicationContext());
            String loggedInDriverId = tokenManager.getDriverId();
            String returnedDriverId = response.getDriverId();

            Log.i(TAG, "Verify check: response.verified=" + response.isVerified()
                    + ", response.driver_id=" + returnedDriverId
                    + ", loggedInDriverId=" + loggedInDriverId);

            // Kiểm tra điều kiện 1: verified == true
            if (!response.isVerified()) {
                String msg = response.getMessage() != null ? response.getMessage() : "Khuôn mặt không khớp dữ liệu";
                setStatus("✗ " + msg + "\nNhấn \"Chụp ảnh\" để thử lại", "#F44336");
                resetCaptureButton();
                return;
            }

            // Kiểm tra điều kiện 2: driver_id trả về phải khớp với driver_id đang đăng nhập
            boolean isDriverIdMatched = false;
            if (loggedInDriverId != null && returnedDriverId != null) {
                isDriverIdMatched = loggedInDriverId.trim().equalsIgnoreCase(returnedDriverId.trim());
            }

            if (isDriverIdMatched) {
                // ✅ Thỏa mãn cả 2 điều kiện: verified = true VÀ đúng tài xế đang đăng nhập
                hasNavigated = true;
                setStatus("✅ Xác thực thành công!\nĐúng tài xế: " + returnedDriverId, "#4CAF50");
                Toast.makeText(this, "Xác thực danh tính thành công!", Toast.LENGTH_SHORT).show();

                // Delay 800ms để người dùng thấy thông báo thành công
                handler.postDelayed(this::proceedToMonitor, 800);
            } else {
                // ✗ Khuôn mặt khớp 1 người nào đó nhưng KHÔNG PHẢI tài xế đang đăng nhập
                Log.w(TAG, "Cảnh báo mạo danh: Đăng nhập là " + loggedInDriverId + " nhưng nhận diện là " + returnedDriverId);
                setStatus("✗ Khuôn mặt không khớp với tài xế đang đăng nhập!\n(Nhận diện: "
                        + (returnedDriverId != null ? returnedDriverId : "N/A") + ")\nNhấn \"Chụp ảnh\" để thử lại", "#F44336");
                resetCaptureButton();
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Navigation
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Chuyển sang DriverMonitorActivity mang theo toàn bộ thông tin chuyến đi.
     */
    private void proceedToMonitor() {
        hasNavigated = true;
        cancelCountdown();

        Intent intent = new Intent(DriverVerifyActivity.this, DriverMonitorActivity.class);
        if (tripId != null)       intent.putExtra("TRIP_ID", tripId);
        if (vehicleId != null)    intent.putExtra("VEHICLE_ID", vehicleId);
        if (vehiclePlate != null) intent.putExtra("VEHICLE_PLATE", vehiclePlate);
        startActivity(intent);
        finish();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private void bindViews() {
        previewView     = findViewById(R.id.previewView);
        tvStatus        = findViewById(R.id.tvStatus);
        tvCountdown     = findViewById(R.id.tvCountdown);
        layoutCountdown = findViewById(R.id.layoutCountdown);
        btnCapture      = findViewById(R.id.btnCapture);
        btnSkipVerify   = findViewById(R.id.btnSkipVerify);

        // Disable nút cho đến khi camera sẵn sàng
        btnCapture.setEnabled(false);
        setStatus("Đang khởi động camera...", "#FFFFFF");
    }

    private void setStatus(String text, String hexColor) {
        tvStatus.setText(text);
        tvStatus.setTextColor(android.graphics.Color.parseColor(hexColor));
    }

    private void setBtnCaptureEnabled(boolean enabled) {
        btnCapture.setEnabled(enabled);
        btnCapture.setAlpha(enabled ? 1.0f : 0.5f);
    }

    private void resetCaptureButton() {
        isCounting  = false;
        isUploading = false;
        setBtnCaptureEnabled(true);
        btnSkipVerify.setEnabled(true);
    }

    /** Xoay và resize bitmap về chiều chuẩn trước khi gửi lên server */
    private Bitmap processBitmap(Bitmap src, int rotationDegrees, int targetWidth) {
        if (src == null) return null;
        Matrix matrix = new Matrix();
        if (rotationDegrees != 0) matrix.postRotate(rotationDegrees);
        if (src.getWidth() > targetWidth) {
            float scale = (float) targetWidth / src.getWidth();
            matrix.postScale(scale, scale);
        }
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
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
                Toast.makeText(this, "Cần cấp quyền camera để nhận diện!", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
}
