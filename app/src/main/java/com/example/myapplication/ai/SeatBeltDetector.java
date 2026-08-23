package com.example.myapplication.ai;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
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
 * SeatBeltDetector - Chạy model YOLOv8 TFLite để phát hiện dây an toàn.
 *
 * Output của YOLOv8 TFLite detection thường có shape: [1, num_classes+4, 8400]
 * Với 2 class (belt, no-belt) → [1, 6, 8400]
 *
 * Mỗi cột i trong 8400 là một anchor point:
 * row 0: cx (normalized 0-1 hoặc pixel 0-640)
 * row 1: cy
 * row 2: w
 * row 3: h
 * row 4: score class 0 (belt)
 * row 5: score class 1 (no-belt)
 *
 * Tối ưu performance:
 * - GPU Delegate (auto-fallback NNAPI → CPU)
 * - Pre-allocated ByteBuffer & output array (tái dùng mỗi frame, tránh GC)
 * - Scaled bitmap tái dùng
 */
public class SeatBeltDetector {

    private static final String TAG = "SeatBeltDetector";
    private static final String MODEL_FILE = "seatbelt_detection.tflite";

    // Input size của model (YOLOv8 mặc định 640x640)
    public static final int INPUT_SIZE = 640;

    // Ngưỡng giống Python: conf=0.25
    private static final float CONFIDENCE_THRESHOLD = 0.25f;

    // Ngưỡng IoU cho NMS
    private static final float IOU_THRESHOLD = 0.45f;

    private final Interpreter interpreter;
    private final GpuDelegate gpuDelegate; // null nếu GPU không khả dụng
    private final String[] labels;
    private final int numClasses;
    private final int numBoxes;
    private final boolean isTransposed; // true nếu shape [1, 6, 8400]; false nếu [1, 8400, 6]

    // ── Pre-allocated buffers (tái dùng mỗi frame để tránh GC pressure) ────────
    private final ByteBuffer inputBuffer; // [1, 640, 640, 3] float32
    private final float[][][] outputBuffer; // [1][dim1][dim2]
    private final int[] pixelBuffer; // pixel scratch

    // --------------------------------------------------------
    // Data class chứa kết quả 1 detection
    // --------------------------------------------------------
    public static class Detection {
        public final RectF bbox; // Normalized [0,1]: left, top, right, bottom
        public final int classId;
        public final float confidence;
        public final String label;

        public Detection(RectF bbox, int classId, float confidence, String label) {
            this.bbox = bbox;
            this.classId = classId;
            this.confidence = confidence;
            this.label = label;
        }
    }

    // --------------------------------------------------------
    // Constructor
    // --------------------------------------------------------
    public SeatBeltDetector(Context context, String[] labels) throws IOException {
        this.labels = labels;

        // Load model dưới dạng MappedByteBuffer (memory-mapped → không copy vào heap)
        MappedByteBuffer modelBuffer = loadModelFile(context);

        Interpreter.Options options = new Interpreter.Options();

        // ── Thử bật GPU Delegate ──────────────────────────────────────────────
        GpuDelegate tempDelegate = null;
        try (CompatibilityList compatList = new CompatibilityList()) {
            if (compatList.isDelegateSupportedOnThisDevice()) {
                GpuDelegate.Options gpuOptions = compatList.getBestOptionsForThisDevice();
                tempDelegate = new GpuDelegate(gpuOptions);
                options.addDelegate(tempDelegate);
                Log.i(TAG, "✅ GPU Delegate enabled");
            } else {
                // Thử NNAPI (DSP/NPU nếu có)
                options.setUseNNAPI(true);
                Log.i(TAG, "⚡ NNAPI delegate enabled (GPU không hỗ trợ)");
            }
        } catch (Exception e) {
            Log.w(TAG, "⚠️ GPU/NNAPI delegate failed, dùng CPU: " + e.getMessage());
            options.setUseNNAPI(false);
        }
        gpuDelegate = tempDelegate;

        // Số luồng CPU: dùng khi GPU không có / làm backup
        options.setNumThreads(4);
        // Cho phép fp16 precision trên CPU để tăng tốc thêm
        options.setAllowFp16PrecisionForFp32(true);
        // Bật XNNPack (SIMD-optimized CPU backend) — tự động trên LiteRT 2.x
        options.setUseXNNPACK(true);

        interpreter = new Interpreter(modelBuffer, options);

        // ── Phân tích output shape ────────────────────────────────────────────
        int[] outputShape = interpreter.getOutputTensor(0).shape();
        Log.d(TAG, "Input shape:  " + Arrays.toString(interpreter.getInputTensor(0).shape()));
        Log.d(TAG, "Output shape: " + Arrays.toString(outputShape));

        // Xác định format: [1, 6, 8400] hay [1, 8400, 6]
        if (outputShape[1] < outputShape[2]) {
            isTransposed = true;
            numClasses = outputShape[1] - 4;
            numBoxes = outputShape[2];
        } else {
            isTransposed = false;
            numBoxes = outputShape[1];
            numClasses = outputShape[2] - 4;
        }
        Log.d(TAG, "Format: " + (isTransposed ? "TRANSPOSED [1,ch,boxes]" : "NORMAL [1,boxes,ch]")
                + " | numClasses=" + numClasses + " | numBoxes=" + numBoxes);

        // ── Pre-allocate buffers ──────────────────────────────────────────────
        // Input: 1 * 640 * 640 * 3 channels * 4 bytes (float32)
        inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4);
        inputBuffer.order(ByteOrder.nativeOrder());

        // Output: theo đúng shape của model
        outputBuffer = new float[outputShape[0]][outputShape[1]][outputShape[2]];

        // Pixel scratch array cho getPixels()
        pixelBuffer = new int[INPUT_SIZE * INPUT_SIZE];
    }

    // --------------------------------------------------------
    // Public API: nhận Bitmap, trả về list Detection
    // --------------------------------------------------------
    public List<Detection> detect(Bitmap bitmap) {
        int origW = bitmap.getWidth();
        int origH = bitmap.getHeight();

        // 1. Resize về INPUT_SIZE x INPUT_SIZE với letterbox
        Bitmap resized = createLetterboxBitmap(bitmap, INPUT_SIZE, INPUT_SIZE);

        // 2. Chuyển Bitmap → ByteBuffer float32 [1, H, W, 3] (tái dùng buffer)
        fillInputBuffer(resized);

        // 3. Inference (tái dùng outputBuffer)
        interpreter.run(inputBuffer, outputBuffer);

        // 4. Parse kết quả
        List<Detection> candidates = parseOutput(outputBuffer, origW, origH);

        // 5. NMS để loại bỏ box trùng lặp
        return applyNMS(candidates);
    }

    // --------------------------------------------------------
    // Giải phóng tài nguyên
    // --------------------------------------------------------
    public void close() {
        if (interpreter != null) {
            interpreter.close();
        }
        if (gpuDelegate != null) {
            gpuDelegate.close();
        }
    }

    // --------------------------------------------------------
    // Private: Load model từ assets
    // --------------------------------------------------------
    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(MODEL_FILE);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    // --------------------------------------------------------
    // Private: Điền Bitmap → pre-allocated inputBuffer
    // Tránh tạo mảng mới mỗi frame (dùng lại pixelBuffer)
    // --------------------------------------------------------
    private void fillInputBuffer(Bitmap bitmap) {
        inputBuffer.rewind(); // Reset position về 0 để ghi lại từ đầu

        bitmap.getPixels(pixelBuffer, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        for (int pixel : pixelBuffer) {
            inputBuffer.putFloat(((pixel >> 16) & 0xFF) * (1f / 255f)); // R
            inputBuffer.putFloat(((pixel >> 8) & 0xFF) * (1f / 255f)); // G
            inputBuffer.putFloat((pixel & 0xFF) * (1f / 255f)); // B
        }
    }

    // --------------------------------------------------------
    // Private: Resize với Letterbox (giữ nguyên tỷ lệ)
    // --------------------------------------------------------
    private Bitmap createLetterboxBitmap(Bitmap src, int targetWidth, int targetHeight) {
        Bitmap dst = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(dst);
        canvas.drawColor(Color.rgb(114, 114, 114));

        float scale = Math.min((float) targetWidth / src.getWidth(), (float) targetHeight / src.getHeight());
        float dx = (targetWidth - src.getWidth() * scale) / 2f;
        float dy = (targetHeight - src.getHeight() * scale) / 2f;

        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        matrix.postTranslate(dx, dy);

        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(src, matrix, paint);
        return dst;
    }

    // --------------------------------------------------------
    // Private: Parse output tensor → list Detection
    // --------------------------------------------------------
    private List<Detection> parseOutput(float[][][] output, int origW, int origH) {
        List<Detection> candidates = new ArrayList<>();

        // Tự động phán đoán tọa độ là pixel (0-640) hay normalized (0-1)
        float sampleCx = output[0][0][0];
        boolean needNormalize = sampleCx > 1.5f;

        // Tính toán thông số letterbox để scale ngược lại
        float scale = Math.min((float) INPUT_SIZE / origW, (float) INPUT_SIZE / origH);
        float padX = (INPUT_SIZE - origW * scale) / 2f;
        float padY = (INPUT_SIZE - origH * scale) / 2f;

        for (int i = 0; i < numBoxes; i++) {
            float cx, cy, w, h;
            float[] classScores = new float[numClasses];

            if (isTransposed) {
                // Shape: [1, 6, 8400] → output[0][row][col]
                cx = output[0][0][i];
                cy = output[0][1][i];
                w = output[0][2][i];
                h = output[0][3][i];
                for (int c = 0; c < numClasses; c++) {
                    classScores[c] = output[0][4 + c][i];
                }
            } else {
                // Shape: [1, 8400, 6] → output[0][row][col]
                cx = output[0][i][0];
                cy = output[0][i][1];
                w = output[0][i][2];
                h = output[0][i][3];
                for (int c = 0; c < numClasses; c++) {
                    classScores[c] = output[0][i][4 + c];
                }
            }

            // Tìm class có score cao nhất
            int bestClass = 0;
            float bestScore = classScores[0];
            for (int c = 1; c < numClasses; c++) {
                if (classScores[c] > bestScore) {
                    bestScore = classScores[c];
                    bestClass = c;
                }
            }

            // Lọc theo ngưỡng confidence
            if (bestScore < CONFIDENCE_THRESHOLD)
                continue;

            // Chuyển về tọa độ pixel trong 640x640 letterbox image
            float px = needNormalize ? cx : cx * INPUT_SIZE;
            float py = needNormalize ? cy : cy * INPUT_SIZE;
            float pw = needNormalize ? w : w * INPUT_SIZE;
            float ph = needNormalize ? h : h * INPUT_SIZE;
            
            // Map back to original image pixel coordinates
            float origPx = (px - padX) / scale;
            float origPy = (py - padY) / scale;
            float origPw = pw / scale;
            float origPh = ph / scale;

            // Convert to normalized coordinates [0,1] for original image
            float normCx = origPx / origW;
            float normCy = origPy / origH;
            float normW = origPw / origW;
            float normH = origPh / origH;

            // cx,cy,w,h → x1,y1,x2,y2
            float x1 = normCx - normW / 2f;
            float y1 = normCy - normH / 2f;
            float x2 = normCx + normW / 2f;
            float y2 = normCy + normH / 2f;

            // Giới hạn trong [0,1]
            x1 = Math.max(0f, Math.min(1f, x1));
            y1 = Math.max(0f, Math.min(1f, y1));
            x2 = Math.max(0f, Math.min(1f, x2));
            y2 = Math.max(0f, Math.min(1f, y2));

            if (x2 <= x1 || y2 <= y1)
                continue; // box vô nghĩa

            String label = (bestClass < labels.length) ? labels[bestClass] : "class_" + bestClass;
            candidates.add(new Detection(new RectF(x1, y1, x2, y2), bestClass, bestScore, label));
        }

        return candidates;
    }

    // --------------------------------------------------------
    // Private: Non-Maximum Suppression
    // --------------------------------------------------------
    private List<Detection> applyNMS(List<Detection> detections) {
        // Sắp xếp giảm dần theo confidence
        detections.sort((a, b) -> Float.compare(b.confidence, a.confidence));

        List<Detection> result = new ArrayList<>();
        boolean[] suppressed = new boolean[detections.size()];

        for (int i = 0; i < detections.size(); i++) {
            if (suppressed[i])
                continue;
            result.add(detections.get(i));

            for (int j = i + 1; j < detections.size(); j++) {
                if (suppressed[j])
                    continue;
                if (computeIoU(detections.get(i).bbox, detections.get(j).bbox) >= IOU_THRESHOLD) {
                    suppressed[j] = true;
                }
            }
        }

        return result;
    }

    // --------------------------------------------------------
    // Private: Tính IoU (Intersection over Union)
    // --------------------------------------------------------
    private float computeIoU(RectF a, RectF b) {
        float interLeft = Math.max(a.left, b.left);
        float interTop = Math.max(a.top, b.top);
        float interRight = Math.min(a.right, b.right);
        float interBottom = Math.min(a.bottom, b.bottom);

        if (interRight <= interLeft || interBottom <= interTop)
            return 0f;

        float interArea = (interRight - interLeft) * (interBottom - interTop);
        float aArea = a.width() * a.height();
        float bArea = b.width() * b.height();

        return interArea / (aArea + bArea - interArea);
    }
}
