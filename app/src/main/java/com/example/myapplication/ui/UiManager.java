package com.example.myapplication.ui;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.camera.view.PreviewView;

import com.example.myapplication.R;
import com.example.myapplication.ai.Detection;
import com.example.myapplication.ai.DrowsinessDetector;

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
    private final AlertSoundManager alertSoundManager;

    public UiManager(Activity activity) {
        this.activity = activity;
        this.alertSoundManager = new AlertSoundManager();

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

    public void updateUI(List<Detection> detections, DrowsinessDetector.DrowsinessResult dResult,
                         float fps, int imgW, int imgH) {
        fpsText.setText(String.format("%.1f FPS", fps));
        bboxOverlay.setDetections(detections, imgW, imgH);

        // Logic hiển thị cảnh báo an toàn
        if (dResult.isFaceMissing && !dResult.isDrowsy) {
            redFlashOverlay.setVisibility(View.VISIBLE);
            statusIcon.setText("⚠️");
            statusText.setText("CẢNH BÁO - KHÔNG THẤY TÀI XẾ!");
            statusText.setTextColor(Color.RED);
            confidenceText.setText("Hệ thống mất dấu người lái!");
            alertSoundManager.startAlert();
        } else if (dResult.isDistracted) {
            // Ưu tiên cảnh báo mất tập trung khi tài xế quay đầu
            redFlashOverlay.setVisibility(View.VISIBLE);
            statusIcon.setText("👀");
            statusText.setText("CẢNH BÁO - MẤT TẬP TRUNG!");
            statusText.setTextColor(Color.RED);
            confidenceText.setText(String.format("Hãy nhìn thẳng! (Góc quay: %.0f°)", dResult.headEulerY));
            alertSoundManager.startAlert();
        } else if (dResult.isDrowsy) {
            // Cảnh báo buồn ngủ khi tài xế nhìn thẳng nhưng sụp mí
            redFlashOverlay.setVisibility(View.VISIBLE);
            statusIcon.setText("😴");
            statusText.setText("NGUY HIỂM - ĐANG NGỦ GẬT!");
            statusText.setTextColor(Color.RED);
            confidenceText.setText(String.format("Hãy dừng xe! (AI: %.0f%% | EAR: %.2f)",
                    dResult.drowsinessScore * 100f, dResult.ear));
            alertSoundManager.startAlert();
        } else {
            redFlashOverlay.setVisibility(View.GONE);
            alertSoundManager.stopAlert();

            if (dResult.faceDetected && dResult.isYawning) {
                statusIcon.setText("🥱");
                statusText.setText("CẢNH BÁO - Đang ngáp!");
                statusText.setTextColor(Color.parseColor("#FF9100"));
                confidenceText.setText("Bạn có vẻ đang mệt mỏi.");
            } else {
                statusIcon.setText("😊");
                statusText.setText(
                        dResult.faceDetected ? "Đã thấy mặt - Đang theo dõi..." : "Đang tìm kiếm khuôn mặt...");
                statusText.setTextColor(Color.WHITE);

                if (dResult.faceDetected) {
                    confidenceText.setText(String.format("AI: %.0f%% | EAR: %.2f | Yaw: %.0f°",
                            dResult.drowsinessScore * 100f, dResult.ear, dResult.headEulerY));
                } else {
                    confidenceText.setText("");
                }
            }
        }
    }

    public void stopAlertSound() {
        if (alertSoundManager != null) {
            alertSoundManager.stopAlert();
        }
    }

    public void release() {
        if (alertSoundManager != null) {
            alertSoundManager.release();
        }
    }
}
