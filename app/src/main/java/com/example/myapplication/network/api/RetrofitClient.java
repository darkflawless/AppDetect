package com.example.myapplication.network.api;

import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RetrofitClient {
    private static final String BASE_URL = " https://your.api.base.url/\; // TODO: replace with actual base URL
 private static Retrofit retrofit;

 private RetrofitClient() {}

 private static Retrofit getRetrofitInstance() {
 if (retrofit == null) {
 retrofit = new Retrofit.Builder()
 .baseUrl(BASE_URL)
 .addConverterFactory(GsonConverterFactory.create())
 .build();
 }
 return retrofit;
 }

 public static ApiService getApiService() {
 return getRetrofitInstance().create(ApiService.class);
 }
}
