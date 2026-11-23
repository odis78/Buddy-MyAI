package app.jiti.controlcenter.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

import app.jiti.controlcenter.core.DiagnosticEvent;
import app.jiti.controlcenter.core.ComponentStatus;
import app.jiti.controlcenter.core.RuntimeSnapshot;

public final class EventStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "jiti-events.db";
    private static final int DB_VERSION = 2;
    private static final String TABLE = "events";
    private static final int MAX_ROWS = 2000;

    public EventStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "timestamp_ms INTEGER NOT NULL,"
                + "component_id TEXT NOT NULL,"
                + "event_type TEXT NOT NULL,"
                + "message TEXT NOT NULL)");
        db.execSQL("CREATE INDEX idx_events_timestamp ON " + TABLE + "(timestamp_ms DESC)");
        db.execSQL("CREATE INDEX idx_events_component ON " + TABLE + "(component_id)");
        createStateTable(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createStateTable(db);
    }

    private void createStateTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS component_state ("
                + "component_id TEXT PRIMARY KEY NOT NULL,"
                + "status TEXT NOT NULL,"
                + "checked_at_ms INTEGER NOT NULL,"
                + "pid INTEGER NOT NULL,"
                + "error_count INTEGER NOT NULL,"
                + "retry_count INTEGER NOT NULL,"
                + "detail TEXT NOT NULL)");
    }

    public synchronized void saveSnapshot(RuntimeSnapshot snapshot) {
        ContentValues values = new ContentValues();
        values.put("component_id", snapshot.componentId);
        values.put("status", snapshot.status.name());
        values.put("checked_at_ms", snapshot.checkedAtEpochMillis);
        values.put("pid", snapshot.pid);
        values.put("error_count", snapshot.errorCount);
        values.put("retry_count", snapshot.retryCount);
        values.put("detail", snapshot.detail);
        getWritableDatabase().insertWithOnConflict("component_state", null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized RuntimeSnapshot snapshot(String componentId) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT status, checked_at_ms, pid, error_count, retry_count, detail FROM component_state WHERE component_id=?",
                new String[]{componentId})) {
            if (!cursor.moveToFirst()) return null;
            ComponentStatus status;
            try { status = ComponentStatus.valueOf(cursor.getString(0)); }
            catch (Exception ignored) { status = ComponentStatus.UNKNOWN; }
            return new RuntimeSnapshot(componentId, status, cursor.getLong(1), cursor.getInt(2),
                    cursor.getInt(3), cursor.getInt(4), cursor.getString(5));
        }
    }

    public synchronized List<RuntimeSnapshot> snapshots() {
        List<RuntimeSnapshot> values = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT component_id, status, checked_at_ms, pid, error_count, retry_count, detail FROM component_state",
                null)) {
            while (cursor.moveToNext()) {
                ComponentStatus status;
                try { status = ComponentStatus.valueOf(cursor.getString(1)); }
                catch (Exception ignored) { status = ComponentStatus.UNKNOWN; }
                values.add(new RuntimeSnapshot(cursor.getString(0), status, cursor.getLong(2),
                        cursor.getInt(3), cursor.getInt(4), cursor.getInt(5), cursor.getString(6)));
            }
        }
        return values;
    }

    public synchronized void append(DiagnosticEvent event) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("timestamp_ms", event.timestampEpochMillis);
        values.put("component_id", event.componentId);
        values.put("event_type", event.eventType);
        values.put("message", event.message);
        db.insertOrThrow(TABLE, null, values);
        db.execSQL("DELETE FROM " + TABLE + " WHERE id NOT IN (SELECT id FROM " + TABLE
                + " ORDER BY id DESC LIMIT " + MAX_ROWS + ")");
    }

    public synchronized List<DiagnosticEvent> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<DiagnosticEvent> events = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT timestamp_ms, component_id, event_type, message FROM " + TABLE
                        + " ORDER BY id DESC LIMIT " + safeLimit, null)) {
            while (cursor.moveToNext()) {
                events.add(new DiagnosticEvent(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3)));
            }
        }
        return events;
    }

    public synchronized int countType(String eventType) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE event_type=?",
                new String[]{eventType})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public synchronized int count(String componentId, String eventType) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE component_id=? AND event_type=?",
                new String[]{componentId, eventType})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }
}
