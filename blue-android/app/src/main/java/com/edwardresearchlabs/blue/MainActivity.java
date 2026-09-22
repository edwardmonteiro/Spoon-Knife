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
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            blueView.onMicDenied();
        }
    }

    @Override public void onBackPressed() {
        if (blueView != null && !blueView.isHome()) {
            blueView.goHome();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        listening = false;
        if (audioThread != null) audioThread.interrupt();
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        if (blueView != null) blueView.stopCallAudio();
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
                runOnUiThread(() -> blueView.setMessage("Microphone stopped."));
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
        private static final int HOME = -10;
        private static final int CALIBRATE = 0;
        private static final int BREATH = 1;
        private static final int DRAW = 2;
        private static final int SUSTAIN = 3;
        private static final int BEND = 4;
        private static final int PHRASE = 5;
        private static final int RESULT = 6;
        private static final int FREE = 7;
        private static final int CALL = 8;

        private static final int MODE_FULL = 0;
        private static final int MODE_BEND = 1;
        private static final int MODE_CALL = 2;
        private static final int MODE_FREE = 3;

        private static final int CALL_LISTEN = 0;
        private static final int CALL_READY = 1;
        private static final int CALL_RESPOND = 2;

        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path trailPath = new Path();

        private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
        private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
        private final Typeface serif = Typeface.create("serif", Typeface.NORMAL);
        private final SharedPreferences prefs;
        private final BluesCallPlayer callPlayer = new BluesCallPlayer();

        private final Target plus4 = new Target("+4", "C5", "blow", 523.251, 520);
        private final Target minus4 = new Target("−4", "D5", "draw", 587.330, 520);
        private final Target sustain4 = new Target("+4", "C5", "blow", 523.251, 1800);
        private final Target minus2 = new Target("−2", "G4", "draw", 391.995, 220);
        private final Target minus3 = new Target("−3", "B4", "draw", 493.883, 220);
        private final Target plus4Phrase = new Target("+4", "C5", "blow", 523.251, 220);
        private final Target[] phrase = {minus2, minus3, plus4Phrase, minus3, minus2};
        private final double[] callFrequencies = {
                minus2.hz, minus3.hz, plus4Phrase.hz, minus3.hz, minus2.hz
        };

        private final ArrayDeque<Float> trail = new ArrayDeque<>();

        private int screen = HOME;
        private int activeMode = MODE_FULL;
        private int pendingMode = MODE_FULL;
        private int phraseIndex = 0;
        private int callPhase = CALL_LISTEN;
        private long responseStartedAt = 0;
        private long currentNoteStartedAt = 0;
        private long timingErrorSum = 0;
        private int timingSamples = 0;
        private int timingScore = 0;

        private long holdSince = 0;
        private long bendHoldSince = 0;
        private long calibrationStart = 0;
        private long lastPitchAt = 0;

        private double frequency = -1;
        private double cents = 0;
        private double rms = 0;
        private double soundGate = 0.010;
        private double ambientSum = 0;
        private int ambientFrames = 0;

        private boolean micReady = false;
        private boolean micDenied = false;
        private boolean inBand = false;
        private boolean bendStarted = false;

        private String message = "MIC NOT STARTED";

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

            linePaint.setStrokeWidth(dp(1));
            trailPaint.setStyle(Paint.Style.STROKE);
            trailPaint.setStrokeWidth(dp(1));
            trailPaint.setStrokeCap(Paint.Cap.ROUND);
            trailPaint.setStrokeJoin(Paint.Join.ROUND);
            trailPaint.setColor(0xFF5D5D5D);

            prefs = getSharedPreferences("blue_progress", MODE_PRIVATE);
            bestScore = prefs.getInt("best_score", 0);
        }

        boolean isHome() {
            return screen == HOME;
        }

        void goHome() {
            stopCallAudio();
            screen = HOME;
            phraseIndex = 0;
            holdSince = 0;
            bendHoldSince = 0;
            inBand = false;
            bendStarted = false;
            trail.clear();
            message = micReady ? "MIC READY" : (micDenied ? "MIC ACCESS REQUIRED" : "MIC STARTING");
            invalidate();
        }

        void stopCallAudio() {
            callPlayer.stop();
        }

        void onMicStarted() {
            micReady = true;
            micDenied = false;
            if (screen == CALIBRATE) {
                beginCalibration();
            } else {
                message = "MIC READY";
                invalidate();
            }
        }

        void onMicDenied() {
            micDenied = true;
            micReady = false;
            screen = HOME;
            message = "MIC ACCESS REQUIRED";
            invalidate();
        }

        void setMessage(String text) {
            message = text;
            invalidate();
        }

        private void startMode(int mode) {
            activeMode = mode;
            pendingMode = mode;
            resetMetrics();
            phraseIndex = 0;
            callPhase = CALL_LISTEN;
            responseStartedAt = 0;
            currentNoteStartedAt = 0;
            timingErrorSum = 0;
            timingSamples = 0;
            timingScore = 0;
            bendStarted = false;
            trail.clear();
            stopCallAudio();

            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                screen = CALIBRATE;
                message = "ALLOW MICROPHONE";
                MainActivity.this.requestPermissions(
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        REQ_MIC
                );
                invalidate();
                return;
            }

            if (!listening) startListening();
            screen = CALIBRATE;
            beginCalibration();
        }

        private void beginCalibration() {
            screen = CALIBRATE;
            calibrationStart = SystemClock.elapsedRealtime();
            ambientSum = 0;
            ambientFrames = 0;
            message = "STAY QUIET";
            invalidate();
        }

        private void finishCalibration() {
            double ambient = ambientFrames == 0 ? 0.003 : ambientSum / ambientFrames;
            soundGate = clamp(ambient * 3.0 + 0.002, 0.007, 0.035);
            trail.clear();

            if (pendingMode == MODE_FULL) {
                screen = BREATH;
                message = "Find the center.";
            } else if (pendingMode == MODE_BEND) {
                screen = BEND;
                bendStarted = false;
                message = "Start clean at −4.";
            } else if (pendingMode == MODE_CALL) {
                screen = CALL;
                phraseIndex = 0;
                beginCall();
            } else {
                screen = FREE;
                message = "Play anything.";
            }
            invalidate();
        }

        private Target currentTarget() {
            if (screen == BREATH) return plus4;
            if (screen == DRAW) return minus4;
            if (screen == SUSTAIN) return sustain4;
            if (screen == BEND) return minus4;
            if (screen == PHRASE || screen == CALL) return phrase[Math.min(phraseIndex, phrase.length - 1)];
            return plus4;
        }

        void onPitch(double hz, double levelRms) {
            frequency = hz;
            rms = levelRms;
            lastPitchAt = SystemClock.elapsedRealtime();

            if (screen == HOME || screen == RESULT) {
                invalidate();
                return;
            }

            if (screen == CALL && callPhase != CALL_RESPOND) {
                invalidate();
                return;
            }

            if (screen == CALIBRATE) {
                ambientSum += levelRms;
                ambientFrames++;
                if (calibrationStart == 0) calibrationStart = SystemClock.elapsedRealtime();
                if (SystemClock.elapsedRealtime() - calibrationStart >= 1250) finishCalibration();
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

            if (screen == FREE) {
                handleFree(hz);
                invalidate();
                return;
            }

            if (screen == CALL) {
                handleCallResponse(hz);
                invalidate();
                return;
            }

            Target target = currentTarget();
            cents = PitchDetector.centsFromTarget(hz, target.hz);
            long now = SystemClock.elapsedRealtime();

            if (screen == BEND) {
                handleBend(now);
            } else {
                double tolerance = screen == PHRASE ? 45.0 : 30.0;
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

        private void beginCall() {
            callPhase = CALL_LISTEN;
            phraseIndex = 0;
            holdSince = 0;
            responseStartedAt = 0;
            currentNoteStartedAt = 0;
            trail.clear();
            message = "LISTEN";

            callPlayer.play(callFrequencies, 360, 105, () ->
                    runOnUiThread(() -> {
                        if (screen != CALL) return;
                        callPhase = CALL_READY;
                        message = "YOUR TURN";
                        invalidate();
                        postDelayed(() -> {
                            if (screen != CALL || callPhase != CALL_READY) return;
                            callPhase = CALL_RESPOND;
                            responseStartedAt = SystemClock.elapsedRealtime();
                            currentNoteStartedAt = responseStartedAt;
                            phraseIndex = 0;
                            message = "RESPOND";
                            vibrate();
                            invalidate();
                        }, 420);
                    })
            );
        }

        private void replayCall() {
            stopCallAudio();
            resetMetrics();
            phraseIndex = 0;
            timingErrorSum = 0;
            timingSamples = 0;
            timingScore = 0;
            beginCall();
        }

        private void handleCallResponse(double hz) {
            if (phraseIndex >= phrase.length) return;

            Target target = phrase[phraseIndex];
            cents = PitchDetector.centsFromTarget(hz, target.hz);
            inBand = Math.abs(cents) <= 45.0;
            addTrail((float) cents);

            if (Math.abs(cents) <= 140) registerMetrics(Math.abs(cents), cents);

            long now = SystemClock.elapsedRealtime();
            if (inBand) {
                if (holdSince == 0) holdSince = now;

                if (now - holdSince >= target.holdMs) {
                    long actual = now - currentNoteStartedAt;
                    long expected = 465L;
                    timingErrorSum += Math.min(800L, Math.abs(actual - expected));
                    timingSamples++;

                    phraseIndex++;
                    holdSince = 0;
                    currentNoteStartedAt = now;
                    vibrate();

                    if (phraseIndex >= phrase.length) {
                        finishSession();
                    } else {
                        message = "NEXT";
                    }
                }
            } else {
                holdSince = 0;
            }
        }

        private void handleFree(double hz) {
            double midi = PitchDetector.midiFromFrequency(hz);
            int nearest = (int) Math.round(midi);
            double targetHz = 440.0 * Math.pow(2.0, (nearest - 69) / 12.0);
            cents = PitchDetector.centsFromTarget(hz, targetHz);
            inBand = Math.abs(cents) <= 18;
            addTrail((float) cents);
        }

        private void handleBend(long now) {
            addTrail((float) cents);

            if (!bendStarted && cents > -28 && cents < 32) {
                bendStarted = true;
                message = "Now lower the note.";
            }

            if (bendStarted && cents < -18 && cents > -150) {
                registerMetrics(Math.abs(cents + 100.0), cents);
            }

            inBand = bendStarted && cents <= -68 && cents >= -132;

            if (inBand) {
                message = "Hold it.";
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

            if (activeMode == MODE_BEND && screen == BEND) {
                finishSession();
                return;
            }

            if (screen == PHRASE) {
                phraseIndex++;
                if (phraseIndex >= phrase.length) {
                    finishSession();
                    return;
                }
                message = "Next.";
                return;
            }

            if (screen == BREATH) {
                screen = DRAW;
                message = "Pull the air in.";
            } else if (screen == DRAW) {
                screen = SUSTAIN;
                message = "Own the note.";
            } else if (screen == SUSTAIN) {
                screen = BEND;
                bendStarted = false;
                message = "Start clean at −4.";
            } else if (screen == BEND) {
                screen = CALL;
                phraseIndex = 0;
                beginCall();
            }
        }

        private void finishSession() {
            pitchScore = pitchFrames == 0
                    ? 0
                    : (int) Math.round(clamp(100.0 - (pitchErrorSum / pitchFrames) * 1.15, 0, 100));

            steadyScore = stabilityFrames == 0
                    ? 0
                    : (int) Math.round(clamp(100.0 - (stabilityDeltaSum / stabilityFrames) * 2.10, 0, 100));

            timingScore = timingSamples == 0
                    ? 0
                    : (int) Math.round(clamp(
                            100.0 - (timingErrorSum / (double) timingSamples) / 5.5,
                            0,
                            100
                    ));

            if (activeMode == MODE_CALL || screen == CALL) {
                finalScore = (int) Math.round(
                        pitchScore * 0.50 + steadyScore * 0.25 + timingScore * 0.25
                );
            } else {
                finalScore = (int) Math.round(pitchScore * 0.65 + steadyScore * 0.35);
            }

            if (finalScore > bestScore) {
                bestScore = finalScore;
                prefs.edit().putInt("best_score", bestScore).apply();
                message = "NEW BEST · SAVED LOCALLY";
            } else {
                message = "SAVED LOCALLY · NO ACCOUNT";
            }

            screen = RESULT;
            inBand = false;
            trail.clear();
            vibrateSuccess();
            invalidate();
        }

        private void resetMetrics() {
            pitchErrorSum = 0;
            pitchFrames = 0;
            stabilityDeltaSum = 0;
            stabilityFrames = 0;
            lastMetricCents = Double.NaN;
            pitchScore = 0;
            steadyScore = 0;
            finalScore = 0;
            timingScore = 0;
            timingErrorSum = 0;
            timingSamples = 0;
        }

        private void restartCurrentMode() {
            startMode(activeMode);
        }

        private void addTrail(float value) {
            float v = (float) clamp(value, -150, 150);
            trail.addLast(v);
            while (trail.size() > 52) trail.removeFirst();
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
                    v.vibrate(VibrationEffect.createWaveform(
                            new long[]{0, 28, 55, 42},
                            new int[]{0, 65, 0, 90},
                            -1
                    ));
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
            drawText(c, s, x, y, size, color, spacing, light);
        }

        private void hero(Canvas c, String s, float x, float y, float size, int color) {
            drawText(c, s, x, y, size, color, -0.02f, serif);
        }

        private void drawText(Canvas c, String s, float x, float y, float size, int color, float spacing, Typeface face) {
            textPaint.setColor(color);
            textPaint.setTextSize(dp(size));
            textPaint.setTypeface(face);
            if (Build.VERSION.SDK_INT >= 21) textPaint.setLetterSpacing(spacing);
            c.drawText(s, x, y, textPaint);
            if (Build.VERSION.SDK_INT >= 21) textPaint.setLetterSpacing(0);
        }

        private void line(Canvas c, float x1, float y1, float x2, float y2, int color, float width) {
            linePaint.setColor(color);
            linePaint.setStrokeWidth(dp(width));
            c.drawLine(x1, y1, x2, y2, linePaint);
        }

        private void drawHome(Canvas c, float left, float right, float oy, float h) {
            text(c, "BLUE", left, oy + dp(44), 10, 0xFFBDBDBD, 0.24f);
            text(c, "BEST  " + bestScore, right - dp(56), oy + dp(44), 8, 0xFF5D5D5D, 0.10f);

            text(c, "HARMONICA IN C", left, oy + dp(108), 8, 0xFF5E5E5E, 0.18f);
            hero(c, "Play blues.", left, oy + dp(175), 42, 0xFFF0F0F0);
            text(c, "Small practice. Deeper sound.", left, oy + dp(205), 11, 0xFF737373, 0.01f);

            float start = oy + dp(262);
            float row = dp(88);
            String[] number = {"01", "02", "03", "04"};
            String[] title = {"PRACTICE", "BEND", "CALL & RESPONSE", "FREE PLAY"};
            String[] sub = {
                    "breath · draw · sustain · bend · response",
                    "lower pitch with control",
                    "listen · remember · answer",
                    "listen to every note"
            };

            for (int i = 0; i < 4; i++) {
                float y = start + i * row;
                line(c, left, y, right, y, 0xFF1B1B1B, 1f);
                text(c, number[i], left, y + dp(31), 8, 0xFF555555, 0.14f);
                text(c, title[i], left + dp(42), y + dp(31), 11, 0xFFC8C8C8, 0.12f);
                text(c, sub[i], left + dp(42), y + dp(56), 10, 0xFF686868, 0.01f);
                text(c, "›", right - dp(5), y + dp(36), 18, 0xFF6E6E6E, 0);
            }
            line(c, left, start + 4 * row, right, start + 4 * row, 0xFF1B1B1B, 1f);

            String mic = micReady ? "MIC READY" : (micDenied ? "MIC ACCESS REQUIRED" : "MIC OFF");
            text(c, mic, left, oy + h - dp(48), 8, micReady ? 0xFF777777 : 0xFF8A6D6D, 0.14f);
            text(c, "LOCAL AUDIO · NO ACCOUNT", right - dp(118), oy + h - dp(48), 8, 0xFF484848, 0.08f);
        }

        private void drawCalibration(Canvas c, float left, float right, float oy, float h) {
            text(c, "BLUE", left, oy + dp(44), 10, 0xFFBDBDBD, 0.24f);
            text(c, "MENU", right - dp(34), oy + dp(44), 8, 0xFF626262, 0.12f);

            text(c, "MIC CALIBRATION", left, oy + dp(112), 8, 0xFF5F5F5F, 0.16f);
            hero(c, "Listen.", left, oy + dp(177), 42, 0xFFEDEDED);
            text(c, "Measuring the room for a cleaner signal.", left, oy + dp(210), 11, 0xFF737373, 0.01f);

            float cy = oy + h * 0.49f;
            line(c, left, cy, right, cy, 0xFF232323, 1f);

            float pulse = (float) clamp(rms * 360.0, 3.5, 10.0);
            dotPaint.setColor(0xFFE0E0E0);
            c.drawCircle((left + right) / 2f, cy, dp(pulse), dotPaint);

            long elapsed = calibrationStart == 0 ? 0 : SystemClock.elapsedRealtime() - calibrationStart;
            float p = Math.min(1f, elapsed / 1250f);
            line(c, left, cy + dp(42), right, cy + dp(42), 0xFF181818, 1f);
            line(c, left, cy + dp(42), left + (right - left) * p, cy + dp(42), 0xFF8A8A8A, 1f);

            text(c, message, left, oy + h - dp(48), 8, 0xFF666666, 0.14f);
            postInvalidateDelayed(32);
        }

        private void drawProgress(Canvas c, float left, float right, float y) {
            if (activeMode != MODE_FULL || screen < BREATH || screen > PHRASE) return;

            float gap = dp(5);
            float seg = (right - left - gap * 4) / 5f;
            int active = Math.max(0, Math.min(4, screen - 1));

            for (int i = 0; i < 5; i++) {
                int color = i < active ? 0xFF5F5F5F : (i == active ? 0xFFD7D7D7 : 0xFF171717);
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

        private float holdProgress() {
            if (screen == BEND && bendHoldSince > 0) {
                return Math.min(1f, (SystemClock.elapsedRealtime() - bendHoldSince) / 480f);
            }
            if (screen >= BREATH && screen <= PHRASE && holdSince > 0) {
                return Math.min(1f,
                        (SystemClock.elapsedRealtime() - holdSince) / (float) currentTarget().holdMs);
            }
            return 0f;
        }

        private int liveSteadyScore() {
            if (stabilityFrames == 0) return 0;
            return (int) Math.round(
                    clamp(100.0 - (stabilityDeltaSum / stabilityFrames) * 2.10, 0, 100)
            );
        }

        private void drawCall(Canvas c, float left, float right, float oy, float h) {
            text(c, "BLUE", left, oy + dp(44), 10, 0xFFBDBDBD, 0.24f);
            text(c, "MENU", right - dp(34), oy + dp(44), 8, 0xFF666666, 0.12f);

            text(c, "CALL & RESPONSE", left, oy + dp(116), 8, 0xFF606060, 0.17f);

            String title;
            String sub;
            if (callPhase == CALL_LISTEN) {
                title = "Listen.";
                sub = "BLUE plays first";
            } else if (callPhase == CALL_READY) {
                title = "Your turn.";
                sub = "breathe · then answer";
            } else {
                title = phrase[Math.min(phraseIndex, phrase.length - 1)].tab;
                sub = "respond · " + (phraseIndex + 1) + " / " + phrase.length;
            }

            hero(c, title, left, oy + dp(192), callPhase == CALL_RESPOND ? 58 : 44, 0xFFF0F0F0);
            text(c, sub, left, oy + dp(222), 11, 0xFF777777, 0.01f);

            float top = oy + dp(286);
            float bottom = Math.min(oy + h - dp(250), oy + dp(520));
            float cy = (top + bottom) / 2f;

            line(c, left, cy, right, cy, 0xFF2A2A2A, 1f);

            float gap = (right - left) / phrase.length;
            for (int i = 0; i < phrase.length; i++) {
                int color;
                if (callPhase == CALL_LISTEN) {
                    color = 0xFF686868;
                } else if (callPhase == CALL_READY) {
                    color = 0xFF454545;
                } else {
                    color = i < phraseIndex
                            ? 0xFF626262
                            : (i == phraseIndex ? 0xFFE7E7E7 : 0xFF343434);
                }
                float x = left + gap * i;
                text(c, phrase[i].tab, x, cy - dp(28), 14, color, 0.01f);
                if (callPhase == CALL_RESPOND && i == phraseIndex) {
                    line(c, x, cy - dp(17), x + dp(21), cy - dp(17), 0xFFB2B2B2, 1f);
                }
            }

            if (callPhase == CALL_RESPOND) {
                drawTrail(c, left, right, top, bottom, false);
                if (frequency > 0 && SystemClock.elapsedRealtime() - lastPitchAt < 500 && rms >= soundGate) {
                    float normalized = (float) clamp(cents / 120.0, -1, 1);
                    float y = cy - normalized * (bottom - top) * 0.42f;
                    dotPaint.setColor(inBand ? 0xFFF4F4F4 : 0xFF8F8F8F);
                    c.drawCircle(right - dp(9), y, dp(inBand ? 5f : 4f), dotPaint);
                }
            } else {
                long t = SystemClock.elapsedRealtime();
                float pulse = (float) ((Math.sin(t / 150.0) + 1.0) * 0.5);
                dotPaint.setColor(0xFFB7B7B7);
                c.drawCircle(left + (right - left) * (0.2f + 0.6f * pulse), cy, dp(3.5f), dotPaint);
            }

            float actionY = bottom + dp(34);
            line(c, left, actionY, right, actionY, 0xFF171717, 1f);
            text(c, callPhase == CALL_LISTEN ? "PLAYING CALL" :
                            (callPhase == CALL_READY ? "GET READY" : "LISTENING"),
                    left, actionY + dp(30), 8, 0xFF676767, 0.14f);
            text(c, "REPLAY", right - dp(42), actionY + dp(30), 8, 0xFF777777, 0.14f);

            drawMetrics(c, left, right, oy, h, true);
            postInvalidateDelayed(32);
        }

        private void drawTraining(Canvas c, float left, float right, float oy, float h) {
            text(c, "BLUE", left, oy + dp(44), 10, 0xFFBDBDBD, 0.24f);
            text(c, "MENU", right - dp(34), oy + dp(44), 8, 0xFF666666, 0.12f);

            if (activeMode == MODE_FULL) {
                text(c, String.format(Locale.US, "%02d / 05", Math.max(1, Math.min(5, screen))),
                        right - dp(91), oy + dp(44), 8, 0xFF575757, 0.10f);
            }
            drawProgress(c, left, right, oy + dp(70));

            Target target = currentTarget();
            String label;
            String title;
            String subtitle;

            if (screen == BREATH) {
                label = "FIRST BREATH";
                title = "+4";
                subtitle = "hole 4 · blow · C5";
            } else if (screen == DRAW) {
                label = "DRAW";
                title = "−4";
                subtitle = "hole 4 · draw · D5";
            } else if (screen == SUSTAIN) {
                label = "SUSTAIN";
                title = "+4";
                subtitle = "hold the note · 2 seconds";
            } else if (screen == BEND) {
                label = "BEND";
                title = "−4 ↓";
                subtitle = "draw · lower D5 toward C♯5";
            } else {
                label = "FIRST PHRASE";
                title = target.tab;
                subtitle = target.action + " · " + target.note;
            }

            text(c, label, left, oy + dp(116), 8, 0xFF606060, 0.17f);
            hero(c, title, left, oy + dp(190), screen == PHRASE ? 50 : 58, 0xFFF0F0F0);
            text(c, subtitle, left, oy + dp(221), 11, 0xFF777777, 0.01f);

            float laneTop = oy + dp(276);
            float laneBottom = Math.min(oy + h - dp(250), oy + dp(530));
            if (laneBottom < laneTop + dp(140)) laneBottom = laneTop + dp(140);

            if (screen == BEND) {
                float startY = laneTop + dp(22);
                float targetY = laneBottom - dp(22);

                line(c, left, startY, right, targetY, 0xFF2A2A2A, 1f);
                line(c, right - dp(66), targetY, right, targetY, 0xFF616161, 1f);
                text(c, "D5", left, startY - dp(10), 8, 0xFF525252, 0.05f);
                text(c, "C♯5", right - dp(24), targetY - dp(10), 8, 0xFF727272, 0.02f);

                drawTrail(c, left, right, startY, targetY, true);

                if (frequency > 0 && SystemClock.elapsedRealtime() - lastPitchAt < 500 && rms >= soundGate) {
                    float bendProgress = (float) clamp((-cents) / 120.0, 0, 1);
                    float x = left + (right - left) * (0.08f + bendProgress * 0.84f);
                    float y = startY + (targetY - startY) * bendProgress;
                    dotPaint.setColor(inBand ? 0xFFF4F4F4 : 0xFF8F8F8F);
                    c.drawCircle(x, y, dp(inBand ? 5f : 4f), dotPaint);
                }
            } else {
                float cy = (laneTop + laneBottom) / 2f;
                line(c, left, cy, right, cy, 0xFF303030, 1f);
                line(c, left, cy - dp(18), right, cy - dp(18), 0xFF191919, 1f);
                line(c, left, cy + dp(18), right, cy + dp(18), 0xFF191919, 1f);

                drawTrail(c, left, right, laneTop, laneBottom, false);

                if (frequency > 0 && SystemClock.elapsedRealtime() - lastPitchAt < 500 && rms >= soundGate) {
                    float normalized = (float) clamp(cents / 120.0, -1, 1);
                    float y = cy - normalized * (laneBottom - laneTop) * 0.42f;
                    dotPaint.setColor(inBand ? 0xFFF4F4F4 : 0xFF8F8F8F);
                    c.drawCircle(right - dp(9), y, dp(inBand ? 5f : 4f), dotPaint);
                }
            }

            float progressY = laneBottom + dp(22);
            line(c, left, progressY, right, progressY, 0xFF161616, 1f);
            line(c, left, progressY, left + (right - left) * holdProgress(), progressY, 0xFF8C8C8C, 1f);

            if (screen == PHRASE) {
                float tabsY = progressY + dp(49);
                float gap = (right - left) / phrase.length;

                for (int i = 0; i < phrase.length; i++) {
                    int color = i < phraseIndex
                            ? 0xFF626262
                            : (i == phraseIndex ? 0xFFE7E7E7 : 0xFF343434);

                    float x = left + gap * i;
                    text(c, phrase[i].tab, x, tabsY, 14, color, 0.01f);
                    if (i == phraseIndex) {
                        line(c, x, tabsY + dp(8), x + dp(21), tabsY + dp(8), 0xFFB2B2B2, 1f);
                    }
                }
            }

            drawMetrics(c, left, right, oy, h, true);
            postInvalidateDelayed(32);
        }

        private void drawFree(Canvas c, float left, float right, float oy, float h) {
            text(c, "BLUE", left, oy + dp(44), 10, 0xFFBDBDBD, 0.24f);
            text(c, "MENU", right - dp(34), oy + dp(44), 8, 0xFF666666, 0.12f);

            text(c, "FREE PLAY", left, oy + dp(116), 8, 0xFF606060, 0.17f);
            String note = frequency > 0 && rms >= soundGate ? PitchDetector.noteName(frequency) : "—";
            hero(c, note, left, oy + dp(192), 58, 0xFFF0F0F0);
            text(c, "play anything · BLUE will listen", left, oy + dp(222), 11, 0xFF777777, 0.01f);

            float top = oy + dp(285);
            float bottom = Math.min(oy + h - dp(250), oy + dp(530));
            float cy = (top + bottom) / 2f;

            line(c, left, cy, right, cy, 0xFF303030, 1f);
            line(c, left, cy - dp(18), right, cy - dp(18), 0xFF191919, 1f);
            line(c, left, cy + dp(18), right, cy + dp(18), 0xFF191919, 1f);

            drawTrail(c, left, right, top, bottom, false);

            if (frequency > 0 && SystemClock.elapsedRealtime() - lastPitchAt < 500 && rms >= soundGate) {
                float normalized = (float) clamp(cents / 120.0, -1, 1);
                float y = cy - normalized * (bottom - top) * 0.42f;
                dotPaint.setColor(inBand ? 0xFFF4F4F4 : 0xFF8F8F8F);
                c.drawCircle(right - dp(9), y, dp(inBand ? 5f : 4f), dotPaint);
            }

            drawMetrics(c, left, right, oy, h, false);
            postInvalidateDelayed(32);
        }

        private void drawMetrics(Canvas c, float left, float right, float oy, float h, boolean withSteady) {
            float y = oy + h - dp(142);
            line(c, left, y - dp(28), right, y - dp(28), 0xFF181818, 1f);

            String detected = frequency > 0 && rms >= soundGate ? PitchDetector.noteName(frequency) : "—";
            String pitchText = frequency > 0 && rms >= soundGate
                    ? String.format(Locale.US, "%+.0f¢", cents)
                    : "—";

            text(c, "NOTE", left, y, 8, 0xFF5B5B5B, 0.14f);
            text(c, detected, left, y + dp(25), 15, 0xFFB7B7B7, 0);

            float m2 = left + (right - left) * 0.36f;
            text(c, "PITCH", m2, y, 8, 0xFF5B5B5B, 0.14f);
            text(c, pitchText, m2, y + dp(25), 15, inBand ? 0xFFE4E4E4 : 0xFF9A9A9A, 0);

            float m3 = left + (right - left) * 0.72f;
            if (withSteady) {
                String steady = stabilityFrames > 4 ? liveSteadyScore() + "%" : "—";
                text(c, "STEADY", m3, y, 8, 0xFF5B5B5B, 0.14f);
                text(c, steady, m3, y + dp(25), 15, 0xFFB7B7B7, 0);
            } else {
                int signal = (int) Math.round(clamp((rms / Math.max(0.01, soundGate * 4.0)) * 100.0, 0, 100));
                text(c, "SIGNAL", m3, y, 8, 0xFF5B5B5B, 0.14f);
                text(c, signal + "%", m3, y + dp(25), 15, 0xFFB7B7B7, 0);
            }

            text(c, message, left, oy + h - dp(48), 8, 0xFF686868, 0.12f);
            text(c, "C · DIATONIC", right - dp(69), oy + h - dp(48), 8, 0xFF484848, 0.11f);
        }

        private void drawResult(Canvas c, float left, float right, float oy, float h) {
            text(c, "BLUE", left, oy + dp(44), 10, 0xFFBDBDBD, 0.24f);
            text(c, "MENU", right - dp(34), oy + dp(44), 8, 0xFF666666, 0.12f);

            text(c, "SESSION COMPLETE", left, oy + dp(112), 8, 0xFF606060, 0.17f);
            hero(c, String.valueOf(finalScore), left, oy + dp(225), 92, 0xFFF0F0F0);
            text(c, "CONTROL SCORE", left + dp(3), oy + dp(254), 8, 0xFF666666, 0.15f);

            float y = oy + dp(345);
            line(c, left, y - dp(28), right, y - dp(28), 0xFF1A1A1A, 1f);

            float third = (right - left) / 3f;
            text(c, "PITCH", left, y, 8, 0xFF5B5B5B, 0.14f);
            text(c, pitchScore + "%", left, y + dp(29), 17, 0xFFBDBDBD, 0);

            if (activeMode == MODE_CALL) {
                text(c, "TIMING", left + third, y, 8, 0xFF5B5B5B, 0.14f);
                text(c, timingScore + "%", left + third, y + dp(29), 17, 0xFFBDBDBD, 0);
            } else {
                text(c, "STEADY", left + third, y, 8, 0xFF5B5B5B, 0.14f);
                text(c, steadyScore + "%", left + third, y + dp(29), 17, 0xFFBDBDBD, 0);
            }

            text(c, "BEST", left + third * 2f, y, 8, 0xFF5B5B5B, 0.14f);
            text(c, String.valueOf(bestScore), left + third * 2f, y + dp(29), 17, 0xFFBDBDBD, 0);

            float actionY = oy + h - dp(108);
            line(c, left, actionY - dp(25), right, actionY - dp(25), 0xFF1A1A1A, 1f);
            text(c, "AGAIN", left, actionY + dp(8), 9, 0xFFBCBCBC, 0.15f);
            text(c, "MENU", right - dp(31), actionY + dp(8), 9, 0xFF777777, 0.15f);
            text(c, message, left, oy + h - dp(48), 8, 0xFF525252, 0.10f);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);

            float w = getWidth() - getPaddingLeft() - getPaddingRight();
            float h = getHeight() - getPaddingTop() - getPaddingBottom();
            float ox = getPaddingLeft();
            float oy = getPaddingTop();
            float left = ox + dp(26);
            float right = ox + w - dp(26);

            if (screen == HOME) {
                drawHome(c, left, right, oy, h);
            } else if (screen == CALIBRATE) {
                drawCalibration(c, left, right, oy, h);
            } else if (screen == RESULT) {
                drawResult(c, left, right, oy, h);
            } else if (screen == FREE) {
                drawFree(c, left, right, oy, h);
            } else if (screen == CALL) {
                drawCall(c, left, right, oy, h);
            } else {
                drawTraining(c, left, right, oy, h);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP) return true;

            float x = event.getX();
            float y = event.getY();
            float oy = getPaddingTop();
            float h = getHeight() - getPaddingTop() - getPaddingBottom();

            if (screen == HOME) {
                float start = oy + dp(262);
                float row = dp(88);
                if (y >= start && y < start + row * 4) {
                    int index = (int) ((y - start) / row);
                    if (index == 0) startMode(MODE_FULL);
                    else if (index == 1) startMode(MODE_BEND);
                    else if (index == 2) startMode(MODE_CALL);
                    else startMode(MODE_FREE);
                }
                return true;
            }

            if (screen == CALL && y > oy + dp(500) && y < oy + h - dp(120)
                    && x > getWidth() * 0.55f) {
                replayCall();
                return true;
            }

            if (screen == RESULT) {
                float actionY = oy + h - dp(108);
                if (y > actionY - dp(45)) {
                    if (x < getWidth() / 2f) restartCurrentMode();
                    else goHome();
                    return true;
                }
            }

            if (y < oy + dp(75) && x > getWidth() - getPaddingRight() - dp(95)) {
                goHome();
                return true;
            }

            return true;
        }
    }
}
