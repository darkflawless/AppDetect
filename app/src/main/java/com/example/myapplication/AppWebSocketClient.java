package com.example.myapplication;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.util.concurrent.TimeUnit;

/**
 * App Android kết nối tới Backend WebSocket với vai trò APP client.
 *
 * URL kết nối: ws://<IP_BACKEND>:8080/ws/realtime?type=APP&driverId=<driverId>
 *
 * Nhận lệnh từ Backend:
 * {"type":"START_STREAM"} → gọi listener.onStartStream()
 * {"type":"STOP_STREAM"} → gọi listener.onStopStream()
 *
 * Tự động reconnect nếu mất kết nối.
 */
public class AppWebSocketClient {

    private static final String TAG = "AppWebSocketClient";
    private static final long RECONNECT_DELAY_MS = 5000; // Thử lại sau 5 giây

    // ── Interface callback ────────────────────────────────────────────────────
    public interface StreamCommandListener {
        void onStartStream();

        void onStopStream();

        void onConnected();

        void onDisconnected();
    }

    // ── Fields ────────────────────────────────────────────────────────────────
    private final String serverUrl;
    private final StreamCommandListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private volatile boolean intentionalClose = false; // Phân biệt close chủ động vs lỗi

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * @param backendIp IP của máy chạy Backend (ví dụ: "192.168.1.100")
     * @param driverId  ID của tài xế đăng nhập trên App
     * @param listener  callback nhận lệnh START/STOP
     */
    public AppWebSocketClient(String backendIp, long driverId, StreamCommandListener listener) {
        this.serverUrl = "ws://" + backendIp + ":8080/ws/realtime?type=APP&driverId=" + driverId;
        this.listener = listener;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void connect() {
        intentionalClose = false;
        httpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS) // Không timeout (kết nối liên tục)
                .build();

        Request request = new Request.Builder()
                .url(serverUrl)
                .build();

        Log.i(TAG, "Đang kết nối tới: " + serverUrl);

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket ws, Response response) {
                Log.i(TAG, "✅ Đã kết nối WebSocket tới Backend");
                mainHandler.post(() -> listener.onConnected());
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                Log.d(TAG, "📩 Nhận lệnh: " + text);

                if (text.contains("START_STREAM")) {
                    mainHandler.post(() -> listener.onStartStream());
                } else if (text.contains("STOP_STREAM")) {
                    mainHandler.post(() -> listener.onStopStream());
                }
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                Log.i(TAG, "🔌 WebSocket đóng: " + reason);
                mainHandler.post(() -> listener.onDisconnected());
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.e(TAG, "❌ WebSocket lỗi: " + t.getMessage());
                mainHandler.post(() -> listener.onDisconnected());

                // Tự động reconnect nếu không phải đóng chủ động
                if (!intentionalClose) {
                    Log.i(TAG, "Thử reconnect sau " + RECONNECT_DELAY_MS + "ms...");
                    mainHandler.postDelayed(() -> connect(), RECONNECT_DELAY_MS);
                }
            }
        });
    }

    public void disconnect() {
        intentionalClose = true;
        if (webSocket != null) {
            webSocket.close(1000, "App đóng bình thường");
        }
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
        }
    }
}
