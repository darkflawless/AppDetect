package com.example.myapplication.data.model;

import com.google.gson.annotations.SerializedName;

/**
 * SomnolenceRecordDto - DTO gửi nhật ký phân tích mắt & miệng (POST /somnolence-records).
 */
public class SomnolenceRecordDto {

    @SerializedName("driverId")
    private long driverId;

    @SerializedName("ear")
    private float ear;

    @SerializedName("mar")
    private float mar;

    @SerializedName("closedEyeDurationMs")
    private long closedEyeDurationMs;

    @SerializedName("timestamp")
    private long timestamp;

    public SomnolenceRecordDto() {
    }

    public SomnolenceRecordDto(long driverId, float ear, float mar, long closedEyeDurationMs) {
        this.driverId = driverId;
        this.ear = ear;
        this.mar = mar;
        this.closedEyeDurationMs = closedEyeDurationMs;
        this.timestamp = System.currentTimeMillis();
    }

    public long getDriverId() {
        return driverId;
    }

    public void setDriverId(long driverId) {
        this.driverId = driverId;
    }

    public float getEar() {
        return ear;
    }

    public void setEar(float ear) {
        this.ear = ear;
    }

    public float getMar() {
        return mar;
    }

    public void setMar(float mar) {
        this.mar = mar;
    }

    public long getClosedEyeDurationMs() {
        return closedEyeDurationMs;
    }

    public void setClosedEyeDurationMs(long closedEyeDurationMs) {
        this.closedEyeDurationMs = closedEyeDurationMs;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
