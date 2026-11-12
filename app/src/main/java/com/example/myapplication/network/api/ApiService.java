package com.example.myapplication.network.api;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;

import com.example.myapplication.data.model.LoginRequest;
import com.example.myapplication.data.model.LoginResponse;
import com.example.myapplication.data.model.DriverMeResponse;
import com.example.myapplication.data.model.AssignmentListResponse;
import com.example.myapplication.data.model.IncidentReportRequest;
import com.example.myapplication.data.model.IncidentReportResponse;
import com.example.myapplication.data.model.TripActionResponse;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface ApiService {
    @POST("api/v1/auth/login")
    Call<LoginResponse> login(@Body LoginRequest request);

    @GET("drivers/me")
    Call<DriverMeResponse> getDriverMe(@Header("Authorization") String bearerToken);

    @GET("assignments/me/today")
    Call<AssignmentListResponse> getTodayAssignments(@Header("Authorization") String bearerToken);

    @GET("assignments/driver/{driverId}/today")
    Call<AssignmentListResponse> getTodayAssignmentsByDriver(
            @Header("Authorization") String bearerToken,
            @Path("driverId") String driverId);

    @GET("assignments/me/history")
    Call<AssignmentListResponse> getTripHistory(
            @Header("Authorization") String bearerToken,
            @Query("status") String status);

    @GET("assignments/driver/{driverId}/history")
    Call<AssignmentListResponse> getTripHistoryByDriver(
            @Header("Authorization") String bearerToken,
            @Path("driverId") String driverId,
            @Query("status") String status);

    @POST("trips/{id}/start")
    Call<TripActionResponse> startTrip(
            @Header("Authorization") String bearerToken,
            @Path("id") String tripId);

    @POST("trips/{id}/end")
    Call<TripActionResponse> endTrip(
            @Header("Authorization") String bearerToken,
            @Path("id") String tripId,
            @Query("distanceKm") Double distanceKm);

    @POST("incidents")
    Call<IncidentReportResponse> reportIncident(
            @Header("Authorization") String bearerToken,
            @Body IncidentReportRequest request);
}
