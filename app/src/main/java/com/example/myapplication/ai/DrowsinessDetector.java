package com.example.myapplication.ai;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * DrowsinessDetector - Nhận diện buồn ngủ thông minh sử dụng:
 * 1. MediaPipe FaceLandmarker trích xuất 468 landmark 3D và ma trận biến đổi
 * không gian.
 * 2. Tính 6 đặc trưng hình học chuẩn xác khớp 100% với Colab huấn luyện:
 * [EAR_Left, EAR_Right, MAR, Pitch, Yaw, Roll].
 * 3. Mô hình BiLSTM (Cửa sổ trượt 15 frame) kết hợp cơ chế chốt giữ liên tục
 * khi vẫn nhắm mắt.
 */
public class DrowsinessDetector {

    private static final String TAG = "DrowsinessDetector";

    // Tên file model và scaler trong thư mục assets
    private static final String LSTM_MODEL_FP16 = "drowsiness_detector_fp16.tflite";
    private static final String SCALER_PARAMS_FILE = "scaler_params.json";
    private static final String FACE_LANDMARKER_MODEL = "face_landmarker.task";

    // Cấu hình Cửa sổ trượt LSTM: 15 frame liên tiếp (Khớp 100% Colab)
    public static final int WINDOW_SIZE = 15;
    public static final int NUM_FEATURES = 6; // [EAR_Left, EAR_Right, MAR, pitch, yaw, roll]
    public static final float DROWSINESS_THRESHOLD = 0.40f; // Ngưỡng nhận diện nhạy và chuẩn xác (40%)

    // Ngưỡng khép chặt mí mắt (Dành riêng cho trạng thái ngủ nhắm nghiền mắt)
    // Khi mí mắt khép chặt vào nhau, EAR luôn tụt sâu xuống dưới 0.17 ở mọi dáng
    // mắt.
    public static final float EYES_CLOSED_EAR_THRESHOLD = 0.15f;

    // Các ngưỡng bổ trợ cảnh báo an toàn
    public static final float MAR_THRESHOLD = 0.65f; // Ngưỡng phát hiện ngáp
    public static final float DISTRACTION_YAW_THRESHOLD = 35.0f; // Góc quay đầu > 35 độ (chuẩn ADAS)
    public static final float HEAD_CENTER_YAW_THRESHOLD = 18.0f; // Vùng an toàn nhìn thẳng (Hysteresis)
    public static final int CENTER_HEAD_FRAMES_TO_CLEAR = 3; // 3 frame liên tiếp nhìn thẳng để tắt đỏ ngay
    public static final long DISTRACTION_TIME_THRESHOLD_MS = 2500L; // Quay đầu 2.5 giây = báo động mất tập trung
    public static final long FACE_MISSING_TIME_THRESHOLD_MS = 3000L; // Mất mặt 3 giây = báo động không thấy tài xế
    public static final long ALERT_PERSISTENCE_MS = 1000L; // Duy trì trạng thái cảnh báo 1 giây (1.0s) sau khi mở mắt

    // Padding cho Bounding Box hiển thị UI
    private static final float FACE_CROP_PADDING_X = 0.15f;

    // Các chỉ số Landmark chuẩn từ MediaPipe (Khớp 100% với file trichXuat.ipynb
    // lúc train)
    private static final int[] LEFT_EYE_CORNERS = { 33, 133 };
    private static final int[] LEFT_EYE_VERT1 = { 160, 144 };
    private static final int[] LEFT_EYE_VERT2 = { 158, 153 };

    private static final int[] RIGHT_EYE_CORNERS = { 362, 263 };
    private static final int[] RIGHT_EYE_VERT1 = { 385, 380 };
    private static final int[] RIGHT_EYE_VERT2 = { 387, 373 };

    private static final int[] MOUTH_CORNERS = { 61, 291 };
    private static final int[] MOUTH_LIPS = { 13, 14 };

    // Detectors
    private final FaceLandmarker faceLandmarker;
    private final Interpreter lstmInterpreter;

    // Bộ nhớ đệm Cửa sổ trượt 15 frame
    private final Deque<float[]> sequenceBuffer = new ArrayDeque<>(WINDOW_SIZE);
    private final float[][][] lstmInputBuffer = new float[1][WINDOW_SIZE][NUM_FEATURES];
    private final float[][] lstmOutputBuffer = new float[1][1];

    // Bộ tham số chuẩn hóa backup (Khớp 100% với scaler_params.json từ Colab)
    private final float[] scalerMean = new float[] {
            0.30109289573440345f, 0.31031264726325436f, 0.13542384890804707f,
            3.0011908976013073f, 14.141056761247397f, 2.122260188271392f
    };
    private final float[] scalerStd = new float[] {
            0.10028701967373528f, 0.09783590447246555f, 0.2004178641198358f,
            9.815895911316911f, 15.381944654242444f, 5.844676978018849f
    };

    // Logging & Timers
    private long lastLogTimeMs = 0;
    private long firstDrowsyTime = 0;
    private long lastDrowsyTime = 0;
    private long firstDistractedTime = 0;
    private long lastDistractedTime = 0;
    private long firstFaceMissingTime = 0;
    private float lastKnownEar = 0.3f;
    private float lastKnownYaw = 0f;

    // Chốt trạng thái buồn ngủ & mất tập trung
    private boolean isDrowsyActive = false;
    private int consecutiveCenterHeadFrames = 0;

    // -------------------------------------------------------------------------
    // Kết quả trả về cho UI và Backend
    // -------------------------------------------------------------------------
    public static class DrowsinessResult {
        public boolean faceDetected = false;
        public boolean isDrowsy = false;
        public boolean isYawning = false;
        public float ear = 0f;
        public float earLeft = 0f;
        public float earRight = 0f;
        public float mar = 0f;
        public float headEulerX = 0f; // Pitch
        public float headEulerY = 0f; // Yaw
        public float headEulerZ = 0f; // Roll
        public float drowsinessScore = 0f; // Xác suất buồn ngủ từ LSTM (0.0 -> 1.0)
        public boolean isDistracted = false;
        public boolean isFaceMissing = false;
        public long closedEyeDurationMs = 0; // Thời lượng buồn ngủ (ms) gửi lên Server
        public long distractedDurationMs = 0;
        public RectF faceBbox = null;
    }

    // -------------------------------------------------------------------------
    // Khởi tạo Detector
    // -------------------------------------------------------------------------
    public DrowsinessDetector(Context context) throws IOException {
        // 1. Khởi tạo MediaPipe Face Landmarker
        BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath(FACE_LANDMARKER_MODEL)
                .build();

        FaceLandmarker.FaceLandmarkerOptions landmarkerOptions = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setOutputFacialTransformationMatrixes(true)
                .setNumFaces(1)
                .setRunningMode(RunningMode.IMAGE)
                .build();

        faceLandmarker = FaceLandmarker.createFromOptions(context, landmarkerOptions);

        // 2. Nạp tham số Scaler từ JSON (nếu có)
        loadScalerParams(context);

        // 3. Khởi tạo Interpreter LSTM TFLite
        MappedByteBuffer modelBuffer = loadModelFile(context);
        Interpreter.Options lstmOptions = new Interpreter.Options();
        lstmOptions.setNumThreads(2);
        lstmOptions.setUseXNNPACK(true);
        lstmInterpreter = new Interpreter(modelBuffer, lstmOptions);
        Log.i(TAG, "Đã khởi tạo DrowsinessDetector thành công (MediaPipe FaceLandmarker + LSTM 15 frames)!");
    }

    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        Log.i(TAG, "Đang nạp mô hình LSTM: " + LSTM_MODEL_FP16);
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(LSTM_MODEL_FP16);
        try (FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor())) {
            FileChannel fileChannel = inputStream.getChannel();
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        }
    }

    private void loadScalerParams(Context context) {
        try (InputStream is = context.getAssets().open(SCALER_PARAMS_FILE)) {
            int size = is.available();
            byte[] buffer = new byte[size];
            int read = is.read(buffer);
            if (read > 0) {
                String json = new String(buffer, StandardCharsets.UTF_8);
                JSONObject obj = new JSONObject(json);
                JSONArray meanArr = obj.getJSONArray("mean");
                JSONArray stdArr = obj.getJSONArray("std");
                for (int i = 0; i < 6; i++) {
                    scalerMean[i] = (float) meanArr.getDouble(i);
                    scalerStd[i] = (float) stdArr.getDouble(i);
                }
                Log.i(TAG, "Đã nạp thành công scaler_params.json!");
            }
        } catch (Exception e) {
            Log.w(TAG, "Không đọc được scaler_params.json (" + e.getMessage() + "), dùng mặc định.");
        }
    }

    // -------------------------------------------------------------------------
    // Hàm nhận diện chính
    // -------------------------------------------------------------------------
    public DrowsinessResult detect(Bitmap bitmap, long timestampMs) {
        DrowsinessResult result = new DrowsinessResult();
        if (bitmap == null || bitmap.isRecycled()) {
            return result;
        }

        try {
            long currentTimeMs = (timestampMs > 0) ? timestampMs : System.currentTimeMillis();

            // Bước 1: Trích xuất Face Landmarker trực tiếp từ Bitmap qua MediaPipe
            MPImage mpImage = new BitmapImageBuilder(bitmap).build();
            FaceLandmarkerResult landmarkerResult = faceLandmarker.detect(mpImage);

            // Bước 2: Kiểm tra nếu không phát hiện khuôn mặt
            if (landmarkerResult == null || landmarkerResult.faceLandmarks().isEmpty()) {
                handleFaceMissing(result, currentTimeMs);

                // Đẩy vector 0 vào hàng đợi và chạy đánh giá LSTM
                pushToSequence(0f, 0f, 0f, 0f, 0f, 0f);
                evaluateLstmDrowsiness(result);

                // Kiểm tra duy trì cảnh báo nếu vừa mới kích hoạt trong vòng 1 giây
                boolean withinAlertWindow = (lastDrowsyTime > 0
                        && (currentTimeMs - lastDrowsyTime < ALERT_PERSISTENCE_MS));
                result.isDrowsy = isDrowsyActive || withinAlertWindow;
                if (!result.isDrowsy) {
                    isDrowsyActive = false;
                    firstDrowsyTime = 0;
                    result.closedEyeDurationMs = 0;
                } else if (firstDrowsyTime > 0) {
                    result.closedEyeDurationMs = currentTimeMs - firstDrowsyTime;
                }

                if (currentTimeMs - lastLogTimeMs >= 500L) {
                    lastLogTimeMs = currentTimeMs;
                    if (result.isDrowsy) {
                        Log.w(TAG, String.format(
                                "🚨 [DROWSY-HEAD_DOWN] Mất dấu mặt khi đang ngủ gật! Active=%b | Thời lượng: %dms",
                                isDrowsyActive, result.closedEyeDurationMs));
                    }
                }
                return result;
            }

            // Bước 3: ĐÃ TÌM THẤY KHUÔN MẶT!
            firstFaceMissingTime = 0;
            result.faceDetected = true;

            List<NormalizedLandmark> faceLm = landmarkerResult.faceLandmarks().get(0);

            // Tính Bounding Box khuôn mặt từ các landmark
            float minX = 1f, minY = 1f, maxX = 0f, maxY = 0f;
            for (NormalizedLandmark lm : faceLm) {
                float x = lm.x();
                float y = lm.y();
                if (x < minX)
                    minX = x;
                if (x > maxX)
                    maxX = x;
                if (y < minY)
                    minY = y;
                if (y > maxY)
                    maxY = y;
            }
            float padX = (maxX - minX) * FACE_CROP_PADDING_X;
            result.faceBbox = new RectF(
                    Math.max(0f, minX - padX),
                    Math.max(0f, minY),
                    Math.min(1f, maxX + padX),
                    Math.min(1f, maxY));

            // Bước 4: Tính EAR (Left & Right) và MAR theo đúng công thức Colab
            result.earLeft = computeEar(faceLm, LEFT_EYE_CORNERS, LEFT_EYE_VERT1, LEFT_EYE_VERT2);
            result.earRight = computeEar(faceLm, RIGHT_EYE_CORNERS, RIGHT_EYE_VERT1, RIGHT_EYE_VERT2);
            result.mar = computeMar(faceLm);

            // Bước 5: Trích xuất góc quay Euler (Pitch, Yaw, Roll) từ ma trận 3D của
            // MediaPipe
            float pitch = 0.0f;
            float yaw = 0.0f;
            float roll = 0.0f;
            if (landmarkerResult.facialTransformationMatrixes().isPresent()) {
                List<float[]> matrices = landmarkerResult.facialTransformationMatrixes().get();
                if (!matrices.isEmpty()) {
                    float[] mat = matrices.get(0);
                    // Ma trận 4x4 lưu theo thứ tự column-major trong flat array 16 phần tử:
                    // mat[0]=r00, mat[1]=r10, mat[2]=r20
                    // mat[6]=r21, mat[10]=r22
                    float m00 = mat[0];
                    float m10 = mat[1];
                    float m20 = mat[2];
                    float m21 = mat[6];
                    float m22 = mat[10];

                    double sy = Math.sqrt(m00 * m00 + m10 * m10);
                    if (sy > 1e-6) {
                        pitch = (float) Math.toDegrees(Math.atan2(m21, m22));
                        yaw = (float) Math.toDegrees(Math.atan2(-m20, sy));
                        roll = (float) Math.toDegrees(Math.atan2(m10, m00));
                    }
                }
            }
            result.headEulerX = pitch;
            result.headEulerY = yaw;
            result.headEulerZ = roll;

            // Tính EAR chống che khuất (Anti-occlusion):
            // Khi quay đầu (|Yaw| > 15 độ) hoặc 1 bên mắt bị sống mũi che (ear <= 0.05):
            boolean isHeadTurned = Math.abs(result.headEulerY) > 15.0f;
            if (isHeadTurned || result.earLeft <= 0.05f || result.earRight <= 0.05f) {
                result.ear = Math.max(result.earLeft, result.earRight);
            } else {
                result.ear = (result.earLeft + result.earRight) / 2.0f;
            }

            lastKnownEar = result.ear;
            lastKnownYaw = result.headEulerY;

            // Bù giá trị cho mắt bị sống mũi che trước khi đẩy vào chuỗi LSTM
            float effEarL = result.earLeft;
            float effEarR = result.earRight;
            if (isHeadTurned) {
                if (effEarL <= 0.05f && effEarR > 0.05f)
                    effEarL = effEarR;
                else if (effEarR <= 0.05f && effEarL > 0.05f)
                    effEarR = effEarL;
            }

            // Bước 6: Đẩy đặc trưng vào chuỗi và chạy mô hình LSTM (1 frame = 1 bước trượt)
            pushToSequence(
                    effEarL,
                    effEarR,
                    result.mar,
                    result.headEulerX,
                    result.headEulerY,
                    result.headEulerZ);

            evaluateLstmDrowsiness(result);

            // Bước 7: Kiểm tra hành vi ngáp
            result.isYawning = (result.mar > MAR_THRESHOLD);

            // Bước 8: Kiểm tra trạng thái quay đầu mất tập trung (Hysteresis & Instant
            // Release)
            boolean isDistractedTurn = Math.abs(result.headEulerY) > DISTRACTION_YAW_THRESHOLD;
            boolean isLookingStraight = Math.abs(result.headEulerY) <= HEAD_CENTER_YAW_THRESHOLD;

            if (isDistractedTurn) {
                consecutiveCenterHeadFrames = 0;
                if (firstDistractedTime == 0) {
                    firstDistractedTime = currentTimeMs;
                }
            } else {
                firstDistractedTime = 0;
                if (isLookingStraight) {
                    consecutiveCenterHeadFrames++;
                    if (consecutiveCenterHeadFrames >= CENTER_HEAD_FRAMES_TO_CLEAR) {
                        lastDistractedTime = 0;
                    }
                } else {
                    consecutiveCenterHeadFrames = 0;
                }
            }
            long currentDistractedDuration = (firstDistractedTime > 0)
                    ? (currentTimeMs - firstDistractedTime)
                    : 0;
            if (currentDistractedDuration >= DISTRACTION_TIME_THRESHOLD_MS) {
                lastDistractedTime = currentTimeMs;
            }
            result.distractedDurationMs = currentDistractedDuration;
            result.isDistracted = (currentDistractedDuration >= DISTRACTION_TIME_THRESHOLD_MS)
                    || (lastDistractedTime > 0 && (currentTimeMs - lastDistractedTime < ALERT_PERSISTENCE_MS));

            // Bước 9: Quyết định trạng thái buồn ngủ thông minh & Giữ cảnh báo liên tục khi
            // vẫn nhắm mắt
            boolean isLstmDrowsy = (result.drowsinessScore >= DROWSINESS_THRESHOLD) && !isDistractedTurn;
            boolean isEyesPhysicallyClosed = (result.ear < EYES_CLOSED_EAR_THRESHOLD) && !isDistractedTurn;

            // Đang có biểu hiện buồn ngủ: Hoặc do AI phát hiện, hoặc do mắt vẫn đang khép
            // chặt khi đang trong đợt cảnh báo
            boolean isCurrentlyDrowsy = isLstmDrowsy || (isDrowsyActive && isEyesPhysicallyClosed);

            if (isCurrentlyDrowsy) {
                isDrowsyActive = true;
                lastDrowsyTime = currentTimeMs; // Liên tục gia hạn mốc thời gian -> Còi kêu liên tục, không bao giờ tắt
                                                // khi vẫn nhắm
                if (firstDrowsyTime == 0) {
                    firstDrowsyTime = currentTimeMs;
                }
                result.closedEyeDurationMs = currentTimeMs - firstDrowsyTime;
            } else {
                // Người lái KHÔNG còn biểu hiện buồn ngủ (mắt đã mở ra VÀ AI đã hạ điểm):
                // Duy trì cảnh báo thêm 1.0 giây sau khi mở mắt để chống giật còi
                if (lastDrowsyTime > 0 && (currentTimeMs - lastDrowsyTime < ALERT_PERSISTENCE_MS)) {
                    if (firstDrowsyTime > 0) {
                        result.closedEyeDurationMs = currentTimeMs - firstDrowsyTime;
                    }
                } else {
                    // Đã qua 1.0 giây sau khi mở mắt tỉnh táo -> Chính thức tắt cảnh báo
                    isDrowsyActive = false;
                    firstDrowsyTime = 0;
                    result.closedEyeDurationMs = 0;
                }
            }

            result.isDrowsy = isDrowsyActive
                    || (lastDrowsyTime > 0 && (currentTimeMs - lastDrowsyTime < ALERT_PERSISTENCE_MS));

            // Log theo thời gian thực (0.5s / lần)
            if (currentTimeMs - lastLogTimeMs >= 500L) {
                lastLogTimeMs = currentTimeMs;
                if (result.isDistracted) {
                    Log.w(TAG, String.format("👀 [DISTRACTED] MẤT TẬP TRUNG (Quay đầu)! Yaw: %.1f° | Dur: %dms",
                            result.headEulerY, result.distractedDurationMs));
                } else if (result.isDrowsy) {
                    Log.w(TAG, String.format(
                            "🚨 [DROWSY] CẢNH BÁO BUỒN NGỦ (Active=%b)! AI: %.1f%% | EAR: %.2f | Dur: %dms",
                            isDrowsyActive, result.drowsinessScore * 100f, result.ear, result.closedEyeDurationMs));
                } else {
                    Log.d(TAG, String.format("✅ [AWAKE] TỈNH TÁO | AI: %.1f%% | EAR: %.2f | Yaw: %.1f°",
                            result.drowsinessScore * 100f, result.ear, result.headEulerY));
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Lỗi khi xử lý frame: " + e.getMessage(), e);
        }

        return result;
    }

    private void pushToSequence(float earL, float earR, float mar, float pitch, float yaw, float roll) {
        float normEarL = (earL - scalerMean[0]) / scalerStd[0];
        float normEarR = (earR - scalerMean[1]) / scalerStd[1];
        float normMar = (mar - scalerMean[2]) / scalerStd[2];
        float normPitch = (pitch - scalerMean[3]) / scalerStd[3];
        float normYaw = (yaw - scalerMean[4]) / scalerStd[4];
        float normRoll = (roll - scalerMean[5]) / scalerStd[5];

        float[] featureVector = new float[] { normEarL, normEarR, normMar, normPitch, normYaw, normRoll };

        synchronized (sequenceBuffer) {
            if (sequenceBuffer.size() >= WINDOW_SIZE) {
                sequenceBuffer.pollFirst();
            }
            sequenceBuffer.addLast(featureVector);
        }
    }

    private void evaluateLstmDrowsiness(DrowsinessResult result) {
        synchronized (sequenceBuffer) {
            if (sequenceBuffer.isEmpty()) {
                result.drowsinessScore = 0f;
                return;
            }

            int size = sequenceBuffer.size();
            int padCount = WINDOW_SIZE - size;

            float[] firstItem = sequenceBuffer.peekFirst();
            for (int i = 0; i < padCount; i++) {
                System.arraycopy(firstItem, 0, lstmInputBuffer[0][i], 0, NUM_FEATURES);
            }

            int idx = padCount;
            for (float[] item : sequenceBuffer) {
                System.arraycopy(item, 0, lstmInputBuffer[0][idx], 0, NUM_FEATURES);
                idx++;
            }
        }

        lstmInterpreter.run(lstmInputBuffer, lstmOutputBuffer);
        float score = lstmOutputBuffer[0][0];
        result.drowsinessScore = score;
    }

    private void handleFaceMissing(DrowsinessResult result, long currentTimeMs) {
        boolean isTurningHead = Math.abs(lastKnownYaw) >= 28.0f;
        consecutiveCenterHeadFrames = 0;

        if (isDrowsyActive) {
            // Đang trong trạng thái buồn ngủ mà mất mặt (gục đầu xuống) -> liên tục gia hạn
            // cảnh báo
            lastDrowsyTime = currentTimeMs;
            if (firstDrowsyTime == 0) {
                firstDrowsyTime = currentTimeMs;
            }
            result.closedEyeDurationMs = currentTimeMs - firstDrowsyTime;
            result.isDrowsy = true;
        } else {
            if (isTurningHead) {
                // Đang quay đầu làm mất mặt -> tính là mất tập trung
                if (firstDistractedTime == 0) {
                    firstDistractedTime = currentTimeMs;
                }
                long distractedDuration = currentTimeMs - firstDistractedTime;
                if (distractedDuration >= DISTRACTION_TIME_THRESHOLD_MS) {
                    lastDistractedTime = currentTimeMs;
                }
                result.distractedDurationMs = distractedDuration;
                result.isDistracted = (distractedDuration >= DISTRACTION_TIME_THRESHOLD_MS)
                        || (lastDistractedTime > 0
                                && (currentTimeMs - lastDistractedTime < ALERT_PERSISTENCE_MS));
            } else {
                firstDistractedTime = 0;
            }

            if (firstFaceMissingTime == 0) {
                firstFaceMissingTime = currentTimeMs;
            }
            long missingDuration = currentTimeMs - firstFaceMissingTime;
            if (missingDuration >= FACE_MISSING_TIME_THRESHOLD_MS) {
                result.isFaceMissing = true;
                lastKnownYaw = 0f;
                firstDistractedTime = 0;
                lastDistractedTime = 0;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Công thức tính EAR & MAR chuẩn MediaPipe 3D khớp 100% Colab (trichXuat.ipynb)
    // -------------------------------------------------------------------------
    private static float dist3D(NormalizedLandmark p1, NormalizedLandmark p2) {
        float dx = p1.x() - p2.x();
        float dy = p1.y() - p2.y();
        float dz = p1.z() - p2.z();
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static float computeEar(List<NormalizedLandmark> lm, int[] corners, int[] vert1, int[] vert2) {
        NormalizedLandmark pC1 = lm.get(corners[0]);
        NormalizedLandmark pC2 = lm.get(corners[1]);
        NormalizedLandmark pV1Top = lm.get(vert1[0]);
        NormalizedLandmark pV1Bot = lm.get(vert1[1]);
        NormalizedLandmark pV2Top = lm.get(vert2[0]);
        NormalizedLandmark pV2Bot = lm.get(vert2[1]);

        float dHoriz = dist3D(pC1, pC2);
        if (dHoriz < 1e-6f) {
            return 0.0f;
        }
        return (dist3D(pV1Top, pV1Bot) + dist3D(pV2Top, pV2Bot)) / (2.0f * dHoriz);
    }

    private static float computeMar(List<NormalizedLandmark> lm) {
        NormalizedLandmark pC1 = lm.get(MOUTH_CORNERS[0]);
        NormalizedLandmark pC2 = lm.get(MOUTH_CORNERS[1]);
        NormalizedLandmark pTop = lm.get(MOUTH_LIPS[0]);
        NormalizedLandmark pBot = lm.get(MOUTH_LIPS[1]);

        float dHoriz = dist3D(pC1, pC2);
        if (dHoriz < 1e-6f) {
            return 0.0f;
        }
        return dist3D(pTop, pBot) / dHoriz;
    }

    public void close() {
        if (lstmInterpreter != null) {
            lstmInterpreter.close();
        }
        if (faceLandmarker != null) {
            faceLandmarker.close();
        }
    }
}
