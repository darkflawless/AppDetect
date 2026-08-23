package com.example.myapplication.data.model;

import com.google.gson.annotations.SerializedName;

/**
 * VehicleLogDto - DTO gửi tọa độ GPS và tốc độ xe lên Server (POST /vehicle-logs).
 */
public class VehicleLogDto {

    @SerializedName("driverId")
    private long driverId;

    @SerializedName("vehicleId")
    private String vehicleId;

    @SerializedName("latitude")
    private double latitude;

    @SerializedName("longitude")
    private double longitude;

    @SerializedName("speed")
    private float speed;

    @SerializedName("timestamp")
    private long timestamp;

    public VehicleLogDto() {
    }

    public VehicleLogDto(long driverId, String vehicleId, double latitude, double longitude, float speed) {
        this.driverId = driverId;
        this.vehicleId = vehicleId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.speed = speed;
        this.timestamp = System.currentTimeMillis();
    }

    public long getDriverId() {
        return driverId;
    }

    public void setDriverId(long driverId) {
        this.driverId = driverId;
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(String vehicleId) {
        this.vehicleId = vehicleId;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public float getSpeed() {
        return speed;
    }

    public void setSpeed(float speed) {
        this.speed = speed;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
