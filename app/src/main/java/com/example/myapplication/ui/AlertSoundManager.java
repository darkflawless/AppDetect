package com.example.myapplication.ui;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.util.Log;

/**
 * Quản lý phát âm thanh cảnh báo tài xế bằng ToneGenerator (còi bíp hệ thống).
 */
public class AlertSoundManager {
    private static final String TAG = "AlertSoundManager";

    private ToneGenerator toneGenerator;
    private long lastToneTime = 0;
    private boolean isAlertActive = false;

    // Khoảng cách nhịp bíp để tạo chuỗi cảnh báo khẩn cấp dồn dập
    private static final long BEEP_INTERVAL_MS = 600;
    private static final int BEEP_DURATION_MS = 500;

    public AlertSoundManager() {
        init();
    }

    private void init() {
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        } catch (Exception e) {
            Log.e(TAG, "Không thể khởi tạo ToneGenerator", e);
            toneGenerator = null;
        }
    }

    /**
     * Kích hoạt âm thanh cảnh báo.
     * Phát tiếng bíp dồn dập chừng nào trạng thái cảnh báo còn kích hoạt.
     */
    public void startAlert() {
        if (toneGenerator == null) {
            init();
            if (toneGenerator == null) return;
        }

        isAlertActive = true;
        long now = System.currentTimeMillis();
        if (now - lastToneTime >= BEEP_INTERVAL_MS) {
            lastToneTime = now;
            try {
                // TONE_CDMA_EMERGENCY_RINGBACK: Âm thanh cảnh báo khẩn cấp tần số cao
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, BEEP_DURATION_MS);
            } catch (Exception e) {
                Log.e(TAG, "Lỗi khi phát ToneGenerator", e);
            }
        }
    }

    /**
     * Dừng ngay âm thanh cảnh báo khi tài xế đã tỉnh táo / mở mắt.
     */
    public void stopAlert() {
        if (!isAlertActive) return;
        isAlertActive = false;

        if (toneGenerator != null) {
            try {
                toneGenerator.stopTone();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Giải phóng tài nguyên âm thanh khi thoát ứng dụng.
     */
    public void release() {
        stopAlert();
        if (toneGenerator != null) {
            try {
                toneGenerator.release();
            } catch (Exception ignored) {
            }
            toneGenerator = null;
        }
    }
}
