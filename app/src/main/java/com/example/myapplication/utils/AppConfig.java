package com.example.myapplication.utils;

import com.example.myapplication.BuildConfig;

/**
 * AppConfig - Quản lý tập trung các hằng số cấu hình IP, Port, Endpoint của hệ thống.
 */
public final class AppConfig {

    private AppConfig() {
        // Prevent instantiation
    }

    // IP và các Port dịch vụ được inject tự động từ gradle.properties thông qua BuildConfig
    public static final String SERVER_IP = BuildConfig.SERVER_IP;
    public static final int REST_PORT = BuildConfig.REST_PORT;
    public static final int WS_PORT = BuildConfig.WS_PORT;
    public static final int UDP_PORT = BuildConfig.UDP_PORT;
    public static final int MQTT_PORT = BuildConfig.MQTT_PORT;
    public static final int MEDIA_PORT = BuildConfig.MEDIA_PORT;

    // ID mặc định (sẽ được thay bằng dữ liệu sau khi đăng nhập / bắt đầu ca)
    public static final long DEFAULT_DRIVER_ID = 1L;
    public static final String DEFAULT_DRIVER_UUID = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d";
    public static final String DEFAULT_VEHICLE_ID = "1";
    public static final String DEFAULT_VEHICLE_UUID = "f8a12345-0000-0000-0000-000000000001";
    public static final String DEFAULT_SHIFT_ID = "shift-active-001";

    // URLs REST API
    public static String getBaseRestUrl() {
        return "http://" + SERVER_IP + ":" + REST_PORT + "/";
    }

    public static String getMqttBrokerUrl() {
        return "tcp://" + SERVER_IP + ":" + MQTT_PORT;
    }

    public static String getPresignedMediaUrl() {
        return getBaseRestUrl() + "api/v1/media/presigned";
    }

    public static String getStartShiftUrl() {
        return getBaseRestUrl() + "api/v1/shifts/start";
    }

    public static String getEndShiftUrl() {
        return getBaseRestUrl() + "api/v1/shifts/end";
    }

    public static String getWebSocketUrl(long driverId) {
        return "ws://" + SERVER_IP + ":" + WS_PORT + "/ws/realtime?type=APP&driverId=" + driverId;
    }

    public static String getFastApiVerifyUrl() {
        return "http://" + SERVER_IP + ":" + REST_PORT + "/api/verify";
    }

    /**
     * Chuyển đổi URL ảnh media nếu backend trả về localhost hoặc 127.0.0.1
     * thành IP và Port thực tế được cấu hình trong gradle.properties
     */
    public static String formatMediaUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) return rawUrl;
        return rawUrl
                .replace("localhost", SERVER_IP)
                .replace("127.0.0.1", SERVER_IP);
    }
}
