package com.example.myapplication.data.model;

import com.google.gson.annotations.SerializedName;

/**
 * ViolationResponseDto - DTO nhận phản hồi từ API ghi nhận vi phạm (POST /violations).
 */
public class ViolationResponseDto {

    @SerializedName("status")
    private String status;

    @SerializedName("violationId")
    private int violationId;

    @SerializedName("message")
    private String message;

    @SerializedName("imageUrl")
    private String imageUrl;

    public ViolationResponseDto() {
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getViolationId() {
        return violationId;
    }

    public void setViolationId(int violationId) {
        this.violationId = violationId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }
}
