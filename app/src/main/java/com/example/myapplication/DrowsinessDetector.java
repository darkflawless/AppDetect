package com.example.myapplication;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceContour;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class DrowsinessDetector {

    private static final String TAG = "DrowsinessDetector";

    public static final float EAR_THRESHOLD = 0.21f;
    public static final float MAR_THRESHOLD = 0.7f;
    public static final long CLOSED_EYE_TIME_THRESHOLD_MS = 3000; // 3 giây nhắm mắt = báo động
    public static final float DISTRACTION_YAW_THRESHOLD = 30.0f; // Góc quay đầu > 30 độ
    public static final long DISTRACTION_TIME_THRESHOLD_MS = 3000; // Quay đầu 3 giây = báo động
    public static final long FACE_MISSING_TIME_THRESHOLD_MS = 3000; // Mất mặt 3 giây = báo động
    private static final long ALERT_PERSISTENCE_MS = 1000; // Duy trì cảnh báo 1 giây

    // Padding thêm khi crop khuôn mặt cho ML Kit
    // Chiều ngang mở rộng 50% (để lấy hết 2 bên má/tai)
    private static final float FACE_CROP_PADDING_X = 0.25f;

    // Tỷ lệ vùng tài xế: crop 1/2 bên trái của frame (ghế lái thường nằm bên trái)
    private static final float DRIVER_REGION_FRACTION = 1f / 2f;

    // Bước 1: TFLite YOLO face detector (model tự train)
    private final TFLiteFaceDetector tfliteDetector;

    // Bước 2: ML Kit chỉ dùng để lấy contour landmark (không dùng để detect mặt
    // nữa)
    private final FaceDetector mlkitDetector;

    private long firstClosedEyeTime = 0;
    private long lastDrowsyTime = 0;

    private long firstDistractedTime = 0;
    private long lastDistractedTime = 0;

    private long firstFaceMissingTime = 0;

    // -------------------------------------------------------------------------
    // Kết quả trả về cho MainActivity
    // -------------------------------------------------------------------------
    public static class DrowsinessResult {
        public boolean faceDetected = false;
        public boolean isDrowsy = false;
        public boolean isYawning = false;
        public float ear = 0f;
        public float mar = 0f;
        public float headEulerY = 0f;
        public boolean isDistracted = false;
        public boolean isFaceMissing = false;
        public long closedEyeDurationMs = 0;
        public long distractedDurationMs = 0;
        public RectF faceBbox = null;
    }

    // -------------------------------------------------------------------------
    // Khởi tạo
    // -------------------------------------------------------------------------
    public DrowsinessDetector(Context context) throws IOException {
        // Bước 1: load TFLite face detector
        tfliteDetector = new TFLiteFaceDetector(context);

        // Bước 2: ML Kit chỉ cần CONTOUR_MODE_ALL để lấy landmark điểm mắt/môi.
        // minFaceSize = 0.5f vì ảnh crop đã có mặt chiếm phần lớn khung hình.
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setMinFaceSize(0.5f)
                .build();
        mlkitDetector = FaceDetection.getClient(options);
    }

    // -------------------------------------------------------------------------
    // Phát hiện ngủ gật — gọi từ background thread (cameraExecutor)
    // -------------------------------------------------------------------------
    public DrowsinessResult detect(Bitmap bitmap, long timestampMs) {
        DrowsinessResult result = new DrowsinessResult();

        try {
            // ── Bước 1: Crop vùng tài xế (1/2 bên trái frame) ───────────────
            // Tài xế luôn ngồi bên trái (từ góc nhìn camera hướng vào cabin)
            Bitmap driverRegion = cropDriverRegion(bitmap);

            // ── Bước 2: TFLite detect khuôn mặt trong vùng tài xế ───────────
            RectF faceBboxInCrop = tfliteDetector.detectBestFace(driverRegion);

            if (faceBboxInCrop == null) {
                // Không tìm thấy mặt ở vị trí ghế lái -> Tăng bộ đếm Face Missing
                if (firstFaceMissingTime == 0) {
                    firstFaceMissingTime = System.currentTimeMillis();
                }
                long missingDuration = System.currentTimeMillis() - firstFaceMissingTime;

                if (missingDuration >= FACE_MISSING_TIME_THRESHOLD_MS) {
                    result.isFaceMissing = true;
                    // Báo động xong thì reset tracker để frame sau tự quét lại từ đầu
                    tfliteDetector.resetTracker();
                }

                // Không tìm thấy mặt → reset timer buồn ngủ/mất tập trung, giữ cảnh báo
                // persistence nếu còn hiệu lực
                firstClosedEyeTime = 0;
                firstDistractedTime = 0;
                result.isDrowsy = (System.currentTimeMillis() - lastDrowsyTime < ALERT_PERSISTENCE_MS);
                result.isDistracted = (System.currentTimeMillis() - lastDistractedTime < ALERT_PERSISTENCE_MS);
                return result;
            } else {
                // Tìm thấy mặt -> reset timer Face Missing
                firstFaceMissingTime = 0;
            }

            // Map bbox từ tọa độ vùng crop (1/2 trái) → tọa độ full frame để vẽ UI đúng
            RectF faceBboxFull = mapBboxToFullFrame(faceBboxInCrop);

            // Tính box đã thêm padding để hiển thị lên màn hình
            float padX = (faceBboxFull.right - faceBboxFull.left) * FACE_CROP_PADDING_X;
            RectF paddedBbox = new RectF(
                    Math.max(0f, faceBboxFull.left - padX),
                    faceBboxFull.top,
                    Math.min(1f, faceBboxFull.right + padX),
                    faceBboxFull.bottom);

            result.faceDetected = true;
            result.faceBbox = paddedBbox; // Gửi box đã padding ra UI để vẽ khung

            // ── Bước 3: Crop khuôn mặt (có padding) từ driverRegion để đưa vào ML Kit
            // Dùng faceBboxInCrop (tọa độ trong vùng crop) để cắt chính xác
            Bitmap faceCrop = cropFace(driverRegion, faceBboxInCrop);

            // ── Bước 4: ML Kit lấy contour landmark trên ảnh crop ────────────
            InputImage inputImage = InputImage.fromBitmap(faceCrop, 0);
            List<Face> faces = Tasks.await(mlkitDetector.process(inputImage));

            if (!faces.isEmpty()) {
                Face face = faces.get(0);

                // --- Tính EAR từ contour mắt ---
                FaceContour leftEyeContour = face.getContour(FaceContour.LEFT_EYE);
                FaceContour rightEyeContour = face.getContour(FaceContour.RIGHT_EYE);
                if (leftEyeContour != null && rightEyeContour != null) {
                    float leftEar = calcEar(leftEyeContour.getPoints());
                    float rightEar = calcEar(rightEyeContour.getPoints());
                    result.ear = (leftEar + rightEar) / 2.0f;
                }

                // --- Tính MAR từ contour môi ---
                FaceContour upperLipTop = face.getContour(FaceContour.UPPER_LIP_TOP);
                FaceContour lowerLipBottom = face.getContour(FaceContour.LOWER_LIP_BOTTOM);
                if (upperLipTop != null && lowerLipBottom != null) {
                    result.mar = calcMar(upperLipTop.getPoints(), lowerLipBottom.getPoints());
                }
                // --- Lấy góc quay của đầu (Yaw) ---
                result.headEulerY = face.getHeadEulerAngleY();

            } else {
                // ML Kit không thấy contour trong crop → dùng EAR mặc định (mắt mở)
                Log.d(TAG, "ML Kit: không tìm thấy contour trong crop");
                result.ear = 0.3f;
                result.headEulerY = 0f;
            }

            // ── Logic cảnh báo ngủ gật ────────────────────────────────────────
            if (result.ear < EAR_THRESHOLD) {
                if (firstClosedEyeTime == 0) {
                    firstClosedEyeTime = System.currentTimeMillis();
                }
            } else {
                firstClosedEyeTime = 0;
            }

            long currentClosedDuration = (firstClosedEyeTime > 0)
                    ? (System.currentTimeMillis() - firstClosedEyeTime)
                    : 0;

            if (currentClosedDuration >= CLOSED_EYE_TIME_THRESHOLD_MS) {
                lastDrowsyTime = System.currentTimeMillis();
            }

            result.closedEyeDurationMs = currentClosedDuration;
            result.isDrowsy = (currentClosedDuration >= CLOSED_EYE_TIME_THRESHOLD_MS)
                    || (System.currentTimeMillis() - lastDrowsyTime < ALERT_PERSISTENCE_MS);
            result.isYawning = (result.mar > MAR_THRESHOLD);

            // ── Logic cảnh báo mất tập trung (Distraction) ────────────────────
            if (Math.abs(result.headEulerY) > DISTRACTION_YAW_THRESHOLD) {
                if (firstDistractedTime == 0) {
                    firstDistractedTime = System.currentTimeMillis();
                }
            } else {
                firstDistractedTime = 0;
            }

            long currentDistractedDuration = (firstDistractedTime > 0)
                    ? (System.currentTimeMillis() - firstDistractedTime)
                    : 0;

            if (currentDistractedDuration >= DISTRACTION_TIME_THRESHOLD_MS) {
                lastDistractedTime = System.currentTimeMillis();
            }

            result.distractedDurationMs = currentDistractedDuration;
            result.isDistracted = (currentDistractedDuration >= DISTRACTION_TIME_THRESHOLD_MS)
                    || (System.currentTimeMillis() - lastDistractedTime < ALERT_PERSISTENCE_MS);

        } catch (ExecutionException | InterruptedException e) {
            // Nếu lỗi inference, trả về result mặc định (không crash app)
            Log.e(TAG, "Lỗi xử lý: " + e.getMessage());
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // cropDriverRegion: Crop 1/2 bên trái của frame để lấy vùng ghế lái
    // -------------------------------------------------------------------------
    private Bitmap cropDriverRegion(Bitmap bitmap) {
        int cropW = (int) (bitmap.getWidth() * DRIVER_REGION_FRACTION);
        cropW = Math.max(1, cropW); // tránh cropW = 0
        return Bitmap.createBitmap(bitmap, 0, 0, cropW, bitmap.getHeight());
    }

    // -------------------------------------------------------------------------
    // mapBboxToFullFrame: Map bbox từ tọa độ vùng crop (1/2 trái) → full frame
    // Trục X được scale theo DRIVER_REGION_FRACTION, trục Y giữ nguyên
    // -------------------------------------------------------------------------
    private RectF mapBboxToFullFrame(RectF bboxInCrop) {
        if (bboxInCrop == null)
            return null;
        return new RectF(
                bboxInCrop.left * DRIVER_REGION_FRACTION,
                bboxInCrop.top,
                bboxInCrop.right * DRIVER_REGION_FRACTION,
                bboxInCrop.bottom);
    }

    // -------------------------------------------------------------------------
    // Crop khuôn mặt từ bitmap + padding để ML Kit không bị cắt viền
    // faceBbox: normalized [0,1] trong không gian của bitmap được truyền vào
    // -------------------------------------------------------------------------
    private Bitmap cropFace(Bitmap bitmap, RectF bbox) {
        int imgW = bitmap.getWidth();
        int imgH = bitmap.getHeight();

        // Tính padding theo tỷ lệ kích thước box
        float padX = (bbox.right - bbox.left) * FACE_CROP_PADDING_X;

        int x1 = (int) Math.max(0, (bbox.left - padX) * imgW);
        int y1 = (int) Math.max(0, bbox.top * imgH);
        int x2 = (int) Math.min(imgW, (bbox.right + padX) * imgW);
        int y2 = (int) Math.min(imgH, bbox.bottom * imgH);

        int cropW = x2 - x1;
        int cropH = y2 - y1;

        if (cropW <= 0 || cropH <= 0)
            return bitmap; // fallback: toàn ảnh

        return Bitmap.createBitmap(bitmap, x1, y1, cropW, cropH);
    }

    // -------------------------------------------------------------------------
    // EAR — Eye Aspect Ratio
    // ML Kit trả về 16 điểm cho mỗi mắt, đi theo chiều kim đồng hồ:
    // p[0] = góc ngoài
    // p[4] = đỉnh trên
    // p[8] = góc trong
    // p[12] = đỉnh dưới
    //
    // EAR = (A + B) / (2 * C)
    // A = khoảng cách dọc tại 1/4 từ ngoài vào (p[2] <-> p[14])
    // B = khoảng cách dọc ở giữa mắt (p[4] <-> p[12])
    // C = khoảng cách ngang (chiều rộng mắt) (p[0] <-> p[8])
    // -------------------------------------------------------------------------
    private float calcEar(List<PointF> pts) {
        if (pts == null || pts.size() < 16)
            return 0.3f; // mặt định: mắt mở

        float A = dist(pts.get(2), pts.get(14));
        float B = dist(pts.get(4), pts.get(12));
        float C = dist(pts.get(0), pts.get(8));

        if (C < 1f)
            return 0.3f;
        return (A + B) / (2.0f * C);
    }

    // -------------------------------------------------------------------------
    // MAR — Mouth Aspect Ratio
    // upperLipTop: đường viền trên của môi trên (thường ~13 điểm)
    // p[0] = góc trái miệng
    // p[mid] = điểm giữa trên
    // p[last] = góc phải miệng
    // lowerLipBottom: đường viền dưới của môi dưới (thường ~13 điểm)
    // p[mid] = điểm giữa dưới
    //
    // MAR = khoảng dọc (trên-dưới) / khoảng ngang (trái-phải)
    // -------------------------------------------------------------------------
    private float calcMar(List<PointF> upper, List<PointF> lower) {
        if (upper == null || lower == null || upper.size() < 3 || lower.size() < 3)
            return 0f;

        int uMid = upper.size() / 2;
        int lMid = lower.size() / 2;

        float vertical = dist(upper.get(uMid), lower.get(lMid));
        float horizontal = dist(upper.get(0), upper.get(upper.size() - 1));

        if (horizontal < 1f)
            return 0f;
        return vertical / horizontal;
    }

    // -------------------------------------------------------------------------
    // Khoảng cách Euclidean giữa 2 điểm pixel
    // -------------------------------------------------------------------------
    private float dist(PointF p1, PointF p2) {
        float dx = p1.x - p2.x;
        float dy = p1.y - p2.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // -------------------------------------------------------------------------
    // Getter — cho phép MainActivity tái sử dụng tfliteDetector trong Calibration
    // -------------------------------------------------------------------------
    public TFLiteFaceDetector getTfliteDetector() {
        return tfliteDetector;
    }

    // -------------------------------------------------------------------------
    // Dọn dẹp tài nguyên
    // -------------------------------------------------------------------------
    public void close() {
        if (tfliteDetector != null)
            tfliteDetector.close();
        if (mlkitDetector != null)
            mlkitDetector.close();
    }
}
