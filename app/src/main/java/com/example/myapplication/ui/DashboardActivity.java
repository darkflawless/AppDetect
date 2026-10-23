package com.example.myapplication.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import com.example.myapplication.R;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.example.myapplication.network.api.ApiService;
import com.example.myapplication.network.api.RetrofitClient;
import com.example.myapplication.utils.SecureTokenManager;
import com.example.myapplication.data.model.DriverResponse;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class DashboardActivity extends AppCompatActivity {

    private Button btnStartTrip;
    private ImageView ivAvatar;
    private TextView tvName, tvId;
    private ApiService apiService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        btnStartTrip = findViewById(R.id.btn_start_trip);
        ivAvatar = findViewById(R.id.driver_avatar);
        tvName = findViewById(R.id.driver_name);
        tvId = findViewById(R.id.driver_id);
        apiService = RetrofitClient.getApiService();
        loadDriverProfile();
        btnStartTrip.setOnClickListener(v -> {
            Intent intent = new Intent(DashboardActivity.this, DriverMonitorActivity.class);
            startActivity(intent);
        });
    }
    private void loadDriverProfile() {
        String driverId = SecureTokenManager.getInstance(getApplicationContext()).getDriverId();
        if (driverId == null) {
            Toast.makeText(this, "Không có thông tin tài xế", Toast.LENGTH_SHORT).show();
            return;
        }
        apiService.getDriverProfile(driverId).enqueue(new Callback<DriverResponse>() {
            @Override
            public void onResponse(Call<DriverResponse> call, Response<DriverResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    DriverResponse driver = response.body();
                    tvName.setText(driver.getStaff().getFullName());
                    tvId.setText("ID: " + driver.getId());
                    // TODO: load avatar URL into ivAvatar using an image library
                } else {
                    Toast.makeText(DashboardActivity.this, "Lấy thông tin tài xế thất bại", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<DriverResponse> call, Throwable t) {
                Toast.makeText(DashboardActivity.this, "Lỗi mạng: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }
}
