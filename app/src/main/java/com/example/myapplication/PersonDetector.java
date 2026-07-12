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
 * PersonDetector — Dùng yolov8n (COCO-80) để detect toàn thân người.
 *
 * Pipeline mục tiêu:
 *   full frame → PersonDetector → [person1_bbox, person2_bbox, ...]
 *              → crop từng người → YoloDetector (seatbelt) trên mỗi crop
 *
 * Output: List<RectF> — mỗi RectF là bbox người (normalized [0,1])
 *
 * Model: yolov8n_320.tflite  (COCO-80 classes, imgsz=320)
 * COCO class 0 = "person"
 */
public class PersonDetector {

    private static final String TAG         = "PersonDetector";
    private static final String MODEL_FILE  = "yolov8n_320.tflite";
    private static final int    INPUT_SIZE  = 320;       // phải khớp với imgsz khi export
    private static final int    PERSON_CLASS = 0;        // COCO class 0 = person
    private static final float  CONF_THRESH = 0.35f;
    private static final float  IOU_THRESH  = 0.45f;

    private final Interpreter interpreter;
    private final GpuDelegate  gpuDelegate;

    // Pre-allocated buffers
    private final ByteBuffer inputBuffer;    // [1, 320, 320, 3] float32
    private final float[][][] outputBuffer;  // [1][dim1][dim2]
    private final int[] pixelBuf;

    private final int     numClasses;
    private final int     numBoxes;
    private final boolean isTransposed;

    // ── Constructor ───────────────────────────────────────────────────────────

    public PersonDetector(Context context) throws IOException {
        MappedByteBuffer modelBuf = loadModel(context);

        Interpreter.Options opts = new Interpreter.Options();

        GpuDelegate tmp = null;
        try (CompatibilityList cl = new CompatibilityList()) {
            if (cl.isDelegateSupportedOnThisDevice()) {
                tmp = new GpuDelegate(cl.getBestOptionsForThisDevice());
                opts.addDelegate(tmp);
                Log.i(TAG, "GPU Delegate enabled");
            } else {
                opts.setUseNNAPI(true);
            }
        } catch (Exception e) {
            Log.w(TAG, "GPU/NNAPI failed, dùng CPU: " + e.getMessage());
            opts.setUseNNAPI(false);
        }
        gpuDelegate = tmp;
        opts.setNumThreads(4);
        opts.setAllowFp16PrecisionForFp32(true);
        opts.setUseXNNPACK(true);

        interpreter = new Interpreter(modelBuf, opts);

        // Phân tích output shape: [1, 84, 8400] hoặc [1, 8400, 84]
        int[] outShape = interpreter.getOutputTensor(0).shape();
        Log.d(TAG, "Input : " + Arrays.toString(interpreter.getInputTensor(0).shape()));
        Log.d(TAG, "Output: " + Arrays.toString(outShape));

        if (outShape[1] < outShape[2]) {         // [1, 84, 8400] — transposed
            isTransposed = true;
            numClasses   = outShape[1] - 4;
            numBoxes     = outShape[2];
        } else {                                  // [1, 8400, 84]
            isTransposed = false;
            numBoxes     = outShape[1];
            numClasses   = outShape[2] - 4;
        }
        Log.d(TAG, (isTransposed ? "TRANSPOSED" : "NORMAL")
                + " | classes=" + numClasses + " | boxes=" + numBoxes);

        inputBuffer  = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4);
        inputBuffer.order(ByteOrder.nativeOrder());
        outputBuffer = new float[outShape[0]][outShape[1]][outShape[2]];
        pixelBuf     = new int[INPUT_SIZE * INPUT_SIZE];
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Detect tất cả người trong bitmap.
     * @return List<RectF> — bbox người, tọa độ normalized [0,1]
     */
    public List<RectF> detectPersons(Bitmap bitmap) {
        // 1. Resize về INPUT_SIZE
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);

        // 2. Fill input buffer
        fillInput(resized);

        // 3. Inference
        interpreter.run(inputBuffer, outputBuffer);

        // 4. Parse — chỉ lấy class 0 (person)
        List<RectF> candidates = parsePersonOnly();

        // 5. NMS
        return nms(candidates);
    }

    public void close() {
        if (interpreter  != null) interpreter.close();
        if (gpuDelegate  != null) gpuDelegate.close();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private MappedByteBuffer loadModel(Context ctx) throws IOException {
        AssetFileDescriptor fd = ctx.getAssets().openFd(MODEL_FILE);
        FileInputStream fis = new FileInputStream(fd.getFileDescriptor());
        return fis.getChannel().map(FileChannel.MapMode.READ_ONLY,
                fd.getStartOffset(), fd.getDeclaredLength());
    }

    private void fillInput(Bitmap bmp) {
        inputBuffer.rewind();
        bmp.getPixels(pixelBuf, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        for (int px : pixelBuf) {
            inputBuffer.putFloat(((px >> 16) & 0xFF) / 255f); // R
            inputBuffer.putFloat(((px >>  8) & 0xFF) / 255f); // G
            inputBuffer.putFloat(( px        & 0xFF) / 255f); // B
        }
    }

    /**
     * Parse output, chỉ giữ lại box class 0 (person) có score cao nhất cho mỗi anchor.
     */
    private List<RectF> parsePersonOnly() {
        List<RectF> result = new ArrayList<>();

        // Tự phát hiện tọa độ pixel hay normalized
        float sampleCx = outputBuffer[0][0][0];
        boolean needNorm = sampleCx > 1.5f;

        for (int i = 0; i < numBoxes; i++) {
            float cx, cy, w, h, personScore;

            if (isTransposed) {
                cx          = outputBuffer[0][0][i];
                cy          = outputBuffer[0][1][i];
                w           = outputBuffer[0][2][i];
                h           = outputBuffer[0][3][i];
                // Chỉ đọc score class 0 (person)
                personScore = (PERSON_CLASS < numClasses)
                        ? outputBuffer[0][4 + PERSON_CLASS][i] : 0f;
            } else {
                cx          = outputBuffer[0][i][0];
                cy          = outputBuffer[0][i][1];
                w           = outputBuffer[0][i][2];
                h           = outputBuffer[0][i][3];
                personScore = (PERSON_CLASS < numClasses)
                        ? outputBuffer[0][i][4 + PERSON_CLASS] : 0f;
            }

            if (personScore < CONF_THRESH) continue;

            // Normalize nếu cần
            float ncx = needNorm ? cx / INPUT_SIZE : cx;
            float ncy = needNorm ? cy / INPUT_SIZE : cy;
            float nw  = needNorm ? w  / INPUT_SIZE : w;
            float nh  = needNorm ? h  / INPUT_SIZE : h;

            float x1 = Math.max(0f, Math.min(1f, ncx - nw / 2f));
            float y1 = Math.max(0f, Math.min(1f, ncy - nh / 2f));
            float x2 = Math.max(0f, Math.min(1f, ncx + nw / 2f));
            float y2 = Math.max(0f, Math.min(1f, ncy + nh / 2f));

            if (x2 > x1 && y2 > y1)
                result.add(new RectF(x1, y1, x2, y2));
        }
        return result;
    }

    private List<RectF> nms(List<RectF> boxes) {
        // Sắp xếp theo diện tích giảm dần (proxy cho confidence vì đã lọc threshold)
        boxes.sort((a, b) -> Float.compare(b.width() * b.height(), a.width() * a.height()));

        boolean[] suppressed = new boolean[boxes.size()];
        List<RectF> out = new ArrayList<>();

        for (int i = 0; i < boxes.size(); i++) {
            if (suppressed[i]) continue;
            out.add(boxes.get(i));
            for (int j = i + 1; j < boxes.size(); j++) {
                if (!suppressed[j] && iou(boxes.get(i), boxes.get(j)) >= IOU_THRESH)
                    suppressed[j] = true;
            }
        }
        return out;
    }

    private float iou(RectF a, RectF b) {
        float iL = Math.max(a.left,  b.left);
        float iT = Math.max(a.top,   b.top);
        float iR = Math.min(a.right, b.right);
        float iB = Math.min(a.bottom,b.bottom);
        if (iR <= iL || iB <= iT) return 0f;
        float inter = (iR - iL) * (iB - iT);
        return inter / (a.width()*a.height() + b.width()*b.height() - inter);
    }
}
