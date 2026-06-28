package com.example.myapplication;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

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
 * YoloDetector - Chạy model YOLOv8 TFLite để phát hiện dây an toàn.
 *
 * Output của YOLOv8 TFLite detection thường có shape: [1, num_classes+4, 8400]
 * Với 2 class (belt, no-belt) → [1, 6, 8400]
 *
 * Mỗi cột i trong 8400 là một anchor point:
 *   row 0: cx (normalized 0-1 hoặc pixel 0-640)
 *   row 1: cy
 *   row 2: w
 *   row 3: h
 *   row 4: score class 0 (belt)
 *   row 5: score class 1 (no-belt)
 */
public class YoloDetector {

    private static final String TAG = "YoloDetector";
    private static final String MODEL_FILE = "best.tflite";

    // Input size của model (YOLOv8 mặc định 640x640)
    public static final int INPUT_SIZE = 640;

    // Ngưỡng giống Python: conf=0.25
    private static final float CONFIDENCE_THRESHOLD = 0.25f;

    // Ngưỡng IoU cho NMS
    private static final float IOU_THRESHOLD = 0.45f;

    private final Interpreter interpreter;
    private final String[] labels;
    private final int numClasses;
    private final int numBoxes;
    private final boolean isTransposed; // true nếu shape [1, 6, 8400]; false nếu [1, 8400, 6]

    // --------------------------------------------------------
    // Data class chứa kết quả 1 detection
    // --------------------------------------------------------
    public static class Detection {
        public final RectF bbox;        // Normalized [0,1]: left, top, right, bottom
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
    public YoloDetector(Context context, String[] labels) throws IOException {
        this.labels = labels;

        // Load model dưới dạng MappedByteBuffer (hiệu quả nhất với file lớn)
        MappedByteBuffer modelBuffer = loadModelFile(context);

        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);
        interpreter = new Interpreter(modelBuffer, options);

        // Phân tích output shape để biết format của model
        int[] outputShape = interpreter.getOutputTensor(0).shape();
        Log.d(TAG, "Input shape:  " + Arrays.toString(interpreter.getInputTensor(0).shape()));
        Log.d(TAG, "Output shape: " + Arrays.toString(outputShape));

        // Xác định format: [1, 6, 8400] hay [1, 8400, 6]
        // Nếu outputShape[1] < outputShape[2] → dạng [1, channels, boxes] (transposed)
        if (outputShape[1] < outputShape[2]) {
            isTransposed = true;
            numClasses = outputShape[1] - 4;
            numBoxes   = outputShape[2];
        } else {
            isTransposed = false;
            numBoxes   = outputShape[1];
            numClasses = outputShape[2] - 4;
        }

        Log.d(TAG, "Format: " + (isTransposed ? "TRANSPOSED [1,ch,boxes]" : "NORMAL [1,boxes,ch]")
                + " | numClasses=" + numClasses + " | numBoxes=" + numBoxes);
    }

    // --------------------------------------------------------
    // Public API: nhận Bitmap, trả về list Detection
    // --------------------------------------------------------
    public List<Detection> detect(Bitmap bitmap) {
        // 1. Resize về INPUT_SIZE x INPUT_SIZE
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);

        // 2. Chuyển Bitmap → ByteBuffer float32 [1, H, W, 3]
        ByteBuffer inputBuf = bitmapToByteBuffer(resized);

        // 3. Chuẩn bị output buffer
        float[][][] output;
        int[] outShape = interpreter.getOutputTensor(0).shape();
        output = new float[outShape[0]][outShape[1]][outShape[2]];

        // 4. Inference
        interpreter.run(inputBuf, output);

        // 5. Parse kết quả
        List<Detection> candidates = parseOutput(output);

        // 6. NMS để loại bỏ box trùng lặp
        return applyNMS(candidates);
    }

    // --------------------------------------------------------
    // Giải phóng tài nguyên
    // --------------------------------------------------------
    public void close() {
        if (interpreter != null) {
            interpreter.close();
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
    // Private: Bitmap → ByteBuffer RGB float32 normalized [0,1]
    // --------------------------------------------------------
    private ByteBuffer bitmapToByteBuffer(Bitmap bitmap) {
        ByteBuffer buf = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4);
        buf.order(ByteOrder.nativeOrder());

        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        for (int pixel : pixels) {
            buf.putFloat(((pixel >> 16) & 0xFF) / 255.0f); // R
            buf.putFloat(((pixel >> 8)  & 0xFF) / 255.0f); // G
            buf.putFloat(( pixel        & 0xFF) / 255.0f); // B
        }

        return buf;
    }

    // --------------------------------------------------------
    // Private: Parse output tensor → list Detection
    // --------------------------------------------------------
    private List<Detection> parseOutput(float[][][] output) {
        List<Detection> candidates = new ArrayList<>();

        // Kiểm tra xem tọa độ có ở dạng pixel (0-640) hay normalized (0-1)
        // Lấy mẫu vài giá trị để tự động phán đoán
        float sampleCx = isTransposed ? output[0][0][0] : output[0][0][0];
        boolean needNormalize = sampleCx > 1.5f; // Nếu > 1.5 thì là pixel space

        for (int i = 0; i < numBoxes; i++) {
            float cx, cy, w, h;
            float[] classScores = new float[numClasses];

            if (isTransposed) {
                // Shape: [1, 6, 8400] → output[0][row][col]
                cx = output[0][0][i];
                cy = output[0][1][i];
                w  = output[0][2][i];
                h  = output[0][3][i];
                for (int c = 0; c < numClasses; c++) {
                    classScores[c] = output[0][4 + c][i];
                }
            } else {
                // Shape: [1, 8400, 6] → output[0][row][col]
                cx = output[0][i][0];
                cy = output[0][i][1];
                w  = output[0][i][2];
                h  = output[0][i][3];
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
            if (bestScore < CONFIDENCE_THRESHOLD) continue;

            // Chuyển về tọa độ normalized [0,1]
            float normCx = needNormalize ? cx / INPUT_SIZE : cx;
            float normCy = needNormalize ? cy / INPUT_SIZE : cy;
            float normW  = needNormalize ? w  / INPUT_SIZE : w;
            float normH  = needNormalize ? h  / INPUT_SIZE : h;

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

            if (x2 <= x1 || y2 <= y1) continue; // box vô nghĩa

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
            if (suppressed[i]) continue;
            result.add(detections.get(i));

            for (int j = i + 1; j < detections.size(); j++) {
                if (suppressed[j]) continue;
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
        float interLeft   = Math.max(a.left,   b.left);
        float interTop    = Math.max(a.top,    b.top);
        float interRight  = Math.min(a.right,  b.right);
        float interBottom = Math.min(a.bottom, b.bottom);

        if (interRight <= interLeft || interBottom <= interTop) return 0f;

        float interArea = (interRight - interLeft) * (interBottom - interTop);
        float aArea = a.width() * a.height();
        float bArea = b.width() * b.height();

        return interArea / (aArea + bArea - interArea);
    }
}
