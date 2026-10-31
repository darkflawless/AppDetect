package com.example.myapplication.data.model;

import com.google.gson.annotations.SerializedName;

public class DriverMeResponse {
    @SerializedName("success")
    private boolean success;

    @SerializedName("message")
    private String message;

    @SerializedName("data")
    private DriverData data;

    @SerializedName("timestamp")
    private String timestamp;

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public DriverData getData() { return data; }
    public String getTimestamp() { return timestamp; }

    public static class DriverData {
        @SerializedName("id")
        private String id;

        @SerializedName("hireDate")
        private String hireDate;

        @SerializedName("urlImage")
        private String urlImage;

        @SerializedName("isActive")
        private boolean isActive;

        @SerializedName("staff")
        private Staff staff;

        public String getId() { return id; }
        public String getHireDate() { return hireDate; }
        public String getUrlImage() { return urlImage; }
        public boolean isActive() { return isActive; }
        public Staff getStaff() { return staff; }
    }

    public static class Staff {
        @SerializedName("id")
        private String id;

        @SerializedName("firstName")
        private String firstName;

        @SerializedName("lastName")
        private String lastName;

        @SerializedName("fullName")
        private String fullName;

        @SerializedName("dateOfBirth")
        private String dateOfBirth;

        @SerializedName("gender")
        private String gender;

        @SerializedName("email")
        private String email;

        @SerializedName("phone")
        private String phone;

        @SerializedName("exactAddress")
        private String exactAddress;

        @SerializedName("commune")
        private String commune;

        @SerializedName("province")
        private String province;

        @SerializedName("createdAt")
        private String createdAt;

        public String getId() { return id; }
        public String getFirstName() { return firstName; }
        public String getLastName() { return lastName; }
        public String getFullName() { return fullName; }
        public String getDateOfBirth() { return dateOfBirth; }
        public String getGender() { return gender; }
        public String getEmail() { return email; }
        public String getPhone() { return phone; }
        public String getExactAddress() { return exactAddress; }
        public String getCommune() { return commune; }
        public String getProvince() { return province; }
        public String getCreatedAt() { return createdAt; }

        public String getFullAddress() {
            StringBuilder sb = new StringBuilder();
            if (exactAddress != null && !exactAddress.isEmpty()) sb.append(exactAddress);
            if (commune != null && !commune.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(commune);
            }
            if (province != null && !province.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(province);
            }
            return sb.toString();
        }
    }
}
