package com.braingods.mcqmaster;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.ai.client.generativeai.GenerativeModel;
import com.google.ai.client.generativeai.java.GenerativeModelFutures;
import com.google.ai.client.generativeai.type.Content;
import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.ai.client.generativeai.type.GenerationConfig;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AnalysisActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_URI = "image_uri";
    private static final String TAG = "AnalysisActivity";
    private static final String GEMINI_MODEL = "gemini-2.5-flash";

    private static final int COLOR_A = 0xFFD32F2F;
    private static final int COLOR_B = 0xFF1565C0;
    private static final int COLOR_C = 0xFF2E7D32;
    private static final int COLOR_D = 0xFFE65100;
    private static final int AUTO_RETURN_SECS = 5;

    private ImageView    ivPhoto;
    private ProgressBar  progressBar;
    private TextView     tvAnalyzing;
    private View         resultContainer;
    private View         fullColorBg;
    private TextView     tvAnswerLetter;
    private TextView     tvQuestion;
    private TextView     tvOptionText;
    private TextView     tvKeyInfo;
    private TextView     tvNoMcq;
    private TextView     tvCountdown;
    private LinearLayout btnNextCapture;

    private final Handler         handler  = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Runnable              autoReturnTask;
    private int                   autoReturnSecs = AUTO_RETURN_SECS;

    private static final String MCQ_PROMPT =
            "You are an MCQ answer detector. Examine this image carefully.\n\n" +
                    "Is there a Multiple Choice Question (MCQ) visible?\n\n" +
                    "Respond ONLY in this exact format, no extra text:\n" +
                    "MCQ: YES\n" +
                    "QUESTION: <the full question text>\n" +
                    "ANSWER: <A or B or C or D>\n" +
                    "OPTION: <full text of the correct answer option>\n\n" +
                    "If NO MCQ is found, respond ONLY:\n" +
                    "MCQ: NO\n\n" +
                    "Rules:\n" +
                    "- ANSWER must be exactly one letter: A, B, C, or D\n" +
                    "- If options are numbered 1-4, map: 1->A, 2->B, 3->C, 4->D\n" +
                    "- Pick the single best/correct answer";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        setContentView(R.layout.activity_analysis);

        ivPhoto        = findViewById(R.id.ivPhoto);
        progressBar    = findViewById(R.id.progressBar);
        tvAnalyzing    = findViewById(R.id.tvAnalyzing);
        resultContainer= findViewById(R.id.resultContainer);
        fullColorBg    = findViewById(R.id.fullColorBg);
        tvAnswerLetter = findViewById(R.id.tvAnswerLetter);
        tvQuestion     = findViewById(R.id.tvQuestion);
        tvOptionText   = findViewById(R.id.tvOptionText);
        tvKeyInfo      = findViewById(R.id.tvKeyInfo);
        tvNoMcq        = findViewById(R.id.tvNoMcq);
        tvCountdown    = findViewById(R.id.tvCountdown);
        btnNextCapture = findViewById(R.id.btnNextCapture);

        resultContainer.setVisibility(View.GONE);
        tvNoMcq.setVisibility(View.GONE);

        ProviderManager pm = ProviderManager.get();
        pm.load(this);
        String label;
        switch (pm.getActiveProvider()) {
            case GROQ:       label = "Groq  |  " + ProviderManager.GROQ_MODEL; break;
            case OPENROUTER: label = "OpenRouter  |  " + pm.getOrModel(); break;
            default:         label = "Gemini  |  " + GEMINI_MODEL;
        }
        tvKeyInfo.setText(label);

        btnNextCapture.setOnClickListener(v -> goBack());
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        String uriStr = getIntent().getStringExtra(EXTRA_IMAGE_URI);
        if (uriStr != null && !uriStr.isEmpty()) {
            Uri uri = Uri.parse(uriStr);
            displayThumbnail(uri);
            analyzeImage(uri);
        } else {
            setAnalyzingText("No image received");
            startAutoReturn();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopAutoReturn();
        executor.shutdown();
    }

    private void displayThumbnail(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (bmp != null) ivPhoto.setImageBitmap(bmp);
        } catch (Exception e) { Log.w(TAG, "thumb", e); }
    }

    private void analyzeImage(Uri uri) {
        switch (ProviderManager.get().getActiveProvider()) {
            case GROQ:       analyzeWithGroq(uri);       break;
            case OPENROUTER: analyzeWithOpenRouter(uri); break;
            default:         analyzeWithGemini(uri);
        }
    }

    // ── 1. GEMINI SDK ──────────────────────────────────────────
    private void analyzeWithGemini(Uri uri) {
        executor.execute(() -> {
            GeminiKeyManager mgr = GeminiKeyManager.get();
            if (mgr.getKeyCount() == 0) { showError("No Gemini key — add one first"); return; }

            Bitmap bitmap = loadBitmap(uri, 1024);
            if (bitmap == null) { showError("Could not read image"); return; }

            Content content = new Content.Builder().addImage(bitmap).addText(MCQ_PROMPT).build();
            GenerationConfig.Builder cb = new GenerationConfig.Builder();
            cb.temperature = 0.1f;
            cb.maxOutputTokens = 400;
            GenerationConfig config = cb.build();

            int total = mgr.getKeyCount(), tried = 0;
            String lastErr = null;

            while (tried < total) {
                String key = mgr.getCurrentKey();
                final int n = mgr.getActiveIndex() + 1;
                updateStatus("Gemini (Key #" + n + "/" + total + ")...");
                try {
                    GenerativeModel model = new GenerativeModel(GEMINI_MODEL, key, config);
                    ListenableFuture<GenerateContentResponse> f =
                            GenerativeModelFutures.from(model).generateContent(content);
                    String text = f.get().getText();
                    runOnUiThread(() -> parseAndShow(text));
                    return;
                } catch (ExecutionException ee) {
                    lastErr = ee.getCause() != null ? ee.getCause().getMessage() : ee.getMessage();
                    if (isQuota(lastErr)) {
                        tried++;
                        if (tried < total) { mgr.forceRotateAndReset(this); toast("Key #"+n+" quota -> Key #"+(mgr.getActiveIndex()+1)); sleep(600); }
                    } else break;
                } catch (InterruptedException ie) { Thread.currentThread().interrupt(); lastErr = "Interrupted"; break; }
            }
            showError("Gemini failed: " + cut(lastErr));
        });
    }

    // ── 2. GROQ ────────────────────────────────────────────────
    private void analyzeWithGroq(Uri uri) {
        executor.execute(() -> {
            ProviderManager pm = ProviderManager.get();
            if (pm.getGroqKeyCount() == 0) { showError("No Groq key — add one first"); return; }

            String b64 = toBase64(uri, 1024);
            if (b64 == null) { showError("Could not read image"); return; }

            int total = pm.getGroqKeyCount(), tried = 0;
            String lastErr = null;

            while (tried < total) {
                String key = pm.getCurrentGroqKey();
                final int n = pm.getGroqActiveIndex() + 1;
                updateStatus("Groq (Key #" + n + "/" + total + ")...");
                try {
                    String body = buildVisionBody(ProviderManager.GROQ_MODEL, b64, MCQ_PROMPT);
                    HttpRes res = post(ProviderManager.GROQ_URL, key, body);
                    if (res.err == null) { runOnUiThread(() -> parseAndShow(openAiText(res.body))); return; }
                    lastErr = res.err;
                    if (res.code == 429 || isQuota(lastErr)) {
                        tried++;
                        if (tried < total) { pm.rotateGroqKey(this); toast("Groq Key #"+n+" limit -> Key #"+(pm.getGroqActiveIndex()+1)); sleep(600); }
                    } else break;
                } catch (Exception e) { lastErr = e.getMessage(); break; }
            }
            showError("Groq failed: " + cut(lastErr));
        });
    }

    // ── 3. OPENROUTER ──────────────────────────────────────────
    private void analyzeWithOpenRouter(Uri uri) {
        executor.execute(() -> {
            ProviderManager pm = ProviderManager.get();
            if (pm.getOrKeyCount() == 0) { showError("No OpenRouter key — add one first"); return; }

            String b64 = toBase64(uri, 1024);
            if (b64 == null) { showError("Could not read image"); return; }

            String model = pm.getOrModel();
            int total = pm.getOrKeyCount(), tried = 0;
            String lastErr = null;

            while (tried < total) {
                String key = pm.getCurrentOrKey();
                final int n = pm.getOrActiveIndex() + 1;
                updateStatus("OpenRouter (Key #" + n + "/" + total + ")...");
                try {
                    String body = buildVisionBody(model, b64, MCQ_PROMPT);
                    HttpRes res = postWithHeaders(ProviderManager.OR_URL, key, body,
                            "HTTP-Referer", "https://mcqmaster.app", "X-Title", "MCQ Master");
                    if (res.err == null) { runOnUiThread(() -> parseAndShow(openAiText(res.body))); return; }
                    lastErr = res.err;
                    if (res.code == 429 || isQuota(lastErr)) {
                        tried++;
                        if (tried < total) { pm.rotateOrKey(this); toast("OR Key #"+n+" limit -> Key #"+(pm.getOrActiveIndex()+1)); sleep(600); }
                    } else break;
                } catch (Exception e) { lastErr = e.getMessage(); break; }
            }
            showError("OpenRouter failed: " + cut(lastErr));
        });
    }

    // ── HTTP helpers ───────────────────────────────────────────
    private static class HttpRes { String body, err; int code; }

    private String buildVisionBody(String model, String b64, String prompt) throws Exception {
        JSONObject img = new JSONObject().put("type","image_url")
                .put("image_url", new JSONObject().put("url","data:image/jpeg;base64,"+b64));
        JSONObject txt = new JSONObject().put("type","text").put("text", prompt);
        JSONObject msg = new JSONObject().put("role","user")
                .put("content", new JSONArray().put(img).put(txt));
        return new JSONObject().put("model", model)
                .put("messages", new JSONArray().put(msg))
                .put("max_tokens", 400).put("temperature", 0.1).toString();
    }

    private HttpRes post(String url, String token, String body) {
        return postWithHeaders(url, token, body);
    }

    private HttpRes postWithHeaders(String urlStr, String token, String body, String... extras) {
        HttpRes r = new HttpRes();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type","application/json; charset=utf-8");
            c.setRequestProperty("Authorization","Bearer "+token);
            for (int i = 0; i+1 < extras.length; i+=2) c.setRequestProperty(extras[i], extras[i+1]);
            c.setDoOutput(true); c.setConnectTimeout(30000); c.setReadTimeout(60000);
            try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes("UTF-8")); }
            r.code = c.getResponseCode();
            InputStream s = r.code == 200 ? c.getInputStream() : c.getErrorStream();
            if (s != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(s,"UTF-8"));
                StringBuilder sb = new StringBuilder(); String ln;
                while ((ln = br.readLine()) != null) sb.append(ln);
                String raw = sb.toString();
                if (r.code == 200) { r.body = raw; }
                else { try { r.err = new JSONObject(raw).getJSONObject("error").getString("message"); } catch (Exception e) { r.err = raw.isEmpty() ? "HTTP "+r.code : raw; } }
            } else { r.err = "HTTP "+r.code; }
        } catch (Exception e) { r.err = e.getMessage(); r.code = -1; }
        return r;
    }

    private String openAiText(String json) {
        try { return new JSONObject(json).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim(); }
        catch (Exception e) { return ""; }
    }

    // ── Parse model response ───────────────────────────────────
    private void parseAndShow(String raw) {
        if (raw == null || raw.isEmpty()) { showNoMcq("Empty response from model"); return; }
        boolean isMcq = false; String question = ""; char answer = 'X'; String option = "";
        for (String line : raw.split("\n")) {
            String t = line.trim(), u = t.toUpperCase();
            if      (u.startsWith("MCQ:"))      isMcq    = u.contains("YES");
            else if (u.startsWith("QUESTION:")) question = t.substring(t.indexOf(':')+1).trim();
            else if (u.startsWith("ANSWER:"))   { String a = t.substring(t.indexOf(':')+1).trim().toUpperCase(); for (char c : a.toCharArray()) if (c=='A'||c=='B'||c=='C'||c=='D') { answer=c; break; } }
            else if (u.startsWith("OPTION:"))   option   = t.substring(t.indexOf(':')+1).trim();
        }
        if (answer == 'X' && isMcq) for (String tok : raw.toUpperCase().split("[^A-Z]+"))
            if (tok.equals("A")||tok.equals("B")||tok.equals("C")||tok.equals("D")) { answer=tok.charAt(0); break; }

        progressBar.setVisibility(View.GONE); tvAnalyzing.setVisibility(View.GONE);
        if (!isMcq || answer == 'X') showNoMcq(isMcq ? "Found MCQ but couldn't identify answer" : "No MCQ in this image");
        else showAnswer(answer, question.isEmpty() ? "MCQ detected" : question, option.isEmpty() ? "Option "+answer : option);
    }

    private void showAnswer(char answer, String question, String optionText) {
        int color; switch (answer) { case 'A': color=COLOR_A; break; case 'B': color=COLOR_B; break; case 'C': color=COLOR_C; break; case 'D': color=COLOR_D; break; default: color=0xFF333344; }
        fullColorBg.setBackgroundColor(color); fullColorBg.setAlpha(0f); fullColorBg.setVisibility(View.VISIBLE); fullColorBg.animate().alpha(0.85f).setDuration(400).start();
        tvAnswerLetter.setText(String.valueOf(answer)); tvAnswerLetter.setBackgroundColor(color);
        tvQuestion.setText(question); tvOptionText.setText(optionText);
        resultContainer.setAlpha(0f); resultContainer.setVisibility(View.VISIBLE); resultContainer.animate().alpha(1f).setDuration(400).start();
        tvNoMcq.setVisibility(View.GONE); startAutoReturn();
    }

    private void showNoMcq(String msg) {
        fullColorBg.setVisibility(View.GONE); resultContainer.setVisibility(View.GONE);
        tvNoMcq.setText(msg); tvNoMcq.setVisibility(View.VISIBLE); startAutoReturn();
    }

    private void startAutoReturn() {
        autoReturnSecs = AUTO_RETURN_SECS; tvCountdown.setVisibility(View.VISIBLE); stopAutoReturn();
        autoReturnTask = new Runnable() { @Override public void run() {
            autoReturnSecs--; tvCountdown.setText("Back in "+autoReturnSecs+"s");
            if (autoReturnSecs <= 0) goBack(); else handler.postDelayed(this, 1000);
        }};
        handler.postDelayed(autoReturnTask, 1000);
    }

    private void stopAutoReturn() { if (autoReturnTask != null) { handler.removeCallbacks(autoReturnTask); autoReturnTask = null; } }
    private void goBack() { stopAutoReturn(); finish(); }

    private Bitmap loadBitmap(Uri uri, int maxPx) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (bmp == null) return null;
            if (bmp.getWidth() <= maxPx && bmp.getHeight() <= maxPx) return bmp;
            float s = Math.min((float)maxPx/bmp.getWidth(), (float)maxPx/bmp.getHeight());
            return Bitmap.createScaledBitmap(bmp, (int)(bmp.getWidth()*s), (int)(bmp.getHeight()*s), true);
        } catch (Exception e) { return null; }
    }

    private String toBase64(Uri uri, int maxPx) {
        try {
            Bitmap bmp = loadBitmap(uri, maxPx); if (bmp == null) return null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) { return null; }
    }

    private boolean isQuota(String e) { if (e==null) return false; String l=e.toLowerCase(); return l.contains("429")||l.contains("quota")||l.contains("rate")||l.contains("exhausted")||l.contains("limit"); }
    private String cut(String s) { if (s==null) return "Unknown"; return s.substring(0, Math.min(s.length(),160)); }
    private void showError(String m) { Log.e(TAG,m); runOnUiThread(() -> { setAnalyzingText(m); startAutoReturn(); }); }
    private void updateStatus(String m) { runOnUiThread(() -> setAnalyzingText(m)); }
    private void toast(String m) { runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show()); }
    private void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
    private void setAnalyzingText(String m) { progressBar.setVisibility(View.GONE); tvAnalyzing.setText(m); }
}