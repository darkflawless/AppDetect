package com.example.myapplication.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.myapplication.R;

/**
 * PreVerificationActivity - Màn hình giới thiệu quy trình xác thực 2 bước.
 * Hiện thị trước khi tài xế bắt đầu hoặc tiếp tục bất kỳ chuyến đi nào.
 *
 * Nhận vào từ Intent:
 *   - TRIP_ID       (String)
 *   - VEHICLE_ID    (String)
 *   - VEHICLE_PLATE (String)
 *
 * Hành động:
 *   - Bấm "Bắt đầu xác thực"  → mở DriverVerifyActivity (Bước 1)
 *   - Bấm "Bỏ qua xác thực"   → chuyển thẳng sang DriverMonitorActivity
 */
public class PreVerificationActivity extends AppCompatActivity {

    private String tripId;
    private String vehicleId;
    private String vehiclePlate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pre_verification);

        // Lấy thông tin chuyến đi từ Dashboard
        Intent intent = getIntent();
        if (intent != null) {
            tripId       = intent.getStringExtra("TRIP_ID");
            vehicleId    = intent.getStringExtra("VEHICLE_ID");
            vehiclePlate = intent.getStringExtra("VEHICLE_PLATE");
        }

        Button btnStartVerification = findViewById(R.id.btnStartVerification);
        Button btnSkipPreVerify     = findViewById(R.id.btnSkipPreVerify);

        // Bắt đầu xác thực → vào Bước 1
        btnStartVerification.setOnClickListener(v -> openStep1FaceVerify());

        // Bỏ qua → vào thẳng buồng lái
        btnSkipPreVerify.setOnClickListener(v -> {
            Toast.makeText(this, "Đã bỏ qua xác thực", Toast.LENGTH_SHORT).show();
            openDriverMonitor();
        });
    }

    /** Mở màn hình Bước 1: Xác thực khuôn mặt */
    private void openStep1FaceVerify() {
        Intent intent = new Intent(this, DriverVerifyActivity.class);
        passExtras(intent);
        startActivity(intent);
        // Không gọi finish() để người dùng có thể back về nếu cần
    }

    /** Bỏ qua toàn bộ, vào thẳng DriverMonitorActivity */
    private void openDriverMonitor() {
        Intent intent = new Intent(this, DriverMonitorActivity.class);
        passExtras(intent);
        startActivity(intent);
        finish();
    }

    private void passExtras(Intent intent) {
        if (tripId != null)       intent.putExtra("TRIP_ID", tripId);
        if (vehicleId != null)    intent.putExtra("VEHICLE_ID", vehicleId);
        if (vehiclePlate != null) intent.putExtra("VEHICLE_PLATE", vehiclePlate);
    }
}

