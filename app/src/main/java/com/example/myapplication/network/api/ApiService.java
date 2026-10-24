package com.example.myapplication.network.api;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;

import com.example.myapplication.data.model.LoginRequest;
import com.example.myapplication.data.model.LoginResponse;
import com.example.myapplication.data.model.DriverMeResponse;

public interface ApiService {
    @POST("api/v1/auth/login")
    Call<LoginResponse> login(@Body LoginRequest request);

    @GET("drivers/me")
    Call<DriverMeResponse> getDriverMe(@Header("Authorization") String bearerToken);
}
