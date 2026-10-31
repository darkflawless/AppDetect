package com.example.myapplication.data.model;

import com.google.gson.annotations.SerializedName;

/**
 * Wrapper response: { "success": true, "message": "...", "data": { ... } }
 */
public class LoginResponse {

    @SerializedName("success")
    private boolean success;

    @SerializedName("message")
    private String message;

    @SerializedName("data")
    private LoginData data;

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public LoginData getData() { return data; }

    // Convenience getters so LoginActivity doesn't need changes
    public String getAccessToken() {
        return data != null ? data.getAccessToken() : null;
    }
    /** Returns driverId directly from data */
    public String getDriverId() {
        if (data == null) return null;
        if (data.getDriverId() != null && !data.getDriverId().isEmpty()) {
            return data.getDriverId();
        }
        if (data.getStaff() != null && data.getStaff().getId() != null) {
            return data.getStaff().getId();
        }
        return data.getAccountId();
    }

    public static class LoginData {
        @SerializedName("accessToken")
        private String accessToken;

        @SerializedName("tokenType")
        private String tokenType;

        @SerializedName("expiresIn")
        private long expiresIn;

        @SerializedName("accountId")
        private String accountId;

        @SerializedName("driverId")
        private String driverId;

        @SerializedName("username")
        private String username;

        @SerializedName("role")
        private String role;

        @SerializedName("status")
        private String status;

        @SerializedName("staff")
        private Staff staff;

        @SerializedName("assigned_vehicle")
        private AssignedVehicle assignedVehicle;

        public String getAccessToken() { return accessToken; }
        public String getAccountId() { return accountId; }
        public String getDriverId() { return driverId; }
        public String getUsername() { return username; }
        public String getRole() { return role; }
        public String getStatus() { return status; }
        public Staff getStaff() { return staff; }
        public AssignedVehicle getAssignedVehicle() { return assignedVehicle; }
    }

    public static class Staff {
        @SerializedName("id")
        private String id;
        @SerializedName("fullName")
        private String fullName;
        @SerializedName("email")
        private String email;
        @SerializedName("phone")
        private String phone;

        public String getId() { return id; }
        public String getFullName() { return fullName; }
        public String getEmail() { return email; }
        public String getPhone() { return phone; }
    }

    public static class AssignedVehicle {
        @SerializedName("id")
        private String id;
        @SerializedName("model")
        private String model;
        @SerializedName("status")
        private String status;

        public String getId() { return id; }
        public String getModel() { return model; }
        public String getStatus() { return status; }
    }
}
