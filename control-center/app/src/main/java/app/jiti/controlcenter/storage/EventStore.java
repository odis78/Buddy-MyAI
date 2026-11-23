package app.jiti.controlcenter.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

import app.jiti.controlcenter.core.DiagnosticEvent;

public final class EventStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "jiti-events.db";
    private static final int DB_VERSION = 1;
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
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Forward-only migrations are added here; never drop the event history implicitly.
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

    public synchronized int count(String componentId, String eventType) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE component_id=? AND event_type=?",
                new String[]{componentId, eventType})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }
}
