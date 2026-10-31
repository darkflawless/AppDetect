package com.example.myapplication.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.example.myapplication.R;
import com.example.myapplication.data.model.AssignmentListResponse;
import com.example.myapplication.data.model.DriverMeResponse;
import com.example.myapplication.network.api.ApiService;
import com.example.myapplication.network.api.AppRestClient;
import com.example.myapplication.network.api.RetrofitClient;
import com.example.myapplication.utils.AppConfig;
import com.example.myapplication.utils.SecureTokenManager;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class DashboardActivity extends AppCompatActivity {

    private TextView tvDashGreeting;
    private TextView tvDashDriverCode;
    private ImageView ivDashAvatar;
    private View btnMenuProfile;
    private View tabProfile;
    private View btnLogout;
    private View btnMenuEditProfile, btnMenuHistory;
    private View tabHome, tabSchedule, tabHistory;
    private View btnMenu, btnNotification;

    // Lịch phân công views
    private LinearLayout llScheduleContainer;
    private CardView cvEmptySchedule;
    private ProgressBar pbScheduleLoading;
    private TextView btnRefreshSchedule;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        // Bind views header & navigation
        tvDashGreeting     = findViewById(R.id.tv_dash_greeting);
        tvDashDriverCode   = findViewById(R.id.tv_dash_driver_code);
        ivDashAvatar       = findViewById(R.id.iv_dash_avatar);
        btnMenuProfile     = findViewById(R.id.btn_menu_profile);
        tabProfile         = findViewById(R.id.tab_profile);
        btnLogout          = findViewById(R.id.btn_logout);
        btnMenuEditProfile = findViewById(R.id.btn_menu_edit_profile);
        btnMenuHistory     = findViewById(R.id.btn_menu_history);
        tabHome            = findViewById(R.id.tab_home);
        tabSchedule        = findViewById(R.id.tab_schedule);
        tabHistory         = findViewById(R.id.tab_history);
        btnMenu            = findViewById(R.id.btn_menu);
        btnNotification    = findViewById(R.id.btn_notification);

        // Bind views lịch phân công hôm nay
        llScheduleContainer = findViewById(R.id.ll_schedule_container);
        cvEmptySchedule     = findViewById(R.id.cv_empty_schedule);
        pbScheduleLoading   = findViewById(R.id.pb_schedule_loading);
        btnRefreshSchedule  = findViewById(R.id.btn_refresh_schedule);

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
            Glide.with(this)
                    .load(cachedAvatar)
                    .circleCrop()
                    .placeholder(R.drawable.ic_launcher_foreground)
                    .error(R.drawable.ic_launcher_foreground)
                    .into(ivDashAvatar);
        }

        // Tự động fetch profile từ /drivers/me để lấy avatar mới nhất và cập nhật UI
        loadDriverProfile(tokenManager);

        // Tự động tải danh sách phân công hôm nay từ API Backend
        loadTodaySchedule(tokenManager);

        // Nút làm mới lịch phân công
        if (btnRefreshSchedule != null) {
            btnRefreshSchedule.setOnClickListener(v -> loadTodaySchedule(tokenManager));
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

        if (btnMenuEditProfile != null) btnMenuEditProfile.setOnClickListener(placeholder);
        if (btnMenuHistory != null) btnMenuHistory.setOnClickListener(placeholder);
        if (tabSchedule != null) tabSchedule.setOnClickListener(v -> loadTodaySchedule(tokenManager));
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
            Glide.with(this)
                    .load(cachedAvatar)
                    .circleCrop()
                    .placeholder(R.drawable.ic_launcher_foreground)
                    .error(R.drawable.ic_launcher_foreground)
                    .into(ivDashAvatar);
        }
        // Tự động làm mới lịch phân công khi quay lại Dashboard
        loadTodaySchedule(tokenManager);
    }

    /**
     * Tải lịch phân công hôm nay của tài xế qua API Backend
     */
    private void loadTodaySchedule(SecureTokenManager tokenManager) {
        String token = tokenManager.getToken();
        if (token == null || token.isEmpty()) {
            showEmptySchedule();
            return;
        }

        AppRestClient.getInstance().setAuthToken(token);

        if (pbScheduleLoading != null) pbScheduleLoading.setVisibility(View.VISIBLE);
        if (cvEmptySchedule != null) cvEmptySchedule.setVisibility(View.GONE);

        ApiService api = RetrofitClient.getApiService();
        api.getTodayAssignments("Bearer " + token).enqueue(new Callback<AssignmentListResponse>() {
            @Override
            public void onResponse(Call<AssignmentListResponse> call, Response<AssignmentListResponse> response) {
                if (pbScheduleLoading != null) pbScheduleLoading.setVisibility(View.GONE);

                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    List<AssignmentListResponse.AssignmentItem> items = response.body().getData();
                    renderScheduleList(items);
                } else {
                    // Fallback thử gọi qua driverId nếu /assignments/me/today chưa khả dụng
                    String driverId = tokenManager.getDriverId();
                    if (driverId != null && !driverId.isEmpty()) {
                        loadTodayScheduleByDriverId(token, driverId);
                    } else {
                        showEmptySchedule();
                    }
                }
            }

            @Override
            public void onFailure(Call<AssignmentListResponse> call, Throwable t) {
                if (pbScheduleLoading != null) pbScheduleLoading.setVisibility(View.GONE);
                // Fallback thử gọi qua driverId
                String driverId = tokenManager.getDriverId();
                if (driverId != null && !driverId.isEmpty()) {
                    loadTodayScheduleByDriverId(token, driverId);
                } else {
                    showEmptySchedule();
                }
            }
        });
    }

    private void loadTodayScheduleByDriverId(String token, String driverId) {
        ApiService api = RetrofitClient.getApiService();
        api.getTodayAssignmentsByDriver("Bearer " + token, driverId).enqueue(new Callback<AssignmentListResponse>() {
            @Override
            public void onResponse(Call<AssignmentListResponse> call, Response<AssignmentListResponse> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    renderScheduleList(response.body().getData());
                } else {
                    showEmptySchedule();
                }
            }

            @Override
            public void onFailure(Call<AssignmentListResponse> call, Throwable t) {
                showEmptySchedule();
            }
        });
    }

    /**
     * Hiển thị danh sách các thẻ chuyến đi được phân công trong ngày
     */
    private void renderScheduleList(List<AssignmentListResponse.AssignmentItem> items) {
        if (llScheduleContainer == null) return;
        llScheduleContainer.removeAllViews();

        if (items == null || items.isEmpty()) {
            showEmptySchedule();
            return;
        }

        if (cvEmptySchedule != null) cvEmptySchedule.setVisibility(View.GONE);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (AssignmentListResponse.AssignmentItem item : items) {
            View card = inflater.inflate(R.layout.item_assignment_schedule, llScheduleContainer, false);

            TextView tvTime         = card.findViewById(R.id.tv_item_trip_time);
            TextView tvStatus       = card.findViewById(R.id.tv_item_trip_status);
            TextView tvRouteVehicle = card.findViewById(R.id.tv_item_route_vehicle);
            TextView tvStart        = card.findViewById(R.id.tv_item_start_location);
            TextView tvEnd          = card.findViewById(R.id.tv_item_end_location);
            TextView tvDistance     = card.findViewById(R.id.tv_item_distance);
            Button btnStart         = card.findViewById(R.id.btn_item_start_trip);

            // 1. Giờ chạy
            String timeText = formatTripTime(item);
            tvTime.setText("🕒 " + timeText + "  |  ");

            // 2. Trạng thái
            formatTripStatus(tvStatus, item.getStatus());

            // 3. Thông tin Tuyến & Biển số xe
            StringBuilder routeVehicleText = new StringBuilder();
            if (item.getRouteName() != null && !item.getRouteName().isEmpty()) {
                routeVehicleText.append("Tuyến: ").append(item.getRouteName());
            } else if (item.getRouteCode() != null && !item.getRouteCode().isEmpty()) {
                routeVehicleText.append("Mã tuyến: ").append(item.getRouteCode());
            }
            if (item.getVehiclePlate() != null && !item.getVehiclePlate().isEmpty()) {
                if (routeVehicleText.length() > 0) routeVehicleText.append("  |  ");
                routeVehicleText.append("Xe: ").append(item.getVehiclePlate());
            }
            if (routeVehicleText.length() > 0) {
                tvRouteVehicle.setText(routeVehicleText.toString());
                tvRouteVehicle.setVisibility(View.VISIBLE);
            } else {
                tvRouteVehicle.setVisibility(View.GONE);
            }

            // 4. Điểm đi & Điểm đến
            String startLoc = (item.getStartLocation() != null && !item.getStartLocation().isEmpty())
                    ? item.getStartLocation() : "Chưa cập nhật";
            String endLoc = (item.getEndLocation() != null && !item.getEndLocation().isEmpty())
                    ? item.getEndLocation() : "Chưa cập nhật";
            tvStart.setText("Điểm đi: " + startLoc);
            tvEnd.setText("Điểm đến: " + endLoc);

            // 5. Khoảng cách (km)
            if (item.getDistanceKm() != null && item.getDistanceKm() > 0) {
                tvDistance.setText(String.format("Khoảng cách: %.1f km", item.getDistanceKm()));
                tvDistance.setVisibility(View.VISIBLE);
            } else {
                tvDistance.setVisibility(View.GONE);
            }

            // 6. Xử lý nút bấm "Bắt đầu chuyến đi"
            String status = item.getStatus() != null ? item.getStatus().toUpperCase() : "PLANNED";
            if ("COMPLETED".equals(status)) {
                btnStart.setText("Chuyến đi đã hoàn thành");
                btnStart.setEnabled(false);
                btnStart.setBackgroundColor(ContextCompat.getColor(this, R.color.text_secondary));
            } else if ("CANCELLED".equals(status)) {
                btnStart.setText("Chuyến đi đã hủy");
                btnStart.setEnabled(false);
                btnStart.setBackgroundColor(ContextCompat.getColor(this, R.color.text_secondary));
            } else if ("IN_PROGRESS".equals(status)) {
                btnStart.setText("Vào buồng lái giám sát");
                btnStart.setEnabled(true);
                btnStart.setBackgroundResource(R.drawable.bg_btn_start_trip);
                btnStart.setOnClickListener(v -> startTripFlow(item));
            } else {
                btnStart.setText("Bắt đầu chuyến đi");
                btnStart.setEnabled(true);
                btnStart.setBackgroundResource(R.drawable.bg_btn_start_trip);
                btnStart.setOnClickListener(v -> startTripFlow(item));
            }

            llScheduleContainer.addView(card);
        }
    }

    private void showEmptySchedule() {
        if (llScheduleContainer != null) llScheduleContainer.removeAllViews();
        if (cvEmptySchedule != null) cvEmptySchedule.setVisibility(View.VISIBLE);
    }

    /**
     * Chuyển thẳng sang màn hình giám sát buồng lái (DriverMonitorActivity) khi bấm bắt đầu chuyến
     * (Tạm thời bỏ qua bước xác thực khuôn mặt DriverVerifyActivity để test trước tính năng)
     */
    private void startTripFlow(AssignmentListResponse.AssignmentItem item) {
        // Tạm thời comment luồng xác thực:
        // Intent intent = new Intent(DashboardActivity.this, DriverVerifyActivity.class);
        Intent intent = new Intent(DashboardActivity.this, DriverMonitorActivity.class);
        if (item != null) {
            if (item.getTripId() != null) intent.putExtra("TRIP_ID", item.getTripId());
            if (item.getVehicleId() != null) intent.putExtra("VEHICLE_ID", item.getVehicleId());
            if (item.getVehiclePlate() != null) intent.putExtra("VEHICLE_PLATE", item.getVehiclePlate());
        }
        startActivity(intent);
    }

    private String formatTripTime(AssignmentListResponse.AssignmentItem item) {
        String start = item.getPlannedStartTime() != null ? item.getPlannedStartTime() : item.getStartTime();
        String end   = item.getPlannedEndTime() != null ? item.getPlannedEndTime() : item.getEndTime();

        if (start != null && !start.isEmpty()) {
            String startFormatted = extractTime(start);
            if (end != null && !end.isEmpty()) {
                return startFormatted + " - " + extractTime(end);
            }
            return startFormatted;
        }
        if (item.getAssignmentAt() != null && !item.getAssignmentAt().isEmpty()) {
            return extractTime(item.getAssignmentAt());
        }
        return "Trong ngày";
    }

    private String extractTime(String isoDateTime) {
        try {
            if (isoDateTime.contains("T")) {
                String timePart = isoDateTime.substring(isoDateTime.indexOf("T") + 1);
                if (timePart.length() >= 5) {
                    return timePart.substring(0, 5);
                }
                return timePart;
            }
        } catch (Exception ignored) {}
        return isoDateTime;
    }

    private void formatTripStatus(TextView tvStatus, String status) {
        if (status == null) {
            tvStatus.setText("● Sắp chạy");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.blue_dot));
            return;
        }
        switch (status.toUpperCase()) {
            case "IN_PROGRESS":
                tvStatus.setText("● Đang chạy");
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.dash_status_green));
                break;
            case "COMPLETED":
                tvStatus.setText("● Đã hoàn thành");
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                break;
            case "CANCELLED":
                tvStatus.setText("● Đã hủy");
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.error));
                break;
            case "ASSIGNED":
            case "PLANNED":
            default:
                tvStatus.setText("● Sắp chạy");
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.blue_dot));
                break;
        }
    }

    private void loadDriverProfile(SecureTokenManager tokenManager) {
        String token = tokenManager.getToken();
        if (token == null || token.isEmpty()) return;

        ApiService api = RetrofitClient.getApiService();
        api.getDriverMe("Bearer " + token).enqueue(new Callback<DriverMeResponse>() {
            @Override
            public void onResponse(Call<DriverMeResponse> call, Response<DriverMeResponse> response) {
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    DriverMeResponse.DriverData data = response.body().getData();
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
                            String fixedAvatarUrl = AppConfig.formatMediaUrl(rawAvatarUrl);
                            tokenManager.saveAvatarUrl(fixedAvatarUrl);

                            if (!isFinishing() && !isDestroyed() && ivDashAvatar != null) {
                                Glide.with(DashboardActivity.this)
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
            public void onFailure(Call<DriverMeResponse> call, Throwable t) {
                // Ignore network error silently on dashboard background fetch
            }
        });
    }
}
