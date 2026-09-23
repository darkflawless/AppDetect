package com.example.myapplication.ai;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
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
import java.util.concurrent.ExecutionException;

/**
 * DrowsinessDetector - Nhận diện buồn ngủ thông minh sử dụng:
 * 1. TFLite Face Detector (YOLO) định vị khuôn mặt tài xế.
 * 2. Google ML Kit Face Mesh trích xuất 6 đặc trưng hình học chuẩn:
 * [EAR_Left, EAR_Right, MAR, Pitch, Yaw, Roll].
 * 3. Quét toàn bộ khung hình (100% full frame) đảm bảo không bị mất dấu mặt khi
 * cầm điện thoại.
 * 4. Bộ điều tiết thời gian (Time-based Resampling ~33ms) chuẩn hóa nhịp 30
 * FPS.
 * 5. Mô hình LSTM (Cửa sổ trượt 30 frame) chạy qua TFLite.
 */
public class DrowsinessDetector {

    private static final String TAG = "DrowsinessDetector";

    // Tên file model và scaler trong thư mục assets
    private static final String LSTM_MODEL_FP16 = "drowsiness_detector_fp16.tflite";
    private static final String LSTM_MODEL_F32 = "drowsiness_detector.tflite";
    private static final String SCALER_PARAMS_FILE = "scaler_params.json";

    // Cấu hình Cửa sổ trượt LSTM
    public static final int WINDOW_SIZE = 30; // 30 frame tương đương 1.0 giây ở 30 FPS
    public static final int NUM_FEATURES = 6; // [EAR_Left, EAR_Right, MAR, pitch, yaw, roll]
    public static final float DROWSINESS_THRESHOLD = 0.40f; // Ngưỡng nhận diện nhạy và chuẩn xác (40%)

    // Bộ điều tiết lấy mẫu theo thời gian (Time-based Resampling)
    public static final long TARGET_FRAME_INTERVAL_MS = 33L; // Chuẩn 30 FPS (1000ms / 30 = 33.3ms)
    private static final long MIN_FRAME_INTERVAL_MS = 25L; // Bỏ qua frame thừa nếu camera chạy quá nhanh (> 40 FPS)
    private static final long MAX_INTERPOLATION_STEPS = 6; // Bù tối đa 6 nhịp (~200ms) nếu camera bị tụt FPS

    // Các ngưỡng bổ trợ cảnh báo
    public static final float EAR_THRESHOLD = 0.20f; // Mốc tham khảo nhắm mắt đo thời lượng
    public static final float EYE_OPEN_EAR_THRESHOLD = 0.21f; // Mốc xác nhận mắt đã mở tỉnh táo (thích ứng tốt cho cả
                                                              // mắt nhỏ)
    public static final int OPEN_EYE_FRAMES_TO_CLEAR = 4; // Chỉ cần 4 frame liên tiếp mở mắt (~0.12s - 0.15s) để tắt
                                                          // cảnh báo ngay
    public static final long CLOSED_EYE_TIME_THRESHOLD_MS = 1500L; // Nhắm mắt liên tục 1.5s = chắc chắn buồn ngủ
                                                                   // (Safety backup)
    public static final float MAR_THRESHOLD = 0.70f; // Ngưỡng phát hiện ngáp
    public static final float DISTRACTION_YAW_THRESHOLD = 35.0f; // Góc quay đầu > 35 độ (chuẩn ADAS, không lo bị báo nhầm khi cầm máy)
    public static final float HEAD_CENTER_YAW_THRESHOLD = 18.0f; // Vùng an toàn nhìn thẳng (Hysteresis)
    public static final int CENTER_HEAD_FRAMES_TO_CLEAR = 3; // Chỉ cần 3 frame liên tiếp nhìn thẳng (~0.1s) để tắt đỏ ngay lập tức
    public static final long DISTRACTION_TIME_THRESHOLD_MS = 2500L; // Quay đầu 2.5 giây = báo động mất tập trung
    public static final long FACE_MISSING_TIME_THRESHOLD_MS = 3000L; // Mất mặt 3 giây = báo động không thấy tài xế
    private static final long ALERT_PERSISTENCE_MS = 1000L; // Duy trì trạng thái cảnh báo tối thiểu 1 giây nếu chưa nhìn thẳng

    // Cấu hình vùng quét: Quét 100% khung hình để không bao giờ bị cắt mất mặt khi
    // cầm điện thoại
    private static final float DRIVER_REGION_FRACTION = 1.0f;
    private static final float FACE_CROP_PADDING_X = 0.25f;

    // Detectors
    private final TFLiteFaceDetector tfliteDetector;
    private final FaceDetector mlkitDetector;
    private final Interpreter lstmInterpreter;

    // Bộ nhớ đệm Cửa sổ trượt 30 frame
    private final Deque<float[]> sequenceBuffer = new ArrayDeque<>(WINDOW_SIZE);
    private final float[][][] lstmInputBuffer = new float[1][WINDOW_SIZE][NUM_FEATURES];
    private final float[][] lstmOutputBuffer = new float[1][1];

    // Bộ tham số chuẩn hóa (Khớp 100% với StandardScaler lúc train)
    private final float[] scalerMean = new float[] {
            0.219958f, 0.251387f, 0.505516f, 0.213905f, 15.005088f, 0.392417f
    };
    private final float[] scalerStd = new float[] {
            0.091953f, 0.100373f, 0.202773f, 7.984931f, 18.029117f, 4.999533f
    };

    // Quản lý nhịp thời gian lấy mẫu và logging
    private long lastSampleTimestampMs = 0;
    private float lastDrowsinessScore = 0f;
    private long lastLogTimeMs = 0;

    // Timers & Trạng thái
    private long firstClosedEyeTime = 0;
    private long lastDrowsyTime = 0;
    private long firstDistractedTime = 0;
    private long lastDistractedTime = 0;
    private long firstFaceMissingTime = 0;
    private float lastKnownEar = 0.3f;
    private float lastKnownYaw = 0f;

    // Chốt trạng thái buồn ngủ & mất tập trung (State Latch & Instant Release)
    private boolean isDrowsyActive = false;
    private int consecutiveOpenEyeFrames = 0;
    private int consecutiveCenterHeadFrames = 0;

    // Quản lý Face Tracking (Tối ưu FPS bằng cách bỏ qua TFLite mỗi frame)
    private RectF lastTrackedFaceBbox = null;
    private int consecutiveTrackFrames = 0;
    private static final int MAX_TRACK_INTERVAL_FRAMES = 5; // Cập nhật lại TFLite sau mỗi 5 frame (~150ms)

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
        public long closedEyeDurationMs = 0;
        public long distractedDurationMs = 0;
        public RectF faceBbox = null;
    }

    // -------------------------------------------------------------------------
    // Khởi tạo Detector
    // -------------------------------------------------------------------------
    public DrowsinessDetector(Context context) throws IOException {
        tfliteDetector = new TFLiteFaceDetector(context);

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setMinFaceSize(0.30f)
                .build();
        mlkitDetector = FaceDetection.getClient(options);

        loadScalerParams(context);

        MappedByteBuffer modelBuffer = loadModelFile(context);
        Interpreter.Options lstmOptions = new Interpreter.Options();
        lstmOptions.setNumThreads(2);
        lstmOptions.setUseXNNPACK(true);
        lstmInterpreter = new Interpreter(modelBuffer, lstmOptions);
        Log.i(TAG, "Đã khởi tạo DrowsinessDetector (Quét toàn màn hình 100% + LSTM 30 FPS)!");
    }

    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        String chosenFile = LSTM_MODEL_FP16;
        try {
            AssetFileDescriptor fd = context.getAssets().openFd(chosenFile);
            fd.close();
        } catch (Exception e) {
            chosenFile = LSTM_MODEL_F32;
        }

        Log.i(TAG, "Đang nạp mô hình LSTM: " + chosenFile);
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(chosenFile);
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

        try {
            long currentTimeMs = (timestampMs > 0) ? timestampMs : System.currentTimeMillis();
            long elapsedSinceLastSample = (lastSampleTimestampMs == 0)
                    ? TARGET_FRAME_INTERVAL_MS
                    : (currentTimeMs - lastSampleTimestampMs);

            // Bước 1: Quét vùng khuôn mặt (Toàn bộ khung hình)
            Bitmap driverRegion = cropDriverRegion(bitmap);

            // Bước 2: Tìm hoặc bám vết khuôn mặt (Face Tracking để tăng tốc FPS)
            RectF faceBboxInCrop = null;
            boolean isTrackingFrame = false;

            if (lastTrackedFaceBbox != null && consecutiveTrackFrames < MAX_TRACK_INTERVAL_FRAMES) {
                faceBboxInCrop = lastTrackedFaceBbox;
                isTrackingFrame = true;
            } else {
                faceBboxInCrop = tfliteDetector.detectBestFace(driverRegion);
            }

            // Bước 3: Crop khuôn mặt cho ML Kit
            List<Face> faces = null;
            if (faceBboxInCrop != null) {
                Bitmap faceCrop = cropFace(driverRegion, faceBboxInCrop);
                InputImage inputImage = InputImage.fromBitmap(faceCrop, 0);
                faces = Tasks.await(mlkitDetector.process(inputImage));
                if (faceCrop != null && faceCrop != driverRegion) {
                    faceCrop.recycle(); // Giải phóng Bitmap crop để tránh áp lực GC
                }

                // Nếu đang dùng tracking mà ML Kit không tìm thấy mặt -> thử fallback lại bằng
                // TFLite
                if ((faces == null || faces.isEmpty()) && isTrackingFrame) {
                    lastTrackedFaceBbox = null;
                    consecutiveTrackFrames = 0;
                    faceBboxInCrop = tfliteDetector.detectBestFace(driverRegion);
                    if (faceBboxInCrop != null) {
                        Bitmap retryFaceCrop = cropFace(driverRegion, faceBboxInCrop);
                        InputImage retryInput = InputImage.fromBitmap(retryFaceCrop, 0);
                        faces = Tasks.await(mlkitDetector.process(retryInput));
                        if (retryFaceCrop != null && retryFaceCrop != driverRegion) {
                            retryFaceCrop.recycle();
                        }
                    }
                }
            }

            // Nếu hoàn toàn không phát hiện khuôn mặt:
            if (faceBboxInCrop == null || faces == null || faces.isEmpty()) {
                lastTrackedFaceBbox = null;
                consecutiveTrackFrames = 0;
                handleFaceMissing(result);

                if (elapsedSinceLastSample >= MIN_FRAME_INTERVAL_MS) {
                    int steps = calculateSteps(elapsedSinceLastSample);
                    for (int s = 0; s < steps; s++) {
                        pushToSequence(0f, 0f, 0f, 0f, 0f, 0f);
                    }
                    lastSampleTimestampMs = currentTimeMs;
                    evaluateLstmDrowsiness(result);
                    lastDrowsinessScore = result.drowsinessScore;
                } else {
                    result.drowsinessScore = lastDrowsinessScore;
                }

                // Chốt giữ cảnh báo nếu đang trong đợt báo động (dù mất mặt do gục đầu)
                result.isDrowsy = isDrowsyActive
                        || (result.drowsinessScore >= DROWSINESS_THRESHOLD)
                        || (result.closedEyeDurationMs >= CLOSED_EYE_TIME_THRESHOLD_MS)
                        || (lastDrowsyTime > 0 && (currentTimeMs - lastDrowsyTime < ALERT_PERSISTENCE_MS));

                if (currentTimeMs - lastLogTimeMs >= 500L) {
                    lastLogTimeMs = currentTimeMs;
                    if (result.isDrowsy) {
                        Log.w(TAG,
                                String.format(
                                        "🚨 [DROWSY-HEAD_DOWN] Mất dấu mặt khi đang ngủ gật! Active=%b | Nhắm: %dms",
                                        isDrowsyActive, result.closedEyeDurationMs));
                    }
                }
                return result;
            }

            // ĐÃ TÌM THẤY MẶT VÀ MLKIT XÁC NHẬN!
            lastTrackedFaceBbox = faceBboxInCrop;
            consecutiveTrackFrames++;
            firstFaceMissingTime = 0;
            result.faceDetected = true;

            RectF faceBboxFull = mapBboxToFullFrame(faceBboxInCrop);
            float padX = (faceBboxFull.right - faceBboxFull.left) * FACE_CROP_PADDING_X;
            result.faceBbox = new RectF(
                    Math.max(0f, faceBboxFull.left - padX),
                    faceBboxFull.top,
                    Math.min(1f, faceBboxFull.right + padX),
                    faceBboxFull.bottom);

            Face face = faces.get(0);
            FaceContour leftEyeContour = face.getContour(FaceContour.LEFT_EYE);
            FaceContour rightEyeContour = face.getContour(FaceContour.RIGHT_EYE);
            result.earLeft = (leftEyeContour != null) ? calcEar(leftEyeContour.getPoints()) : 0.0f;
            result.earRight = (rightEyeContour != null) ? calcEar(rightEyeContour.getPoints()) : 0.0f;

            FaceContour upperLipBottom = face.getContour(FaceContour.UPPER_LIP_BOTTOM);
            FaceContour lowerLipTop = face.getContour(FaceContour.LOWER_LIP_TOP);
            if (upperLipBottom != null && lowerLipTop != null &&
                    upperLipBottom.getPoints().size() >= 9 && lowerLipTop.getPoints().size() >= 9) {
                result.mar = calcMar(upperLipBottom.getPoints(), lowerLipTop.getPoints());
            } else {
                FaceContour upperLipTop = face.getContour(FaceContour.UPPER_LIP_TOP);
                FaceContour lowerLipBottom = face.getContour(FaceContour.LOWER_LIP_BOTTOM);
                if (upperLipTop != null && lowerLipBottom != null) {
                    result.mar = calcMarFallback(upperLipTop.getPoints(), lowerLipBottom.getPoints());
                }
            }

            result.headEulerX = face.getHeadEulerAngleX();
            result.headEulerY = face.getHeadEulerAngleY();
            result.headEulerZ = face.getHeadEulerAngleZ();

            // Tính EAR thông minh chống che khuất (Anti-occlusion):
            // Khi quay đầu nghiêng (|Yaw| > 15 độ) hoặc 1 bên mắt bị sống mũi che (ear <=
            // 0.08):
            // Lấy max(earLeft, earRight) vì con mắt phía trước phản ánh đúng mắt đang mở!
            boolean isHeadTurned = Math.abs(result.headEulerY) > 15.0f;
            if (isHeadTurned || result.earLeft <= 0.08f || result.earRight <= 0.08f) {
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
                if (effEarL <= 0.08f && effEarR > 0.08f)
                    effEarL = effEarR;
                else if (effEarR <= 0.08f && effEarL > 0.08f)
                    effEarR = effEarL;
            }

            // Bước 4: Điều tiết nhịp thời gian đẩy vào LSTM
            if (elapsedSinceLastSample >= MIN_FRAME_INTERVAL_MS) {
                int steps = calculateSteps(elapsedSinceLastSample);

                for (int s = 0; s < steps; s++) {
                    pushToSequence(
                            effEarL,
                            effEarR,
                            result.mar,
                            result.headEulerX,
                            result.headEulerY,
                            result.headEulerZ);
                }

                lastSampleTimestampMs = currentTimeMs;
                evaluateLstmDrowsiness(result);
                lastDrowsinessScore = result.drowsinessScore;

            } else {
                result.drowsinessScore = lastDrowsinessScore;
            }

            // Bước 5: Kiểm tra các trạng thái bổ trợ & Chốt cảnh báo (State Latch)
            result.isYawning = (result.mar > MAR_THRESHOLD);

            // Kiểm tra trạng thái quay đầu mất tập trung (kết hợp Hysteresis & Instant Release)
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
                        // Người lái đã nhìn thẳng lại đủ số frame -> DẬP TẮT CẢNH BÁO ĐỎ NGAY LẬP TỨC!
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

            // Đo thời lượng nhắm mắt liên tục:
            // CHÚ Ý: Nếu đang quay đầu (isDistractedTurn == true), tuyệt đối KHÔNG đếm thời
            // gian nhắm mắt ngủ gật!
            if (result.ear < EAR_THRESHOLD && !isDistractedTurn) {
                if (firstClosedEyeTime == 0) {
                    firstClosedEyeTime = currentTimeMs;
                }
                result.closedEyeDurationMs = currentTimeMs - firstClosedEyeTime;
            } else {
                firstClosedEyeTime = 0;
                result.closedEyeDurationMs = 0;
            }

            // 1. Kiểm tra mắt thực tế ở frame hiện tại có đang mở to tỉnh táo không
            boolean isCurrentlyEyesOpen = (result.ear >= EYE_OPEN_EAR_THRESHOLD);

            // 2. Quyết định trạng thái buồn ngủ:
            // Nếu đang quay đầu mất tập trung, không kích hoạt cảnh báo ngủ gật mới
            boolean isLstmDrowsy = (result.drowsinessScore >= DROWSINESS_THRESHOLD) && !isDistractedTurn;
            boolean isDurationDrowsy = (result.closedEyeDurationMs >= CLOSED_EYE_TIME_THRESHOLD_MS);
            boolean isTriggered = (isLstmDrowsy || isDurationDrowsy);

            if (isDrowsyActive) {
                // ĐANG TRONG TRẠNG THÁI CẢNH BÁO: Ưu tiên kiểm tra mắt thực tế để giải phóng
                // còi/màn hình đỏ
                if (isCurrentlyEyesOpen) {
                    // Mắt thực tế đang mở to -> Bắt đầu đếm số frame mở mắt liên tục
                    consecutiveOpenEyeFrames++;
                    if (consecutiveOpenEyeFrames >= OPEN_EYE_FRAMES_TO_CLEAR) {
                        // Người lái đã mở to mắt liên tục đủ số frame -> CHÍNH THỨC TẮT CẢNH BÁO!
                        isDrowsyActive = false;
                        consecutiveOpenEyeFrames = 0;
                        lastDrowsyTime = 0; // Hủy thời gian chờ cố định để tắt còi và màn hình đỏ ngay lập tức
                    }
                } else {
                    // Vẫn đang nhắm mắt hoặc chập chờn sụp mí -> Reset bộ đếm mở mắt & gia hạn cảnh
                    // báo
                    consecutiveOpenEyeFrames = 0;
                    lastDrowsyTime = currentTimeMs;
                }
            } else {
                // CHƯA TRONG TRẠNG THÁI CẢNH BÁO: Phát hiện buồn ngủ thì mới kích hoạt (chỉ khi
                // không đang quay đầu)
                if (isTriggered && !isDistractedTurn) {
                    isDrowsyActive = true;
                    consecutiveOpenEyeFrames = 0;
                    lastDrowsyTime = currentTimeMs;
                }
            }

            result.isDrowsy = isDrowsyActive
                    || (lastDrowsyTime > 0 && (currentTimeMs - lastDrowsyTime < ALERT_PERSISTENCE_MS));

            // In log kiểm chứng theo thời gian thực (mỗi 0.5s in 1 lần để theo dõi)
            if (currentTimeMs - lastLogTimeMs >= 500L) {
                lastLogTimeMs = currentTimeMs;
                if (result.isDistracted) {
                    Log.w(TAG, String.format("👀 [DISTRACTED] MẤT TẬP TRUNG (Quay đầu)! Yaw: %.1f° | Dur: %dms",
                            result.headEulerY, result.distractedDurationMs));
                } else if (result.isDrowsy) {
                    Log.w(TAG, String.format(
                            "🚨 [DROWSY] CẢNH BÁO BUỒN NGỦ (Active=%b, OpenFrames=%d/%d)! AI: %.1f%% | EAR: %.2f | Nhắm: %dms",
                            isDrowsyActive, consecutiveOpenEyeFrames, OPEN_EYE_FRAMES_TO_CLEAR,
                            result.drowsinessScore * 100f, result.ear, result.closedEyeDurationMs));
                } else {
                    Log.d(TAG, String.format("✅ [AWAKE] TỈNH TÁO | AI: %.1f%% | EAR: %.2f | Yaw: %.1f°",
                            result.drowsinessScore * 100f, result.ear, result.headEulerY));
                }
            }

        } catch (ExecutionException | InterruptedException e) {
            Log.e(TAG, "Lỗi khi xử lý frame: " + e.getMessage());
        }

        return result;
    }

    private int calculateSteps(long elapsedMs) {
        if (elapsedMs > 500L) {
            return 1;
        }
        int steps = (int) Math.round((double) elapsedMs / TARGET_FRAME_INTERVAL_MS);
        if (steps < 1)
            steps = 1;
        if (steps > MAX_INTERPOLATION_STEPS)
            steps = (int) MAX_INTERPOLATION_STEPS;
        return steps;
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

    private void handleFaceMissing(DrowsinessResult result) {
        boolean isTurningHead = Math.abs(lastKnownYaw) >= 28.0f;
        boolean likelySleeping = isDrowsyActive || (!isTurningHead && lastKnownEar < EAR_THRESHOLD);

        consecutiveCenterHeadFrames = 0;

        if (likelySleeping) {
            firstFaceMissingTime = 0;
            firstDistractedTime = 0;
            if (firstClosedEyeTime == 0) {
                firstClosedEyeTime = System.currentTimeMillis();
            }
            long closedDuration = System.currentTimeMillis() - firstClosedEyeTime;
            if (closedDuration >= CLOSED_EYE_TIME_THRESHOLD_MS) {
                lastDrowsyTime = System.currentTimeMillis();
                isDrowsyActive = true;
            }
            if (isDrowsyActive) {
                lastDrowsyTime = System.currentTimeMillis();
                consecutiveOpenEyeFrames = 0;
            }
            result.closedEyeDurationMs = closedDuration;
            result.isDrowsy = isDrowsyActive || (closedDuration >= CLOSED_EYE_TIME_THRESHOLD_MS)
                    || (lastDrowsyTime > 0 && (System.currentTimeMillis() - lastDrowsyTime < ALERT_PERSISTENCE_MS));
        } else {
            firstClosedEyeTime = 0;
            if (isTurningHead) {
                // Đang quay đầu làm mất mặt -> tính là mất tập trung
                if (firstDistractedTime == 0) {
                    firstDistractedTime = System.currentTimeMillis();
                }
                long distractedDuration = System.currentTimeMillis() - firstDistractedTime;
                if (distractedDuration >= DISTRACTION_TIME_THRESHOLD_MS) {
                    lastDistractedTime = System.currentTimeMillis();
                }
                result.distractedDurationMs = distractedDuration;
                result.isDistracted = (distractedDuration >= DISTRACTION_TIME_THRESHOLD_MS)
                        || (lastDistractedTime > 0 && (System.currentTimeMillis() - lastDistractedTime < ALERT_PERSISTENCE_MS));
            } else {
                firstDistractedTime = 0;
            }

            if (firstFaceMissingTime == 0) {
                firstFaceMissingTime = System.currentTimeMillis();
            }
            long missingDuration = System.currentTimeMillis() - firstFaceMissingTime;
            if (missingDuration >= FACE_MISSING_TIME_THRESHOLD_MS) {
                result.isFaceMissing = true;
                lastKnownYaw = 0f; // Reset góc quay cũ để tránh kẹt mãi ở trạng thái mất tập trung
                firstDistractedTime = 0;
                lastDistractedTime = 0;
                tfliteDetector.resetTracker();
            }
            result.isDrowsy = isDrowsyActive
                    || (lastDrowsyTime > 0 && (System.currentTimeMillis() - lastDrowsyTime < ALERT_PERSISTENCE_MS));
        }
    }

    private float calcEar(List<PointF> pts) {
        if (pts == null || pts.size() < 16) {
            return 0.0f;
        }

        PointF p0 = pts.get(0);
        PointF p8 = pts.get(8);
        PointF p3 = pts.get(3);
        PointF p13 = pts.get(13);
        PointF p5 = pts.get(5);
        PointF p11 = pts.get(11);

        float horizontalDist = dist(p0, p8);
        if (horizontalDist <= 0.0001f) {
            return 0.0f;
        }

        float verticalDist1 = dist(p3, p13);
        float verticalDist2 = dist(p5, p11);

        return (verticalDist1 + verticalDist2) / (2.0f * horizontalDist);
    }

    private float calcMar(List<PointF> upperLipBottom, List<PointF> lowerLipTop) {
        PointF cornerLeft = upperLipBottom.get(0);
        PointF cornerRight = upperLipBottom.get(8);
        float horizontalDist = dist(cornerLeft, cornerRight);

        if (horizontalDist > 0.0001f) {
            float h1 = dist(upperLipBottom.get(2), lowerLipTop.get(2));
            float h2 = dist(upperLipBottom.get(4), lowerLipTop.get(4));
            float h3 = dist(upperLipBottom.get(6), lowerLipTop.get(6));
            return (h1 + h2 + h3) / (2.0f * horizontalDist);
        }
        return 0.0f;
    }

    private float calcMarFallback(List<PointF> upper, List<PointF> lower) {
        if (upper.size() < 3 || lower.size() < 3)
            return 0f;
        int uMid = upper.size() / 2;
        int lMid = lower.size() / 2;
        float vertical = dist(upper.get(uMid), lower.get(lMid));
        float horizontal = dist(upper.get(0), upper.get(upper.size() - 1));
        return (horizontal > 0.0001f) ? (vertical / horizontal) : 0f;
    }

    private float dist(PointF p1, PointF p2) {
        float dx = p1.x - p2.x;
        float dy = p1.y - p2.y;
        return (float) Math.hypot(dx, dy);
    }

    private Bitmap cropDriverRegion(Bitmap bitmap) {
        if (DRIVER_REGION_FRACTION >= 1.0f) {
            return bitmap;
        }
        int cropW = (int) (bitmap.getWidth() * DRIVER_REGION_FRACTION);
        cropW = Math.max(1, cropW);
        return Bitmap.createBitmap(bitmap, 0, 0, cropW, bitmap.getHeight());
    }

    private RectF mapBboxToFullFrame(RectF bboxInCrop) {
        if (bboxInCrop == null)
            return null;
        if (DRIVER_REGION_FRACTION >= 1.0f) {
            return bboxInCrop;
        }
        return new RectF(
                bboxInCrop.left * DRIVER_REGION_FRACTION,
                bboxInCrop.top,
                bboxInCrop.right * DRIVER_REGION_FRACTION,
                bboxInCrop.bottom);
    }

    private Bitmap cropFace(Bitmap bitmap, RectF bbox) {
        int imgW = bitmap.getWidth();
        int imgH = bitmap.getHeight();
        float padX = (bbox.right - bbox.left) * FACE_CROP_PADDING_X;

        int x1 = (int) Math.max(0, (bbox.left - padX) * imgW);
        int y1 = (int) Math.max(0, bbox.top * imgH);
        int x2 = (int) Math.min(imgW, (bbox.right + padX) * imgW);
        int y2 = (int) Math.min(imgH, bbox.bottom * imgH);

        int cropW = x2 - x1;
        int cropH = y2 - y1;

        if (cropW <= 0 || cropH <= 0)
            return bitmap;
        return Bitmap.createBitmap(bitmap, x1, y1, cropW, cropH);
    }

    public TFLiteFaceDetector getTfliteDetector() {
        return tfliteDetector;
    }

    public void resetTracker() {
        lastTrackedFaceBbox = null;
        consecutiveTrackFrames = 0;
        if (tfliteDetector != null) {
            tfliteDetector.resetTracker();
        }
    }

    public void close() {
        if (lstmInterpreter != null) {
            lstmInterpreter.close();
        }
        if (tfliteDetector != null) {
            tfliteDetector.close();
        }
        if (mlkitDetector != null) {
            mlkitDetector.close();
        }
    }
}
