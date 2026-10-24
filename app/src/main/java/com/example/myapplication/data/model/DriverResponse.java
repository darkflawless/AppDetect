package com.example.myapplication.data.model;

public class DriverResponse {
    private String id;
    private Staff staff;
    private VehicleInfo vehicleInfo;
    private boolean isActive;
    private String urlImage;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Staff getStaff() { return staff; }
    public void setStaff(Staff staff) { this.staff = staff; }
    public VehicleInfo getVehicleInfo() { return vehicleInfo; }
    public void setVehicleInfo(VehicleInfo vehicleInfo) { this.vehicleInfo = vehicleInfo; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public String getUrlImage() { return urlImage; }
    public void setUrlImage(String urlImage) { this.urlImage = urlImage; }

    public static class Staff {
        private String fullName;
        private String email;
        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }

    public static class VehicleInfo {
        private String id;
        private String plateNumber;
        private String status;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getPlateNumber() { return plateNumber; }
        public void setPlateNumber(String plateNumber) { this.plateNumber = plateNumber; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
}
