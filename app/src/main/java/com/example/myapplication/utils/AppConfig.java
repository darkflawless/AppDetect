package com.example.myapplication.utils;

/**
 * AppConfig - Quản lý tập trung các hằng số cấu hình IP, Port, Endpoint của hệ thống.
 */
public final class AppConfig {

    private AppConfig() {
        // Prevent instantiation
    }

    // IP của máy chủ Backend (Spring Boot & Python Server)
    public static final String SERVER_IP = "10.32.29.15";

    // Cổng dịch vụ
    public static final int REST_PORT = 8080;
    public static final int WS_PORT = 8080;
    public static final int UDP_PORT = 9090;
    public static final int FASTAPI_PORT = 8080;

    // ID mặc định (sẽ được thay bằng dữ liệu sau khi đăng nhập)
    public static final long DEFAULT_DRIVER_ID = 1L;
    public static final String DEFAULT_VEHICLE_ID = "1";

    // URLs
    public static String getBaseRestUrl() {
        return "http://" + SERVER_IP + ":" + REST_PORT;
    }

    public static String getWebSocketUrl(long driverId) {
        return "ws://" + SERVER_IP + ":" + WS_PORT + "/ws/realtime?type=APP&driverId=" + driverId;
    }

    public static String getFastApiVerifyUrl() {
        return "http://" + SERVER_IP + ":" + FASTAPI_PORT + "/api/verify";
    }
}
