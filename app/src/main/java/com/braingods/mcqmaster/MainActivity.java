package com.braingods.mcqmaster;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG        = "MCQMaster";
    private static final int    CAM_PERM   = 100;
    private static final String PREFS_NAME = "mcq_prefs";
    private static final String PREFS_KEYS = "gemini_keys";
    private static final String PREFS_IDX  = "active_key_idx";

    // Gemini 2.5 Flash free tier: 10 req/min, 500 req/day per key
    // Get keys free at: https://aistudio.google.com/apikey
    private static final String GEMINI_MODEL = "gemini-2.0-flash";
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/"
                    + GEMINI_MODEL + ":generateContent?key=";

    private static final long SNAPSHOT_INTERVAL_MS = 15_000;
    private static final long ANSWER_HOLD_MS       = 10_000;

    private static final int COLOR_A = 0xFFD32F2F;
    private static final int COLOR_B = 0xFF1565C0;
    private static final int COLOR_C = 0xFF2E7D32;
    private static final int COLOR_D = 0xFFE65100;

    // ── Views ──────────────────────────────────────────────────
    private PreviewView  previewView;
    private View         fullScreenColor;
    private LinearLayout questionCard;
    private TextView     tvQuestion;
    private TextView     answerLetter;
    private TextView     tvAnswerText;
    private TextView     statusText;
    private ImageButton  btnSettings;
    private LinearLayout settingsPanel;
    private LinearLayout keysContainer;
    private TextView     tvKeyCount;
    private TextView     tvActiveKey;
    private EditText     etNewKey;

    // ── Camera ─────────────────────────────────────────────────
    private ImageCapture    imageCapture;
    private ExecutorService captureExecutor;
    private final Handler   mainHandler = new Handler(Looper.getMainLooper());

    // ── State ──────────────────────────────────────────────────
    private volatile boolean busy = false;
    private volatile long     retryAfterMs = 0; // epoch ms — don't call Gemini before this time

    // ── Keys ───────────────────────────────────────────────────
    private final List<String> apiKeys        = new ArrayList<>();
    private int                activeKeyIndex = 0;

    // ── Countdown ──────────────────────────────────────────────
    private int      countdownSecs  = 15;
    private Runnable countdownTick;
    private Runnable snapshotTrigger;

    // ─────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        setContentView(R.layout.activity_main);

        previewView    = findViewById(R.id.previewView);
        fullScreenColor= findViewById(R.id.fullScreenColor);
        questionCard   = findViewById(R.id.questionCard);
        tvQuestion     = findViewById(R.id.tvQuestion);
        answerLetter   = findViewById(R.id.answerLetter);
        tvAnswerText   = findViewById(R.id.tvAnswerText);
        statusText     = findViewById(R.id.statusText);
        btnSettings    = findViewById(R.id.btnSettings);
        settingsPanel  = findViewById(R.id.settingsPanel);
        keysContainer  = findViewById(R.id.keysContainer);
        tvKeyCount     = findViewById(R.id.tvKeyCount);
        tvActiveKey    = findViewById(R.id.tvActiveKey);
        etNewKey       = findViewById(R.id.etNewKey);

        captureExecutor = Executors.newSingleThreadExecutor();
        loadKeys();
        wireSettingsPanel();

        if (hasCameraPermission()) startCamera();
        else ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, CAM_PERM);
    }

    // ─────────────────────────────────────────────────────────
    // Camera
    // ─────────────────────────────────────────────────────────
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build();
                provider.unbindAll();
                provider.bindToLifecycle(this,
                        CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);
                startSnapshotLoop();
            } catch (Exception e) {
                Log.e(TAG, "Camera init failed", e);
                setStatus("❌ Camera error: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ─────────────────────────────────────────────────────────
    // 15-second snapshot loop with live countdown
    // ─────────────────────────────────────────────────────────
    private void startSnapshotLoop() {
        countdownSecs = 15;
        setStatus("📸 Snapshot in 15s...");

        countdownTick = new Runnable() {
            @Override public void run() {
                if (!busy && countdownSecs > 0) {
                    countdownSecs--;
                    setStatus("📸 Snapshot in " + countdownSecs + "s...");
                }
                mainHandler.postDelayed(this, 1000);
            }
        };
        mainHandler.postDelayed(countdownTick, 1000);

        snapshotTrigger = new Runnable() {
            @Override public void run() {
                if (!busy) takeSnapshotAndAnalyse();
                countdownSecs = 15;
                mainHandler.postDelayed(this, SNAPSHOT_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(snapshotTrigger, SNAPSHOT_INTERVAL_MS);
    }

    // ─────────────────────────────────────────────────────────
    // Take photo → encode → send to Gemini
    // ─────────────────────────────────────────────────────────
    private void takeSnapshotAndAnalyse() {
        if (imageCapture == null) return;
        if (apiKeys.isEmpty()) {
            setStatus("⚙ No Gemini key — tap ⚙ to add");
            return;
        }
        // Honour rate-limit backoff — skip this slot if still cooling down
        long now = System.currentTimeMillis();
        if (now < retryAfterMs) {
            long secsLeft = (retryAfterMs - now) / 1000 + 1;
            setStatus("⏳ Rate limit — wait " + secsLeft + "s...");
            busy = false;
            return;
        }

        busy = true;
        setStatus("📷 Taking photo...");

        imageCapture.takePicture(captureExecutor,
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(@NonNull ImageProxy image) {
                        try {
                            String b64 = encodeImage(image);
                            image.close();
                            if (b64 == null) { busy = false; setStatus("❌ Encode failed"); return; }
                            Log.d(TAG, "Photo b64len=" + b64.length());
                            setStatus("🤖 Asking Gemini 2.5 Flash...");
                            callGemini(b64);
                        } catch (Exception e) {
                            Log.e(TAG, "capture ok but error", e);
                            busy = false; setStatus("❌ " + e.getMessage());
                        }
                    }
                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        Log.e(TAG, "Capture failed", e);
                        busy = false; setStatus("❌ " + e.getMessage());
                    }
                });
    }

    // ─────────────────────────────────────────────────────────
    // Encode ImageProxy → base64 JPEG, max 1280px wide
    // ─────────────────────────────────────────────────────────
    private String encodeImage(ImageProxy proxy) {
        try {
            ByteBuffer buf = proxy.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp == null) return Base64.encodeToString(bytes, Base64.NO_WRAP);
            if (bmp.getWidth() > 1280) {
                int h = (int)(bmp.getHeight() * (1280f / bmp.getWidth()));
                Bitmap s = Bitmap.createScaledBitmap(bmp, 1280, h, true);
                bmp.recycle(); bmp = s;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
            bmp.recycle();
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) { Log.e(TAG, "encodeImage", e); return null; }
    }

    // ─────────────────────────────────────────────────────────
    // Gemini API — tries each key in order, rotates on 429
    // ─────────────────────────────────────────────────────────
    private void callGemini(String b64) {
        String prompt =
                "Look at this image carefully.\n" +
                        "Does it show a multiple choice question (MCQ)?\n\n" +
                        "If YES, reply in EXACTLY this format (nothing else):\n" +
                        "QUESTION: <the full question text>\n" +
                        "ANSWER: <just the letter A, B, C, or D>\n" +
                        "OPTION: <the full text of the correct answer option>\n\n" +
                        "If there is NO MCQ visible, reply with just: NONE";

        for (int ki = 0; ki < apiKeys.size(); ki++) {
            int    keyIdx = (activeKeyIndex + ki) % apiKeys.size();
            String key    = apiKeys.get(keyIdx);

            try {
                Log.d(TAG, "TRY Gemini key#" + (keyIdx + 1));
                setStatus("🤖 Gemini 2.5 Flash · key#" + (keyIdx + 1));

                // Gemini API request format
                JSONObject inlineData = new JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", b64);
                JSONObject body = new JSONObject().put("contents",
                        new JSONArray().put(new JSONObject().put("parts",
                                new JSONArray()
                                        .put(new JSONObject().put("inline_data", inlineData))
                                        .put(new JSONObject().put("text", prompt)))));

                HttpURLConnection conn =
                        (HttpURLConnection) new URL(GEMINI_URL + key).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(30000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes("UTF-8"));
                }

                int code = conn.getResponseCode();
                Log.d(TAG, "HTTP " + code + " key#" + (keyIdx + 1));

                BufferedReader br = new BufferedReader(new InputStreamReader(
                        code == 200 ? conn.getInputStream() : conn.getErrorStream()));
                StringBuilder sb = new StringBuilder();
                String ln;
                while ((ln = br.readLine()) != null) sb.append(ln).append("\n");
                br.close();
                String resp = sb.toString();

                if (code == 200) {
                    // Parse: candidates[0].content.parts[0].text
                    String raw = new JSONObject(resp)
                            .getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts").getJSONObject(0)
                            .getString("text").trim();
                    Log.d(TAG, "GEMINI REPLY: [" + raw + "]");
                    if (ki > 0) { activeKeyIndex = keyIdx; saveKeys(); }
                    parseAndShow(raw);
                    return;

                } else if (code == 429) {
                    retryAfterMs = System.currentTimeMillis() + 65_000;
                    Log.w(TAG, "429 key#" + (keyIdx+1) + " — rate limited, retry after 65s");
                    // Fall through to next key

                } else if (code == 403) {
                    Log.e(TAG, "403 key#" + (keyIdx+1) + " — key invalid/not enabled");
                    setStatus("🔑 Key#" + (keyIdx+1) + " invalid — check aistudio.google.com");

                } else {
                    Log.w(TAG, "HTTP " + code + ": " + resp.substring(0, Math.min(200, resp.length())));
                }

            } catch (Exception e) {
                Log.e(TAG, "key#" + (keyIdx+1) + ": " + e.getMessage());
            }
        }

        Log.w(TAG, "All Gemini keys exhausted");
        busy = false;
        setStatus("⏳ Rate limited — auto-retry in ~60s");
    }

    // ─────────────────────────────────────────────────────────
    // Parse reply and show result
    // ─────────────────────────────────────────────────────────
    private void parseAndShow(String raw) {
        try {
            if (raw.toUpperCase().trim().startsWith("NONE")) {
                busy = false;
                setStatus("🔍 No MCQ in photo — next in 15s");
                return;
            }

            String question = "", answerLine = "", optionLine = "";
            for (String line : raw.split("\n")) {
                String t = line.trim(), tu = t.toUpperCase();
                if      (tu.startsWith("QUESTION:")) question   = t.substring(t.indexOf(':') + 1).trim();
                else if (tu.startsWith("ANSWER:"))   answerLine = t.substring(t.indexOf(':') + 1).trim();
                else if (tu.startsWith("OPTION:"))   optionLine = t.substring(t.indexOf(':') + 1).trim();
            }

            char ans = 'X';
            for (char c : answerLine.toUpperCase().toCharArray())
                if (c=='A'||c=='B'||c=='C'||c=='D') { ans=c; break; }
            if (ans == 'X')
                for (String tok : raw.toUpperCase().split("[^A-Z]+"))
                    if (tok.equals("A")||tok.equals("B")||tok.equals("C")||tok.equals("D"))
                    { ans=tok.charAt(0); break; }

            if (ans == 'X') {
                busy = false;
                setStatus("❓ Couldn't parse answer — next in 15s");
                return;
            }

            if (question.isEmpty()) question = "MCQ detected";
            final char   fa = ans;
            final String fq = question;
            final String fo = optionLine.isEmpty() ? ("Option " + ans) : optionLine;
            mainHandler.post(() -> showResult(fa, fq, fo));

        } catch (Exception e) {
            Log.e(TAG, "parseAndShow", e);
            busy = false;
            setStatus("🔍 Parse error — next in 15s");
        }
    }

    // ─────────────────────────────────────────────────────────
    // Show answer card
    // ─────────────────────────────────────────────────────────
    private void showResult(char answer, String question, String explanation) {
        int color;
        switch (answer) {
            case 'A': color = COLOR_A; break;
            case 'B': color = COLOR_B; break;
            case 'C': color = COLOR_C; break;
            case 'D': color = COLOR_D; break;
            default:  color = 0xFF333333; break;
        }
        fullScreenColor.setBackgroundColor(color);
        fullScreenColor.setAlpha(0f);
        fullScreenColor.setVisibility(View.VISIBLE);
        fullScreenColor.animate().alpha(0.75f).setDuration(350).start();

        tvQuestion.setText(question);
        answerLetter.setText(String.valueOf(answer));
        answerLetter.setBackgroundColor(color);
        tvAnswerText.setText(explanation);

        questionCard.setAlpha(0f);
        questionCard.setVisibility(View.VISIBLE);
        answerLetter.setVisibility(View.VISIBLE);
        tvAnswerText.setVisibility(View.VISIBLE);
        questionCard.animate().alpha(1f).setDuration(350).start();
        setStatus("✅ Answer: " + answer);

        mainHandler.postDelayed(() -> {
            questionCard.animate().alpha(0f).setDuration(400)
                    .withEndAction(() -> questionCard.setVisibility(View.INVISIBLE)).start();
            fullScreenColor.animate().alpha(0f).setDuration(400).withEndAction(() -> {
                fullScreenColor.setVisibility(View.INVISIBLE);
                answerLetter.setVisibility(View.INVISIBLE);
                tvAnswerText.setVisibility(View.INVISIBLE);
                busy = false;
                countdownSecs = 15;
                setStatus("📸 Snapshot in 15s...");
            }).start();
        }, ANSWER_HOLD_MS);
    }

    // ─────────────────────────────────────────────────────────
    // Settings panel
    // ─────────────────────────────────────────────────────────
    private void wireSettingsPanel() {
        btnSettings.setOnClickListener(v -> {
            rebuildKeyList(); settingsPanel.setVisibility(View.VISIBLE); btnSettings.setVisibility(View.GONE);
        });
        View.OnClickListener close = v -> {
            settingsPanel.setVisibility(View.GONE); btnSettings.setVisibility(View.VISIBLE);
        };
        findViewById(R.id.btnCloseSettings).setOnClickListener(close);
        findViewById(R.id.btnCloseSettings2).setOnClickListener(close);
        findViewById(R.id.btnAddKey).setOnClickListener(v -> {
            String key = etNewKey.getText().toString().trim();
            if (key.isEmpty()) { Toast.makeText(this, "Paste a key first", Toast.LENGTH_SHORT).show(); return; }
            if (apiKeys.contains(key)) { Toast.makeText(this, "Already added", Toast.LENGTH_SHORT).show(); return; }
            apiKeys.add(key); saveKeys(); etNewKey.setText(""); rebuildKeyList();
            Toast.makeText(this, "✓ Gemini Key #" + apiKeys.size() + " added", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnClearAllKeys).setOnClickListener(v -> {
            apiKeys.clear(); activeKeyIndex = 0; saveKeys(); rebuildKeyList();
            Toast.makeText(this, "All keys cleared", Toast.LENGTH_SHORT).show();
        });
    }

    private void rebuildKeyList() {
        keysContainer.removeAllViews();
        tvKeyCount.setText(apiKeys.size() + " Gemini key" + (apiKeys.size() == 1 ? "" : "s") + " saved");
        tvActiveKey.setText(apiKeys.isEmpty() ? "Active: — (no keys)"
                : "Active: Key #" + (activeKeyIndex + 1) + " (" + mask(apiKeys.get(activeKeyIndex)) + ")");
        for (int i = 0; i < apiKeys.size(); i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(0, 8, 0, 8);
            TextView tv = new TextView(this);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tv.setText("#" + (i+1) + "  " + mask(apiKeys.get(i)));
            tv.setTextColor(activeKeyIndex == i ? 0xFF44FF88 : 0xFFCCCCCC); tv.setTextSize(13);
            row.addView(tv);
            Button use = new Button(this);
            use.setText(activeKeyIndex == i ? "ACTIVE" : "USE"); use.setTextSize(11); use.setPadding(16,4,16,4);
            use.setBackgroundColor(activeKeyIndex == i ? 0xFF226622 : 0xFF444444); use.setTextColor(Color.WHITE);
            use.setOnClickListener(v2 -> { activeKeyIndex = idx; saveKeys(); rebuildKeyList(); });
            row.addView(use);
            Button del = new Button(this);
            del.setText("✕"); del.setTextSize(13); del.setPadding(16,4,16,4);
            del.setBackgroundColor(0xFF881111); del.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(8); del.setLayoutParams(lp);
            del.setOnClickListener(v2 -> { apiKeys.remove(idx); if (activeKeyIndex >= apiKeys.size()) activeKeyIndex = 0; saveKeys(); rebuildKeyList(); });
            row.addView(del);
            keysContainer.addView(row);
            View div = new View(this);
            div.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
            div.setBackgroundColor(0xFF333333); keysContainer.addView(div);
        }
    }

    private String mask(String k) {
        if (k == null || k.length() < 12) return "***";
        return k.substring(0, 8) + "..." + k.substring(k.length() - 4);
    }

    private void saveKeys() {
        try {
            JSONArray arr = new JSONArray();
            for (String k : apiKeys) arr.put(k);
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(PREFS_KEYS, arr.toString()).putInt(PREFS_IDX, activeKeyIndex).apply();
        } catch (Exception ignored) {}
    }

    private void loadKeys() {
        try {
            SharedPreferences p = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(p.getString(PREFS_KEYS, "[]"));
            apiKeys.clear();
            for (int i = 0; i < arr.length(); i++) apiKeys.add(arr.getString(i));
            activeKeyIndex = p.getInt(PREFS_IDX, 0);
            if (activeKeyIndex >= apiKeys.size()) activeKeyIndex = 0;
        } catch (Exception e) { apiKeys.clear(); activeKeyIndex = 0; }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == CAM_PERM && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show();
    }

    private void setStatus(String msg) { mainHandler.post(() -> statusText.setText(msg)); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countdownTick   != null) mainHandler.removeCallbacks(countdownTick);
        if (snapshotTrigger != null) mainHandler.removeCallbacks(snapshotTrigger);
        captureExecutor.shutdown();
    }
}