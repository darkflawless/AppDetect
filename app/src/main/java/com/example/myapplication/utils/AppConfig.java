package com.example.myapplication.utils;

import com.example.myapplication.BuildConfig;

/**
 * AppConfig - Quản lý tập trung các hằng số cấu hình IP, Port, Endpoint của hệ thống.
 */
public final class AppConfig {

    private AppConfig() {
        // Prevent instantiation
    }

    // IP của máy chủ Backend được inject tự động từ local.properties hoặc biến môi trường SERVER_IP
    public static final String SERVER_IP = BuildConfig.SERVER_IP;

    // Cổng dịch vụ
    public static final int REST_PORT = 8080;
    public static final int WS_PORT = 8080;
    public static final int UDP_PORT = 9090;
    public static final int FASTAPI_PORT = 8080;
    public static final int MQTT_PORT = 1883;

    // ID mặc định (sẽ được thay bằng dữ liệu sau khi đăng nhập / bắt đầu ca)
    public static final long DEFAULT_DRIVER_ID = 1L;
    public static final String DEFAULT_DRIVER_UUID = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d";
    public static final String DEFAULT_VEHICLE_ID = "1";
    public static final String DEFAULT_VEHICLE_UUID = "f8a12345-0000-0000-0000-000000000001";
    public static final String DEFAULT_SHIFT_ID = "shift-active-001";

    // URLs REST API
    public static String getBaseRestUrl() {
        return "http://" + SERVER_IP + ":" + REST_PORT;
    }

    public static String getMqttBrokerUrl() {
        return "tcp://" + SERVER_IP + ":" + MQTT_PORT;
    }

    public static String getPresignedMediaUrl() {
        return getBaseRestUrl() + "/api/v1/media/presigned";
    }

    public static String getStartShiftUrl() {
        return getBaseRestUrl() + "/api/v1/shifts/start";
    }

    public static String getEndShiftUrl() {
        return getBaseRestUrl() + "/api/v1/shifts/end";
    }

    public static String getWebSocketUrl(long driverId) {
        return "ws://" + SERVER_IP + ":" + WS_PORT + "/ws/realtime?type=APP&driverId=" + driverId;
    }

    public static String getFastApiVerifyUrl() {
        return "http://" + SERVER_IP + ":" + FASTAPI_PORT + "/api/verify";
    }
}
