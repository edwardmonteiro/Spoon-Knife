package com.edwardresearchlabs.blue;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
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
        getWindow().setStatusBarColor(0xFF050505);
        getWindow().setNavigationBarColor(0xFF050505);
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
            blueView.setMessage("MIC LIVE · play +4");
            startListening();
        } else {
            blueView.setMessage("Microphone permission is required.");
        }
    }

    @Override protected void onDestroy() {
        listening = false;
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            recorder.release();
            recorder = null;
        }
        super.onDestroy();
    }

    private AudioRecord createRecorder(int sampleRate, int minBuffer) {
        int[] sources = {MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
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
            int minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
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
                runOnUiThread(() -> blueView.setMessage("MIC LIVE · play +4"));

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
                                while (history.size() > 5) history.removeFirst();
                                Double[] values = history.toArray(new Double[0]);
                                Arrays.sort(values);
                                smooth = values[values.length / 2];
                            } else {
                                history.clear();
                            }

                            final double hz = smooth;
                            final double rms = result.rms;
                            runOnUiThread(() -> blueView.onPitch(hz, rms));

                            // 50% overlap improves responsiveness without excessive CPU.
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
            this.tab = tab; this.note = note; this.action = action; this.hz = hz; this.holdMs = holdMs;
        }
    }

    private final class BlueView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thin = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface light = Typeface.create("sans", Typeface.NORMAL);

        private final Target plus4 = new Target("+4", "C5", "blow", 523.251, 800);
        private final Target minus4 = new Target("−4", "D5", "draw", 587.330, 800);
        private final Target minus2 = new Target("−2", "G4", "draw", 391.995, 240);
        private final Target minus3 = new Target("−3", "B4", "draw", 493.883, 240);
        private final Target plus4Phrase = new Target("+4", "C5", "blow", 523.251, 240);

        private final Target[] phrase = {minus2, minus3, plus4Phrase, minus3, minus2};

        private int level = 0;
        private int phraseIndex = 0;
        private long inTuneSince = 0;
        private long lastPitchAt = 0;
        private long completedAt = 0;
        private double frequency = -1;
        private double cents = 0;
        private double rms = 0;
        private String message = "Allow microphone to begin.";
        private boolean inTune = false;
        private boolean finished = false;

        BlueView(Context context) {
            super(context);
            setBackgroundColor(0xFF050505);
            p.setTypeface(light);
            thin.setStrokeWidth(dp(1));
            setClickable(true);
        }

        void setMessage(String text) {
            message = text;
            invalidate();
        }

        private Target currentTarget() {
            if (level == 0) return plus4;
            if (level == 1) return minus4;
            if (level == 2) return new Target("+4", "C5", "blow", 523.251, 2000);
            if (level == 3) return phrase[Math.min(phraseIndex, phrase.length - 1)];
            return plus4;
        }

        void onPitch(double hz, double levelRms) {
            if (finished) return;
            this.frequency = hz;
            this.rms = levelRms;
            this.lastPitchAt = SystemClock.elapsedRealtime();

            if (hz <= 0) {
                inTune = false;
                inTuneSince = 0;
                invalidate();
                return;
            }

            Target target = currentTarget();
            cents = PitchDetector.centsFromTarget(hz, target.hz);
            double tolerance = level == 3 ? 38.0 : 30.0;
            inTune = Math.abs(cents) <= tolerance && rms > 0.012;

            long now = SystemClock.elapsedRealtime();
            if (inTune) {
                if (inTuneSince == 0) inTuneSince = now;
                if (now - inTuneSince >= target.holdMs) {
                    completeTarget(now);
                }
            } else {
                inTuneSince = 0;
            }
            invalidate();
        }

        private void completeTarget(long now) {
            vibrate();
            inTuneSince = 0;
            if (level < 3) {
                level++;
                completedAt = now;
                if (level == 1) message = "Good. Now draw hole 4.";
                else if (level == 2) message = "Now hold +4 for two seconds.";
                else message = "FIRST PHRASE · follow the tabs.";
            } else {
                phraseIndex++;
                if (phraseIndex >= phrase.length) {
                    finished = true;
                    completedAt = now;
                    message = "YOU PLAYED BLUES.";
                }
            }
        }

        private void vibrate() {
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v == null) return;
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(26, 70));
                else v.vibrate(26);
            } catch (Throwable ignored) {}
        }

        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

        private void text(Canvas c, String s, float x, float y, float sizeSp, int color, float letterSpacing) {
            p.setColor(color);
            p.setTextSize(dp(sizeSp));
            p.setTypeface(light);
            if (Build.VERSION.SDK_INT >= 21) p.setLetterSpacing(letterSpacing);
            c.drawText(s, x, y, p);
            if (Build.VERSION.SDK_INT >= 21) p.setLetterSpacing(0);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth() - getPaddingLeft() - getPaddingRight();
            float h = getHeight() - getPaddingTop() - getPaddingBottom();
            float ox = getPaddingLeft();
            float oy = getPaddingTop();
            float left = ox + dp(26);
            float right = ox + w - dp(26);

            Target target = currentTarget();
            String stage;
            String title;
            String subtitle;

            if (finished) {
                stage = "BLUE · 00:01";
                title = "You played blues.";
                subtitle = "Five notes. One phrase. Start again when you want.";
            } else if (level == 0) {
                stage = "01 · FIRST BREATH";
                title = "Find +4.";
                subtitle = "Hole 4 · blow · C5";
            } else if (level == 1) {
                stage = "02 · DRAW";
                title = "Find −4.";
                subtitle = "Hole 4 · draw · D5";
            } else if (level == 2) {
                stage = "03 · HOLD";
                title = "Stay there.";
                subtitle = "Hold +4 for two seconds.";
            } else {
                stage = "04 · FIRST PHRASE";
                title = target.tab;
                subtitle = target.action + " · " + target.note;
            }

            text(c, stage, left, oy + dp(46), 9, 0xFF727272, 0.12f);
            text(c, "RESET", right - dp(38), oy + dp(46), 9, 0xFF727272, 0.10f);
            text(c, title, left, oy + dp(94), 28, 0xFFE8E8E8, -0.015f);
            text(c, subtitle, left, oy + dp(124), 12, 0xFF7F7F7F, 0.01f);

            float laneTop = oy + dp(190);
            float laneBottom = Math.min(oy + h - dp(250), oy + dp(455));
            float cy = (laneTop + laneBottom) / 2f;

            thin.setColor(0xFF1B1B1B);
            c.drawLine(left, cy, right, cy, thin);

            float corridor = dp(18);
            thin.setColor(0xFF2A2A2A);
            c.drawLine(left, cy - corridor, right, cy - corridor, thin);
            c.drawLine(left, cy + corridor, right, cy + corridor, thin);

            float progress = 0f;
            if (inTune && inTuneSince > 0) {
                progress = Math.min(1f, (SystemClock.elapsedRealtime() - inTuneSince) / (float) target.holdMs);
            }
            if (level == 3 && !finished) progress = phraseIndex / (float) phrase.length + progress / phrase.length;
            if (finished) progress = 1f;

            thin.setColor(0xFF777777);
            float progressX = left + (right - left) * progress;
            c.drawLine(left, cy, progressX, cy, thin);

            if (frequency > 0 && SystemClock.elapsedRealtime() - lastPitchAt < 600) {
                double clamped = Math.max(-100, Math.min(100, cents));
                float y = cy - (float)(clamped / 100.0) * dp(58);
                float x;
                if (level == 3) {
                    x = left + (right-left) * ((phraseIndex + Math.min(1f, progress * phrase.length - phraseIndex)) / (float)phrase.length);
                } else {
                    x = left + (right-left) * Math.max(0.08f, progress);
                }
                p.setColor(inTune ? 0xFFECECEC : 0xFF8D8D8D);
                c.drawCircle(x, y, dp(inTune ? 5.2f : 4.0f), p);
            }

            if (level == 3 || finished) {
                float tabsY = laneBottom + dp(48);
                float gap = Math.min(dp(52), (right-left) / phrase.length);
                float startX = left;
                for (int i = 0; i < phrase.length; i++) {
                    int color = i < phraseIndex || finished ? 0xFF777777 : (i == phraseIndex ? 0xFFE5E5E5 : 0xFF383838);
                    text(c, phrase[i].tab, startX + gap * i, tabsY, 13, color, 0.02f);
                }
            }

            float infoY = oy + h - dp(150);
            thin.setColor(0xFF181818);
            c.drawLine(left, infoY - dp(28), right, infoY - dp(28), thin);

            String detected = frequency > 0 ? PitchDetector.noteName(frequency) : "—";
            String pitchText = frequency > 0 ? String.format(Locale.US, "%+.0f¢", cents) : "—";
            String stability = inTune ? "IN" : "—";
            text(c, "NOTE", left, infoY, 8, 0xFF5D5D5D, 0.12f);
            text(c, detected, left, infoY + dp(23), 14, 0xFFB0B0B0, 0);
            text(c, "PITCH", left + w * 0.33f, infoY, 8, 0xFF5D5D5D, 0.12f);
            text(c, pitchText, left + w * 0.33f, infoY + dp(23), 14, 0xFFB0B0B0, 0);
            text(c, "LOCK", left + w * 0.66f, infoY, 8, 0xFF5D5D5D, 0.12f);
            text(c, stability, left + w * 0.66f, infoY + dp(23), 14, inTune ? 0xFFE0E0E0 : 0xFF777777, 0);

            text(c, message, left, oy + h - dp(52), 11, 0xFF777777, 0.01f);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (event.getY() < getPaddingTop() + dp(78) && event.getX() > getWidth() - getPaddingRight() - dp(100)) {
                    level = 0;
                    phraseIndex = 0;
                    inTuneSince = 0;
                    frequency = -1;
                    cents = 0;
                    finished = false;
                    message = "MIC LIVE · play +4";
                    invalidate();
                    if (!listening && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening();
                    return true;
                }
            }
            return true;
        }
    }
}
