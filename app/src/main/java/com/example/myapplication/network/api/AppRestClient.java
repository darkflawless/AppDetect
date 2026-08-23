package com.example.myapplication.network.api;

import android.graphics.Bitmap;
import android.util.Log;

import com.example.myapplication.data.model.SomnolenceRecordDto;
import com.example.myapplication.data.model.VehicleLogDto;
import com.example.myapplication.data.model.VerifyResponseDto;
import com.example.myapplication.utils.AppConfig;
import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;

/**
 * AppRestClient - REST API Client chuẩn Enterprise cho AppDetect:
 * Quản lý toàn bộ vòng đời và kết nối HTTP bất đồng bộ thông qua các DTO:
 * 1. POST /api/verify: Xác thực khuôn mặt tài xế
 * 2. POST /vehicle-logs: Gửi định kỳ GPS (VehicleLogDto)
 * 3. POST /violations: Gửi vi phạm kèm ảnh bằng chứng Multipart
 * 4. POST /somnolence-records: Gửi nhật ký buồn ngủ (SomnolenceRecordDto)
 */
public class AppRestClient {

    private static final String TAG = "AppRestClient";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType JPEG_MEDIA_TYPE = MediaType.parse("image/jpeg");

    private static volatile AppRestClient instance;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final ExecutorService networkExecutor;

    public interface ApiCallback<T> {
        void onSuccess(T result);
        void onError(String errorMessage);
    }

    private AppRestClient() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build();

        gson = new Gson();
        networkExecutor = Executors.newFixedThreadPool(3);
    }

    public static synchronized AppRestClient getInstance() {
        if (instance == null) {
            instance = new AppRestClient();
        }
        return instance;
    }

    // ── 1. Xác Thực Tài Xế (POST /api/verify) ─────────────────────────────────
    public void verifyDriver(Bitmap faceBitmap, ApiCallback<VerifyResponseDto> callback) {
        networkExecutor.execute(() -> {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                faceBitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos);
                byte[] jpegBytes = baos.toByteArray();

                RequestBody fileBody = RequestBody.create(jpegBytes, JPEG_MEDIA_TYPE);
                MultipartBody requestBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("image", "driver_verify.jpg", fileBody)
                        .build();

                Request request = new Request.Builder()
                        .url(AppConfig.getFastApiVerifyUrl())
                        .post(requestBody)
                        .build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        Log.e(TAG, "Lỗi kết nối verify: " + e.getMessage());
                        if (callback != null) callback.onError(e.getMessage());
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        try {
                            if (response.isSuccessful() && response.body() != null) {
                                String resJson = response.body().string();
                                VerifyResponseDto dto = gson.fromJson(resJson, VerifyResponseDto.class);
                                if (callback != null) callback.onSuccess(dto);
                            } else {
                                if (callback != null) callback.onError("Server trả mã lỗi: " + response.code());
                            }
                        } catch (Exception e) {
                            if (callback != null) callback.onError("Lỗi parse phản hồi: " + e.getMessage());
                        } finally {
                            response.close();
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Lỗi chuẩn bị ảnh verify: " + e.getMessage());
                if (callback != null) callback.onError(e.getMessage());
            }
        });
    }

    // ── 2. Gửi Tọa Độ GPS Định Kỳ (POST /vehicle-logs) ────────────────────────
    public void sendVehicleLog(VehicleLogDto logDto, Callback callback) {
        networkExecutor.execute(() -> {
            try {
                String jsonStr = gson.toJson(logDto);
                RequestBody body = RequestBody.create(jsonStr, JSON_MEDIA_TYPE);
                Request request = new Request.Builder()
                        .url(AppConfig.getBaseRestUrl() + "/vehicle-logs")
                        .post(body)
                        .build();

                httpClient.newCall(request).enqueue(callback != null ? callback : new DefaultCallback("sendVehicleLog"));
            } catch (Exception e) {
                Log.e(TAG, "Lỗi gửi vehicle-log: " + e.getMessage());
            }
        });
    }

    // ── 3. Gửi Vi Phạm & Bằng Chứng Multipart (POST /violations) ─────────────
    public void sendViolation(long driverId, String violationType, Bitmap evidenceBitmap,
                              double latitude, double longitude, Callback callback) {
        networkExecutor.execute(() -> {
            try {
                MultipartBody.Builder builder = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("driverId", String.valueOf(driverId))
                        .addFormDataPart("violationType", violationType)
                        .addFormDataPart("latitude", String.valueOf(latitude))
                        .addFormDataPart("longitude", String.valueOf(longitude))
                        .addFormDataPart("timestamp", String.valueOf(System.currentTimeMillis()));

                if (evidenceBitmap != null && !evidenceBitmap.isRecycled()) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    evidenceBitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                    byte[] jpegBytes = baos.toByteArray();

                    builder.addFormDataPart(
                            "image",
                            "evidence_" + driverId + "_" + System.currentTimeMillis() + ".jpg",
                            RequestBody.create(jpegBytes, JPEG_MEDIA_TYPE)
                    );
                }

                RequestBody requestBody = builder.build();
                Request request = new Request.Builder()
                        .url(AppConfig.getBaseRestUrl() + "/violations")
                        .post(requestBody)
                        .build();

                httpClient.newCall(request).enqueue(callback != null ? callback : new DefaultCallback("sendViolation"));
            } catch (Exception e) {
                Log.e(TAG, "Lỗi gửi violation multipart: " + e.getMessage());
            }
        });
    }

    // ── 4. Gửi Nhật Ký Buồn Ngủ (POST /somnolence-records) ─────────────────────
    public void sendSomnolenceRecord(SomnolenceRecordDto somnolenceDto, Callback callback) {
        networkExecutor.execute(() -> {
            try {
                String jsonStr = gson.toJson(somnolenceDto);
                RequestBody body = RequestBody.create(jsonStr, JSON_MEDIA_TYPE);
                Request request = new Request.Builder()
                        .url(AppConfig.getBaseRestUrl() + "/somnolence-records")
                        .post(body)
                        .build();

                httpClient.newCall(request).enqueue(callback != null ? callback : new DefaultCallback("sendSomnolenceRecord"));
            } catch (Exception e) {
                Log.e(TAG, "Lỗi gửi somnolence-record: " + e.getMessage());
            }
        });
    }

    private static class DefaultCallback implements Callback {
        private final String apiName;

        public DefaultCallback(String apiName) {
            this.apiName = apiName;
        }

        @Override
        public void onFailure(Call call, IOException e) {
            Log.w(TAG, "❌ API " + apiName + " thất bại: " + e.getMessage());
        }

        @Override
        public void onResponse(Call call, Response response) {
            try {
                if (response.isSuccessful()) {
                    Log.d(TAG, "✅ API " + apiName + " thành công [code=" + response.code() + "]");
                } else {
                    Log.w(TAG, "⚠️ API " + apiName + " trả về lỗi [code=" + response.code() + "]");
                }
            } finally {
                response.close();
            }
        }
    }
}
