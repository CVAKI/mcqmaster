package com.braingods.mcqmaster;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the active AI provider (Gemini / Groq / OpenRouter)
 * and their respective API key lists with round-robin rotation.
 */
public class ProviderManager {

    public enum Provider {
        GEMINI,
        GROQ,
        OPENROUTER
    }

    private static final String PREFS              = "mcq_prefs";
    private static final String KEY_PROVIDER       = "active_provider";
    private static final String KEY_GROQ_KEYS      = "groq_keys";
    private static final String KEY_GROQ_IDX       = "groq_key_idx";
    private static final String KEY_OR_KEYS        = "or_keys";
    private static final String KEY_OR_IDX         = "or_key_idx";
    private static final String KEY_OR_MODEL       = "or_model";

    // Groq vision model — fast, free tier, supports image input
    public static final String  GROQ_MODEL         = "meta-llama/llama-4-scout-17b-16e-instruct";
    public static final String  GROQ_URL            = "https://api.groq.com/openai/v1/chat/completions";

    // OpenRouter — default model, user can change
    public static final String  OR_DEFAULT_MODEL    = "google/gemini-2.0-flash-lite";
    public static final String  OR_URL              = "https://openrouter.ai/api/v1/chat/completions";

    private static volatile ProviderManager instance;

    private Provider     activeProvider = Provider.GEMINI;

    private final List<String> groqKeys  = new ArrayList<>();
    private int                groqIdx   = 0;

    private final List<String> orKeys    = new ArrayList<>();
    private int                orIdx     = 0;
    private String             orModel   = OR_DEFAULT_MODEL;

    private ProviderManager() {}

    public static ProviderManager get() {
        if (instance == null) {
            synchronized (ProviderManager.class) {
                if (instance == null) instance = new ProviderManager();
            }
        }
        return instance;
    }

    // ── Load / Save ────────────────────────────────────────────
    public void load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        String pName = p.getString(KEY_PROVIDER, Provider.GEMINI.name());
        try { activeProvider = Provider.valueOf(pName); }
        catch (Exception e) { activeProvider = Provider.GEMINI; }

        groqKeys.clear();
        try {
            JSONArray a = new JSONArray(p.getString(KEY_GROQ_KEYS, "[]"));
            for (int i = 0; i < a.length(); i++) groqKeys.add(a.getString(i));
        } catch (Exception ignored) {}
        groqIdx = p.getInt(KEY_GROQ_IDX, 0);
        if (groqIdx >= groqKeys.size()) groqIdx = 0;

        orKeys.clear();
        try {
            JSONArray a = new JSONArray(p.getString(KEY_OR_KEYS, "[]"));
            for (int i = 0; i < a.length(); i++) orKeys.add(a.getString(i));
        } catch (Exception ignored) {}
        orIdx   = p.getInt(KEY_OR_IDX, 0);
        if (orIdx >= orKeys.size()) orIdx = 0;
        orModel = p.getString(KEY_OR_MODEL, OR_DEFAULT_MODEL);
    }

    public void save(Context ctx) {
        try {
            JSONArray ga = new JSONArray(); for (String k : groqKeys) ga.put(k);
            JSONArray oa = new JSONArray(); for (String k : orKeys)   oa.put(k);
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_PROVIDER,  activeProvider.name())
                    .putString(KEY_GROQ_KEYS, ga.toString())
                    .putInt   (KEY_GROQ_IDX,  groqIdx)
                    .putString(KEY_OR_KEYS,   oa.toString())
                    .putInt   (KEY_OR_IDX,    orIdx)
                    .putString(KEY_OR_MODEL,  orModel)
                    .apply();
        } catch (Exception ignored) {}
    }

    // ── Provider selection ─────────────────────────────────────
    public Provider getActiveProvider()                   { return activeProvider; }
    public void setActiveProvider(Provider p, Context ctx) {
        activeProvider = p;
        save(ctx);
    }

    // ── Groq keys ──────────────────────────────────────────────
    public boolean addGroqKey(String key, Context ctx) {
        key = key.trim();
        if (key.isEmpty() || groqKeys.contains(key)) return false;
        groqKeys.add(key); save(ctx); return true;
    }
    public void removeGroqKey(int i, Context ctx) {
        if (i < 0 || i >= groqKeys.size()) return;
        groqKeys.remove(i);
        if (groqIdx >= groqKeys.size()) groqIdx = 0;
        save(ctx);
    }
    public void clearGroqKeys(Context ctx) { groqKeys.clear(); groqIdx = 0; save(ctx); }
    public String getCurrentGroqKey()      { return groqKeys.isEmpty() ? null : groqKeys.get(groqIdx); }
    public int    getGroqKeyCount()        { return groqKeys.size(); }
    public int    getGroqActiveIndex()     { return groqIdx; }
    public List<String> getAllGroqKeys()   { return new ArrayList<>(groqKeys); }
    public void rotateGroqKey(Context ctx) {
        if (groqKeys.size() <= 1) return;
        groqIdx = (groqIdx + 1) % groqKeys.size();
        save(ctx);
    }

    // ── OpenRouter keys ────────────────────────────────────────
    public boolean addOrKey(String key, Context ctx) {
        key = key.trim();
        if (key.isEmpty() || orKeys.contains(key)) return false;
        orKeys.add(key); save(ctx); return true;
    }
    public void removeOrKey(int i, Context ctx) {
        if (i < 0 || i >= orKeys.size()) return;
        orKeys.remove(i);
        if (orIdx >= orKeys.size()) orIdx = 0;
        save(ctx);
    }
    public void clearOrKeys(Context ctx) { orKeys.clear(); orIdx = 0; save(ctx); }
    public String getCurrentOrKey()      { return orKeys.isEmpty() ? null : orKeys.get(orIdx); }
    public int    getOrKeyCount()        { return orKeys.size(); }
    public int    getOrActiveIndex()     { return orIdx; }
    public List<String> getAllOrKeys()   { return new ArrayList<>(orKeys); }
    public void rotateOrKey(Context ctx) {
        if (orKeys.size() <= 1) return;
        orIdx = (orIdx + 1) % orKeys.size();
        save(ctx);
    }
    public String getOrModel()                         { return orModel; }
    public void   setOrModel(String m, Context ctx)    { orModel = m; save(ctx); }

    // ── Shared mask helper ─────────────────────────────────────
    public String mask(String k) {
        if (k == null || k.length() < 12) return "***";
        return k.substring(0, 8) + "..." + k.substring(k.length() - 4);
    }
}