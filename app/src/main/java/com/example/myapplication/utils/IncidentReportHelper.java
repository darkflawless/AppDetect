package com.example.myapplication.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.location.Location;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.example.myapplication.R;
import com.example.myapplication.data.model.IncidentReportRequest;
import com.example.myapplication.data.model.IncidentReportResponse;
import com.example.myapplication.network.api.ApiService;
import com.example.myapplication.network.api.RetrofitClient;
import com.example.myapplication.service.LocationTrackingService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class IncidentReportHelper {

    private static final String TAG = "IncidentReportHelper";

    private static final String[] TYPE_LABELS = {
            "Hỏng hóc kỹ thuật / Xe chết máy",
            "Va chạm / Tai nạn giao thông",
            "Kẹt xe / Trễ giờ nghiêm trọng",
            "Thời tiết xấu / Ngập lụt đường",
            "Sự cố hành khách / An ninh"
    };

    private static final String[] TYPE_VALUES = {
            "BREAKDOWN",
            "ACCIDENT",
            "DELAY",
            "WEATHER",
            "PASSENGER"
    };

    private static final String[] SEVERITY_LABELS = {
            "Vừa (Trung bình)",
            "Nhẹ (Có thể tự khắc phục)",
            "Nghiêm trọng (Cần hỗ trợ)",
            "Khẩn cấp (Cần cứu hộ ngay)"
    };

    private static final String[] SEVERITY_VALUES = {
            "MEDIUM",
            "LOW",
            "HIGH",
            "CRITICAL"
    };

    public interface OnReportSuccessListener {
        void onSuccess();
    }

    public static void showReportDialog(Activity activity, String tripId, OnReportSuccessListener callback) {
        if (activity == null || activity.isFinishing()) return;

        if (tripId == null || tripId.trim().isEmpty()) {
            Toast.makeText(activity, "Chưa xác định chuyến đi để báo cáo sự cố!", Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_report_incident, null);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        Spinner spType = dialogView.findViewById(R.id.sp_incident_type);
        Spinner spSeverity = dialogView.findViewById(R.id.sp_incident_severity);
        EditText etDescription = dialogView.findViewById(R.id.et_incident_description);
        TextView tvLocation = dialogView.findViewById(R.id.tv_incident_location_info);
        Button btnCancel = dialogView.findViewById(R.id.btn_incident_cancel);
        Button btnSubmit = dialogView.findViewById(R.id.btn_incident_submit);

        // Thiết lập Adapter cho loại sự cố
        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, TYPE_LABELS);
        spType.setAdapter(typeAdapter);

        // Thiết lập Adapter cho mức độ nghiêm trọng
        ArrayAdapter<String> severityAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, SEVERITY_LABELS);
        spSeverity.setAdapter(severityAdapter);

        // Lấy tọa độ GPS thời gian thực
        Location loc = LocationTrackingService.getSafeLocation(activity);
        String locationText = String.format(Locale.US, "📍 Tọa độ GPS: %.5f, %.5f", loc.getLatitude(), loc.getLongitude());
        tvLocation.setText(locationText);

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSubmit.setOnClickListener(v -> {
            int selectedTypeIdx = spType.getSelectedItemPosition();
            int selectedSeverityIdx = spSeverity.getSelectedItemPosition();

            String typeVal = TYPE_VALUES[Math.max(0, Math.min(selectedTypeIdx, TYPE_VALUES.length - 1))];
            String severityVal = SEVERITY_VALUES[Math.max(0, Math.min(selectedSeverityIdx, SEVERITY_VALUES.length - 1))];
            String userDesc = etDescription.getText() != null ? etDescription.getText().toString().trim() : "";

            // Tự động gán vị trí GPS vào phần mô tả
            String fullDescription = (userDesc.isEmpty() ? "" : userDesc + " ")
                    + String.format(Locale.US, "[Vị trí GPS: %.5f, %.5f]", loc.getLatitude(), loc.getLongitude());

            String timeIncident = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(new Date());

            IncidentReportRequest request = new IncidentReportRequest(
                    tripId.trim(),
                    timeIncident,
                    typeVal,
                    severityVal,
                    fullDescription
            );

            SecureTokenManager tokenManager = SecureTokenManager.getInstance(activity.getApplicationContext());
            String token = tokenManager.getToken();
            if (token == null || token.isEmpty()) {
                Toast.makeText(activity, "Phiên đăng nhập đã hết hạn!", Toast.LENGTH_SHORT).show();
                return;
            }

            btnSubmit.setEnabled(false);
            btnSubmit.setText("Đang gửi...");

            ApiService api = RetrofitClient.getApiService();
            api.reportIncident("Bearer " + token, request).enqueue(new Callback<IncidentReportResponse>() {
                @Override
                public void onResponse(Call<IncidentReportResponse> call, Response<IncidentReportResponse> response) {
                    if (response.isSuccessful()) {
                        Toast.makeText(activity, "Đã gửi báo cáo sự cố thành công!", Toast.LENGTH_LONG).show();
                        dialog.dismiss();
                        if (callback != null) {
                            callback.onSuccess();
                        }
                    } else {
                        btnSubmit.setEnabled(true);
                        btnSubmit.setText("Gửi báo cáo");
                        Toast.makeText(activity, "Lỗi gửi báo cáo (Mã " + response.code() + ")", Toast.LENGTH_SHORT).show();
                        Log.w(TAG, "Gửi báo cáo thất bại HTTP: " + response.code());
                    }
                }

                @Override
                public void onFailure(Call<IncidentReportResponse> call, Throwable t) {
                    btnSubmit.setEnabled(true);
                    btnSubmit.setText("Gửi báo cáo");
                    Toast.makeText(activity, "Lỗi kết nối mạng: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Lỗi kết nối API incidents: " + t.getMessage());
                }
            });
        });

        dialog.show();
    }
}

