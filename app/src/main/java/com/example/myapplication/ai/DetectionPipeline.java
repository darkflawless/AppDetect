package com.example.myapplication.ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import com.example.myapplication.utils.ImageUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * DetectionPipeline - Quản lý bộ máy AI 3 models và luồng xử lý bất đồng bộ.
 */
public class DetectionPipeline {

    private static final String TAG = "DetectionPipeline";

    // Detectors
    private SeatBeltDetector seatbeltDetector;
    private DrowsinessDetector drowsinessDetector;
    private PersonDetector personDetector;

    // Executors
    private final ExecutorService cameraExecutor;
    private final ExecutorService seatbeltExecutor;
    private final ExecutorService drowsinessExecutor;
    private final ExecutorService personExecutor;

    // Pending futures
    private Future<List<SeatBeltDetector.Detection>> pendingSeatbeltFuture = null;
    private Future<DrowsinessDetector.DrowsinessResult> pendingDrowsinessFuture = null;
    private Future<List<RectF>> pendingPersonFuture = null;

    // Last results
    private List<SeatBeltDetector.Detection> lastSeatbeltDetections = new ArrayList<>();
    private DrowsinessDetector.DrowsinessResult lastDrowsinessResult = null;
    private List<RectF> lastPersonBboxes = new ArrayList<>();

    private long lastFrameTime = 0L;

    public interface PipelineCallback {
        void onPipelineResult(List<SeatBeltDetector.Detection> detections,
                DrowsinessDetector.DrowsinessResult drowsinessResult,
                float fps, int imgW, int imgH);
    }

    public interface InitCallback {
        void onSuccess();

        void onError(Exception e);
    }

    public DetectionPipeline() {
        cameraExecutor = Executors.newSingleThreadExecutor();
        seatbeltExecutor = Executors.newSingleThreadExecutor();
        drowsinessExecutor = Executors.newSingleThreadExecutor();
        personExecutor = Executors.newSingleThreadExecutor();
    }

    public ExecutorService getCameraExecutor() {
        return cameraExecutor;
    }

    public void loadModelsAsync(Context context, String[] labels, InitCallback callback) {
        cameraExecutor.execute(() -> {
            try {
                drowsinessDetector = new DrowsinessDetector(context);
                seatbeltDetector = new SeatBeltDetector(context, labels);
                personDetector = new PersonDetector(context);
                Log.d(TAG, "All models loaded successfully");
                if (callback != null)
                    callback.onSuccess();
            } catch (Exception e) {
                Log.e(TAG, "Error loading models", e);
                if (callback != null)
                    callback.onError(e);
            }
        });
    }

    public void processFrame(Bitmap bitmap, long timestampMs, PipelineCallback callback) {
        if (drowsinessDetector == null || bitmap == null)
            return;

        // 1. Thu kết quả person detection
        if (pendingPersonFuture != null && pendingPersonFuture.isDone()) {
            try {
                lastPersonBboxes = new ArrayList<>(pendingPersonFuture.get());
            } catch (Exception e) {
                Log.w(TAG, "Person result error: " + e.getMessage());
            }
            pendingPersonFuture = null;
        }

        // 2. Thu kết quả drowsiness
        if (pendingDrowsinessFuture != null && pendingDrowsinessFuture.isDone()) {
            try {
                lastDrowsinessResult = pendingDrowsinessFuture.get();
            } catch (Exception e) {
                Log.w(TAG, "Drowsiness result error: " + e.getMessage());
            }
            pendingDrowsinessFuture = null;
        }

        // 3. Thu kết quả seatbelt
        if (pendingSeatbeltFuture != null && pendingSeatbeltFuture.isDone()) {
            try {
                lastSeatbeltDetections = new ArrayList<>(pendingSeatbeltFuture.get());
            } catch (Exception e) {
                Log.w(TAG, "YOLO result error: " + e.getMessage());
            }
            pendingSeatbeltFuture = null;
        }

        // ── Submit Person Detection (Step 1) ──────────────────────────────────
        if (personDetector != null && pendingPersonFuture == null) {
            final Bitmap personBitmap = bitmap.copy(bitmap.getConfig(), false);
            pendingPersonFuture = personExecutor.submit(() -> {
                List<RectF> persons = personDetector.detectPersons(personBitmap);
                personBitmap.recycle();
                return persons;
            });
        }

        // ── Submit Seatbelt Detection (Step 2): Crop từng người ───────────────
        if (seatbeltDetector != null && pendingSeatbeltFuture == null && !lastPersonBboxes.isEmpty()) {
            final Bitmap fullBitmap = bitmap.copy(bitmap.getConfig(), false);
            final List<RectF> persons = new ArrayList<>(lastPersonBboxes);

            pendingSeatbeltFuture = seatbeltExecutor.submit(() -> {
                List<SeatBeltDetector.Detection> allSb = new ArrayList<>();

                int pIdx = 1;
                for (RectF pBbox : persons) {
                    // Crop người từ ảnh gốc sắc nét với 8% margin
                    ImageUtils.CropResult crop = ImageUtils.cropPersonWithMargin(fullBitmap, pBbox, 0.08f);
                    if (crop == null)
                        continue;

                    // Detect seatbelt trên crop
                    List<SeatBeltDetector.Detection> sbOnCrop = seatbeltDetector.detect(crop.cropBitmap);
                    crop.cropBitmap.recycle();

                    // Map tọa độ bbox từ crop → full frame (normalized)
                    for (SeatBeltDetector.Detection d : sbOnCrop) {
                        float gL = crop.cropL + d.bbox.left * crop.getNormW();
                        float gT = crop.cropT + d.bbox.top * crop.getNormH();
                        float gR = crop.cropL + d.bbox.right * crop.getNormW();
                        float gB = crop.cropT + d.bbox.bottom * crop.getNormH();
                        allSb.add(new SeatBeltDetector.Detection(
                                new RectF(gL, gT, gR, gB),
                                d.classId, d.confidence, d.label));
                    }

                    // Thêm bbox người vào overlay (P1, P2, P3...)
                    allSb.add(new SeatBeltDetector.Detection(new RectF(pBbox), -2, 1.0f, "P" + pIdx));
                    pIdx++;
                }

                fullBitmap.recycle();
                return allSb;
            });
        } else if (seatbeltDetector != null && pendingSeatbeltFuture == null && lastPersonBboxes.isEmpty()) {
            // Fallback: Chạy full frame nếu không detect được người
            final Bitmap seatbeltBitmap = bitmap.copy(bitmap.getConfig(), false);
            pendingSeatbeltFuture = seatbeltExecutor.submit(() -> {
                List<SeatBeltDetector.Detection> result = seatbeltDetector.detect(seatbeltBitmap);
                seatbeltBitmap.recycle();
                return result;
            });
        }

        // ── Submit Drowsiness ─────────────────────────────────────────────────
        if (pendingDrowsinessFuture == null) {
            final Bitmap drowsinessBitmap = bitmap.copy(bitmap.getConfig(), false);
            final long ts = timestampMs;
            pendingDrowsinessFuture = drowsinessExecutor.submit(() -> {
                DrowsinessDetector.DrowsinessResult r = drowsinessDetector.detect(drowsinessBitmap, ts);
                drowsinessBitmap.recycle();
                return r;
            });
        }

        // ── Tổng hợp kết quả mới nhất ─────────────────────────────────────────
        DrowsinessDetector.DrowsinessResult drowsinessResult = (lastDrowsinessResult != null)
                ? lastDrowsinessResult
                : new DrowsinessDetector.DrowsinessResult();

        List<SeatBeltDetector.Detection> seatbeltDetections = new ArrayList<>(lastSeatbeltDetections);

        if (drowsinessResult.faceDetected && drowsinessResult.faceBbox != null) {
            String faceLabel = "Face";
            if (drowsinessResult.isDrowsy)
                faceLabel = "Drowsy";
            else if (drowsinessResult.isDistracted)
                faceLabel = "Distracted";
            else if (drowsinessResult.isYawning)
                faceLabel = "Yawning";

            seatbeltDetections.add(new SeatBeltDetector.Detection(
                drowsinessResult.faceBbox, -1, 1.0f, faceLabel));
        }

        long now = System.currentTimeMillis();
        float fps = (lastFrameTime > 0) ? 1000f / (now - lastFrameTime) : 0f;
        lastFrameTime = now;

        if (callback != null) {
            callback.onPipelineResult(seatbeltDetections, drowsinessResult, fps,
                    bitmap.getWidth(), bitmap.getHeight());
        }
    }

    public void close() {
        if (cameraExecutor != null)
            cameraExecutor.shutdown();
        if (seatbeltExecutor != null)
            seatbeltExecutor.shutdown();
        if (drowsinessExecutor != null)
            drowsinessExecutor.shutdown();
        if (personExecutor != null)
            personExecutor.shutdown();

        if (seatbeltDetector != null)
            seatbeltDetector.close();
        if (drowsinessDetector != null)
            drowsinessDetector.close();
        if (personDetector != null)
            personDetector.close();
    }
}
