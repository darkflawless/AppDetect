package com.example.myapplication.data.model;

import com.google.gson.annotations.SerializedName;

/**
 * AlcoholRecordResponse - DTO nhận phản hồi từ BE sau khi gửi kết quả đo nồng độ cồn.
 * Format ApiResponse<AlcoholRecordData>:
 * {
 *   "success": true,
 *   "data": { ... },
 *   "timestamp": "2026-11-21T01:00:05",
 *   "message": "...",
 *   "errorCode": "..."
 * }
 */
public class AlcoholRecordResponse {

    @SerializedName("success")
    private boolean success;

    @SerializedName("message")
    private String message;

    @SerializedName("errorCode")
    private String errorCode;

    @SerializedName("data")
    private AlcoholRecordData data;

    @SerializedName("timestamp")
    private String timestamp;

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public AlcoholRecordData getData() {
        return data;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public static class AlcoholRecordData {
        @SerializedName("id")
        private Object id;

        @SerializedName("driverId")
        private String driverId;

        @SerializedName("driverName")
        private String driverName;

        @SerializedName("alcoholLevel")
        private Double alcoholLevel;

        @SerializedName("measurementTime")
        private String measurementTime;

        @SerializedName("notes")
        private String notes;

        @SerializedName("violation")
        private Boolean violation;

        public Object getId() { return id; }
        public String getDriverId() { return driverId; }
        public String getDriverName() { return driverName; }
        public Double getAlcoholLevel() { return alcoholLevel; }
        public String getMeasurementTime() { return measurementTime; }
        public String getNotes() { return notes; }
        public Boolean isViolation() { return violation != null && violation; }
        public Boolean getViolation() { return violation; }
    }
}
