package com.edwardresearchlabs.blue;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_MIC = 10;
    private BlueView blueView;
    private volatile boolean listening;
    private Thread audioThread;
    private AudioRecord recorder;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF030303);
        getWindow().setNavigationBarColor(0xFF030303);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        blueView = new BlueView(this);
        setContentView(blueView);

        blueView.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            blueView.setMessage("Microphone permission is required.");
        }
    }

    @Override protected void onDestroy() {
        listening = false;
        if (audioThread != null) audioThread.interrupt();
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        super.onDestroy();
    }

    private AudioRecord createRecorder(int sampleRate, int minBuffer) {
        int[] sources = {
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };
        for (int source : sources) {
            try {
                AudioRecord r = new AudioRecord(
                        source,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(minBuffer * 2, 8192)
                );
                if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
                r.release();
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private void startListening() {
        if (listening) return;
        listening = true;
        audioThread = new Thread(() -> {
            final int sampleRate = 44100;
            final int frame = 2048;
            int minBuffer = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            recorder = createRecorder(sampleRate, minBuffer);
            if (recorder == null) {
                runOnUiThread(() -> blueView.setMessage("Could not open microphone."));
                listening = false;
                return;
            }

            short[] readBuffer = new short[Math.max(1024, minBuffer / 2)];
            short[] window = new short[frame];
            int filled = 0;
            PitchDetector detector = new PitchDetector(sampleRate, frame);
            Deque<Double> history = new ArrayDeque<>();

            try {
                recorder.startRecording();
                runOnUiThread(blueView::onMicStarted);

                while (listening && !Thread.currentThread().isInterrupted()) {
                    int n = recorder.read(readBuffer, 0, readBuffer.length);
                    if (n <= 0) continue;

                    int offset = 0;
                    while (offset < n) {
                        int copy = Math.min(frame - filled, n - offset);
                        System.arraycopy(readBuffer, offset, window, filled, copy);
                        filled += copy;
                        offset += copy;

                        if (filled == frame) {
                            PitchDetector.Result result = detector.detect(window, frame);
                            double smooth = result.frequency;

                            if (smooth > 0) {
                                history.addLast(smooth);
                                while (history.size() > 3) history.removeFirst();
                                Double[] values = history.toArray(new Double[0]);
                                Arrays.sort(values);
                                smooth = values[values.length / 2];
                            } else {
                                history.clear();
                            }

                            final double hz = smooth;
                            final double rms = result.rms;
                            runOnUiThread(() -> blueView.onPitch(hz, rms));

                            System.arraycopy(window, frame / 2, window, 0, frame / 2);
                            filled = frame / 2;
                        }
                    }
                }
            } catch (Throwable e) {
                runOnUiThread(() -> blueView.setMessage("Microphone stopped. Tap RESET."));
            } finally {
                try { recorder.stop(); } catch (Exception ignored) {}
                try { recorder.release(); } catch (Exception ignored) {}
                recorder = null;
                listening = false;
            }
        }, "blue-audio");
        audioThread.start();
    }

    private static final class Target {
        final String tab;
        final String note;
        final String action;
        final double hz;
        final long holdMs;

        Target(String tab, String note, String action, double hz, long holdMs) {
            this.tab = tab;
            this.note = note;
            this.action = action;
            this.hz = hz;
            this.holdMs = holdMs;
        }
    }

    private final class BlueView extends View {
        private static final int CALIBRATE = 0;
        private static final int BREATH = 1;
        private static final int DRAW = 2;
        private static final int SUSTAIN = 3;
        private static final int BEND = 4;
        private static final int PHRASE = 5;
        private static final int RESULT = 6;

        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path trailPath = new Path();
        private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
        private final SharedPreferences prefs;

        private final Target plus4 = new Target("+4", "C5", "blow", 523.251, 520);
        private final Target minus4 = new Target("−4", "D5", "draw", 587.330, 520);
        private final Target sustain4 = new Target("+4", "C5", "blow", 523.251, 1800);
        private final Target minus2 = new Target("−2", "G4", "draw", 391.995, 220);
        private final Target minus3 = new Target("−3", "B4", "draw", 493.883, 220);
        private final Target plus4Phrase = new Target("+4", "C5", "blow", 523.251, 220);
        private final Target[] phrase = {minus2, minus3, plus4Phrase, minus3, minus2};

        private final ArrayDeque<Float> trail = new ArrayDeque<>();

        private int step = CALIBRATE;
        private int phraseIndex = 0;
        private long holdSince = 0;
        private long bendHoldSince = 0;
        private long calibrationStart = 0;
        private long stepStarted = 0;
        private long lastPitchAt = 0;

        private double frequency = -1;
        private double cents = 0;
        private double rms = 0;
        private double soundGate = 0.010;
        private double ambientSum = 0;
        private int ambientFrames = 0;

        private boolean inBand = false;
        private boolean bendStarted = false;
        private String message = "Allow microphone to begin.";

        private double pitchErrorSum = 0;
        private int pitchFrames = 0;
        private double stabilityDeltaSum = 0;
        private int stabilityFrames = 0;
        private double lastMetricCents = Double.NaN;
        private int pitchScore = 0;
        private int steadyScore = 0;
        private int finalScore = 0;
        private int bestScore = 0;

        BlueView(Context context) {
            super(context);
            setBackgroundColor(0xFF030303);
            setClickable(true);

            textPaint.setTypeface(light);
            linePaint.setStrokeWidth(dp(1));
            trailPaint.setStyle(Paint.Style.STROKE);
            trailPaint.setStrokeWidth(dp(1));
            trailPaint.setStrokeCap(Paint.Cap.ROUND);
            trailPaint.setStrokeJoin(Paint.Join.ROUND);
            trailPaint.setColor(0xFF5D5D5D);

            prefs = getSharedPreferences("blue_progress", MODE_PRIVATE);
            bestScore = prefs.getInt("best_score", 0);
        }

        void onMicStarted() {
            step = CALIBRATE;
            calibrationStart = SystemClock.elapsedRealtime();
            ambientSum = 0;
            ambientFrames = 0;
            message = "Stay quiet for a moment.";
            invalidate();
        }

        void setMessage(String text) {
            message = text;
            invalidate();
        }

        private Target currentTarget() {
            if (step == BREATH) return plus4;
            if (step == DRAW) return minus4;
            if (step == SUSTAIN) return sustain4;
            if (step == BEND) return minus4;
            if (step == PHRASE) return phrase[Math.min(phraseIndex, phrase.length - 1)];
            return plus4;
        }

        void onPitch(double hz, double levelRms) {
            this.frequency = hz;
            this.rms = levelRms;
            this.lastPitchAt = SystemClock.elapsedRealtime();

            if (step == CALIBRATE) {
                calibrate(levelRms);
                invalidate();
                return;
            }

            if (step == RESULT) {
                invalidate();
                return;
            }

            boolean audible = hz > 0 && levelRms >= soundGate;
            if (!audible) {
                inBand = false;
                holdSince = 0;
                bendHoldSince = 0;
                invalidate();
                return;
            }

            Target target = currentTarget();
            cents = PitchDetector.centsFromTarget(hz, target.hz);
            long now = SystemClock.elapsedRealtime();

            if (step == BEND) {
                handleBend(now);
            } else {
                double tolerance = step == PHRASE ? 45.0 : 30.0;
                inBand = Math.abs(cents) <= tolerance;
                addTrail((float) cents);

                if (Math.abs(cents) <= 130) registerMetrics(Math.abs(cents), cents);

                if (inBand) {
                    if (holdSince == 0) holdSince = now;
                    if (now - holdSince >= target.holdMs) completeTarget();
                } else {
                    holdSince = 0;
                }
            }
            invalidate();
        }

        private void calibrate(double levelRms) {
            long now = SystemClock.elapsedRealtime();
            if (calibrationStart == 0) calibrationStart = now;

            ambientSum += levelRms;
            ambientFrames++;

            if (now - calibrationStart >= 1500) {
                double ambient = ambientFrames == 0 ? 0.003 : ambientSum / ambientFrames;
                soundGate = clamp(ambient * 3.0 + 0.002, 0.007, 0.035);
                step = BREATH;
                stepStarted = now;
                message = "MIC READY · play +4";
                trail.clear();
            }
        }

        private void handleBend(long now) {
            addTrail((float) cents);

            if (!bendStarted && cents > -28 && cents < 32) {
                bendStarted = true;
                message = "Good. Now lower the note.";
            }

            if (bendStarted && cents < -18 && cents > -150) {
                registerMetrics(Math.abs(cents + 100.0), cents);
            }

            inBand = bendStarted && cents <= -68 && cents >= -132;

            if (inBand) {
                message = "Hold the bend.";
                if (bendHoldSince == 0) bendHoldSince = now;
                if (now - bendHoldSince >= 480) completeTarget();
            } else {
                bendHoldSince = 0;
                if (bendStarted && cents > -45) message = "Lower it gently.";
            }
        }

        private void registerMetrics(double error, double actualCents) {
            pitchErrorSum += Math.min(120, error);
            pitchFrames++;

            if (!Double.isNaN(lastMetricCents)) {
                stabilityDeltaSum += Math.min(80, Math.abs(actualCents - lastMetricCents));
                stabilityFrames++;
            }
            lastMetricCents = actualCents;
        }

        private void completeTarget() {
            vibrate();
            holdSince = 0;
            bendHoldSince = 0;
            trail.clear();
            lastMetricCents = Double.NaN;

            if (step == BREATH) {
                step = DRAW;
                message = "Now pull the air in.";
            } else if (step == DRAW) {
                step = SUSTAIN;
                message = "Own the note.";
            } else if (step == SUSTAIN) {
                step = BEND;
                bendStarted = false;
                message = "Start clean at −4.";
            } else if (step == BEND) {
                step = PHRASE;
                phraseIndex = 0;
                message = "Five notes. No rush.";
            } else if (step == PHRASE) {
                phraseIndex++;
                if (phraseIndex >= phrase.length) {
                    finishSession();
                    return;
                }
                message = "Next.";
            }

            stepStarted = SystemClock.elapsedRealtime();
            invalidate();
        }

        private void finishSession() {
            pitchScore = pitchFrames == 0
                    ? 0
                    : (int) Math.round(clamp(100.0 - (pitchErrorSum / pitchFrames) * 1.15, 0, 100));

            steadyScore = stabilityFrames == 0
                    ? 0
                    : (int) Math.round(clamp(100.0 - (stabilityDeltaSum / stabilityFrames) * 2.10, 0, 100));

            finalScore = (int) Math.round(pitchScore * 0.65 + steadyScore * 0.35);
            if (finalScore > bestScore) {
                bestScore = finalScore;
                prefs.edit().putInt("best_score", bestScore).apply();
                message = "NEW BEST · saved locally";
            } else {
                message = "Saved locally · no account";
            }

            step = RESULT;
            inBand = false;
            trail.clear();
            vibrateSuccess();
            invalidate();
        }

        private void resetSession() {
            step = BREATH;
            phraseIndex = 0;
            holdSince = 0;
            bendHoldSince = 0;
            stepStarted = SystemClock.elapsedRealtime();
            frequency = -1;
            cents = 0;
            inBand = false;
            bendStarted = false;
            message = "MIC READY · play +4";
            trail.clear();

            pitchErrorSum = 0;
            pitchFrames = 0;
            stabilityDeltaSum = 0;
            stabilityFrames = 0;
            lastMetricCents = Double.NaN;
            pitchScore = 0;
            steadyScore = 0;
            finalScore = 0;

            invalidate();

            if (!listening && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startListening();
            }
        }

        private void addTrail(float value) {
            float v = (float) clamp(value, -150, 150);
            trail.addLast(v);
            while (trail.size() > 48) trail.removeFirst();
        }

        private void vibrate() {
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v == null) return;
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(22, 65));
                else v.vibrate(22);
            } catch (Throwable ignored) {}
        }

        private void vibrateSuccess() {
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v == null) return;
                if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(VibrationEffect.createWaveform(new long[]{0, 28, 55, 42}, new int[]{0, 65, 0, 90}, -1));
                } else {
                    v.vibrate(new long[]{0, 28, 55, 42}, -1);
                }
            } catch (Throwable ignored) {}
        }

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }

        private double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }

        private void text(Canvas c, String s, float x, float y, float size, int color, float spacing) {
            textPaint.setColor(color);
            textPaint.setTextSize(dp(size));
            textPaint.setTypeface(light);
            if (Build.VERSION.SDK_INT >= 21) textPaint.setLetterSpacing(spacing);
            c.drawText(s, x, y, textPaint);
            if (Build.VERSION.SDK_INT >= 21) textPaint.setLetterSpacing(0);
        }

        private void line(Canvas c, float x1, float y1, float x2, float y2, int color, float width) {
            linePaint.setColor(color);
            linePaint.setStrokeWidth(dp(width));
            c.drawLine(x1, y1, x2, y2, linePaint);
        }

        private float holdProgress() {
            if (step == BEND && bendHoldSince > 0) {
                return Math.min(1f, (SystemClock.elapsedRealtime() - bendHoldSince) / 480f);
            }
            Target t = currentTarget();
            if (holdSince > 0) {
                return Math.min(1f, (SystemClock.elapsedRealtime() - holdSince) / (float) t.holdMs);
            }
            return 0f;
        }

        private int liveSteadyScore() {
            if (stabilityFrames == 0) return 0;
            return (int) Math.round(clamp(100.0 - (stabilityDeltaSum / stabilityFrames) * 2.10, 0, 100));
        }

        private void drawProgress(Canvas c, float left, float right, float y) {
            if (step == CALIBRATE || step == RESULT) return;

            float gap = dp(5);
            float seg = (right - left - gap * 4) / 5f;
            int active = Math.max(0, Math.min(4, step - 1));

            for (int i = 0; i < 5; i++) {
                int color;
                if (i < active) color = 0xFF686868;
                else if (i == active) color = 0xFFD7D7D7;
                else color = 0xFF181818;

                line(c, left + i * (seg + gap), y, left + i * (seg + gap) + seg, y, color, 1f);
            }
        }

        private void drawTrail(Canvas c, float left, float right, float top, float bottom, boolean bendMode) {
            if (trail.size() < 2) return;

            trailPath.reset();
            int n = trail.size();
            int i = 0;
            for (Float value : trail) {
                float x = left + (right - left) * (i / (float) Math.max(1, n - 1));
                float y;
                if (bendMode) {
                    float normalized = (float) clamp((-value) / 120.0, 0, 1);
                    y = top + (bottom - top) * normalized;
                } else {
                    float normalized = (float) clamp(value / 120.0, -1, 1);
                    y = (top + bottom) / 2f - normalized * (bottom - top) * 0.42f;
                }

                if (i == 0) trailPath.moveTo(x, y);
                else trailPath.lineTo(x, y);
                i++;
            }
            c.drawPath(trailPath, trailPaint);
        }

        private void drawCalibration(Canvas c, float left, float right, float oy, float h) {
            text(c, "MIC CALIBRATION", left, oy + dp(90), 9, 0xFF646464, 0.16f);
            text(c, "Listen.", left, oy + dp(145), 34, 0xFFE8E8E8, -0.015f);
            text(c, "Measuring the room for a cleaner signal.", left, oy + dp(176), 12, 0xFF777777, 0.01f);

            float cy = oy + h * 0.48f;
            line(c, left, cy, right, cy, 0xFF222222, 1f);

            float pulse = (float) clamp(rms * 350.0, 3.5, 11.0);
            dotPaint.setColor(0xFFDCDCDC);
            c.drawCircle((left + right) / 2f, cy, dp(pulse), dotPaint);

            long elapsed = calibrationStart == 0 ? 0 : SystemClock.elapsedRealtime() - calibrationStart;
            float p = Math.min(1f, elapsed / 1500f);
            line(c, left, cy + dp(42), left + (right - left) * p, cy + dp(42), 0xFF777777, 1f);

            text(c, "STAY QUIET", left, oy + h - dp(48), 9, 0xFF666666, 0.14f);
        }

        private void drawResult(Canvas c, float left, float right, float oy, float h) {
            text(c, "SESSION COMPLETE", left, oy + dp(92), 9, 0xFF686868, 0.16f);
            text(c, String.valueOf(finalScore), left, oy + dp(190), 72, 0xFFF0F0F0, -0.025f);
            text(c, "CONTROL SCORE", left + dp(4), oy + dp(220), 9, 0xFF666666, 0.14f);

            float y = oy + dp(310);
            line(c, left, y - dp(25), right, y - dp(25), 0xFF181818, 1f);

            text(c, "PITCH", left, y, 8, 0xFF606060, 0.14f);
            text(c, pitchScore + "%", left, y + dp(27), 16, 0xFFB8B8B8, 0);

            float mid = left + (right - left) * 0.5f;
            text(c, "STEADY", mid, y, 8, 0xFF606060, 0.14f);
            text(c, steadyScore + "%", mid, y + dp(27), 16, 0xFFB8B8B8, 0);

            float bestY = y + dp(88);
            line(c, left, bestY - dp(26), right, bestY - dp(26), 0xFF181818, 1f);
            text(c, "BEST", left, bestY, 8, 0xFF606060, 0.14f);
            text(c, bestScore + "", left, bestY + dp(27), 16, 0xFFB8B8B8, 0);

            text(c, message, left, oy + h - dp(76), 10, 0xFF6F6F6F, 0.04f);
            text(c, "AGAIN", right - dp(42), oy + h - dp(34), 9, 0xFFBDBDBD, 0.14f);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);

            float w = getWidth() - getPaddingLeft() - getPaddingRight();
            float h = getHeight() - getPaddingTop() - getPaddingBottom();
            float ox = getPaddingLeft();
            float oy = getPaddingTop();
            float left = ox + dp(26);
            float right = ox + w - dp(26);

            text(c, "BLUE", left, oy + dp(42), 10, 0xFFBDBDBD, 0.24f);

            if (step == CALIBRATE) {
                drawCalibration(c, left, right, oy, h);
                postInvalidateDelayed(32);
                return;
            }

            if (step == RESULT) {
                text(c, "RESET", right - dp(40), oy + dp(42), 9, 0xFF676767, 0.12f);
                drawResult(c, left, right, oy, h);
                return;
            }

            text(c, String.format(Locale.US, "%02d / 05", Math.max(1, Math.min(5, step))),
                    right - dp(52), oy + dp(42), 9, 0xFF676767, 0.12f);

            drawProgress(c, left, right, oy + dp(70));

            Target target = currentTarget();
            String label;
            String title;
            String subtitle;

            if (step == BREATH) {
                label = "FIRST BREATH";
                title = "+4";
                subtitle = "hole 4 · blow · C5";
            } else if (step == DRAW) {
                label = "DRAW";
                title = "−4";
                subtitle = "hole 4 · draw · D5";
            } else if (step == SUSTAIN) {
                label = "SUSTAIN";
                title = "+4";
                subtitle = "hold the note · 2 seconds";
            } else if (step == BEND) {
                label = "BEND";
                title = "−4  ↓";
                subtitle = "draw · lower D5 toward C♯5";
            } else {
                label = "FIRST PHRASE";
                title = target.tab;
                subtitle = target.action + " · " + target.note;
            }

            text(c, label, left, oy + dp(116), 9, 0xFF686868, 0.16f);
            text(c, title, left, oy + dp(184), 48, 0xFFECECEC, -0.02f);
            text(c, subtitle, left, oy + dp(214), 12, 0xFF777777, 0.01f);

            float laneTop = oy + dp(268);
            float laneBottom = Math.min(oy + h - dp(260), oy + dp(525));
            if (laneBottom < laneTop + dp(140)) laneBottom = laneTop + dp(140);

            if (step == BEND) {
                float startY = laneTop + dp(24);
                float targetY = laneBottom - dp(24);

                line(c, left, startY, right, targetY, 0xFF242424, 1f);
                line(c, right - dp(76), targetY, right, targetY, 0xFF626262, 1f);
                text(c, "0¢", left, startY - dp(10), 8, 0xFF505050, 0.04f);
                text(c, "−100¢", right - dp(44), targetY - dp(10), 8, 0xFF777777, 0.02f);

                drawTrail(c, left, right, startY, targetY, true);

                if (frequency > 0 && SystemClock.elapsedRealtime() - lastPitchAt < 500 && rms >= soundGate) {
                    float bendProgress = (float) clamp((-cents) / 120.0, 0, 1);
                    float x = left + (right - left) * (0.08f + bendProgress * 0.84f);
                    float y = startY + (targetY - startY) * bendProgress;
                    dotPaint.setColor(inBand ? 0xFFF1F1F1 : 0xFF8D8D8D);
                    c.drawCircle(x, y, dp(inBand ? 5f : 4f), dotPaint);
                }
            } else {
                float cy = (laneTop + laneBottom) / 2f;
                line(c, left, cy, right, cy, 0xFF323232, 1f);
                line(c, left, cy - dp(18), right, cy - dp(18), 0xFF1C1C1C, 1f);
                line(c, left, cy + dp(18), right, cy + dp(18), 0xFF1C1C1C, 1f);

                drawTrail(c, left, right, laneTop, laneBottom, false);

                if (frequency > 0 && SystemClock.elapsedRealtime() - lastPitchAt < 500 && rms >= soundGate) {
                    float normalized = (float) clamp(cents / 120.0, -1, 1);
                    float y = cy - normalized * (laneBottom - laneTop) * 0.42f;
                    float x = right - dp(10);
                    dotPaint.setColor(inBand ? 0xFFF1F1F1 : 0xFF8D8D8D);
                    c.drawCircle(x, y, dp(inBand ? 5f : 4f), dotPaint);
                }
            }

            float progressY = laneBottom + dp(22);
            line(c, left, progressY, right, progressY, 0xFF161616, 1f);
            line(c, left, progressY, left + (right - left) * holdProgress(), progressY, 0xFF8A8A8A, 1f);

            if (step == PHRASE) {
                float tabsY = progressY + dp(48);
                float gap = (right - left) / phrase.length;
                for (int i = 0; i < phrase.length; i++) {
                    int color = i < phraseIndex
                            ? 0xFF666666
                            : (i == phraseIndex ? 0xFFE7E7E7 : 0xFF343434);

                    float x = left + gap * i;
                    text(c, phrase[i].tab, x, tabsY, 14, color, 0.01f);
                    if (i == phraseIndex) {
                        line(c, x, tabsY + dp(8), x + dp(22), tabsY + dp(8), 0xFFADADAD, 1f);
                    }
                }
            }

            float infoY = oy + h - dp(144);
            line(c, left, infoY - dp(28), right, infoY - dp(28), 0xFF181818, 1f);

            String detected = frequency > 0 && rms >= soundGate ? PitchDetector.noteName(frequency) : "—";
            String pitchText = frequency > 0 && rms >= soundGate
                    ? String.format(Locale.US, "%+.0f¢", cents)
                    : "—";
            String steady = stabilityFrames > 4 ? liveSteadyScore() + "%" : "—";

            text(c, "NOTE", left, infoY, 8, 0xFF5C5C5C, 0.14f);
            text(c, detected, left, infoY + dp(25), 15, 0xFFB7B7B7, 0);

            float m2 = left + (right - left) * 0.36f;
            text(c, "PITCH", m2, infoY, 8, 0xFF5C5C5C, 0.14f);
            text(c, pitchText, m2, infoY + dp(25), 15, inBand ? 0xFFE4E4E4 : 0xFF9B9B9B, 0);

            float m3 = left + (right - left) * 0.72f;
            text(c, "STEADY", m3, infoY, 8, 0xFF5C5C5C, 0.14f);
            text(c, steady, m3, infoY + dp(25), 15, 0xFFB7B7B7, 0);

            text(c, message, left, oy + h - dp(50), 10, 0xFF6D6D6D, 0.03f);
            text(c, "C · DIATONIC", right - dp(70), oy + h - dp(50), 8, 0xFF4E4E4E, 0.12f);

            postInvalidateDelayed(32);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP) return true;

            float y = event.getY();
            float x = event.getX();

            if (step == RESULT && y > getHeight() - getPaddingBottom() - dp(90)) {
                resetSession();
                return true;
            }

            if (y < getPaddingTop() + dp(78) && x > getWidth() - getPaddingRight() - dp(105)) {
                resetSession();
                return true;
            }

            return true;
        }
    }
}
