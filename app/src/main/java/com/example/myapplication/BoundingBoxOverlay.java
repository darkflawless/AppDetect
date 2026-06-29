package com.example.myapplication;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * BoundingBoxOverlay - Custom View vẽ bounding box lên ảnh camera.
 *
 * - Màu xanh lá  (#00E676) cho "belt"   → AN TOÀN
 * - Màu đỏ       (#FF1744) cho "no-belt" → NGUY HIỂM
 */
public class BoundingBoxOverlay extends View {

    private static final int COLOR_BELT    = Color.parseColor("#00E676"); // Xanh lá sáng
    private static final int COLOR_NO_BELT = Color.parseColor("#FF1744"); // Đỏ sáng

    private List<YoloDetector.Detection> detections = new ArrayList<>();

    private final Paint boxPaint    = new Paint();
    private final Paint labelBgPaint = new Paint();
    private final Paint labelPaint  = new Paint();
    private final Paint cornerPaint = new Paint();

    public BoundingBoxOverlay(Context context) {
        super(context);
        init();
    }

    public BoundingBoxOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // Paint cho khung bounding box
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(5f);
        boxPaint.setAntiAlias(true);

        // Paint cho góc bo tròn (decoration)
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(8f);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);
        cornerPaint.setAntiAlias(true);

        // Paint nền label
        labelBgPaint.setStyle(Paint.Style.FILL);
        labelBgPaint.setAntiAlias(true);

        // Paint text label
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(42f);
        labelPaint.setAntiAlias(true);
        labelPaint.setFakeBoldText(true);
    }

    /**
     * Gọi từ Main Thread (hoặc postInvalidate) khi có detection mới.
     */
    public void setDetections(List<YoloDetector.Detection> newDetections) {
        this.detections = new ArrayList<>(newDetections);
        postInvalidate(); // Yêu cầu vẽ lại trên UI thread
    }

    /**
     * Xóa tất cả bounding box.
     */
    public void clearDetections() {
        this.detections = new ArrayList<>();
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float viewW = getWidth();
        float viewH = getHeight();

        for (YoloDetector.Detection det : detections) {
            String lower = det.label.toLowerCase();
            int color;
            if (lower.equals("belt") || lower.equals("seatbelt") || lower.equals("face")) {
                color = COLOR_BELT; // Xanh lá
            } else if (lower.equals("yawning")) {
                color = Color.parseColor("#FF9100"); // Cam
            } else {
                color = COLOR_NO_BELT; // Đỏ (no-belt hoặc drowsy)
            }

            boxPaint.setColor(color);
            cornerPaint.setColor(color);
            labelBgPaint.setColor(color);

            // Chuyển tọa độ normalized [0,1] → pixel màn hình
            RectF box = det.bbox;
            float left   = box.left   * viewW;
            float top    = box.top    * viewH;
            float right  = box.right  * viewW;
            float bottom = box.bottom * viewH;

            // Vẽ khung chính (bounding box)
            canvas.drawRect(left, top, right, bottom, boxPaint);

            // Vẽ góc L đẹp hơn ở 4 góc
            float cornerLen = Math.min((right - left), (bottom - top)) * 0.2f;
            cornerLen = Math.max(cornerLen, 30f);
            drawCorners(canvas, left, top, right, bottom, cornerLen, cornerPaint);

            // --- Vẽ label phía trên box ---
            String prefix = "";
            if (lower.equals("belt") || lower.equals("seatbelt")) prefix = "✓ ";
            else if (lower.equals("no-belt") || lower.equals("no_belt")) prefix = "✗ ";
            else if (lower.equals("drowsy")) prefix = "😴 ";
            else if (lower.equals("yawning")) prefix = "🥱 ";
            else if (lower.equals("face")) prefix = "👤 ";

            String labelText = prefix + det.label.toUpperCase();
            if (!lower.equals("face") && !lower.equals("drowsy") && !lower.equals("yawning")) {
                labelText += "  " + String.format("%.0f%%", det.confidence * 100f);
            }

            float textH = labelPaint.getTextSize() + 14f;
            float textW = labelPaint.measureText(labelText) + 24f;

            // Nếu box sát đỉnh màn hình, đặt label bên dưới top
            float labelTop = (top - textH > 0) ? top - textH : top;

            // Nền label bo góc
            RectF labelRect = new RectF(left, labelTop, left + textW, labelTop + textH);
            canvas.drawRoundRect(labelRect, 8f, 8f, labelBgPaint);

            // Text label
            canvas.drawText(labelText, left + 12f, labelTop + textH - 8f, labelPaint);
        }
    }

    /**
     * Vẽ 4 góc chữ L cho bounding box (trông pro hơn box thường).
     */
    private void drawCorners(Canvas canvas, float l, float t, float r, float b,
                             float len, Paint paint) {
        // Góc trên-trái
        canvas.drawLine(l, t, l + len, t, paint);
        canvas.drawLine(l, t, l, t + len, paint);
        // Góc trên-phải
        canvas.drawLine(r, t, r - len, t, paint);
        canvas.drawLine(r, t, r, t + len, paint);
        // Góc dưới-trái
        canvas.drawLine(l, b, l + len, b, paint);
        canvas.drawLine(l, b, l, b - len, paint);
        // Góc dưới-phải
        canvas.drawLine(r, b, r - len, b, paint);
        canvas.drawLine(r, b, r, b - len, paint);
    }

    /**
     * Kiểm tra label có phải "có dây" không.
     * Hỗ trợ cả "belt" lẫn "seatbelt".
     */
    private boolean isBeltClass(String label) {
        if (label == null) return false;
        String lower = label.toLowerCase();
        return lower.equals("belt") || lower.equals("seatbelt");
    }
}
