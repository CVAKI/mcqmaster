package com.braingods.mcqmaster;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PhotoCaptureActivity extends AppCompatActivity {

    private static final String TAG       = "PhotoCapture";
    private static final int    CAM_PERM  = 101;
    private static final int    COUNTDOWN = 5;

    private PreviewView     previewView;
    private TextView        tvCountdown;
    private TextView        tvStatus;
    private TextView        tvKeyInfo;
    private View            flashOverlay;

    private ImageCapture    imageCapture;
    private ExecutorService executor;
    private final Handler   handler = new Handler(Looper.getMainLooper());

    private int      countdownSecs = COUNTDOWN;
    private Runnable countdownTick;
    private boolean  capturing     = false;
    private boolean  cameraReady   = false;

    // ── Lifecycle ──────────────────────────────────────────────
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

        setContentView(R.layout.activity_photo_capture);

        previewView  = findViewById(R.id.previewView);
        tvCountdown  = findViewById(R.id.tvCountdown);
        tvStatus     = findViewById(R.id.tvStatus);
        tvKeyInfo    = findViewById(R.id.tvKeyInfo);
        flashOverlay = findViewById(R.id.flashOverlay);

        executor = Executors.newSingleThreadExecutor();
        updateKeyInfo();

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnManualCapture).setOnClickListener(v -> {
            if (!capturing && cameraReady) {
                stopCountdown();
                triggerCapture();
            }
        });

        if (hasCameraPermission()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAM_PERM);
        }
    }

    /** Called when returning from AnalysisActivity — restart the loop */
    @Override
    protected void onResume() {
        super.onResume();
        capturing = false;
        updateKeyInfo();
        if (cameraReady) {
            stopCountdown();
            startCountdown();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopCountdown();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCountdown();
        if (executor != null) executor.shutdown();
    }

    // ── Camera ─────────────────────────────────────────────────
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

                cameraReady = true;
                startCountdown();

            } catch (Exception e) {
                Log.e(TAG, "Camera init failed", e);
                setStatus("❌ Camera error: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ── 10-second Countdown ────────────────────────────────────
    private void startCountdown() {
        countdownSecs = COUNTDOWN;
        tvCountdown.setTextColor(0xFF44FF88);
        tvCountdown.setText(String.valueOf(COUNTDOWN));
        setStatus("📸 Auto-capture in " + COUNTDOWN + "s...");

        countdownTick = new Runnable() {
            @Override
            public void run() {
                countdownSecs--;
                tvCountdown.setText(String.valueOf(Math.max(0, countdownSecs)));

                if (countdownSecs > 6)      tvCountdown.setTextColor(0xFF44FF88);
                else if (countdownSecs > 3) tvCountdown.setTextColor(0xFFFFCC00);
                else                        tvCountdown.setTextColor(0xFFFF4444);

                if (countdownSecs <= 0) {
                    triggerCapture();
                } else {
                    setStatus("📸 Auto-capture in " + countdownSecs + "s...");
                    handler.postDelayed(this, 1000);
                }
            }
        };
        handler.postDelayed(countdownTick, 1000);
    }

    private void stopCountdown() {
        if (countdownTick != null) {
            handler.removeCallbacks(countdownTick);
            countdownTick = null;
        }
    }

    // ── Capture Photo → Gallery ────────────────────────────────
    private void triggerCapture() {
        if (capturing || imageCapture == null) return;
        capturing = true;
        setStatus("📷 Capturing...");
        tvCountdown.setText("📷");
        tvCountdown.setTextColor(0xFFFFFFFF);

        // Flash overlay
        flashOverlay.setAlpha(1f);
        flashOverlay.setVisibility(View.VISIBLE);
        flashOverlay.animate().alpha(0f).setDuration(300)
                .withEndAction(() -> flashOverlay.setVisibility(View.GONE)).start();

        // Millisecond timestamp → every filename is unique, no UNIQUE constraint clash
        String stamp    = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        String fileName = "MCQ_" + stamp + ".jpg";

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/MCQMaster");
        }

        // ⚠️  DO NOT call getContentResolver().insert() before building options.
        //     CameraX inserts into MediaStore itself.  Pre-inserting causes a
        //     UNIQUE constraint SQLite crash on every capture attempt.
        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(
                        getContentResolver(),
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cv)
                        .build();

        imageCapture.takePicture(options, executor,
                new ImageCapture.OnImageSavedCallback() {

                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                        Uri savedUri = results.getSavedUri();
                        Log.d(TAG, "Photo saved: " + savedUri);
                        runOnUiThread(() -> setStatus("✅ Saved! Analysing..."));

                        handler.postDelayed(() -> {
                            Intent intent = new Intent(
                                    PhotoCaptureActivity.this, AnalysisActivity.class);
                            if (savedUri != null) {
                                intent.putExtra(AnalysisActivity.EXTRA_IMAGE_URI,
                                        savedUri.toString());
                            }
                            startActivity(intent);
                            // onResume() will reset capturing=false and restart countdown
                        }, 300);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        Log.e(TAG, "Capture failed", e);
                        runOnUiThread(() -> {
                            setStatus("❌ " + e.getMessage() + " — retrying in 5s");
                            capturing = false;
                            handler.postDelayed(() -> startCountdown(), 5000);
                        });
                    }
                });
    }

    // ── Permissions ────────────────────────────────────────────
    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == CAM_PERM && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void updateKeyInfo() {
        GeminiKeyManager mgr = GeminiKeyManager.get();
        tvKeyInfo.setText("Key #" + (mgr.getActiveIndex() + 1) + "/"
                + mgr.getKeyCount() + "  " + mgr.getMaskedCurrent());
    }

    private void setStatus(String msg) {
        runOnUiThread(() -> tvStatus.setText(msg));
    }
}