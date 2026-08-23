package com.example.myapplication.data.model;

import com.google.gson.annotations.SerializedName;

/**
 * VerifyResponseDto - DTO nhận phản hồi từ API xác thực tài xế (POST /api/verify).
 */
public class VerifyResponseDto {

    @SerializedName("verified")
    private boolean verified;

    @SerializedName("driver_id")
    private String driverId;

    @SerializedName("similarity")
    private float similarity;

    @SerializedName("message")
    private String message;

    public VerifyResponseDto() {
    }

    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }

    public String getDriverId() {
        return driverId;
    }

    public void setDriverId(String driverId) {
        this.driverId = driverId;
    }

    public float getSimilarity() {
        return similarity;
    }

    public void setSimilarity(float similarity) {
        this.similarity = similarity;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
