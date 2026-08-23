package com.example.myapplication.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.myapplication.R;
import com.example.myapplication.data.model.VehicleLogDto;
import com.example.myapplication.network.api.AppRestClient;
import com.example.myapplication.utils.AppConfig;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

/**
 * LocationTrackingService - Foreground Service chạy nền liên tục để thu thập GPS xe
 * và gửi về Server (POST /vehicle-logs) mỗi 5 giây.
 */
public class LocationTrackingService extends Service {

    private static final String TAG = "LocationTrackingService";
    private static final String CHANNEL_ID = "appdetect_gps_channel";
    private static final int NOTIFICATION_ID = 1001;

    private static final long GPS_INTERVAL_MS = 5000L; // Cứ 5 giây cập nhật vị trí 1 lần
    private static final long GPS_FASTEST_INTERVAL_MS = 3000L;

    public static final String ACTION_START = "ACTION_START_LOCATION_SERVICE";
    public static final String ACTION_STOP = "ACTION_STOP_LOCATION_SERVICE";

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private boolean isTracking = false;

    public static void start(Context context) {
        Intent intent = new Intent(context, LocationTrackingService.class);
        intent.setAction(ACTION_START);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, LocationTrackingService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                for (Location location : locationResult.getLocations()) {
                    onNewLocation(location);
                }
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_START.equals(action)) {
                startForegroundTracking();
            } else if (ACTION_STOP.equals(action)) {
                stopForegroundTracking();
            }
        }
        return START_STICKY;
    }

    private void startForegroundTracking() {
        if (isTracking) return;
        isTracking = true;

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AppDetect - Giám sát hành trình")
                .setContentText("Đang định vị GPS và truyền dữ liệu thời gian thực...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);
        startLocationUpdates();
        Log.i(TAG, "🟢 Foreground Location Tracking Service đã khởi động");
    }

    private void stopForegroundTracking() {
        if (!isTracking) return;
        isTracking = false;

        stopLocationUpdates();
        stopForeground(true);
        stopSelf();
        Log.i(TAG, "🔴 Foreground Location Tracking Service đã dừng");
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, GPS_INTERVAL_MS)
                .setMinUpdateIntervalMillis(GPS_FASTEST_INTERVAL_MS)
                .setWaitForAccurateLocation(false)
                .build();

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        } catch (Exception e) {
            Log.e(TAG, "Lỗi request location updates: " + e.getMessage());
        }
    }

    private void stopLocationUpdates() {
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }

    private void onNewLocation(Location location) {
        double lat = location.getLatitude();
        double lng = location.getLongitude();
        float speed = location.getSpeed(); // m/s
        Log.d(TAG, String.format("📍 GPS: Lat=%.6f, Lng=%.6f, Speed=%.1f km/h", lat, lng, speed * 3.6f));

        // Gửi REST API POST /vehicle-logs lên Server qua DTO
        VehicleLogDto dto = new VehicleLogDto(
                AppConfig.DEFAULT_DRIVER_ID,
                AppConfig.DEFAULT_VEHICLE_ID,
                lat,
                lng,
                speed
        );
        AppRestClient.getInstance().sendVehicleLog(dto, null);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Kênh Định Vị Xe",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Thông báo dịch vụ theo dõi vị trí GPS cho xe");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopLocationUpdates();
    }
}
