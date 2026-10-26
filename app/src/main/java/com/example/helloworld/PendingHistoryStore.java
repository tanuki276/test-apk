package com.example.liefantidia2;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Durable transfer outbox, excluded from Android backup. No credentials are stored. */
final class PendingHistoryStore extends SQLiteOpenHelper {
    static final class Entry {
        final String id;
        final Map<String, Object> data;
        Entry(String id, Map<String, Object> data) { this.id = id; this.data = data; }
    }
    private final Gson gson = new Gson();
    PendingHistoryStore(Context context) { super(context, "history_transfers.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE pending (owner TEXT NOT NULL, id TEXT NOT NULL, data TEXT NOT NULL, PRIMARY KEY(owner,id))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
    synchronized void put(String owner, String id, Map<String, Object> data) {
        ContentValues values = new ContentValues();
        values.put("owner", owner); values.put("id", id); values.put("data", gson.toJson(data));
        if (getWritableDatabase().insertWithOnConflict("pending", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1)
            throw new android.database.sqlite.SQLiteException("Could not persist history transfer");
    }
    synchronized void putAll(String owner, List<Entry> entries) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Entry entry : entries) put(owner, entry.id, entry.data);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    synchronized List<Entry> page(String owner) {
        List<Entry> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("pending", new String[]{"id", "data"},
                "owner=?", new String[]{owner}, null, null, "id", "100")) {
            while (cursor.moveToNext()) {
                Map<String, Object> data = gson.fromJson(cursor.getString(1),
                    new TypeToken<Map<String, Object>>() {}.getType());
                // Gson's generic number adapter returns Double; Firestore rules require an integer.
                data.put("timestamp", ((Number) data.get("timestamp")).longValue());
                result.add(new Entry(cursor.getString(0), data));
            }
        }
        return result;
    }
    synchronized void remove(String owner, List<Entry> entries) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Entry entry : entries) db.delete("pending", "owner=? AND id=?", new String[]{owner, entry.id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    synchronized boolean hasPending(String owner) {
        if (owner == null) return false;
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT 1 FROM pending WHERE owner=? LIMIT 1", new String[]{owner})) {
            return cursor.moveToFirst();
        }
    }
}
