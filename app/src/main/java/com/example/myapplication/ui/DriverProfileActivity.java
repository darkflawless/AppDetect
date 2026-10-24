package com.example.myapplication.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.example.myapplication.R;
import com.example.myapplication.data.model.DriverMeResponse;
import com.example.myapplication.network.api.ApiService;
import com.example.myapplication.network.api.RetrofitClient;
import com.example.myapplication.utils.SecureTokenManager;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class DriverProfileActivity extends AppCompatActivity {

    private TextView tvDriverName, tvDriverId, tvDriverPhone, tvDriverEmail;
    private TextView tvDriverDob, tvDriverGender, tvDriverHireDate, tvDriverAddress;
    private TextView tvActiveStatus, tvAccessToken;
    private android.widget.ImageView ivDriverAvatar;
    private View btnBack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_driver_profile);

        // Bind views
        tvDriverName     = findViewById(R.id.tv_driver_name);
        tvDriverId       = findViewById(R.id.tv_driver_id);
        tvDriverPhone    = findViewById(R.id.tv_driver_phone);
        tvDriverEmail    = findViewById(R.id.tv_driver_email);
        tvDriverDob      = findViewById(R.id.tv_driver_dob);
        tvDriverGender   = findViewById(R.id.tv_driver_gender);
        tvDriverHireDate = findViewById(R.id.tv_driver_hire_date);
        tvDriverAddress  = findViewById(R.id.tv_driver_address);
        tvActiveStatus   = findViewById(R.id.tv_active_status);
        tvAccessToken    = findViewById(R.id.tv_access_token);
        ivDriverAvatar   = findViewById(R.id.iv_driver_avatar);
        btnBack          = findViewById(R.id.btn_back);

        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        SecureTokenManager mgr = SecureTokenManager.getInstance(getApplicationContext());
        String savedDriverId = mgr.getDriverId();
        String token         = mgr.getToken();

        if (savedDriverId != null && !savedDriverId.isEmpty()) {
            tvDriverId.setText(savedDriverId);
        }
        if (token != null && !token.isEmpty()) {
            tvAccessToken.setText(token);
        } else {
            tvAccessToken.setText("Chưa có token");
        }

        if (token == null || token.isEmpty()) {
            Toast.makeText(this, "Chưa có Bearer Token đăng nhập", Toast.LENGTH_SHORT).show();
            return;
        }

        // Gọi API GET /drivers/me kèm Header Bearer Token
        String authHeader = "Bearer " + token;
        ApiService api = RetrofitClient.getApiService();
        api.getDriverMe(authHeader).enqueue(new Callback<DriverMeResponse>() {
            @Override
            public void onResponse(Call<DriverMeResponse> call, Response<DriverMeResponse> resp) {
                if (resp.isSuccessful() && resp.body() != null && resp.body().isSuccess()) {
                    DriverMeResponse.DriverData data = resp.body().getData();
                    if (data != null) {
                        // Hiển thị id tài xế
                        if (data.getId() != null) {
                            tvDriverId.setText(data.getId());
                        }
                        // Hiển thị trạng thái
                        tvActiveStatus.setText(data.isActive() ? "Đang hoạt động" : "Tạm ngưng");
                        
                        // Ngày nhận việc
                        tvDriverHireDate.setText(data.getHireDate() != null ? data.getHireDate() : "---");

                        // Load avatar tài xế bằng Glide
                        String rawAvatarUrl = data.getUrlImage();
                        if (rawAvatarUrl != null && !rawAvatarUrl.isEmpty()) {
                            // Chuyển localhost hoặc 127.0.0.1 thành 192.168.1.11 để điện thoại truy cập được
                            String fixedAvatarUrl = rawAvatarUrl
                                    .replace("localhost", "192.168.1.11")
                                    .replace("127.0.0.1", "192.168.1.11");

                            mgr.saveAvatarUrl(fixedAvatarUrl);

                            if (!isFinishing() && !isDestroyed()) {
                                com.bumptech.glide.Glide.with(DriverProfileActivity.this)
                                        .load(fixedAvatarUrl)
                                        .circleCrop()
                                        .placeholder(R.drawable.ic_launcher_foreground)
                                        .error(R.drawable.ic_launcher_foreground)
                                        .into(ivDriverAvatar);
                            }
                        }

                        // Thông tin staff
                        DriverMeResponse.Staff staff = data.getStaff();
                        if (staff != null) {
                            tvDriverName.setText(staff.getFullName() != null ? staff.getFullName() : "---");
                            tvDriverPhone.setText(staff.getPhone() != null ? staff.getPhone() : "---");
                            tvDriverEmail.setText(staff.getEmail() != null ? staff.getEmail() : "---");
                            tvDriverDob.setText(staff.getDateOfBirth() != null ? staff.getDateOfBirth() : "---");
                            
                            String gender = "MALE".equalsIgnoreCase(staff.getGender()) ? "Nam"
                                    : ("FEMALE".equalsIgnoreCase(staff.getGender()) ? "Nữ" : staff.getGender());
                            tvDriverGender.setText(gender != null ? gender : "---");

                            tvDriverAddress.setText(staff.getFullAddress().isEmpty() ? "---" : staff.getFullAddress());
                        }
                    }
                } else {
                    Toast.makeText(DriverProfileActivity.this,
                            "Lỗi lấy hồ sơ: HTTP " + resp.code(), Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<DriverMeResponse> call, Throwable t) {
                Toast.makeText(DriverProfileActivity.this, "Lỗi kết nối: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }
}
