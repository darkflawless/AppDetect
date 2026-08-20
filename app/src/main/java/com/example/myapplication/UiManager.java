package com.example.myapplication;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.camera.view.PreviewView;

import java.util.List;

/**
 * UiManager - Quản lý toàn bộ View UI và hiển thị trạng thái ứng dụng.
 */
public class UiManager {

    private final Activity activity;

    // UI Views
    public final PreviewView cameraPreview;
    public final BoundingBoxOverlay bboxOverlay;
    public final View redFlashOverlay;
    public final TextView statusIcon;
    public final TextView statusText;
    public final TextView confidenceText;
    public final TextView fpsText;
    public final ImageButton btnSwitchCamera;
    public final LinearLayout startOverlay;
    public final Button btnStartTrip;

    public UiManager(Activity activity) {
        this.activity = activity;

        cameraPreview = activity.findViewById(R.id.camera_preview);
        bboxOverlay = activity.findViewById(R.id.bbox_overlay);
        redFlashOverlay = activity.findViewById(R.id.red_flash_overlay);
        statusIcon = activity.findViewById(R.id.status_icon);
        statusText = activity.findViewById(R.id.status_text);
        confidenceText = activity.findViewById(R.id.confidence_text);
        fpsText = activity.findViewById(R.id.fps_text);
        btnSwitchCamera = activity.findViewById(R.id.btn_switch_camera);
        startOverlay = activity.findViewById(R.id.start_overlay);
        btnStartTrip = activity.findViewById(R.id.btn_start_trip);
    }

    public void enableStartButton() {
        activity.runOnUiThread(() -> btnStartTrip.setEnabled(true));
    }

    public void showError(String message) {
        activity.runOnUiThread(() -> {
            statusIcon.setText("❌");
            statusText.setText("Lỗi: " + message);
        });
    }

    public void updateUI(List<SeatBeltDetector.Detection> detections, DrowsinessDetector.DrowsinessResult dResult,
                         float fps, int imgW, int imgH) {
        fpsText.setText(String.format("%.1f FPS", fps));
        bboxOverlay.setDetections(detections, imgW, imgH);

        SeatBeltDetector.Detection bestBelt = null;
        SeatBeltDetector.Detection bestNoBelt = null;
        for (SeatBeltDetector.Detection d : detections) {
            if (d.label.equalsIgnoreCase("seatbelt") || d.label.equalsIgnoreCase("belt")) {
                if (bestBelt == null || d.confidence > bestBelt.confidence)
                    bestBelt = d;
            } else if (d.label.equalsIgnoreCase("no-seatbelt") || d.label.equalsIgnoreCase("no_belt")) {
                if (bestNoBelt == null || d.confidence > bestNoBelt.confidence)
                    bestNoBelt = d;
            }
        }

        // Logic hiển thị (Ưu tiên các cảnh báo nguy hiểm trước)
        if (dResult.isFaceMissing) {
            redFlashOverlay.setVisibility(View.VISIBLE);
            statusIcon.setText("🫥");
            statusText.setText("CẢNH BÁO - KHÔNG THẤY TÀI XẾ!");
            statusText.setTextColor(Color.RED);
            confidenceText.setText("Hệ thống mất dấu người lái!");
        } else if (dResult.faceDetected && dResult.isDrowsy) {
            redFlashOverlay.setVisibility(View.VISIBLE);
            statusIcon.setText("😴");
            statusText.setText("NGUY HIỂM - ĐANG NGỦ GẬT!");
            statusText.setTextColor(Color.RED);
            confidenceText.setText(String.format("Hãy dừng xe! (EAR: %.2f)", dResult.ear));
        } else if (dResult.faceDetected && dResult.isDistracted) {
            redFlashOverlay.setVisibility(View.VISIBLE);
            statusIcon.setText("🫣");
            statusText.setText("CẢNH BÁO - MẤT TẬP TRUNG!");
            statusText.setTextColor(Color.RED);
            confidenceText.setText(String.format("Hãy nhìn thẳng! (Góc quay: %.0f°)", dResult.headEulerY));
        } else {
            redFlashOverlay.setVisibility(View.GONE);

            if (dResult.faceDetected && dResult.isYawning) {
                statusIcon.setText("🥱");
                statusText.setText("CẢNH BÁO - Đang ngáp!");
                statusText.setTextColor(Color.parseColor("#FF9100"));
                confidenceText.setText("Bạn có vẻ đang mệt mỏi.");
            } else if (bestNoBelt != null) {
                statusIcon.setText("⚠️");
                statusText.setText("CẢNH BÁO - Không đeo dây!");
                statusText.setTextColor(Color.RED);
                confidenceText.setText(String.format("Độ tin cậy: %.1f%%", bestNoBelt.confidence * 100f));
            } else if (bestBelt != null) {
                statusIcon.setText("✅");
                statusText.setText("AN TOÀN - Đang đeo dây");
                statusText.setTextColor(Color.GREEN);
                confidenceText.setText(String.format("Độ tin cậy: %.1f%%", bestBelt.confidence * 100f));
            } else {
                statusIcon.setText("🔍");
                statusText.setText(
                        dResult.faceDetected ? "Đã thấy mặt - Đang theo dõi..." : "Đang tìm kiếm khuôn mặt...");
                statusText.setTextColor(Color.WHITE);

                if (dResult.faceDetected) {
                    confidenceText.setText(String.format("EAR: %.2f | Yaw: %.0f°",
                            dResult.ear, dResult.headEulerY));
                } else {
                    confidenceText.setText("");
                }
            }
        }
    }
}
