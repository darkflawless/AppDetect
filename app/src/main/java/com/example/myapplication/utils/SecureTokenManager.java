package com.example.myapplication.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

public class SecureTokenManager {
    private static final String TAG = "SecureTokenManager";
    private static final String PREF_FILE_NAME = "secure_token_prefs";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_DRIVER_ID = "driver_id";
    private static final String KEY_FULL_NAME = "full_name";
    private static SecureTokenManager instance;
    private SharedPreferences encryptedPreferences;

    private SecureTokenManager(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            encryptedPreferences = EncryptedSharedPreferences.create(
                    context,
                    PREF_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            Log.e(TAG, "Error initializing EncryptedSharedPreferences", e);
        }
    }

    public static synchronized SecureTokenManager getInstance(Context context) {
        if (instance == null) {
            instance = new SecureTokenManager(context.getApplicationContext());
        }
        return instance;
    }

    public void saveToken(String token, String driverId) {
        if (encryptedPreferences != null) {
            encryptedPreferences.edit()
                    .putString(KEY_ACCESS_TOKEN, token)
                    .putString(KEY_DRIVER_ID, driverId)
                    .apply();
        }
    }

    public void saveFullName(String fullName) {
        if (encryptedPreferences != null) {
            encryptedPreferences.edit()
                    .putString(KEY_FULL_NAME, fullName)
                    .apply();
        }
    }

    public String getFullName() {
        return encryptedPreferences != null ? encryptedPreferences.getString(KEY_FULL_NAME, null) : null;
    }

    public String getToken() {
        return encryptedPreferences != null ? encryptedPreferences.getString(KEY_ACCESS_TOKEN, null) : null;
    }

    public String getDriverId() {
        return encryptedPreferences != null ? encryptedPreferences.getString(KEY_DRIVER_ID, null) : null;
    }

    public void clear() {
        if (encryptedPreferences != null) {
            encryptedPreferences.edit().clear().apply();
        }
    }
}
