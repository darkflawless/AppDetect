package com.example.myapplication.data.model;

import com.google.gson.annotations.SerializedName;

import java.math.BigDecimal;

/**
 * AlcoholRecordRequest - DTO gửi kết quả đo nồng độ cồn từ thiết bị BLE (ESP32_Alcohol) lên BE.
 *
 * API endpoint: POST /alcohol-records
 */
public class AlcoholRecordRequest {

    @SerializedName("driverId")
    private String driverId;

    @SerializedName("tripId")
    private String tripId;

    @SerializedName("alcoholLevel")
    private BigDecimal alcoholLevel;

    @SerializedName("measurementTime")
    private String measurementTime;

    @SerializedName("notes")
    private String notes;

    public AlcoholRecordRequest() {}

    public AlcoholRecordRequest(String driverId, String tripId, double alcoholLevelDouble, String measurementTime, String notes) {
        this.driverId = driverId;
        this.tripId = tripId;
        this.alcoholLevel = BigDecimal.valueOf(alcoholLevelDouble);
        this.measurementTime = measurementTime;
        this.notes = notes;
    }

    public String getDriverId() {
        return driverId;
    }

    public void setDriverId(String driverId) {
        this.driverId = driverId;
    }

    public String getTripId() {
        return tripId;
    }

    public void setTripId(String tripId) {
        this.tripId = tripId;
    }

    public BigDecimal getAlcoholLevel() {
        return alcoholLevel;
    }

    public void setAlcoholLevel(BigDecimal alcoholLevel) {
        this.alcoholLevel = alcoholLevel;
    }

    public String getMeasurementTime() {
        return measurementTime;
    }

    public void setMeasurementTime(String measurementTime) {
        this.measurementTime = measurementTime;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
