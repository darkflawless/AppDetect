package com.example.myapplication;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceContour;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * DrowsinessDetector — phát hiện ngủ gật dựa trên EAR và MAR.
 *
 * Thay thế MediaPipe FaceLandmarker bằng ML Kit Face Detection
 * (Google Play Services) để tránh lỗi 16KB ELF alignment.
 *
 * EAR = Eye Aspect Ratio: mắt nhắm → EAR thấp
 * MAR = Mouth Aspect Ratio: miệng há → MAR cao
 */
public class DrowsinessDetector {

    public static final float EAR_THRESHOLD = 0.21f;
    public static final float MAR_THRESHOLD = 0.5f;
    public static final long CLOSED_EYE_TIME_THRESHOLD_MS = 3000; // 3 giây nhắm mắt = báo động
    private static final long ALERT_PERSISTENCE_MS = 1000;         // Duy trì cảnh báo 1 giây

    private final FaceDetector faceDetector;
    private long firstClosedEyeTime = 0;
    private long lastDrowsyTime = 0;

    // -------------------------------------------------------------------------
    // Kết quả trả về cho MainActivity
    // -------------------------------------------------------------------------
    public static class DrowsinessResult {
        public boolean faceDetected = false;
        public boolean isDrowsy = false;
        public boolean isYawning = false;
        public float ear = 0f;
        public float mar = 0f;
        public long closedEyeDurationMs = 0;
        public RectF faceBbox = null;
    }

    // -------------------------------------------------------------------------
    // Khởi tạo
    // -------------------------------------------------------------------------
    public DrowsinessDetector(Context context) {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL) // Lấy contour mắt + môi
                .setMinFaceSize(0.15f)                                  // Bỏ qua mặt quá nhỏ
                .build();

        faceDetector = FaceDetection.getClient(options);
    }

    // -------------------------------------------------------------------------
    // Phát hiện ngủ gật — gọi từ background thread (cameraExecutor)
    // -------------------------------------------------------------------------
    public DrowsinessResult detect(Bitmap bitmap, long timestampMs) {
        DrowsinessResult result = new DrowsinessResult();

        try {
            InputImage inputImage = InputImage.fromBitmap(bitmap, 0);

            // Tasks.await() dùng OK vì đang trên background thread
            List<Face> faces = Tasks.await(faceDetector.process(inputImage));

            if (faces.isEmpty()) {
                firstClosedEyeTime = 0;
                result.isDrowsy = (System.currentTimeMillis() - lastDrowsyTime < ALERT_PERSISTENCE_MS);
                return result;
            }

            Face face = faces.get(0);
            result.faceDetected = true;

            // Bounding box khuôn mặt (normalize về [0,1])
            android.graphics.Rect bbox = face.getBoundingBox();
            float imgW = bitmap.getWidth();
            float imgH = bitmap.getHeight();
            result.faceBbox = new RectF(
                    Math.max(0f, bbox.left   / imgW),
                    Math.max(0f, bbox.top    / imgH),
                    Math.min(1f, bbox.right  / imgW),
                    Math.min(1f, bbox.bottom / imgH)
            );

            // --- Tính EAR từ contour mắt ---
            FaceContour leftEyeContour  = face.getContour(FaceContour.LEFT_EYE);
            FaceContour rightEyeContour = face.getContour(FaceContour.RIGHT_EYE);

            if (leftEyeContour != null && rightEyeContour != null) {
                float leftEar  = calcEar(leftEyeContour.getPoints());
                float rightEar = calcEar(rightEyeContour.getPoints());
                result.ear = (leftEar + rightEar) / 2.0f;
            }

            // --- Tính MAR từ contour môi ---
            FaceContour upperLipTop    = face.getContour(FaceContour.UPPER_LIP_TOP);
            FaceContour lowerLipBottom = face.getContour(FaceContour.LOWER_LIP_BOTTOM);

            if (upperLipTop != null && lowerLipBottom != null) {
                result.mar = calcMar(upperLipTop.getPoints(), lowerLipBottom.getPoints());
            }

            // --- Logic cảnh báo ngủ gật ---
            if (result.ear < EAR_THRESHOLD) {
                if (firstClosedEyeTime == 0) {
                    firstClosedEyeTime = System.currentTimeMillis();
                }
            } else {
                firstClosedEyeTime = 0;
            }

            long currentClosedDuration = (firstClosedEyeTime > 0)
                    ? (System.currentTimeMillis() - firstClosedEyeTime) : 0;

            if (currentClosedDuration >= CLOSED_EYE_TIME_THRESHOLD_MS) {
                lastDrowsyTime = System.currentTimeMillis();
            }

            result.closedEyeDurationMs = currentClosedDuration;
            result.isDrowsy = (currentClosedDuration >= CLOSED_EYE_TIME_THRESHOLD_MS)
                    || (System.currentTimeMillis() - lastDrowsyTime < ALERT_PERSISTENCE_MS);
            result.isYawning = (result.mar > MAR_THRESHOLD);

        } catch (ExecutionException | InterruptedException e) {
            // Nếu lỗi inference, trả về result mặc định (không crash app)
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // EAR — Eye Aspect Ratio
    // ML Kit trả về 16 điểm cho mỗi mắt, đi theo chiều kim đồng hồ:
    //   p[0]  = góc ngoài
    //   p[4]  = đỉnh trên
    //   p[8]  = góc trong
    //   p[12] = đỉnh dưới
    //
    // EAR = (A + B) / (2 * C)
    //   A = khoảng cách dọc tại 1/4 từ ngoài vào  (p[2]  <-> p[14])
    //   B = khoảng cách dọc ở giữa mắt             (p[4]  <-> p[12])
    //   C = khoảng cách ngang (chiều rộng mắt)      (p[0]  <-> p[8])
    // -------------------------------------------------------------------------
    private float calcEar(List<PointF> pts) {
        if (pts == null || pts.size() < 16) return 0.3f; // mặt định: mắt mở

        float A = dist(pts.get(2),  pts.get(14));
        float B = dist(pts.get(4),  pts.get(12));
        float C = dist(pts.get(0),  pts.get(8));

        if (C < 1f) return 0.3f;
        return (A + B) / (2.0f * C);
    }

    // -------------------------------------------------------------------------
    // MAR — Mouth Aspect Ratio
    // upperLipTop: đường viền trên của môi trên (thường ~13 điểm)
    //   p[0]      = góc trái miệng
    //   p[mid]    = điểm giữa trên
    //   p[last]   = góc phải miệng
    // lowerLipBottom: đường viền dưới của môi dưới (thường ~13 điểm)
    //   p[mid]    = điểm giữa dưới
    //
    // MAR = khoảng dọc (trên-dưới) / khoảng ngang (trái-phải)
    // -------------------------------------------------------------------------
    private float calcMar(List<PointF> upper, List<PointF> lower) {
        if (upper == null || lower == null || upper.size() < 3 || lower.size() < 3) return 0f;

        int uMid = upper.size() / 2;
        int lMid = lower.size() / 2;

        float vertical   = dist(upper.get(uMid), lower.get(lMid));
        float horizontal = dist(upper.get(0), upper.get(upper.size() - 1));

        if (horizontal < 1f) return 0f;
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
    // Dọn dẹp tài nguyên
    // -------------------------------------------------------------------------
    public void close() {
        if (faceDetector != null) {
            faceDetector.close();
        }
    }
}
