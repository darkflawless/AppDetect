package com.example.myapplication.data.model;

import com.google.gson.annotations.SerializedName;

public class IncidentReportRequest {

    @SerializedName("tripId")
    private String tripId;

    @SerializedName("timeIncident")
    private String timeIncident;

    @SerializedName("type")
    private String type;

    @SerializedName("severity")
    private String severity;

    @SerializedName("description")
    private String description;

    public IncidentReportRequest() {
    }

    public IncidentReportRequest(String tripId, String timeIncident, String type, String severity, String description) {
        this.tripId = tripId;
        this.timeIncident = timeIncident;
        this.type = type;
        this.severity = severity;
        this.description = description;
    }

    public String getTripId() {
        return tripId;
    }

    public void setTripId(String tripId) {
        this.tripId = tripId;
    }

    public String getTimeIncident() {
        return timeIncident;
    }

    public void setTimeIncident(String timeIncident) {
        this.timeIncident = timeIncident;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
