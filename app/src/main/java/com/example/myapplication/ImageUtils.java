package com.example.myapplication;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageProxy;

/**
 * ImageUtils - Chứa các hàm tiện ích xử lý ảnh chuyên biệt.
 */
public class ImageUtils {

    /**
     * Chuyển ImageProxy thành Bitmap, xoay theo góc rotation và lật gương nếu là camera trước.
     * Giữ nguyên độ phân giải sắc nét từ camera (không nén xuống 480px).
     */
    public static Bitmap imageProxyToBitmap(@NonNull ImageProxy imageProxy, int lensFacing) {
        Bitmap bitmap = imageProxy.toBitmap();
        if (bitmap == null)
            return null;

        Matrix matrix = new Matrix();
        matrix.postRotate(imageProxy.getImageInfo().getRotationDegrees());

        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            matrix.postScale(-1f, 1f, bitmap.getWidth() / 2f, bitmap.getHeight() / 2f);
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    /**
     * Crop người từ ảnh gốc với safety margin (mở rộng nhẹ % mỗi chiều để không bị xén cụt vai/ngực).
     * Trả về CropResult chứa cả Bitmap crop và tọa độ crop (normalized [0,1]) để map ngược lại.
     */
    public static CropResult cropPersonWithMargin(Bitmap fullBitmap, RectF pBbox, float marginPercent) {
        if (fullBitmap == null || pBbox == null) return null;

        int bmpW = fullBitmap.getWidth();
        int bmpH = fullBitmap.getHeight();

        float marginX = pBbox.width() * marginPercent;
        float marginY = pBbox.height() * marginPercent;

        float cropL = Math.max(0f, pBbox.left - marginX);
        float cropT = Math.max(0f, pBbox.top - marginY);
        float cropR = Math.min(1f, pBbox.right + marginX);
        float cropB = Math.min(1f, pBbox.bottom + marginY);

        int px1 = Math.max(0, (int) (cropL * bmpW));
        int py1 = Math.max(0, (int) (cropT * bmpH));
        int px2 = Math.min(bmpW, (int) (cropR * bmpW));
        int py2 = Math.min(bmpH, (int) (cropB * bmpH));
        int pw = px2 - px1;
        int ph = py2 - py1;

        if (pw < 10 || ph < 10) return null;

        Bitmap cropBmp = Bitmap.createBitmap(fullBitmap, px1, py1, pw, ph);
        return new CropResult(cropBmp, cropL, cropT, cropR, cropB);
    }

    public static class CropResult {
        public final Bitmap cropBitmap;
        public final float cropL, cropT, cropR, cropB;

        public CropResult(Bitmap cropBitmap, float cropL, float cropT, float cropR, float cropB) {
            this.cropBitmap = cropBitmap;
            this.cropL = cropL;
            this.cropT = cropT;
            this.cropR = cropR;
            this.cropB = cropB;
        }

        public float getNormW() { return cropR - cropL; }
        public float getNormH() { return cropB - cropT; }
    }
}
