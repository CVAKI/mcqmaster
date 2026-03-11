package com.braingods.mcqmaster;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Singleton that manages Gemini API keys with automatic 2-minute rotation.
 * Thread-safe key access for background threads.
 */
public class GeminiKeyManager {

    private static final String TAG          = "GeminiKeyManager";
    private static final String PREFS_NAME   = "mcq_prefs";
    private static final String PREFS_KEYS   = "gemini_keys";
    private static final String PREFS_IDX    = "active_key_idx";
    private static final long   ROTATE_MS    = 120_000L; // 2 minutes

    private static volatile GeminiKeyManager instance;

    private final List<String>  keys         = new ArrayList<>();
    private volatile int        activeIndex  = 0;
    private final Handler       handler      = new Handler(Looper.getMainLooper());
    private Runnable            rotateTask;
    private OnKeyChangedListener listener;

    public interface OnKeyChangedListener {
        void onKeyChanged(int newIndex, String maskedKey);
    }

    // ── Singleton ──────────────────────────────────────────────
    private GeminiKeyManager() {}

    public static GeminiKeyManager get() {
        if (instance == null) {
            synchronized (GeminiKeyManager.class) {
                if (instance == null) instance = new GeminiKeyManager();
            }
        }
        return instance;
    }

    // ── Init / Persistence ─────────────────────────────────────
    public void init(Context ctx) {
        load(ctx);
        scheduleRotation(ctx);
    }

    public void load(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(p.getString(PREFS_KEYS, "[]"));
            keys.clear();
            for (int i = 0; i < arr.length(); i++) keys.add(arr.getString(i));
            activeIndex = p.getInt(PREFS_IDX, 0);
            if (activeIndex >= keys.size()) activeIndex = 0;
        } catch (Exception e) {
            keys.clear();
            activeIndex = 0;
        }
    }

    public void save(Context ctx) {
        try {
            JSONArray arr = new JSONArray();
            for (String k : keys) arr.put(k);
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(PREFS_KEYS, arr.toString())
                    .putInt(PREFS_IDX, activeIndex)
                    .apply();
        } catch (Exception ignored) {}
    }

    // ── Key Operations ─────────────────────────────────────────
    public boolean addKey(String key, Context ctx) {
        if (key == null || key.trim().isEmpty()) return false;
        key = key.trim();
        if (keys.contains(key)) return false;
        keys.add(key);
        save(ctx);
        return true;
    }

    public void removeKey(int index, Context ctx) {
        if (index < 0 || index >= keys.size()) return;
        keys.remove(index);
        if (activeIndex >= keys.size()) activeIndex = 0;
        save(ctx);
    }

    public void clearAll(Context ctx) {
        keys.clear();
        activeIndex = 0;
        save(ctx);
    }

    public String getCurrentKey() {
        if (keys.isEmpty()) return null;
        return keys.get(activeIndex);
    }

    public int getActiveIndex() { return activeIndex; }

    public int getKeyCount() { return keys.size(); }

    public List<String> getAllKeys() { return new ArrayList<>(keys); }

    public String mask(String k) {
        if (k == null || k.length() < 12) return "***";
        return k.substring(0, 8) + "..." + k.substring(k.length() - 4);
    }

    public String getMaskedCurrent() {
        String k = getCurrentKey();
        return k == null ? "(no key)" : mask(k);
    }

    // ── Auto Rotation ──────────────────────────────────────────
    private void scheduleRotation(Context ctx) {
        if (rotateTask != null) handler.removeCallbacks(rotateTask);
        rotateTask = new Runnable() {
            @Override
            public void run() {
                rotateKey(ctx);
                handler.postDelayed(this, ROTATE_MS);
            }
        };
        handler.postDelayed(rotateTask, ROTATE_MS);
    }

    /** Immediately rotate to next key */
    public void rotateKey(Context ctx) {
        if (keys.size() <= 1) return;
        activeIndex = (activeIndex + 1) % keys.size();
        save(ctx);
        Log.d(TAG, "Rotated to key #" + (activeIndex + 1));
        if (listener != null) listener.onKeyChanged(activeIndex, getMaskedCurrent());
    }

    /** Force rotate and reset the 2-min timer */
    public void forceRotateAndReset(Context ctx) {
        if (rotateTask != null) handler.removeCallbacks(rotateTask);
        rotateKey(ctx);
        scheduleRotation(ctx);
    }

    public void setOnKeyChangedListener(OnKeyChangedListener l) { this.listener = l; }

    public void stopRotation() {
        if (rotateTask != null) handler.removeCallbacks(rotateTask);
    }
}