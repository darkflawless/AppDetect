package com.example.myapplication.network.stream;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UdpFrameSender - Chuyên trách mở UDP Socket (port 9090) nén JPEG 60%
 * và stream trực tiếp các khung hình video lên Server.
 *
 * Định dạng packet gửi đi:
 * ┌──────────────────────────┬─────────────────────────┐
 * │ 8 byte (driverId - long) │ N byte (JPEG bytes)     │
 * └──────────────────────────┴─────────────────────────┘
 */
public class UdpFrameSender {

    private static final String TAG = "UdpFrameSender";

    // ── Config ────────────────────────────────────────────────────────────────
    private static final int JPEG_QUALITY = 60; // Chất lượng JPEG (0–100), thấp = nhỏ hơn
    private static final long FRAME_INTERVAL_MS = 200; // Gửi tối đa 5fps (200ms/frame)
    private static final int MAX_UDP_SIZE = 60000; // Giới hạn an toàn cho UDP packet

    // ── Fields ────────────────────────────────────────────────────────────────
    private final String serverIp;
    private final int serverPort;
    private final long driverId;

    private final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private DatagramSocket socket;
    private long lastSentTimeMs = 0;

    // ── Constructor ───────────────────────────────────────────────────────────

    public UdpFrameSender(String serverIp, int serverPort, long driverId) {
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.driverId = driverId;

        try {
            socket = new DatagramSocket();
            Log.i(TAG, "UDP socket tạo thành công");
        } catch (Exception e) {
            Log.e(TAG, "Không thể tạo UDP socket: " + e.getMessage());
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Bắt đầu stream - gọi khi nhận lệnh START_STREAM từ Backend */
    public void startStreaming() {
        isStreaming.set(true);
        Log.i(TAG, "🟢 Bắt đầu stream UDP → " + serverIp + ":" + serverPort);
    }

    /** Dừng stream - gọi khi nhận lệnh STOP_STREAM từ Backend */
    public void stopStreaming() {
        isStreaming.set(false);
        Log.i(TAG, "🔴 Dừng stream UDP");
    }

    public boolean isStreaming() {
        return isStreaming.get();
    }

    /**
     * Gửi 1 frame ảnh lên Backend.
     * Tự bỏ qua nếu chưa đến lượt gửi (throttle 5fps) hoặc đang dừng.
     *
     * @param bitmap Frame ảnh hiện tại
     */
    public void sendFrame(Bitmap bitmap) {
        // Không stream → bỏ qua
        if (!isStreaming.get() || socket == null || bitmap == null)
            return;

        // Throttle: chưa đến thời điểm gửi → bỏ qua
        long now = System.currentTimeMillis();
        if (now - lastSentTimeMs < FRAME_INTERVAL_MS)
            return;
        lastSentTimeMs = now;

        // Copy bitmap vì sẽ xử lý trên thread khác
        final Bitmap copy = bitmap.copy(bitmap.getConfig(), false);

        executor.execute(() -> {
            try {
                // Bước 1: Nén Bitmap → JPEG bytes
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                copy.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
                byte[] jpegBytes = baos.toByteArray();
                copy.recycle();

                // Bước 2: Kiểm tra kích thước
                if (jpegBytes.length > MAX_UDP_SIZE) {
                    Log.w(TAG, "Frame quá lớn: " + jpegBytes.length + " bytes, bỏ qua");
                    return;
                }

                // Bước 3: Đóng gói [8 byte driverId] + [jpeg bytes]
                ByteBuffer buffer = ByteBuffer.allocate(8 + jpegBytes.length);
                buffer.putLong(driverId);
                buffer.put(jpegBytes);
                byte[] packetData = buffer.array();

                // Bước 4: Gửi UDP
                InetAddress address = InetAddress.getByName(serverIp);
                DatagramPacket packet = new DatagramPacket(
                        packetData, packetData.length, address, serverPort);
                socket.send(packet);

                Log.d(TAG, "📤 Gửi frame " + jpegBytes.length + " bytes");

            } catch (Exception e) {
                Log.e(TAG, "Lỗi gửi frame: " + e.getMessage());
            }
        });
    }

    /** Giải phóng tài nguyên khi App đóng */
    public void close() {
        isStreaming.set(false);
        executor.shutdown();
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}
