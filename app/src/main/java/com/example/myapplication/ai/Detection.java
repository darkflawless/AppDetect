package com.example.myapplication.ai;

import android.graphics.RectF;

/**
 * Detection - Data class chứa kết quả bounding box và nhãn của đối tượng phát hiện.
 */
public class Detection {
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
