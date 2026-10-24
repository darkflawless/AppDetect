package com.example.myapplication.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.myapplication.R;
import com.example.myapplication.utils.SecureTokenManager;

public class DashboardActivity extends AppCompatActivity {

    private TextView tvDashGreeting;
    private TextView tvDashDriverCode;
    private View btnMenuProfile;
    private View tabProfile;
    private View btnLogout;
    private Button btnStartTrip;
    private View btnMenuEditProfile, btnMenuHistory;
    private View tabHome, tabSchedule, tabHistory;
    private View btnMenu, btnNotification;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        // Bind views
        tvDashGreeting     = findViewById(R.id.tv_dash_greeting);
        tvDashDriverCode   = findViewById(R.id.tv_dash_driver_code);
        btnMenuProfile     = findViewById(R.id.btn_menu_profile);
        tabProfile         = findViewById(R.id.tab_profile);
        btnLogout          = findViewById(R.id.btn_logout);
        btnStartTrip       = findViewById(R.id.btn_start_trip);
        btnMenuEditProfile = findViewById(R.id.btn_menu_edit_profile);
        btnMenuHistory     = findViewById(R.id.btn_menu_history);
        tabHome            = findViewById(R.id.tab_home);
        tabSchedule        = findViewById(R.id.tab_schedule);
        tabHistory         = findViewById(R.id.tab_history);
        btnMenu            = findViewById(R.id.btn_menu);
        btnNotification    = findViewById(R.id.btn_notification);

        // Hiển thị thông tin tài xế từ TokenManager
        SecureTokenManager tokenManager = SecureTokenManager.getInstance(getApplicationContext());
        String fullName = tokenManager.getFullName();
        String driverId = tokenManager.getDriverId();

        if (fullName != null && !fullName.isEmpty()) {
            tvDashGreeting.setText("Xin chào, " + fullName);
        }
        if (driverId != null && !driverId.isEmpty()) {
            // Lấy 6-8 ký tự đầu để hiển thị mã tài xế ngắn gọn giống ảnh mẫu (DRV-XXXXXX)
            String shortCode = driverId.length() > 8 ? driverId.substring(0, 8).toUpperCase() : driverId.toUpperCase();
            tvDashDriverCode.setText("DRV-" + shortCode);
        }

        // Click Xem hồ sơ cá nhân -> Mở màn hình DriverProfileActivity
        View.OnClickListener openProfileListener = v -> {
            Intent intent = new Intent(DashboardActivity.this, DriverProfileActivity.class);
            startActivity(intent);
        };
        btnMenuProfile.setOnClickListener(openProfileListener);
        tabProfile.setOnClickListener(openProfileListener);

        // Nút Đăng xuất
        btnLogout.setOnClickListener(v -> {
            tokenManager.clear();
            Toast.makeText(this, "Đã đăng xuất", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(DashboardActivity.this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });

        // Placeholder cho các chức năng đang hoàn thiện
        View.OnClickListener placeholder = v ->
                Toast.makeText(DashboardActivity.this, "Chức năng đang phát triển", Toast.LENGTH_SHORT).show();

        if (btnStartTrip != null) btnStartTrip.setOnClickListener(placeholder);
        if (btnMenuEditProfile != null) btnMenuEditProfile.setOnClickListener(placeholder);
        if (btnMenuHistory != null) btnMenuHistory.setOnClickListener(placeholder);
        if (tabSchedule != null) tabSchedule.setOnClickListener(placeholder);
        if (tabHistory != null) tabHistory.setOnClickListener(placeholder);
        if (btnMenu != null) btnMenu.setOnClickListener(placeholder);
        if (btnNotification != null) btnNotification.setOnClickListener(placeholder);
    }
}
