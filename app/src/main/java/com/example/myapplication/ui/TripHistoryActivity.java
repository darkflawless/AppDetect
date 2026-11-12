package com.example.myapplication.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import com.example.myapplication.R;
import com.example.myapplication.data.model.AssignmentListResponse;
import com.example.myapplication.network.api.ApiService;
import com.example.myapplication.network.api.RetrofitClient;
import com.example.myapplication.utils.SecureTokenManager;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class TripHistoryActivity extends AppCompatActivity {

    private static final String TAG = "TripHistoryActivity";

    private View btnBack;
    private View btnRefresh;
    private TextView filterAll, filterCompleted, filterCancelled;
    private ProgressBar pbLoading;
    private CardView cvEmptyHistory;
    private LinearLayout llHistoryContainer;

    private String currentStatusFilter = null; // null = tất cả (COMPLETED + CANCELLED)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trip_history);

        // Bind views
        btnBack            = findViewById(R.id.btn_back_history);
        btnRefresh         = findViewById(R.id.btn_refresh_history);
        filterAll          = findViewById(R.id.filter_all);
        filterCompleted    = findViewById(R.id.filter_completed);
        filterCancelled    = findViewById(R.id.filter_cancelled);
        pbLoading          = findViewById(R.id.pb_history_loading);
        cvEmptyHistory     = findViewById(R.id.cv_empty_history);
        llHistoryContainer = findViewById(R.id.ll_history_container);

        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> loadHistory());
        }

        setupFilters();

        // Tải dữ liệu ban đầu
        loadHistory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHistory();
    }

    private void setupFilters() {
        if (filterAll != null) {
            filterAll.setOnClickListener(v -> {
                currentStatusFilter = null;
                updateFilterUi(filterAll);
                loadHistory();
            });
        }

        if (filterCompleted != null) {
            filterCompleted.setOnClickListener(v -> {
                currentStatusFilter = "COMPLETED";
                updateFilterUi(filterCompleted);
                loadHistory();
            });
        }

        if (filterCancelled != null) {
            filterCancelled.setOnClickListener(v -> {
                currentStatusFilter = "CANCELLED";
                updateFilterUi(filterCancelled);
                loadHistory();
            });
        }
    }

    private void updateFilterUi(TextView activeFilter) {
        TextView[] allFilters = {filterAll, filterCompleted, filterCancelled};
        for (TextView f : allFilters) {
            if (f == null) continue;
            if (f == activeFilter) {
                f.setBackgroundResource(R.drawable.bg_btn_start_trip);
                f.setTextColor(ContextCompat.getColor(this, R.color.white));
            } else {
                f.setBackgroundResource(R.drawable.bg_card_bordered);
                f.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            }
        }
    }

    private void loadHistory() {
        SecureTokenManager tokenManager = SecureTokenManager.getInstance(getApplicationContext());
        String token = tokenManager.getToken();
        if (token == null || token.isEmpty()) {
            Toast.makeText(this, "Chưa có phiên đăng nhập", Toast.LENGTH_SHORT).show();
            showEmpty();
            return;
        }

        if (pbLoading != null) pbLoading.setVisibility(View.VISIBLE);
        if (cvEmptyHistory != null) cvEmptyHistory.setVisibility(View.GONE);
        if (llHistoryContainer != null) llHistoryContainer.removeAllViews();

        Log.i(TAG, "Đang tải lịch sử chuyến đi: status=" + currentStatusFilter);
        ApiService api = RetrofitClient.getApiService();
        api.getTripHistory("Bearer " + token, currentStatusFilter).enqueue(new Callback<AssignmentListResponse>() {
            @Override
            public void onResponse(Call<AssignmentListResponse> call, Response<AssignmentListResponse> response) {
                if (pbLoading != null) pbLoading.setVisibility(View.GONE);

                Log.i(TAG, "Kết quả /assignments/me/history: HTTP " + response.code());
                if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                    List<AssignmentListResponse.AssignmentItem> items = response.body().getData();
                    renderList(items);
                } else {
                    // Fallback thử gọi theo driverId
                    String driverId = tokenManager.getDriverId();
                    if (driverId != null && !driverId.isEmpty()) {
                        loadHistoryByDriverId(token, driverId);
                    } else {
                        showEmpty();
                    }
                }
            }

            @Override
            public void onFailure(Call<AssignmentListResponse> call, Throwable t) {
                if (pbLoading != null) pbLoading.setVisibility(View.GONE);
                Log.e(TAG, "Lỗi mạng hoặc parse JSON khi lấy lịch sử: " + t.getMessage(), t);
                String driverId = tokenManager.getDriverId();
                if (driverId != null && !driverId.isEmpty()) {
                    loadHistoryByDriverId(token, driverId);
                } else {
                    showEmpty();
                }
            }
        });
    }

    private void loadHistoryByDriverId(String token, String driverId) {
        ApiService api = RetrofitClient.getApiService();
        api.getTripHistoryByDriver("Bearer " + token, driverId, currentStatusFilter)
                .enqueue(new Callback<AssignmentListResponse>() {
                    @Override
                    public void onResponse(Call<AssignmentListResponse> call, Response<AssignmentListResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().isSuccess()) {
                            renderList(response.body().getData());
                        } else {
                            showEmpty();
                        }
                    }

                    @Override
                    public void onFailure(Call<AssignmentListResponse> call, Throwable t) {
                        showEmpty();
                    }
                });
    }

    private void renderList(List<AssignmentListResponse.AssignmentItem> items) {
        if (llHistoryContainer == null) return;
        llHistoryContainer.removeAllViews();

        if (items == null || items.isEmpty()) {
            showEmpty();
            return;
        }

        if (cvEmptyHistory != null) cvEmptyHistory.setVisibility(View.GONE);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (AssignmentListResponse.AssignmentItem item : items) {
            View card = inflater.inflate(R.layout.item_assignment_schedule, llHistoryContainer, false);

            TextView tvTime         = card.findViewById(R.id.tv_item_trip_time);
            TextView tvStatus       = card.findViewById(R.id.tv_item_trip_status);
            TextView tvRouteVehicle = card.findViewById(R.id.tv_item_route_vehicle);
            TextView tvStart        = card.findViewById(R.id.tv_item_start_location);
            TextView tvEnd          = card.findViewById(R.id.tv_item_end_location);
            TextView tvDistance     = card.findViewById(R.id.tv_item_distance);
            Button btnAction        = card.findViewById(R.id.btn_item_start_trip);

            // 1. Giờ & Ngày
            String dateFormatted = formatDateTime(item);
            tvTime.setText("🕒 " + dateFormatted + "  |  ");

            // 2. Trạng thái
            formatTripStatus(tvStatus, item.getStatus());

            // 3. Tuyến & Biển số xe
            StringBuilder routeVehicle = new StringBuilder();
            if (item.getRouteName() != null && !item.getRouteName().isEmpty()) {
                routeVehicle.append("Tuyến: ").append(item.getRouteName());
            } else if (item.getRouteCode() != null && !item.getRouteCode().isEmpty()) {
                routeVehicle.append("Mã tuyến: ").append(item.getRouteCode());
            }
            if (item.getVehiclePlate() != null && !item.getVehiclePlate().isEmpty()) {
                if (routeVehicle.length() > 0) routeVehicle.append("  |  ");
                routeVehicle.append("Xe: ").append(item.getVehiclePlate());
            }
            if (routeVehicle.length() > 0) {
                tvRouteVehicle.setText(routeVehicle.toString());
                tvRouteVehicle.setVisibility(View.VISIBLE);
            } else {
                tvRouteVehicle.setVisibility(View.GONE);
            }

            // 4. Điểm đi & Điểm đến
            tvStart.setText("Điểm đi: " + (item.getStartLocation() != null ? item.getStartLocation() : "Chưa cập nhật"));
            tvEnd.setText("Điểm đến: " + (item.getEndLocation() != null ? item.getEndLocation() : "Chưa cập nhật"));

            // 5. Khoảng cách (km)
            if (item.getDistanceKm() != null && item.getDistanceKm() > 0) {
                tvDistance.setText(String.format("Khoảng cách: %.1f km", item.getDistanceKm()));
                tvDistance.setVisibility(View.VISIBLE);
            } else {
                tvDistance.setVisibility(View.GONE);
            }

            // 6. Nút trạng thái
            String status = item.getStatus() != null ? item.getStatus().toUpperCase() : "";
            if ("COMPLETED".equals(status)) {
                btnAction.setText("Chuyến đi đã hoàn thành");
                btnAction.setEnabled(false);
                btnAction.setBackgroundColor(ContextCompat.getColor(this, R.color.text_secondary));
            } else if ("CANCELLED".equals(status)) {
                btnAction.setText("Chuyến đi đã hủy");
                btnAction.setEnabled(false);
                btnAction.setBackgroundColor(ContextCompat.getColor(this, R.color.text_secondary));
            } else if ("IN_PROGRESS".equals(status)) {
                btnAction.setText("Tiếp tục chuyến đi");
                btnAction.setEnabled(true);
                btnAction.setBackgroundResource(R.drawable.bg_btn_start_trip);
                btnAction.setOnClickListener(v -> startTripFlow(item));
            } else {
                btnAction.setVisibility(View.GONE);
            }

            llHistoryContainer.addView(card);
        }
    }

    private void startTripFlow(AssignmentListResponse.AssignmentItem item) {
        Intent intent = new Intent(TripHistoryActivity.this, DriverMonitorActivity.class);
        if (item != null) {
            if (item.getTripId() != null) intent.putExtra("TRIP_ID", item.getTripId());
            if (item.getVehicleId() != null) intent.putExtra("VEHICLE_ID", item.getVehicleId());
            if (item.getVehiclePlate() != null) intent.putExtra("VEHICLE_PLATE", item.getVehiclePlate());
        }
        startActivity(intent);
    }

    private void showEmpty() {
        if (llHistoryContainer != null) llHistoryContainer.removeAllViews();
        if (cvEmptyHistory != null) cvEmptyHistory.setVisibility(View.VISIBLE);
    }

    private String formatDateTime(AssignmentListResponse.AssignmentItem item) {
        String dt = item.getStartTime() != null ? item.getStartTime()
                : item.getPlannedStartTime() != null ? item.getPlannedStartTime()
                : item.getAssignmentAt();

        if (dt == null || dt.isEmpty()) return "Không xác định";

        try {
            if (dt.contains("T")) {
                String[] parts = dt.split("T");
                String datePart = parts[0];
                String timePart = parts[1].length() >= 5 ? parts[1].substring(0, 5) : parts[1];
                return timePart + " " + datePart;
            }
        } catch (Exception ignored) {}
        return dt;
    }

    private void formatTripStatus(TextView tvStatus, String status) {
        if (status == null) {
            tvStatus.setText("● Không rõ");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            return;
        }
        switch (status.toUpperCase()) {
            case "COMPLETED":
                tvStatus.setText("● Hoàn thành");
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.dash_status_green));
                break;
            case "IN_PROGRESS":
                tvStatus.setText("● Đang chạy");
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.blue_dot));
                break;
            case "CANCELLED":
                tvStatus.setText("● Đã hủy");
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.error));
                break;
            case "ASSIGNED":
                tvStatus.setText("● Đã phân công");
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.blue_dot));
                break;
            case "PLANNED":
            default:
                tvStatus.setText("● Sắp chạy");
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.blue_dot));
                break;
        }
    }
}

