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

    private android.widget.ImageView ivDashAvatar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        // Bind views
        tvDashGreeting     = findViewById(R.id.tv_dash_greeting);
        tvDashDriverCode   = findViewById(R.id.tv_dash_driver_code);
        ivDashAvatar       = findViewById(R.id.iv_dash_avatar);
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
        String cachedAvatar = tokenManager.getAvatarUrl();

        if (fullName != null && !fullName.isEmpty()) {
            tvDashGreeting.setText("Xin chào, " + fullName);
        }
        if (driverId != null && !driverId.isEmpty()) {
            String shortCode = driverId.length() > 8 ? driverId.substring(0, 8).toUpperCase() : driverId.toUpperCase();
            tvDashDriverCode.setText("DRV-" + shortCode);
        }
        if (cachedAvatar != null && !cachedAvatar.isEmpty()) {
            com.bumptech.glide.Glide.with(this)
                    .load(cachedAvatar)
                    .circleCrop()
                    .placeholder(R.drawable.ic_launcher_foreground)
                    .error(R.drawable.ic_launcher_foreground)
                    .into(ivDashAvatar);
        }

        // Tự động fetch profile từ /drivers/me để lấy avatar mới nhất và cập nhật UI
        loadDriverProfile(tokenManager);

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

    @Override
    protected void onResume() {
        super.onResume();
        SecureTokenManager tokenManager = SecureTokenManager.getInstance(getApplicationContext());
        String cachedAvatar = tokenManager.getAvatarUrl();
        if (cachedAvatar != null && !cachedAvatar.isEmpty() && ivDashAvatar != null) {
            com.bumptech.glide.Glide.with(this)
                    .load(cachedAvatar)
                    .circleCrop()
                    .placeholder(R.drawable.ic_launcher_foreground)
                    .error(R.drawable.ic_launcher_foreground)
                    .into(ivDashAvatar);
        }
    }

    private void loadDriverProfile(SecureTokenManager tokenManager) {
        String token = tokenManager.getToken();
        if (token == null || token.isEmpty()) return;

        com.example.myapplication.network.api.ApiService api = com.example.myapplication.network.api.RetrofitClient.getApiService();
        api.getDriverMe("Bearer " + token).enqueue(new retrofit2.Callback<com.example.myapplication.data.model.DriverMeResponse>() {
            @Override
            public void onResponse(retrofit2.Call<com.example.myapplication.data.model.DriverMeResponse> call,
                                   retrofit2.Response<com.example.myapplication.data.model.DriverMeResponse> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    com.example.myapplication.data.model.DriverMeResponse.DriverData data = response.body().getData();
                    if (data != null) {
                        if (data.getStaff() != null && data.getStaff().getFullName() != null) {
                            String name = data.getStaff().getFullName();
                            tvDashGreeting.setText("Xin chào, " + name);
                            tokenManager.saveFullName(name);
                        }
                        if (data.getId() != null) {
                            String code = data.getId().length() > 8 ? data.getId().substring(0, 8).toUpperCase() : data.getId().toUpperCase();
                            tvDashDriverCode.setText("DRV-" + code);
                            tokenManager.saveToken(token, data.getId());
                        }

                        String rawAvatarUrl = data.getUrlImage();
                        if (rawAvatarUrl != null && !rawAvatarUrl.isEmpty()) {
                            String fixedAvatarUrl = com.example.myapplication.utils.AppConfig.formatMediaUrl(rawAvatarUrl);
                            tokenManager.saveAvatarUrl(fixedAvatarUrl);

                            if (!isFinishing() && !isDestroyed() && ivDashAvatar != null) {
                                com.bumptech.glide.Glide.with(DashboardActivity.this)
                                        .load(fixedAvatarUrl)
                                        .circleCrop()
                                        .placeholder(R.drawable.ic_launcher_foreground)
                                        .error(R.drawable.ic_launcher_foreground)
                                        .into(ivDashAvatar);
                            }
                        }
                    }
                }
            }

            @Override
            public void onFailure(retrofit2.Call<com.example.myapplication.data.model.DriverMeResponse> call, Throwable t) {
                // Ignore network error silently on dashboard background fetch
            }
        });
    }
}
