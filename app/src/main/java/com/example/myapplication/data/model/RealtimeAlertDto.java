package com.example.myapplication.data.model;

import com.google.gson.annotations.SerializedName;

/**
 * RealtimeAlertDto - DTO gửi thông báo cảnh báo tức thời qua WebSocket Hub.
 */
public class RealtimeAlertDto {

    @SerializedName("type")
    private String type = "REALTIME_ALERT";

    @SerializedName("driverId")
    private long driverId;

    @SerializedName("alertType")
    private String alertType;

    @SerializedName("message")
    private String message;

    @SerializedName("timestamp")
    private long timestamp;

    public RealtimeAlertDto() {
    }

    public RealtimeAlertDto(long driverId, String alertType, String message) {
        this.type = "REALTIME_ALERT";
        this.driverId = driverId;
        this.alertType = alertType;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public long getDriverId() {
        return driverId;
    }

    public void setDriverId(long driverId) {
        this.driverId = driverId;
    }

    public String getAlertType() {
        return alertType;
    }

    public void setAlertType(String alertType) {
        this.alertType = alertType;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
