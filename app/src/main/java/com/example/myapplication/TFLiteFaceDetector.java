package com.example.myapplication;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * TFLiteFaceDetector — Dùng model YOLO TFLite tự train để phát hiện khuôn mặt.
 *
 * Input: [1, 640, 640, 3] float32, pixel normalized [0, 1]
 * Output: [1, 5, num_boxes] hoặc [1, num_boxes, 5]
 * Mỗi detection: [cx, cy, w, h, confidence] (YOLO format)
 * cx/cy/w/h có thể là pixel (0-640) hoặc normalized (0-1) — tự phát hiện.
 *
 * Pattern tương tự YoloDetector: GPU Delegate, pre-allocated buffer, NMS.
 */
public class TFLiteFaceDetector {

    private static final String TAG = "TFLiteFaceDetector";
    private static final String MODEL_FILE = "face_detection.tflite";

    // Input size của YOLO model (khớp với lúc train)
    public static final int INPUT_SIZE = 640;

    // Ngưỡng confidence để giữ detection
    private static final float CONFIDENCE_THRESHOLD = 0.40f;

    // Ngưỡng IoU cho NMS
    private static final float IOU_THRESHOLD = 0.45f;

    private final Interpreter interpreter;
    private final GpuDelegate gpuDelegate; // null nếu GPU không khả dụng

    // Output shape analysis
    private final boolean isTransposed; // true: [1, 5, num_boxes]; false: [1, num_boxes, 5]
    private final int numBoxes;

    // Pre-allocated buffers (tái dùng mỗi frame để tránh GC pressure)
    private final ByteBuffer inputBuffer; // [1, 640, 640, 3] float32
    private final float[][][] outputBuffer; // [1][dim1][dim2]
    private final int[] pixelBuffer; // pixel scratch

    // IoU Tracking variables
    private RectF lastDriverBbox = null;
    private static final float IOU_TRACKING_THRESHOLD = 0.2f;

    // -------------------------------------------------------------------------
    // Data class: kết quả 1 detection khuôn mặt
    // -------------------------------------------------------------------------
    public static class FaceBox {
        public final RectF bbox; // Normalized [0,1]: left, top, right, bottom
        public final float confidence;

        public FaceBox(RectF bbox, float confidence) {
            this.bbox = bbox;
            this.confidence = confidence;
        }
    }

    // -------------------------------------------------------------------------
    // Constructor: load model + khởi tạo GPU Delegate
    // -------------------------------------------------------------------------
    public TFLiteFaceDetector(Context context) throws IOException {
        MappedByteBuffer modelBuffer = loadModelFile(context);

        Interpreter.Options options = new Interpreter.Options();

        // Thử bật GPU Delegate (auto-fallback về CPU nếu không được)
        GpuDelegate tempDelegate = null;
        try (CompatibilityList compatList = new CompatibilityList()) {
            if (compatList.isDelegateSupportedOnThisDevice()) {
                GpuDelegate.Options gpuOptions = compatList.getBestOptionsForThisDevice();
                tempDelegate = new GpuDelegate(gpuOptions);
                options.addDelegate(tempDelegate);
                Log.i(TAG, "✅ GPU Delegate enabled");
            } else {
                options.setUseNNAPI(true);
                Log.i(TAG, "⚡ NNAPI delegate enabled (GPU không hỗ trợ)");
            }
        } catch (Exception e) {
            Log.w(TAG, "⚠️ GPU/NNAPI failed, dùng CPU: " + e.getMessage());
            options.setUseNNAPI(false);
        }
        gpuDelegate = tempDelegate;

        options.setNumThreads(4);
        options.setAllowFp16PrecisionForFp32(true);
        options.setUseXNNPACK(true);

        interpreter = new Interpreter(modelBuffer, options);

        // ── Log và phân tích shape ────────────────────────────────────────────
        int[] inputShape = interpreter.getInputTensor(0).shape();
        int[] outputShape = interpreter.getOutputTensor(0).shape();
        Log.d(TAG, "Input shape:  " + Arrays.toString(inputShape));
        Log.d(TAG, "Output shape: " + Arrays.toString(outputShape));

        // YOLO face (1 class): 4 coords + 1 conf = 5 features
        // Xác định format: [1, 5, num_boxes] hay [1, num_boxes, 5]
        if (outputShape[1] < outputShape[2]) {
            isTransposed = true; // [1, 5, 8400] — features là dim1
            numBoxes = outputShape[2];
        } else {
            isTransposed = false; // [1, 8400, 5] — features là dim2
            numBoxes = outputShape[1];
        }
        Log.d(TAG, "Format: " + (isTransposed ? "TRANSPOSED [1,5,boxes]" : "NORMAL [1,boxes,5]")
                + " | numBoxes=" + numBoxes);

        // Pre-allocate buffers
        inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4);
        inputBuffer.order(ByteOrder.nativeOrder());
        outputBuffer = new float[outputShape[0]][outputShape[1]][outputShape[2]];
        pixelBuffer = new int[INPUT_SIZE * INPUT_SIZE];
    }

    // -------------------------------------------------------------------------
    // detectBestFace: trả về bounding box của tài xế (dùng IoU Tracking).
    // @return RectF normalized [0,1] hoặc null nếu mất dấu tài xế.
    // -------------------------------------------------------------------------
    public void resetTracker() {
        lastDriverBbox = null;
    }

    public RectF detectBestFace(Bitmap bitmap) {
        List<FaceBox> faces = detect(bitmap);
        if (faces.isEmpty())
            return null;

        if (lastDriverBbox == null) {
            // BƯỚC 1 (Init): Tìm khuôn mặt có diện tích Bounding Box lớn nhất (Tài xế)
            RectF largestFace = faces.get(0).bbox;
            float maxArea = largestFace.width() * largestFace.height();

            for (int i = 1; i < faces.size(); i++) {
                RectF currentFace = faces.get(i).bbox;
                float currentArea = currentFace.width() * currentFace.height();
                if (currentArea > maxArea) {
                    maxArea = currentArea;
                    largestFace = currentFace;
                }
            }
            lastDriverBbox = largestFace;
            return largestFace;
        } else {
            // BƯỚC 2 (Track): Tìm khuôn mặt trùng khớp với vị trí cũ nhất
            RectF bestTrackedFace = null;
            float bestIoU = -1f;

            for (FaceBox f : faces) {
                float iou = computeIoU(f.bbox, lastDriverBbox);
                if (iou > bestIoU) {
                    bestIoU = iou;
                    bestTrackedFace = f.bbox;
                }
            }

            // Nếu độ trùng khớp đủ lớn -> Cập nhật vị trí
            if (bestIoU > IOU_TRACKING_THRESHOLD && bestTrackedFace != null) {
                lastDriverBbox = bestTrackedFace;
                return bestTrackedFace;
            } else {
                // Không tìm thấy ai ở vị trí ghế lái (Tài xế đã gục hoặc rời đi)
                // Lưu ý: KHÔNG resetTracker ở đây, để DrowsinessDetector quyết định
                return null;
            }
        }
    }

    // -------------------------------------------------------------------------
    // detect: trả về tất cả khuôn mặt sau NMS.
    // -------------------------------------------------------------------------
    public List<FaceBox> detect(Bitmap bitmap) {
        // 1. Resize về INPUT_SIZE × INPUT_SIZE
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);

        // 2. Bitmap → ByteBuffer float32 (tái dùng inputBuffer)
        fillInputBuffer(resized);

        // 3. TFLite inference (tái dùng outputBuffer)
        interpreter.run(inputBuffer, outputBuffer);

        // 4. Parse output → candidates
        List<FaceBox> candidates = parseOutput(outputBuffer);

        // 5. NMS
        return applyNMS(candidates);
    }

    // -------------------------------------------------------------------------
    // Giải phóng tài nguyên
    // -------------------------------------------------------------------------
    public void close() {
        if (interpreter != null)
            interpreter.close();
        if (gpuDelegate != null)
            gpuDelegate.close();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        AssetFileDescriptor fd = context.getAssets().openFd(MODEL_FILE);
        FileInputStream fis = new FileInputStream(fd.getFileDescriptor());
        FileChannel fc = fis.getChannel();
        return fc.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
    }

    private void fillInputBuffer(Bitmap bitmap) {
        inputBuffer.rewind();
        bitmap.getPixels(pixelBuffer, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        for (int pixel : pixelBuffer) {
            inputBuffer.putFloat(((pixel >> 16) & 0xFF) * (1f / 255f)); // R
            inputBuffer.putFloat(((pixel >> 8) & 0xFF) * (1f / 255f)); // G
            inputBuffer.putFloat((pixel & 0xFF) * (1f / 255f)); // B
        }
    }

    private List<FaceBox> parseOutput(float[][][] output) {
        List<FaceBox> candidates = new ArrayList<>();

        // Phát hiện tọa độ là pixel (0-640) hay normalized (0-1)
        float sampleCx = isTransposed ? output[0][0][0] : output[0][0][0];
        boolean needNorm = sampleCx > 1.5f;

        for (int i = 0; i < numBoxes; i++) {
            float cx, cy, w, h, conf;

            if (isTransposed) {
                // [1, 5, num_boxes] → row 0=cx, 1=cy, 2=w, 3=h, 4=conf
                cx = output[0][0][i];
                cy = output[0][1][i];
                w = output[0][2][i];
                h = output[0][3][i];
                conf = output[0][4][i];
            } else {
                // [1, num_boxes, 5] → col 0=cx, 1=cy, 2=w, 3=h, 4=conf
                cx = output[0][i][0];
                cy = output[0][i][1];
                w = output[0][i][2];
                h = output[0][i][3];
                conf = output[0][i][4];
            }

            if (conf < CONFIDENCE_THRESHOLD)
                continue;

            // Normalize về [0,1] nếu tọa độ đang ở pixel space
            float nCx = needNorm ? cx / INPUT_SIZE : cx;
            float nCy = needNorm ? cy / INPUT_SIZE : cy;
            float nW = needNorm ? w / INPUT_SIZE : w;
            float nH = needNorm ? h / INPUT_SIZE : h;

            // cx,cy,w,h → x1,y1,x2,y2
            float x1 = Math.max(0f, Math.min(1f, nCx - nW / 2f));
            float y1 = Math.max(0f, Math.min(1f, nCy - nH / 2f));
            float x2 = Math.max(0f, Math.min(1f, nCx + nW / 2f));
            float y2 = Math.max(0f, Math.min(1f, nCy + nH / 2f));

            if (x2 <= x1 || y2 <= y1)
                continue; // box vô nghĩa

            candidates.add(new FaceBox(new RectF(x1, y1, x2, y2), conf));
        }

        return candidates;
    }

    private List<FaceBox> applyNMS(List<FaceBox> detections) {
        // Sắp xếp giảm dần theo confidence
        detections.sort((a, b) -> Float.compare(b.confidence, a.confidence));

        List<FaceBox> result = new ArrayList<>();
        boolean[] suppressed = new boolean[detections.size()];

        for (int i = 0; i < detections.size(); i++) {
            if (suppressed[i])
                continue;
            result.add(detections.get(i));
            for (int j = i + 1; j < detections.size(); j++) {
                if (!suppressed[j]
                        && computeIoU(detections.get(i).bbox, detections.get(j).bbox) >= IOU_THRESHOLD) {
                    suppressed[j] = true;
                }
            }
        }
        return result;
    }

    private float computeIoU(RectF a, RectF b) {
        float iL = Math.max(a.left, b.left);
        float iT = Math.max(a.top, b.top);
        float iR = Math.min(a.right, b.right);
        float iB = Math.min(a.bottom, b.bottom);
        if (iR <= iL || iB <= iT)
            return 0f;
        float inter = (iR - iL) * (iB - iT);
        return inter / (a.width() * a.height() + b.width() * b.height() - inter);
    }
}
