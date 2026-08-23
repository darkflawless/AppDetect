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
 * PersonDetector — Dùng yolov8n (COCO-80) để detect toàn thân người.
 *
 * Pipeline mục tiêu:
 * full frame → PersonDetector → [person1_bbox, person2_bbox, ...]
 * → crop từng người → SeatBeltDetector (seatbelt) trên mỗi crop
 *
 * Output: List<RectF> — mỗi RectF là bbox người (normalized [0,1])
 *
 * Model: yolov8n_320.tflite (COCO-80 classes, imgsz=320)
 * COCO class 0 = "person"
 */
public class PersonDetector {

    private static final String TAG = "PersonDetector";
    private static final String MODEL_FILE = "human_detection.tflite";
    private static final int INPUT_SIZE = 320; // phải khớp với imgsz khi export
    private static final int PERSON_CLASS = 0; // COCO class 0 = person
    private static final float CONF_THRESH = 0.25f;
    private static final float IOU_THRESH = 0.45f;

    private final Interpreter interpreter;
    private final GpuDelegate gpuDelegate;

    // Pre-allocated buffers
    private final ByteBuffer inputBuffer; // [1, 320, 320, 3] float32
    private final float[][][] outputBuffer; // [1][dim1][dim2]
    private final int[] pixelBuf;

    private final int numClasses;
    private final int numBoxes;
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

        if (outShape[1] < outShape[2]) { // [1, 84, 8400] — transposed
            isTransposed = true;
            numClasses = outShape[1] - 4;
            numBoxes = outShape[2];
        } else { // [1, 8400, 84]
            isTransposed = false;
            numBoxes = outShape[1];
            numClasses = outShape[2] - 4;
        }
        Log.d(TAG, (isTransposed ? "TRANSPOSED" : "NORMAL")
                + " | classes=" + numClasses + " | boxes=" + numBoxes);

        inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4);
        inputBuffer.order(ByteOrder.nativeOrder());
        outputBuffer = new float[outShape[0]][outShape[1]][outShape[2]];
        pixelBuf = new int[INPUT_SIZE * INPUT_SIZE];
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Detect tất cả người trong bitmap.
     * 
     * @return List<RectF> — bbox người, tọa độ normalized [0,1]
     */
    public List<RectF> detectPersons(Bitmap bitmap) {
        int origW = bitmap.getWidth();
        int origH = bitmap.getHeight();

        // 1. Resize với Letterbox (giữ nguyên tỷ lệ, thêm viền đen)
        Bitmap resized = createLetterboxBitmap(bitmap, INPUT_SIZE, INPUT_SIZE);

        // 2. Fill input buffer
        fillInput(resized);

        // 3. Inference
        interpreter.run(inputBuffer, outputBuffer);

        // 4. Parse
        List<PersonDet> candidates = parsePersonOnly(origW, origH);

        // 5. NMS
        return nms(candidates);
    }

    public void close() {
        if (interpreter != null)
            interpreter.close();
        if (gpuDelegate != null)
            gpuDelegate.close();
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
            inputBuffer.putFloat(((px >> 8) & 0xFF) / 255f); // G
            inputBuffer.putFloat((px & 0xFF) / 255f); // B
        }
    }

    private static class PersonDet {
        RectF box;
        float score;
        PersonDet(RectF box, float score) {
            this.box = box;
            this.score = score;
        }
    }

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

    private List<PersonDet> parsePersonOnly(int origW, int origH) {
        List<PersonDet> result = new ArrayList<>();

        float sampleCx = outputBuffer[0][0][0];
        boolean needNorm = sampleCx > 1.5f;

        // Tính toán thông số letterbox để scale ngược lại
        float scale = Math.min((float) INPUT_SIZE / origW, (float) INPUT_SIZE / origH);
        float padX = (INPUT_SIZE - origW * scale) / 2f;
        float padY = (INPUT_SIZE - origH * scale) / 2f;

        for (int i = 0; i < numBoxes; i++) {
            float cx, cy, w, h, personScore;

            if (isTransposed) {
                cx = outputBuffer[0][0][i];
                cy = outputBuffer[0][1][i];
                w = outputBuffer[0][2][i];
                h = outputBuffer[0][3][i];
                // Chỉ đọc score class 0 (person)
                personScore = (PERSON_CLASS < numClasses)
                        ? outputBuffer[0][4 + PERSON_CLASS][i]
                        : 0f;
            } else {
                cx = outputBuffer[0][i][0];
                cy = outputBuffer[0][i][1];
                w = outputBuffer[0][i][2];
                h = outputBuffer[0][i][3];
                personScore = (PERSON_CLASS < numClasses)
                        ? outputBuffer[0][i][4 + PERSON_CLASS]
                        : 0f;
            }

            if (personScore < CONF_THRESH)
                continue;

            // Convert to pixel coordinates in 320x320 letterbox image
            float px = needNorm ? cx : cx * INPUT_SIZE;
            float py = needNorm ? cy : cy * INPUT_SIZE;
            float pw = needNorm ? w : w * INPUT_SIZE;
            float ph = needNorm ? h : h * INPUT_SIZE;
            
            // Map back to original image pixel coordinates
            float origPx = (px - padX) / scale;
            float origPy = (py - padY) / scale;
            float origPw = pw / scale;
            float origPh = ph / scale;
            
            // Convert to normalized coordinates [0,1] for original image
            float ncx = origPx / origW;
            float ncy = origPy / origH;
            float nw = origPw / origW;
            float nh = origPh / origH;

            float x1 = Math.max(0f, Math.min(1f, ncx - nw / 2f));
            float y1 = Math.max(0f, Math.min(1f, ncy - nh / 2f));
            float x2 = Math.max(0f, Math.min(1f, ncx + nw / 2f));
            float y2 = Math.max(0f, Math.min(1f, ncy + nh / 2f));

            if (x2 > x1 && y2 > y1)
                result.add(new PersonDet(new RectF(x1, y1, x2, y2), personScore));
        }
        return result;
    }

    private List<RectF> nms(List<PersonDet> boxes) {
        // Sắp xếp theo confidence giảm dần
        boxes.sort((a, b) -> Float.compare(b.score, a.score));

        boolean[] suppressed = new boolean[boxes.size()];
        List<RectF> out = new ArrayList<>();

        for (int i = 0; i < boxes.size(); i++) {
            if (suppressed[i])
                continue;
            out.add(boxes.get(i).box);
            for (int j = i + 1; j < boxes.size(); j++) {
                if (!suppressed[j] && iou(boxes.get(i).box, boxes.get(j).box) >= IOU_THRESH)
                    suppressed[j] = true;
            }
        }
        return out;
    }

    private float iou(RectF a, RectF b) {
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
