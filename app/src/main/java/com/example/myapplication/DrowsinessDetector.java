package com.example.myapplication;

import android.content.Context;
import android.graphics.Bitmap;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FaceLandmarkerOptions;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.ArrayList;
import java.util.List;

public class DrowsinessDetector {

    private static final String MODEL_ASSET_PATH = "face_landmarker.task";
    public static final float EAR_THRESHOLD = 0.21f; // Cân bằng lại ngưỡng EAR
    public static final float MAR_THRESHOLD = 0.5f;
    public static final long CLOSED_EYE_TIME_THRESHOLD_MS = 3000; // Nhắm mắt 3 giây là báo
    private static final long ALERT_PERSISTENCE_MS = 1000; // Duy trì 1s để không chớp tắt

    private FaceLandmarker faceLandmarker;
    private long firstClosedEyeTime = 0;
    private long lastDrowsyTime = 0;
    private int closedEyeFrames = 0;

    public static class DrowsinessResult {
        public boolean faceDetected = false;
        public boolean isDrowsy = false;
        public boolean isYawning = false;
        public float ear = 0f;
        public float mar = 0f;
        public long closedEyeDurationMs = 0;
        public android.graphics.RectF faceBbox = null;
    }

    public DrowsinessDetector(Context context) {
        BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET_PATH)
                .build();

        FaceLandmarkerOptions options = FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.VIDEO)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.3f)
                .setMinFacePresenceConfidence(0.3f)
                .setMinTrackingConfidence(0.3f)
                .build();

        faceLandmarker = FaceLandmarker.createFromOptions(context, options);
    }

    public DrowsinessResult detect(Bitmap bitmap, long timestampMs) {
        DrowsinessResult result = new DrowsinessResult();

        MPImage mpImage = new BitmapImageBuilder(bitmap).build();
        FaceLandmarkerResult landmarkerResult = faceLandmarker.detectForVideo(mpImage, timestampMs);

        if (landmarkerResult.faceLandmarks().isEmpty()) {
            firstClosedEyeTime = 0;
            result.isDrowsy = (System.currentTimeMillis() - lastDrowsyTime < ALERT_PERSISTENCE_MS);
            return result;
        }

        result.faceDetected = true;
        List<NormalizedLandmark> landmarks = landmarkerResult.faceLandmarks().get(0);

        float minX = 1.0f, minY = 1.0f, maxX = 0.0f, maxY = 0.0f;
        for (NormalizedLandmark lm : landmarks) {
            if (lm.x() < minX)
                minX = lm.x();
            if (lm.y() < minY)
                minY = lm.y();
            if (lm.x() > maxX)
                maxX = lm.x();
            if (lm.y() > maxY)
                maxY = lm.y();
        }
        result.faceBbox = new android.graphics.RectF(minX, minY, maxX, maxY);

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        int[] LEFT_EYE_IDX = { 362, 385, 387, 263, 373, 380 };
        int[] RIGHT_EYE_IDX = { 33, 160, 158, 133, 153, 144 };
        int[] MOUTH_IDX = { 61, 291, 13, 14 };

        List<NormalizedLandmark> leftEye = new ArrayList<>();
        for (int idx : LEFT_EYE_IDX)
            leftEye.add(landmarks.get(idx));

        List<NormalizedLandmark> rightEye = new ArrayList<>();
        for (int idx : RIGHT_EYE_IDX)
            rightEye.add(landmarks.get(idx));

        List<NormalizedLandmark> mouth = new ArrayList<>();
        for (int idx : MOUTH_IDX)
            mouth.add(landmarks.get(idx));

        float leftEar = calculateEar(leftEye, width, height);
        float rightEar = calculateEar(rightEye, width, height);
        result.ear = (leftEar + rightEar) / 2.0f;
        result.mar = calculateMar(mouth, width, height);

        if (result.ear < EAR_THRESHOLD) {
            if (firstClosedEyeTime == 0) {
                firstClosedEyeTime = System.currentTimeMillis();
            }
        } else {
            firstClosedEyeTime = 0;
        }

        long currentClosedDuration = (firstClosedEyeTime > 0) ? (System.currentTimeMillis() - firstClosedEyeTime) : 0;

        if (currentClosedDuration >= CLOSED_EYE_TIME_THRESHOLD_MS) {
            lastDrowsyTime = System.currentTimeMillis();
        }

        result.closedEyeDurationMs = currentClosedDuration;
        result.isDrowsy = (currentClosedDuration >= CLOSED_EYE_TIME_THRESHOLD_MS)
                || (System.currentTimeMillis() - lastDrowsyTime < ALERT_PERSISTENCE_MS);
        result.isYawning = (result.mar > MAR_THRESHOLD);

        return result;
    }

    private float calculateEar(List<NormalizedLandmark> eye, int w, int h) {
        if (eye.size() < 6)
            return 0f;
        float A = distance(eye.get(1), eye.get(5), w, h);
        float B = distance(eye.get(2), eye.get(4), w, h);
        float C = distance(eye.get(0), eye.get(3), w, h);
        return (A + B) / (2.0f * C);
    }

    private float calculateMar(List<NormalizedLandmark> mouth, int w, int h) {
        if (mouth.size() < 4)
            return 0f;
        float vertical = distance(mouth.get(2), mouth.get(3), w, h);
        float horizontal = distance(mouth.get(0), mouth.get(1), w, h);
        return vertical / horizontal;
    }

    private float distance(NormalizedLandmark p1, NormalizedLandmark p2, int w, int h) {
        float dx = (p1.x() - p2.x()) * w;
        float dy = (p1.y() - p2.y()) * h;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public void close() {
        if (faceLandmarker != null)
            faceLandmarker.close();
    }
}
