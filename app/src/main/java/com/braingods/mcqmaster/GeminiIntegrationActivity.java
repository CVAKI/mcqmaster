package com.braingods.mcqmaster;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

public class GeminiIntegrationActivity extends AppCompatActivity {

    private static final int TAB_GEMINI = 0;
    private static final int TAB_GROQ   = 1;
    private static final int TAB_OR     = 2;
    private int currentTab = TAB_GEMINI;

    private Button       btnTabGemini, btnTabGroq, btnTabOr;
    private LinearLayout keysContainer;
    private TextView     tvKeyCount, tvActiveKey, tvRotateTimer;
    private EditText     etNewKey, etOrModel;
    private LinearLayout layoutOrModel;
    private Button       btnStart;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timerTick;
    private long rotateCountdownSec = 120;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_gemini_integration);

        ProviderManager.get().load(this);
        GeminiKeyManager.get().init(this);

        keysContainer = findViewById(R.id.keysContainer);
        tvKeyCount    = findViewById(R.id.tvKeyCount);
        tvActiveKey   = findViewById(R.id.tvActiveKey);
        tvRotateTimer = findViewById(R.id.tvRotateTimer);
        etNewKey      = findViewById(R.id.etNewKey);
        btnStart      = findViewById(R.id.btnStart);
        btnTabGemini  = findViewById(R.id.btnTabGemini);
        btnTabGroq    = findViewById(R.id.btnTabGroq);
        btnTabOr      = findViewById(R.id.btnTabOr);
        etOrModel     = findViewById(R.id.etOrModel);
        layoutOrModel = findViewById(R.id.layoutOrModel);

        etOrModel.setText(ProviderManager.get().getOrModel());

        switch (ProviderManager.get().getActiveProvider()) {
            case GROQ:       currentTab = TAB_GROQ; break;
            case OPENROUTER: currentTab = TAB_OR;   break;
            default:         currentTab = TAB_GEMINI;
        }

        btnTabGemini.setOnClickListener(v -> switchTab(TAB_GEMINI));
        btnTabGroq.setOnClickListener(v -> switchTab(TAB_GROQ));
        btnTabOr.setOnClickListener(v -> switchTab(TAB_OR));

        findViewById(R.id.btnAddKey).setOnClickListener(v -> {
            String key = etNewKey.getText().toString().trim();
            if (key.isEmpty()) { Toast.makeText(this,"Paste your API key first",Toast.LENGTH_SHORT).show(); return; }
            boolean added; int count;
            switch (currentTab) {
                case TAB_GROQ: added=ProviderManager.get().addGroqKey(key,this); count=ProviderManager.get().getGroqKeyCount(); break;
                case TAB_OR:   added=ProviderManager.get().addOrKey(key,this);   count=ProviderManager.get().getOrKeyCount();   break;
                default:       added=GeminiKeyManager.get().addKey(key,this);    count=GeminiKeyManager.get().getKeyCount();
            }
            if (added) { etNewKey.setText(""); refreshKeyList(); Toast.makeText(this,"Key #"+count+" added!",Toast.LENGTH_SHORT).show(); }
            else Toast.makeText(this,"Key already exists",Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnClearAll).setOnClickListener(v -> {
            switch (currentTab) {
                case TAB_GROQ: ProviderManager.get().clearGroqKeys(this); break;
                case TAB_OR:   ProviderManager.get().clearOrKeys(this);   break;
                default:       GeminiKeyManager.get().clearAll(this);
            }
            refreshKeyList();
            Toast.makeText(this,"All keys cleared",Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnForceRotate).setOnClickListener(v -> {
            if (currentTab == TAB_GEMINI) {
                if (GeminiKeyManager.get().getKeyCount() < 2) { Toast.makeText(this,"Need 2+ keys",Toast.LENGTH_SHORT).show(); return; }
                GeminiKeyManager.get().forceRotateAndReset(this); rotateCountdownSec = 120;
            } else { Toast.makeText(this,"Rotation auto on quota errors",Toast.LENGTH_SHORT).show(); }
        });

        findViewById(R.id.btnSaveOrModel).setOnClickListener(v -> {
            String m = etOrModel.getText().toString().trim();
            if (m.isEmpty()) m = ProviderManager.OR_DEFAULT_MODEL;
            ProviderManager.get().setOrModel(m, this);
            Toast.makeText(this,"Model saved: "+m,Toast.LENGTH_SHORT).show();
            refreshKeyList();
        });

        btnStart.setOnClickListener(v -> {
            boolean hasKey;
            switch (currentTab) {
                case TAB_GROQ: hasKey = ProviderManager.get().getGroqKeyCount() > 0; break;
                case TAB_OR:   hasKey = ProviderManager.get().getOrKeyCount() > 0;   break;
                default:       hasKey = GeminiKeyManager.get().getKeyCount() > 0;
            }
            if (!hasKey) { Toast.makeText(this,"Add at least 1 API key!",Toast.LENGTH_LONG).show(); return; }
            ProviderManager.Provider p;
            switch (currentTab) {
                case TAB_GROQ: p = ProviderManager.Provider.GROQ; break;
                case TAB_OR:   p = ProviderManager.Provider.OPENROUTER; break;
                default:       p = ProviderManager.Provider.GEMINI;
            }
            ProviderManager.get().setActiveProvider(p, this);
            startActivity(new Intent(this, PhotoCaptureActivity.class));
        });

        GeminiKeyManager.get().setOnKeyChangedListener((idx, masked) -> runOnUiThread(() -> { rotateCountdownSec = 120; refreshKeyList(); }));
        switchTab(currentTab);
        startRotateTimer();
    }

    private void switchTab(int tab) {
        currentTab = tab;
        int on = 0xFF1565C0, off = 0xFF2A2A3A;
        btnTabGemini.setBackgroundColor(tab == TAB_GEMINI ? on : off);
        btnTabGroq.setBackgroundColor(tab == TAB_GROQ ? on : off);
        btnTabOr.setBackgroundColor(tab == TAB_OR ? on : off);
        layoutOrModel.setVisibility(tab == TAB_OR ? View.VISIBLE : View.GONE);
        refreshKeyList();
    }

    private void startRotateTimer() {
        timerTick = new Runnable() { @Override public void run() {
            rotateCountdownSec--; if (rotateCountdownSec < 0) rotateCountdownSec = 120;
            tvRotateTimer.setText(String.format("Auto-rotate in %d:%02d", rotateCountdownSec/60, rotateCountdownSec%60));
            handler.postDelayed(this, 1000);
        }};
        handler.post(timerTick);
    }

    private void refreshKeyList() {
        List<String> all; int aIdx; String header;
        switch (currentTab) {
            case TAB_GROQ:
                all = ProviderManager.get().getAllGroqKeys(); aIdx = ProviderManager.get().getGroqActiveIndex();
                header = "Groq  |  " + ProviderManager.GROQ_MODEL; break;
            case TAB_OR:
                all = ProviderManager.get().getAllOrKeys(); aIdx = ProviderManager.get().getOrActiveIndex();
                header = "OpenRouter  |  " + ProviderManager.get().getOrModel(); break;
            default:
                all = GeminiKeyManager.get().getAllKeys(); aIdx = GeminiKeyManager.get().getActiveIndex();
                header = "Gemini  |  gemini-2.5-flash";
        }
        tvKeyCount.setText(header + "   [" + all.size() + " key" + (all.size()==1?"":"s") + "]");
        if (all.isEmpty()) { tvActiveKey.setText("Active: none"); }
        else {
            String mk;
            switch (currentTab) {
                case TAB_GROQ: mk = ProviderManager.get().mask(ProviderManager.get().getCurrentGroqKey()); break;
                case TAB_OR:   mk = ProviderManager.get().mask(ProviderManager.get().getCurrentOrKey());   break;
                default:       mk = GeminiKeyManager.get().getMaskedCurrent();
            }
            tvActiveKey.setText("Active: Key #"+(aIdx+1)+"  "+mk);
        }
        btnStart.setEnabled(!all.isEmpty()); btnStart.setAlpha(all.isEmpty()?0.4f:1f);

        keysContainer.removeAllViews();
        for (int i = 0; i < all.size(); i++) {
            final int idx = i; boolean active = (i == aIdx);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(16,12,16,12);
            row.setBackgroundColor(active ? 0xFF1A3A1A : 0xFF1E1E2E);

            TextView badge = new TextView(this);
            badge.setText("#"+(i+1)); badge.setTextColor(active?0xFF44FF88:0xFF888888);
            badge.setTextSize(13); badge.setPadding(0,0,12,0); row.addView(badge);

            TextView tv = new TextView(this);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tv.setText(ProviderManager.get().mask(all.get(i)));
            tv.setTextColor(active?0xFF44FF88:0xFFCCCCCC); tv.setTextSize(13); row.addView(tv);

            Button use = new Button(this);
            use.setText(active ? "ACTIVE" : "USE"); use.setTextSize(11); use.setPadding(20,4,20,4);
            use.setBackgroundColor(active?0xFF226622:0xFF334455); use.setTextColor(Color.WHITE); use.setEnabled(!active);
            use.setOnClickListener(v -> {
                switch (currentTab) {
                    case TAB_GROQ: getSharedPreferences("mcq_prefs",MODE_PRIVATE).edit().putInt("groq_key_idx",idx).apply(); ProviderManager.get().load(this); break;
                    case TAB_OR:   getSharedPreferences("mcq_prefs",MODE_PRIVATE).edit().putInt("or_key_idx",idx).apply();   ProviderManager.get().load(this); break;
                    default:       getSharedPreferences("mcq_prefs",MODE_PRIVATE).edit().putInt("active_key_idx",idx).apply(); GeminiKeyManager.get().load(this);
                }
                rotateCountdownSec = 120; refreshKeyList();
            });
            LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ulp.setMarginStart(8); use.setLayoutParams(ulp); row.addView(use);

            Button del = new Button(this);
            del.setText("X"); del.setTextSize(14); del.setPadding(20,4,20,4);
            del.setBackgroundColor(0xFF881111); del.setTextColor(Color.WHITE);
            del.setOnClickListener(v -> {
                switch (currentTab) {
                    case TAB_GROQ: ProviderManager.get().removeGroqKey(idx,this); break;
                    case TAB_OR:   ProviderManager.get().removeOrKey(idx,this);   break;
                    default:       GeminiKeyManager.get().removeKey(idx,this);
                }
                refreshKeyList();
            });
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.setMarginStart(8); del.setLayoutParams(dlp); row.addView(del);
            keysContainer.addView(row);

            View div = new View(this);
            div.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1));
            div.setBackgroundColor(0xFF2A2A3A); keysContainer.addView(div);
        }
    }

    @Override protected void onResume() { super.onResume(); ProviderManager.get().load(this); refreshKeyList(); }
    @Override protected void onDestroy() { super.onDestroy(); if (timerTick!=null) handler.removeCallbacks(timerTick); GeminiKeyManager.get().stopRotation(); }
}