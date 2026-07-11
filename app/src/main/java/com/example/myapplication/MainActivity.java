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

// Calibration phase dùng TFLite face_detection.tflite (không cần ML Kit)
import android.graphics.RectF;

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

    // Camera state
    private int lensFacing = CameraSelector.LENS_FACING_FRONT;
    private boolean isTripStarted = false;

    // FPS tracking
    private long lastFrameTime = 0L;
    private boolean isProcessing = false; // Cờ chặn chồng chéo frame

    // Frame skip: YOLO chạy mỗi YOLO_SKIP_FRAMES frame (tiết kiệm tài nguyên)
    private static final int YOLO_SKIP_FRAMES = 2;
    private int frameCounter = 0;
    private List<YoloDetector.Detection> lastYoloDetections = new ArrayList<>();

    // ── Calibration: quét cabin 5 giây đầu để xác định vị trí từng người ──────────
    private static final long  CALIBRATION_DURATION_MS   = 5000L;  // 5 giây
    private static final float POSE_CONFIDENCE_THRESHOLD = 0.5f;
    private static final float NMS_IOU_THRESHOLD         = 0.45f;

    private boolean isCalibrating      = false;
    private long    calibrationStartTime = 0L;
    private final List<List<RectF>> accumulatedRegions = new ArrayList<>(); // tích lũy trong 5s
    private final List<RectF>       seatRegions        = new ArrayList<>(); // vùng ghế cuối cùng
    // faceDetector đã bị loại bỏ — Calibration dùng TFLiteFaceDetector từ drowsinessDetector

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
            startCalibration();      // Bắt đầu quét cabin 5 giây
            checkAndStartCamera();
        });

        labels = loadLabels();

        cameraExecutor = Executors.newSingleThreadExecutor();
        yoloExecutor = Executors.newSingleThreadExecutor();

        // Load model trước để sẵn sàng
        cameraExecutor.execute(() -> {
            try {
                // Load cả 2 model trên background thread
                drowsinessDetector = new DrowsinessDetector(this);
                detector = new YoloDetector(this, labels);
                // Calibration phase tái sử dụng TFLiteFaceDetector bên trong drowsinessDetector
                // → không cần khởi tạo thêm ML Kit FaceDetector nữa

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
            frameCounter++;

            // ── CALIBRATION PHASE ─────────────────────────────────────────────
            // 5 giây đầu: dùng PoseDetector tìm tất cả người, tích lũy vùng ghế
            if (isCalibrating) {
                processCalibrationFrame(bitmap);
                DrowsinessDetector.DrowsinessResult dCal = drowsinessDetector.detect(bitmap, timestampMs);
                long nowCal = System.currentTimeMillis();
                float fpsCal = (lastFrameTime > 0) ? 1000f / (nowCal - lastFrameTime) : 0f;
                lastFrameTime = nowCal;
                List<YoloDetector.Detection> faceOnly = new ArrayList<>();
                if (dCal.faceDetected && dCal.faceBbox != null)
                    faceOnly.add(new YoloDetector.Detection(dCal.faceBbox, -1, 1.0f, "Face"));
                runOnUiThread(() -> updateUI(faceOnly, dCal, fpsCal));
                return; // finally vẫn chạy: imageProxy.close() + isProcessing = false
            }

            // ── MONITORING PHASE: YOLO detect trên vùng ghế đã calibrate ──────────
            java.util.concurrent.Future<List<YoloDetector.Detection>> yoloFuture = null;
            if (detector != null && frameCounter % YOLO_SKIP_FRAMES == 0) {
                final Bitmap yoloBitmap = bitmap.copy(bitmap.getConfig(), false);
                final List<RectF> regions = new ArrayList<>(seatRegions); // snapshot an toàn

                yoloFuture = yoloExecutor.submit(() -> {
                    List<YoloDetector.Detection> allDetections = new ArrayList<>();
                    if (!regions.isEmpty()) {
                        // Detect từng vùng ghế → crop mang lại độ phân giải cao cho mỗi người
                        for (RectF region : regions) {
                            Bitmap crop = cropBitmapNormalized(yoloBitmap, region);
                            List<YoloDetector.Detection> dets = detector.detect(crop);
                            if (crop != yoloBitmap) crop.recycle();
                            allDetections.addAll(mapDetections(dets, region));
                        }
                    } else {
                        // Chưa calibrate (0 người) → fallback detect toàn ảnh
                        allDetections = detector.detect(yoloBitmap);
                    }
                    yoloBitmap.recycle();
                    return applyGlobalNMS(allDetections);
                });
            }

            // ── DrowsinessDetector: chạy trên thread hiện tại (blocking OK) ─────
            DrowsinessDetector.DrowsinessResult drowsinessResult = drowsinessDetector.detect(bitmap, timestampMs);

            // ── Lấy kết quả YOLO (đợi Future nếu đã gửi) ────────────────────
            if (yoloFuture != null) {
                try {
                    lastYoloDetections = new ArrayList<>(yoloFuture.get());
                } catch (Exception e) {
                    Log.w(TAG, "YOLO inference error: " + e.getMessage());
                }
            }
            List<YoloDetector.Detection> seatbeltDetections = new ArrayList<>(lastYoloDetections);

            // ── Thêm Bbox khuôn mặt vào danh sách hiển thị ──────────────────
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

    // =========================================================================
    // Calibration helpers
    // =========================================================================

    /** Khởi động Calibration phase: reset trạng thái và hiển thị UI đếm ngược. */
    private void startCalibration() {
        isCalibrating       = true;
        calibrationStartTime = System.currentTimeMillis();
        accumulatedRegions.clear();
        seatRegions.clear();
        runOnUiThread(() -> {
            statusIcon.setText("📷");
            statusText.setText("Đang quét cabin... (5 giây)");
            statusText.setTextColor(Color.WHITE);
            confidenceText.setText("Vui lòng ngồi vào đúng vị trí ghế");
        });
    }

    /**
     * Xử lý mỗi frame trong Calibration phase:
     *   - Cập nhật đếm ngược
     *   - Chạy PoseDetector → lấy các vùng thân người
     *   - Tích lũy vào accumulatedRegions (mỗi người = 1 slot)
     *   - Khi hết thói gian: gọi finalizeCalibration()
     */
    private void processCalibrationFrame(Bitmap bitmap) {
        long elapsed = System.currentTimeMillis() - calibrationStartTime;
        long secsLeft = Math.max(0, (CALIBRATION_DURATION_MS - elapsed) / 1000 + 1);
        runOnUiThread(() -> {
            statusText.setText("Đang quét cabin... " + secsLeft + " giây");
            fpsText.setText("CAL");
        });

        if (elapsed >= CALIBRATION_DURATION_MS) {
            finalizeCalibration();
            return;
        }
        if (drowsinessDetector == null) return;

        try {
            // Dùng TFLiteFaceDetector (face_detection.tflite) thay cho ML Kit
            // → detect() trả về tất cả khuôn mặt trong khung hình
            List<TFLiteFaceDetector.FaceBox> faces =
                    drowsinessDetector.getTfliteDetector().detect(bitmap);

            for (TFLiteFaceDetector.FaceBox faceBox : faces) {
                RectF region = extractTorsoRegionFromFaceBox(faceBox.bbox, bitmap);
                if (region != null) {
                    // Khớp vào slot tồn tại theo IoU, nếu không mở slot mới
                    boolean matched = false;
                    for (List<RectF> slot : accumulatedRegions) {
                        if (computeIoU(slot.get(slot.size() - 1), region) > 0.3f) {
                            slot.add(region);
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        List<RectF> newSlot = new ArrayList<>();
                        newSlot.add(region);
                        accumulatedRegions.add(newSlot);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Calibration TFLite face detection error: " + e.getMessage());
        }
    }

    /**
     * Kết thúc Calibration: average bbox của mỗi slot → lưu vào seatRegions.
     * Slot có ít hơn 3 quan sát bị loại (không ổn định).
     */
    private void finalizeCalibration() {
        isCalibrating = false;
        seatRegions.clear();
        for (List<RectF> slot : accumulatedRegions) {
            if (slot.size() < 3) continue;
            float l = 0, t = 0, r = 0, b = 0;
            for (RectF rect : slot) { l += rect.left; t += rect.top; r += rect.right; b += rect.bottom; }
            int n = slot.size();
            seatRegions.add(new RectF(l / n, t / n, r / n, b / n));
        }
        int count = seatRegions.size();
        Log.i(TAG, "Calibration done: " + count + " seat(s) → " + seatRegions);
        runOnUiThread(() -> {
            statusIcon.setText(count > 0 ? "✅" : "⚠️");
            statusText.setText(count > 0
                    ? "Phát hiện " + count + " người — Bắt đầu theo dõi!"
                    : "Không phát hiện ai — Dùng chế độ toàn ảnh");
            statusText.setTextColor(count > 0 ? Color.GREEN : Color.YELLOW);
            confidenceText.setText("");
            fpsText.setText("0.0 FPS");
        });
    }

    /**
     * Tính vùng thân người từ bounding box khuôn mặt chuẩn hóa [0,1]
     * (thay thế extractTorsoRegionFromFace dùng ML Kit).
     *
     * @param normalizedFaceBbox  RectF normalized [0,1] từ TFLiteFaceDetector.FaceBox.bbox
     * @param bitmap              Frame gốc (chỉ cần kích thước)
     * @return RectF vùng thân normalized [0,1], hoặc null nếu không hợp lệ
     */
    private RectF extractTorsoRegionFromFaceBox(RectF normalizedFaceBbox, Bitmap bitmap) {
        float imgW = bitmap.getWidth();
        float imgH = bitmap.getHeight();

        // Chuyển bbox normalized → pixel để tính offset
        float faceCenterX = normalizedFaceBbox.centerX() * imgW;
        float faceTop     = normalizedFaceBbox.top    * imgH;
        float faceBottom  = normalizedFaceBbox.bottom * imgH;
        float faceW       = normalizedFaceBbox.width()  * imgW;
        float faceH       = normalizedFaceBbox.height() * imgH;

        // Mở rộng xuống phía dưới để lấy phần thân (rộng = 3x mặt, cao = 3.5x mặt)
        float torsoWidth  = faceW * 3.0f;
        float torsoHeight = faceH * 3.5f;

        float minX = faceCenterX - torsoWidth / 2.0f;
        float maxX = faceCenterX + torsoWidth / 2.0f;
        float minY = faceTop - faceH * 0.5f; // Bao gồm cả phần đầu
        float maxY = faceBottom + torsoHeight;

        // Trả về normalized [0,1]
        float left   = Math.max(0f, minX / imgW);
        float top    = Math.max(0f, minY / imgH);
        float right  = Math.min(1f, maxX / imgW);
        float bottom = Math.min(1f, maxY / imgH);

        if (right <= left || bottom <= top || (right - left) < 0.05f) return null;
        return new RectF(left, top, right, bottom);
    }

    /** Crop bitmap theo RectF normalized [0,1]. Trả về bitmap gốc nếu crop không hợp lệ. */
    private Bitmap cropBitmapNormalized(Bitmap bitmap, RectF r) {
        int x = Math.max(0, (int)(r.left   * bitmap.getWidth()));
        int y = Math.max(0, (int)(r.top    * bitmap.getHeight()));
        int w = (int)(r.width()  * bitmap.getWidth());
        int h = (int)(r.height() * bitmap.getHeight());
        w = Math.min(w, bitmap.getWidth()  - x);
        h = Math.min(h, bitmap.getHeight() - y);
        if (w <= 0 || h <= 0) return bitmap;
        return Bitmap.createBitmap(bitmap, x, y, w, h);
    }

    /** Map toạ độ detection từ không gian crop → không gian ảnh gốc. */
    private List<YoloDetector.Detection> mapDetections(
            List<YoloDetector.Detection> detections, RectF region) {
        List<YoloDetector.Detection> mapped = new ArrayList<>();
        float rW = region.width(), rH = region.height();
        for (YoloDetector.Detection d : detections) {
            float l = region.left + d.bbox.left   * rW;
            float t = region.top  + d.bbox.top    * rH;
            float r = region.left + d.bbox.right  * rW;
            float b = region.top  + d.bbox.bottom * rH;
            mapped.add(new YoloDetector.Detection(
                    new RectF(Math.max(0f, Math.min(1f, l)), Math.max(0f, Math.min(1f, t)),
                              Math.max(0f, Math.min(1f, r)), Math.max(0f, Math.min(1f, b))),
                    d.classId, d.confidence, d.label));
        }
        return mapped;
    }

    /** Global NMS: loại bbox trùng từ nhiều crop, dùng IoU threshold giống YoloDetector. */
    private List<YoloDetector.Detection> applyGlobalNMS(List<YoloDetector.Detection> detections) {
        if (detections.isEmpty()) return detections;
        detections.sort((a, b) -> Float.compare(b.confidence, a.confidence));
        List<YoloDetector.Detection> result = new ArrayList<>();
        boolean[] suppressed = new boolean[detections.size()];
        for (int i = 0; i < detections.size(); i++) {
            if (suppressed[i]) continue;
            result.add(detections.get(i));
            for (int j = i + 1; j < detections.size(); j++) {
                if (!suppressed[j] && computeIoU(detections.get(i).bbox, detections.get(j).bbox) >= NMS_IOU_THRESHOLD)
                    suppressed[j] = true;
            }
        }
        return result;
    }

    private float computeIoU(RectF a, RectF b) {
        float iL = Math.max(a.left, b.left), iT = Math.max(a.top, b.top);
        float iR = Math.min(a.right, b.right), iB = Math.min(a.bottom, b.bottom);
        if (iR <= iL || iB <= iT) return 0f;
        float inter = (iR - iL) * (iB - iT);
        return inter / (a.width() * a.height() + b.width() * b.height() - inter);
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
        if (detector != null)
            detector.close();
        if (drowsinessDetector != null)
            drowsinessDetector.close();
        // faceDetector (ML Kit) đã được loại bỏ — TFLiteFaceDetector được close trong drowsinessDetector.close()
    }
}
