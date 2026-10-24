package com.example.myapplication.network.api;

import com.example.myapplication.utils.AppConfig;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RetrofitClient {
    private static Retrofit retrofit;

    private RetrofitClient() {}

    private static Retrofit getRetrofitInstance() {
        if (retrofit == null) {
            retrofit = new Retrofit.Builder()
                    .baseUrl(AppConfig.getBaseRestUrl())
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
        }
        return retrofit;
    }

    public static ApiService getApiService() {
        return getRetrofitInstance().create(ApiService.class);
    }
}

