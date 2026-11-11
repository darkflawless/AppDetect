package com.example.myapplication.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

public class SecureTokenManager {
    private static final String TAG = "SecureTokenManager";
    private static final String PREF_FILE_NAME = "token_prefs";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_DRIVER_ID = "driver_id";
    private static final String KEY_FULL_NAME = "full_name";
    private static final String KEY_AVATAR_URL = "avatar_url";
    private static SecureTokenManager instance;
    private final SharedPreferences prefs;

    private SecureTokenManager(Context context) {
        prefs = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE);
        Log.i(TAG, "TokenManager khởi tạo thành công");
    }

    public static synchronized SecureTokenManager getInstance(Context context) {
        if (instance == null) {
            instance = new SecureTokenManager(context.getApplicationContext());
        }
        return instance;
    }

    public void saveToken(String token, String driverId) {
        prefs.edit()
                .putString(KEY_ACCESS_TOKEN, token)
                .putString(KEY_DRIVER_ID, driverId)
                .apply();
        Log.i(TAG, "Đã lưu token (độ dài: " + (token != null ? token.length() : 0) + ")");
    }

    public void saveFullName(String fullName) {
        prefs.edit().putString(KEY_FULL_NAME, fullName).apply();
    }

    public String getFullName() {
        return prefs.getString(KEY_FULL_NAME, null);
    }

    public void saveAvatarUrl(String avatarUrl) {
        prefs.edit().putString(KEY_AVATAR_URL, avatarUrl).apply();
    }

    public String getAvatarUrl() {
        return prefs.getString(KEY_AVATAR_URL, null);
    }

    public String getToken() {
        String token = prefs.getString(KEY_ACCESS_TOKEN, null);
        Log.i(TAG, "getToken: " + (token != null ? "có token (độ dài: " + token.length() + ")" : "null"));
        return token;
    }

    public String getDriverId() {
        return prefs.getString(KEY_DRIVER_ID, null);
    }

    public void clear() {
        prefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_DRIVER_ID)
                .remove(KEY_FULL_NAME)
                .remove(KEY_AVATAR_URL)
                .apply();
        instance = null;
        Log.i(TAG, "Đã xóa toàn bộ token");
    }
}
