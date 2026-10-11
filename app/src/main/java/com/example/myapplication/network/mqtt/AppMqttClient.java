package com.example.myapplication.network.mqtt;

import android.content.Context;
import android.util.Log;

import com.example.myapplication.data.db.OfflineOutboxDbHelper;
import com.example.myapplication.utils.AppConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AppMqttClient - Client MQTT v3/v5 chuẩn cho DriverGuard Mobile Edge:
 * 1. Publish GPS Telemetry định kỳ lên driverguard/telemetry/{vehicle_id}/gps (QoS 0)
 * 2. Publish Safety Alerts tức thời lên driverguard/alerts/{vehicle_id}/{alert_type} (QoS 1)
 * 3. Tự động lưu trữ ngoại tuyến qua SQLite Outbox nếu mất kết nối 4G/WiFi
 * 4. Tự động quét và đẩy lại toàn bộ Outbox ngay khi khôi phục mạng
 */
public class AppMqttClient {

    private static final String TAG = "AppMqttClient";

    private static AppMqttClient instance;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Gson gson = new Gson();

    private MqttAsyncClient mqttClient;
    private OfflineOutboxDbHelper outboxDb;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private String currentClientId;
    private String currentBrokerUrl;

    public interface MqttStatusListener {
        void onConnected();
        void onConnectionLost(Throwable cause);
    }

    private MqttStatusListener statusListener;

    public static synchronized AppMqttClient getInstance() {
        if (instance == null) {
            instance = new AppMqttClient();
        }
        return instance;
    }

    private AppMqttClient() {
    }

    public void init(Context context) {
        if (this.outboxDb == null) {
            this.outboxDb = OfflineOutboxDbHelper.getInstance(context);
        }
    }

    public void setStatusListener(MqttStatusListener listener) {
        this.statusListener = listener;
    }

    public synchronized void connect(Context context, String brokerUrl, String clientId) {
        init(context);

        this.currentBrokerUrl = (brokerUrl != null && !brokerUrl.isBlank()) ? brokerUrl : AppConfig.getMqttBrokerUrl();
        this.currentClientId = (clientId != null && !clientId.isBlank())
                ? clientId
                : "DRIVERGUARD_veh_" + UUID.randomUUID().toString().substring(0, 6);

        if (mqttClient != null && isConnected.get()) {
            Log.i(TAG, "Mqtt Client đã kết nối sẵn");
            return;
        }

        executor.execute(() -> {
            try {
                if (mqttClient != null) {
                    try {
                        mqttClient.disconnect();
                    } catch (Exception ignored) {}
                }

                mqttClient = new MqttAsyncClient(currentBrokerUrl, currentClientId, new MemoryPersistence());

                MqttConnectOptions options = new MqttConnectOptions();
                options.setAutomaticReconnect(true);
                options.setCleanSession(false);
                options.setConnectionTimeout(10);
                options.setKeepAliveInterval(30);

                mqttClient.setCallback(new MqttCallbackExtended() {
                    @Override
                    public void connectComplete(boolean reconnect, String serverURI) {
                        isConnected.set(true);
                        Log.i(TAG, "🟢 [MQTT Connected] Kết nối thành công tới " + serverURI + " (reconnect=" + reconnect + ")");
                        if (statusListener != null) {
                            statusListener.onConnected();
                        }
                        // Quét và xả toàn bộ hàng đợi SQLite Outbox khi có mạng
                        drainOutbox();
                    }

                    @Override
                    public void connectionLost(Throwable cause) {
                        isConnected.set(false);
                        Log.w(TAG, "🔴 [MQTT Disconnected] Mất kết nối tới Broker: " + (cause != null ? cause.getMessage() : "Unknown"));
                        if (statusListener != null) {
                            statusListener.onConnectionLost(cause);
                        }
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        Log.d(TAG, "📩 Nhận gói tin từ topic: " + topic);
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {
                        Log.d(TAG, "✅ Gửi gói tin MQTT thành công");
                    }
                });

                Log.i(TAG, "Đang kết nối MQTT tới: " + currentBrokerUrl + " với clientId=" + currentClientId);
                mqttClient.connect(options, null, new IMqttActionListener() {
                    @Override
                    public void onSuccess(IMqttToken asyncActionToken) {
                        isConnected.set(true);
                        Log.i(TAG, "✅ MQTT Handshake hoàn tất");
                    }

                    @Override
                    public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                        isConnected.set(false);
                        Log.e(TAG, "❌ Kết nối MQTT thất bại: " + (exception != null ? exception.getMessage() : "null"));
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Lỗi tạo MQTT Client: " + e.getMessage());
            }
        });
    }

    public boolean isConnected() {
        return isConnected.get() && mqttClient != null && mqttClient.isConnected();
    }

    /**
     * 1. Gửi GPS Telemetry lên topic driverguard/telemetry/{vehicle_id}/gps (QoS 0)
     */
    public void publishTelemetry(String vehicleId, double latitude, double longitude, float speedKmh) {
        if (!isConnected()) {
            // QoS 0: Không gửi nếu mất mạng để tránh nghẽn băng thông
            return;
        }

        executor.execute(() -> {
            try {
                String topic = "driverguard/telemetry/" + vehicleId + "/gps";

                JsonObject root = new JsonObject();
                root.addProperty("version", "1.0");
                root.addProperty("vehicle_id", vehicleId);
                root.addProperty("timestamp", System.currentTimeMillis() / 1000);

                JsonObject gps = new JsonObject();
                gps.addProperty("latitude", latitude);
                gps.addProperty("longitude", longitude);
                gps.addProperty("speed_kmh", speedKmh);
                gps.addProperty("heading", 0.0);
                gps.addProperty("altitude", 0.0);
                gps.addProperty("accuracy_m", 5.0);
                root.add("gps", gps);

                String payload = gson.toJson(root);
                MqttMessage message = new MqttMessage(payload.getBytes());
                message.setQos(0);

                mqttClient.publish(topic, message);
                Log.d(TAG, "📍 [MQTT Telemetry QoS 0] Lat=" + latitude + ", Lng=" + longitude);
            } catch (Exception e) {
                Log.w(TAG, "Lỗi publish telemetry: " + e.getMessage());
            }
        });
    }

    /**
     * 2. Gửi Cảnh báo an toàn lên topic driverguard/alerts/{vehicle_id}/{alert_type} (QoS 1)
     * Kèm fallback tự động vào SQLite Outbox nếu không có mạng.
     */
    public void publishSafetyAlert(String vehicleId, String driverId, String shiftId,
                                   String alertType, String severity,
                                   double latitude, double longitude, float speedKmh,
                                   float ear, float mar, float pitch, float yaw, float perclos,
                                   String snapshotUrl) {
        executor.execute(() -> {
            try {
                String topic = "driverguard/alerts/" + vehicleId + "/" + alertType;

                JsonObject root = new JsonObject();
                root.addProperty("event_id", UUID.randomUUID().toString());
                root.addProperty("version", "1.0");
                root.addProperty("vehicle_id", vehicleId);
                root.addProperty("shift_id", shiftId != null ? shiftId : AppConfig.DEFAULT_SHIFT_ID);
                root.addProperty("driver_id", driverId != null ? driverId : AppConfig.DEFAULT_DRIVER_UUID);
                root.addProperty("alert_type", alertType);
                root.addProperty("severity", severity != null ? severity : "HIGH");
                root.addProperty("timestamp", System.currentTimeMillis() / 1000);

                JsonObject telemetry = new JsonObject();
                telemetry.addProperty("latitude", latitude);
                telemetry.addProperty("longitude", longitude);
                telemetry.addProperty("speed_kmh", speedKmh);
                root.add("telemetry", telemetry);

                JsonObject biometrics = new JsonObject();
                biometrics.addProperty("ear", ear);
                biometrics.addProperty("mar", mar);
                biometrics.addProperty("pitch_deg", pitch);
                biometrics.addProperty("yaw_deg", yaw);
                biometrics.addProperty("perclos_60s", perclos);
                root.add("biometrics", biometrics);

                JsonObject evidence = new JsonObject();
                evidence.addProperty("snapshot_url", snapshotUrl != null ? snapshotUrl : "");
                evidence.addProperty("clip_duration_s", 0);
                root.add("evidence", evidence);

                String payload = gson.toJson(root);

                if (isConnected()) {
                    MqttMessage message = new MqttMessage(payload.getBytes());
                    message.setQos(1);
                    mqttClient.publish(topic, message, null, new IMqttActionListener() {
                        @Override
                        public void onSuccess(IMqttToken asyncActionToken) {
                            Log.i(TAG, "🚨 [MQTT Alert Sent QoS 1] " + alertType + " (" + severity + ")");
                        }

                        @Override
                        public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                            Log.w(TAG, "⚠️ Publish thất bại, lưu vào SQLite Outbox...");
                            if (outboxDb != null) {
                                outboxDb.saveAlert(topic, payload);
                            }
                        }
                    });
                } else {
                    Log.w(TAG, "⚠️ Mất mạng! Đang lưu vi phạm vào SQLite Outbox...");
                    if (outboxDb != null) {
                        outboxDb.saveAlert(topic, payload);
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Lỗi khi xử lý gói tin cảnh báo: " + e.getMessage());
            }
        });
    }

    /**
     * 3. Xả hàng đợi ngoại tuyến SQLite Outbox
     */
    public void drainOutbox() {
        if (!isConnected() || outboxDb == null) {
            return;
        }

        executor.execute(() -> {
            try {
                List<OfflineOutboxDbHelper.OutboxItem> pendingList = outboxDb.getPendingAlerts(50);
                if (pendingList.isEmpty()) {
                    return;
                }

                Log.i(TAG, "📦 Đang đồng bộ " + pendingList.size() + " cảnh báo từ SQLite Outbox lên Broker...");

                for (OfflineOutboxDbHelper.OutboxItem item : pendingList) {
                    if (!isConnected()) break;

                    try {
                        MqttMessage msg = new MqttMessage(item.payload.getBytes());
                        msg.setQos(1);
                        mqttClient.publish(item.topic, msg);
                        outboxDb.deleteAlert(item.id);
                        Thread.sleep(100); // Giãn cách ngắn tránh nghẽn
                    } catch (Exception e) {
                        Log.e(TAG, "Lỗi gửi bản ghi outbox id=" + item.id + ": " + e.getMessage());
                        break;
                    }
                }
                Log.i(TAG, "✅ Hoàn tất xả SQLite Outbox");
            } catch (Exception e) {
                Log.e(TAG, "Lỗi drainOutbox: " + e.getMessage());
            }
        });
    }

    public void disconnect() {
        executor.execute(() -> {
            try {
                if (mqttClient != null && mqttClient.isConnected()) {
                    mqttClient.disconnect();
                }
                isConnected.set(false);
            } catch (Exception e) {
                Log.e(TAG, "Lỗi ngắt kết nối MQTT: " + e.getMessage());
            }
        });
    }
}
