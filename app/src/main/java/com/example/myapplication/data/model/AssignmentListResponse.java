package com.example.myapplication.data.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class AssignmentListResponse {

    @SerializedName("success")
    private boolean success;

    @SerializedName("message")
    private String message;

    @SerializedName("data")
    private List<AssignmentItem> data;

    @SerializedName("timestamp")
    private String timestamp;

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public List<AssignmentItem> getData() {
        return data;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public static class AssignmentItem {
        @SerializedName("id")
        private String id;

        @SerializedName("tripId")
        private String tripId;

        @SerializedName("driverId")
        private String driverId;

        @SerializedName("driverName")
        private String driverName;

        @SerializedName("vehiclePlate")
        private String vehiclePlate;

        @SerializedName("vehicleId")
        private String vehicleId;

        @SerializedName("routeCode")
        private String routeCode;

        @SerializedName("routeName")
        private String routeName;

        @SerializedName("startLocation")
        private String startLocation;

        @SerializedName("endLocation")
        private String endLocation;

        @SerializedName("distanceKm")
        private Double distanceKm;

        @SerializedName("status")
        private String status;

        @SerializedName("plannedStartTime")
        private String plannedStartTime;

        @SerializedName("plannedEndTime")
        private String plannedEndTime;

        @SerializedName("startTime")
        private String startTime;

        @SerializedName("endTime")
        private String endTime;

        @SerializedName("note")
        private String note;

        @SerializedName("assignmentAt")
        private String assignmentAt;

        public String getId() { return id; }
        public String getTripId() { return tripId; }
        public String getDriverId() { return driverId; }
        public String getDriverName() { return driverName; }
        public String getVehiclePlate() { return vehiclePlate; }
        public String getVehicleId() { return vehicleId; }
        public String getRouteCode() { return routeCode; }
        public String getRouteName() { return routeName; }
        public String getStartLocation() { return startLocation; }
        public String getEndLocation() { return endLocation; }
        public Double getDistanceKm() { return distanceKm; }
        public String getStatus() { return status; }
        public String getPlannedStartTime() { return plannedStartTime; }
        public String getPlannedEndTime() { return plannedEndTime; }
        public String getStartTime() { return startTime; }
        public String getEndTime() { return endTime; }
        public String getNote() { return note; }
        public String getAssignmentAt() { return assignmentAt; }
    }
}
