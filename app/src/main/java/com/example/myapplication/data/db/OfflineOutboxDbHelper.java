package com.example.myapplication.data.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * OfflineOutboxDbHelper - SQLite Outbox lưu trữ ngoại tuyến trên Android:
 * Đảm bảo các gói tin cảnh báo an toàn KHÔNG BAO GIỜ BỊ MẤT khi xe mất sóng 4G/WiFi.
 * Khi kết nối lại, background worker sẽ tự động đẩy lên EMQX với QoS 1.
 */
public class OfflineOutboxDbHelper extends SQLiteOpenHelper {

    private static final String TAG = "OfflineOutboxDb";
    private static final String DATABASE_NAME = "driverguard_offline.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_OUTBOX = "offline_alerts_outbox";
    public static final String COLUMN_ID = "id";
    public static final String COLUMN_TOPIC = "topic";
    public static final String COLUMN_PAYLOAD = "payload";
    public static final String COLUMN_CREATED_AT = "created_at";

    private static OfflineOutboxDbHelper instance;

    public static synchronized OfflineOutboxDbHelper getInstance(Context context) {
        if (instance == null) {
            instance = new OfflineOutboxDbHelper(context.getApplicationContext());
        }
        return instance;
    }

    public OfflineOutboxDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTableSql = "CREATE TABLE " + TABLE_OUTBOX + " ("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COLUMN_TOPIC + " TEXT NOT NULL, "
                + COLUMN_PAYLOAD + " TEXT NOT NULL, "
                + COLUMN_CREATED_AT + " INTEGER NOT NULL"
                + ")";
        db.execSQL(createTableSql);
        Log.i(TAG, "Đã khởi tạo bảng SQLite Outbox: " + TABLE_OUTBOX);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_OUTBOX);
        onCreate(db);
    }

    public static class OutboxItem {
        public final long id;
        public final String topic;
        public final String payload;
        public final long createdAt;

        public OutboxItem(long id, String topic, String payload, long createdAt) {
            this.id = id;
            this.topic = topic;
            this.payload = payload;
            this.createdAt = createdAt;
        }
    }

    public synchronized long saveAlert(String topic, String payload) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(COLUMN_TOPIC, topic);
            values.put(COLUMN_PAYLOAD, payload);
            values.put(COLUMN_CREATED_AT, System.currentTimeMillis());
            long newId = db.insert(TABLE_OUTBOX, null, values);
            Log.i(TAG, "📦 [Outbox Saved] Đã lưu cảnh báo offline vào SQLite [id=" + newId + ", topic=" + topic + "]");
            return newId;
        } catch (Exception e) {
            Log.e(TAG, "Lỗi lưu Outbox: " + e.getMessage());
            return -1;
        }
    }

    public synchronized List<OutboxItem> getPendingAlerts(int limit) {
        List<OutboxItem> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(
                    TABLE_OUTBOX,
                    null,
                    null,
                    null,
                    null,
                    null,
                    COLUMN_CREATED_AT + " ASC",
                    String.valueOf(limit)
            );
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    long id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID));
                    String topic = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TOPIC));
                    String payload = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PAYLOAD));
                    long createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT));
                    list.add(new OutboxItem(id, topic, payload, createdAt));
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Lỗi đọc Outbox: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return list;
    }

    public synchronized void deleteAlert(long id) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete(TABLE_OUTBOX, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
            Log.d(TAG, "🗑️ [Outbox Cleared] Đã xóa bản ghi id=" + id + " sau khi gửi thành công lên Broker");
        } catch (Exception e) {
            Log.e(TAG, "Lỗi xóa Outbox bản ghi id=" + id + ": " + e.getMessage());
        }
    }

    public synchronized int getPendingCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_OUTBOX, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Lỗi đếm số lượng outbox: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return 0;
    }
}
