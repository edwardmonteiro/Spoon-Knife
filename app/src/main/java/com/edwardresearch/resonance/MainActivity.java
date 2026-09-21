package com.edwardresearch.resonance;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.media.*;
import android.os.*;
import android.view.*;
import java.util.*;

public final class MainActivity extends Activity {
    private GameView game;
    private static final int MIC = 42;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(9, 9, 10));
        getWindow().setNavigationBarColor(Color.rgb(9, 9, 10));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        game = new GameView();
        setContentView(game);
    }

    void begin() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            game.startSession();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == MIC && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            game.startSession();
        } else {
            game.message = "Microfone necessário. Nenhum áudio sai do aparelho.";
            game.invalidate();
        }
    }

    @Override protected void onStop() {
        game.stopAudio();
        super.onStop();
    }

    final class GameView extends View {
        static final int INTRO = 0;
        static final int CALIBRATE = 1;
        static final int HOLD = 2;
        static final int GLIDE = 3;
        static final int INTERVAL = 4;
        static final int VIBRATO = 5;
        static final int RESULT = 6;

        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF action = new RectF();
        final ArrayDeque<Double> hzMedian = new ArrayDeque<>();
        final ArrayDeque<Frame> trail = new ArrayDeque<>();
        final ArrayList<Double> calibration = new ArrayList<>();
        final ArrayList<Double> errors = new ArrayList<>();
        final ArrayList<Double> holdErrors = new ArrayList<>();
        final ArrayList<Double> glideErrors = new ArrayList<>();
        final VoiceMetrics metrics = new VoiceMetrics();
        final String[] noteNames = {"C","C♯","D","D♯","E","F","F♯","G","G♯","A","A♯","B"};

        final SharedPreferences prefs = getSharedPreferences("resonance_local", 0);

        volatile boolean running = false;
        Thread audioThread;

        int stage = INTRO;
        int level = 1;
        int nextLevel = 1;
        int intervalIndex = 0;
        int score = 0;
        int pitchScore = 0;
        int stabilityScore = 0;
        int glideScore = 0;
        int voicedVibratoFrames = 0;

        double hz = 0;
        double midi = 60;
        double rawMidi = 60;
        double base = 60;
        double target = 60;
        double confidence = 0;
        double goodMs = 0;
        double observedMin = 200;
        double observedMax = 0;
        double vibratoRate = 0;
        double vibratoExtent = 0;
        double vibratoRegularity = 0;
        boolean vibratoDetected = false;

        long voicedAt = 0;
        long stageStartedAt = 0;
        long lastPitchAt = 0;
        long lastAcceptedAt = 0;

        String message = "Sua voz fica no aparelho.";
        String coach = "O coach adaptativo aprende apenas com métricas locais.";

        GameView() {
            super(MainActivity.this);
            setBackgroundColor(Color.rgb(9, 9, 10));
            setClickable(true);
            stroke.setStyle(Paint.Style.STROKE);
            level = Math.max(1, Math.min(5, prefs.getInt("level", 1)));
        }

        void startSession() {
            stopAudio();
            level = Math.max(1, Math.min(5, prefs.getInt("level", 1)));
            nextLevel = level;
            stage = CALIBRATE;
            score = pitchScore = stabilityScore = glideScore = 0;
            hz = 0;
            midi = rawMidi = 60;
            confidence = goodMs = 0;
            voicedAt = lastPitchAt = lastAcceptedAt = 0;
            observedMin = 200;
            observedMax = 0;
            intervalIndex = 0;
            voicedVibratoFrames = 0;
            vibratoRate = vibratoExtent = vibratoRegularity = 0;
            vibratoDetected = false;

            calibration.clear();
            errors.clear();
            holdErrors.clear();
            glideErrors.clear();
            hzMedian.clear();
            trail.clear();
            metrics.resetVibrato();

            message = "Cante uma nota confortável. Sem força.";
            coach = "Pare se sentir dor ou esforço.";
            stageStartedAt = System.currentTimeMillis();

            running = true;
            audioThread = new Thread(this::audioLoop, "ResonanceAudio");
            audioThread.start();
            invalidate();
        }

        void stopAudio() {
            running = false;
            if (audioThread != null) audioThread.interrupt();
            audioThread = null;
        }

        void audioLoop() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            final int sampleRate = 44100;
            final int samples = 2048;
            int minBuffer = AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord recorder = null;
            try {
                recorder = new AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(minBuffer, samples * 6));
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    post(() -> {
                        message = "Não consegui iniciar o microfone.";
                        invalidate();
                    });
                    return;
                }

                PitchDetector detector = new PitchDetector(sampleRate);
                short[] buffer = new short[samples];
                recorder.startRecording();

                while (running) {
                    int read = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                    if (read > 0) {
                        PitchDetector.Result result = detector.detect(buffer, read);
                        post(() -> accept(result));
                    }
                }
            } catch (Exception error) {
                post(() -> {
                    message = "Falha ao acessar o microfone.";
                    invalidate();
                });
            } finally {
                if (recorder != null) {
                    try { recorder.stop(); } catch (Exception ignored) {}
                    recorder.release();
                }
            }
        }

        void accept(PitchDetector.Result result) {
            long now = System.currentTimeMillis();
            if (stage == INTRO || stage == RESULT) return;

            if (result.frequency <= 0) {
                if (now - lastPitchAt > 260) {
                    hz = 0;
                    if (stage == CALIBRATE) voicedAt = 0;
                }
                updateTimedStages(now);
                invalidate();
                return;
            }

            lastPitchAt = now;
            confidence = result.confidence;
            rawMidi = frequencyToMidi(result.frequency);

            hzMedian.addLast(result.frequency);
            while (hzMedian.size() > 3) hzMedian.removeFirst();
            ArrayList<Double> ordered = new ArrayList<>(hzMedian);
            Collections.sort(ordered);
            hz = ordered.get(ordered.size() / 2);
            midi = frequencyToMidi(hz);

            long frameMs = lastAcceptedAt == 0 ? 46 : Math.max(20, Math.min(110, now - lastAcceptedAt));
            lastAcceptedAt = now;

            observedMin = Math.min(observedMin, midi);
            observedMax = Math.max(observedMax, midi);

            if (stage == CALIBRATE) {
                handleCalibration(now);
                invalidate();
                return;
            }

            target = currentTarget(now);
            double cents = (midi - target) * 100.0;
            addTrail(midi, target);

            if (stage == HOLD) {
                errors.add(cents);
                holdErrors.add(cents);
                updateGoodTime(cents, frameMs, toleranceCents());
                if (goodMs >= 1600) enterStage(GLIDE, now);
            } else if (stage == GLIDE) {
                glideErrors.add(cents);
                if (Math.abs(cents) < 140) errors.add(cents);
            } else if (stage == INTERVAL) {
                errors.add(cents);
                updateGoodTime(cents, frameMs, toleranceCents());
                if (goodMs >= 650) advanceInterval(now);
            } else if (stage == VIBRATO) {
                errors.add(cents);
                voicedVibratoFrames++;
                metrics.addVibratoSample(now, (rawMidi - base) * 100.0);
            }

            updateTimedStages(now);
            invalidate();
        }

        void handleCalibration(long now) {
            if (confidence < 0.68) return;
            if (voicedAt == 0) voicedAt = now;
            calibration.add(rawMidi);

            if (now - voicedAt >= 2300 && calibration.size() >= 24) {
                ArrayList<Double> sorted = new ArrayList<>(calibration);
                Collections.sort(sorted);
                double median = sorted.get(sorted.size() / 2);
                base = Math.rint(median);
                target = base;
                observedMin = Math.min(observedMin, median);
                observedMax = Math.max(observedMax, median);
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                enterStage(HOLD, now);
            }
        }

        void updateGoodTime(double cents, long frameMs, double tolerance) {
            if (Math.abs(cents) <= tolerance && confidence >= 0.62) {
                goodMs += frameMs;
            } else {
                goodMs = Math.max(0, goodMs - frameMs * 0.7);
            }
        }

        void updateTimedStages(long now) {
            long elapsed = now - stageStartedAt;
            if (stage == GLIDE && elapsed >= glideDurationMs()) {
                enterStage(INTERVAL, now);
            } else if (stage == VIBRATO && elapsed >= 5200) {
                VoiceMetrics.Vibrato v = metrics.analyzeVibrato();
                vibratoRate = v.rateHz;
                vibratoExtent = v.extentCents;
                vibratoRegularity = v.regularity;
                vibratoDetected = v.detected;
                finishSession();
            }
        }

        void enterStage(int newStage, long now) {
            stage = newStage;
            stageStartedAt = now;
            goodMs = 0;

            if (newStage == HOLD) {
                target = base;
                message = "Segure " + note(base) + " no centro.";
                coach = "Quanto mais estável a linha, melhor.";
            } else if (newStage == GLIDE) {
                message = "Siga a linha: suba e desça sem aumentar a força.";
                coach = "Sua voz é o controle do jogo.";
            } else if (newStage == INTERVAL) {
                intervalIndex = 0;
                target = intervalTarget();
                message = "Acerte os portais, um de cada vez.";
                coach = "Chegue perto da nota antes de aumentar o volume.";
            } else if (newStage == VIBRATO) {
                target = base;
                metrics.resetVibrato();
                voicedVibratoFrames = 0;
                message = "Sustente " + note(base) + ". Deixe a voz oscilar naturalmente.";
                coach = "Não force vibrato. O app apenas mede o que aparecer.";
            }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }

        void advanceInterval(long now) {
            intervalIndex++;
            goodMs = 0;
            int[] pattern = intervalPattern();
            if (intervalIndex >= pattern.length) {
                enterStage(VIBRATO, now);
            } else {
                target = intervalTarget();
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
        }

        int[] intervalPattern() {
            int step = Math.min(5, level + 1);
            return new int[]{0, step, 0, -step, 0};
        }

        double intervalTarget() {
            int[] pattern = intervalPattern();
            int index = Math.max(0, Math.min(pattern.length - 1, intervalIndex));
            return base + pattern[index];
        }

        long glideDurationMs() {
            return 7800;
        }

        double currentTarget(long now) {
            if (stage == HOLD || stage == VIBRATO) return base;
            if (stage == INTERVAL) return intervalTarget();
            if (stage == GLIDE) {
                double progress = VoiceMetrics.clamp((now - stageStartedAt) / (double)glideDurationMs(), 0, 1);
                double span = Math.min(5.0, 2.0 + level);
                return base + span * Math.sin(progress * Math.PI * 2.0);
            }
            return base;
        }

        double toleranceCents() {
            return Math.max(26, 50 - (level - 1) * 6);
        }

        void finishSession() {
            stage = RESULT;
            stopAudio();

            double meanAbs = VoiceMetrics.meanAbsolute(errors, 85);
            double meanSigned = VoiceMetrics.mean(errors, 0);
            double stabilitySd = VoiceMetrics.standardDeviation(holdErrors);
            double glideMean = VoiceMetrics.meanAbsolute(glideErrors, 100);

            pitchScore = (int)Math.round(VoiceMetrics.clamp(100 - meanAbs * 1.05, 0, 100));
            stabilityScore = (int)Math.round(VoiceMetrics.clamp(100 - Math.max(0, stabilitySd - 7) * 1.55, 0, 100));
            glideScore = (int)Math.round(VoiceMetrics.clamp(100 - glideMean * 0.72, 0, 100));
            score = (int)Math.round(pitchScore * 0.46 + stabilityScore * 0.30 + glideScore * 0.24);

            nextLevel = level;
            if (score >= 78 && stabilityScore >= 55 && level < 5) nextLevel = level + 1;

            if (meanSigned < -18) {
                coach = "Você tende a chegar por baixo. Mire no centro antes de sustentar.";
            } else if (meanSigned > 18) {
                coach = "Você tende a chegar acima. Ataque mais leve e encontre o centro.";
            } else if (stabilityScore < 55) {
                coach = "Seu principal treino agora é estabilidade, não extensão.";
            } else if (glideScore < 55) {
                coach = "Subidas e descidas contínuas ainda são seu maior gargalo.";
            } else if (vibratoDetected) {
                coach = "Vibrato detectado. Preserve a regularidade sem forçar amplitude.";
            } else {
                coach = "Bom controle central. O próximo treino pode ampliar os intervalos.";
            }

            message = nextLevel > level
                    ? "LEVEL " + String.format(Locale.US, "%02d", nextLevel) + " UNLOCKED"
                    : "Sessão concluída · coach atualizado";

            int best = Math.max(score, prefs.getInt("best", 0));
            prefs.edit()
                    .putInt("best", best)
                    .putInt("level", nextLevel)
                    .putFloat("last_pitch", pitchScore)
                    .putFloat("last_stability", stabilityScore)
                    .putFloat("last_glide", glideScore)
                    .putFloat("range_low", (float)(observedMin < 150 ? observedMin : base))
                    .putFloat("range_high", (float)(observedMax > 0 ? observedMax : base))
                    .putFloat("vibrato_rate", (float)vibratoRate)
                    .putFloat("vibrato_extent", (float)vibratoExtent)
                    .apply();

            invalidate();
        }

        void addTrail(double sung, double wanted) {
            trail.addLast(new Frame(sung, wanted));
            while (trail.size() > 150) trail.removeFirst();
        }

        String liveHint() {
            if (hz <= 0 || stage == CALIBRATE || stage == RESULT) return "";
            double cents = (midi - target) * 100.0;
            if (cents < -70) return "ABAIXO · suba um pouco";
            if (cents > 70) return "ACIMA · desça um pouco";
            if (Math.abs(cents) <= toleranceCents()) return "CENTERED";
            return "aproxime a linha do alvo";
        }

        double frequencyToMidi(double frequency) {
            return 69.0 + 12.0 * (Math.log(frequency / 440.0) / Math.log(2.0));
        }

        String note(double midiValue) {
            int number = (int)Math.round(midiValue);
            int pitchClass = ((number % 12) + 12) % 12;
            int octave = number / 12 - 1;
            return noteNames[pitchClass] + octave;
        }

        float yFor(double midiValue, float top, float bottom) {
            double center = base;
            double range = 7.0;
            double relative = VoiceMetrics.clamp((midiValue - center) / range, -1, 1);
            return (float)((top + bottom) / 2.0 - relative * (bottom - top) * 0.46);
        }

        void drawText(Canvas canvas, String text, float x, float y, float size, int color, Paint.Align align) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setTextAlign(align);
            paint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            canvas.drawText(text, x, y, paint);
        }

        void drawPitchWorld(Canvas canvas, float left, float right, float top, float bottom) {
            float width = right - left;

            for (int offset = -6; offset <= 6; offset++) {
                float y = yFor(base + offset, top, bottom);
                stroke.setStrokeWidth(dp(offset == 0 ? 1.1f : 0.65f));
                stroke.setColor(offset == 0 ? Color.rgb(54,54,61) : Color.rgb(29,29,34));
                canvas.drawLine(left, y, right, y, stroke);
                if (offset % 2 == 0) {
                    drawText(canvas, note(base + offset), left, y - dp(4), dp(9),
                            Color.rgb(76,76,86), Paint.Align.LEFT);
                }
            }

            if (!trail.isEmpty()) {
                Path targetPath = new Path();
                Path sungPath = new Path();
                int size = trail.size();
                int i = 0;
                for (Frame frame : trail) {
                    float x = left + (size <= 1 ? width : width * i / (float)(size - 1));
                    float sy = yFor(frame.sungMidi, top, bottom);
                    float ty = yFor(frame.targetMidi, top, bottom);
                    if (i == 0) {
                        targetPath.moveTo(x, ty);
                        sungPath.moveTo(x, sy);
                    } else {
                        targetPath.lineTo(x, ty);
                        sungPath.lineTo(x, sy);
                    }
                    i++;
                }

                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(dp(1.15f));
                stroke.setColor(Color.rgb(92,92,103));
                canvas.drawPath(targetPath, stroke);

                stroke.setStrokeWidth(dp(2.25f));
                stroke.setColor(Color.rgb(231,231,235));
                canvas.drawPath(sungPath, stroke);
            }

            if (stage >= HOLD && stage <= VIBRATO) {
                target = currentTarget(System.currentTimeMillis());
                float ty = yFor(target, top, bottom);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.4f));
                paint.setColor(Color.rgb(190,190,199));
                canvas.drawCircle(right - dp(18), ty, dp(17), paint);

                if (hz > 0) {
                    float sy = yFor(midi, top, bottom);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(Color.rgb(245,245,247));
                    canvas.drawCircle(right - dp(18), sy, dp(5.5f), paint);
                }
            }
        }

        void drawMetric(Canvas canvas, String label, int value, float left, float right, float y) {
            drawText(canvas, label, left, y, dp(10), Color.rgb(112,112,123), Paint.Align.LEFT);
            drawText(canvas, String.format(Locale.US, "%02d", value), right, y, dp(10),
                    Color.rgb(200,200,207), Paint.Align.RIGHT);

            float barTop = y + dp(8);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(31,31,36));
            canvas.drawRoundRect(left, barTop, right, barTop + dp(3), dp(2), dp(2), paint);
            paint.setColor(Color.rgb(205,205,213));
            canvas.drawRoundRect(left, barTop, left + (right-left) * value / 100f,
                    barTop + dp(3), dp(2), dp(2), paint);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float w = getWidth();
            float h = getHeight();
            float left = dp(26);
            float right = w - dp(26);
            float worldTop = dp(100);
            float worldBottom = h - dp(240);

            drawText(canvas, "RESONANCE", left, dp(42), dp(13),
                    Color.rgb(225,225,229), Paint.Align.LEFT);
            drawText(canvas, "LEVEL " + String.format(Locale.US, "%02d", level), right, dp(42), dp(11),
                    Color.rgb(120,120,130), Paint.Align.RIGHT);

            if (stage == INTRO) {
                drawText(canvas, "VOICE", w/2, h*0.37f, dp(58),
                        Color.rgb(239,239,242), Paint.Align.CENTER);
                drawText(canvas, "move your voice · move the world", w/2, h*0.37f + dp(38), dp(11),
                        Color.rgb(105,105,116), Paint.Align.CENTER);
                int best = prefs.getInt("best", 0);
                if (best > 0) {
                    drawText(canvas, "BEST " + best, w/2, h*0.37f + dp(72), dp(10),
                            Color.rgb(85,85,95), Paint.Align.CENTER);
                }
            } else if (stage == CALIBRATE) {
                drawText(canvas, hz > 0 ? note(midi) : "—", w/2, h*0.36f, dp(58),
                        Color.rgb(239,239,242), Paint.Align.CENTER);
                drawText(canvas, "FIND YOUR CENTER", w/2, h*0.36f + dp(42), dp(10),
                        Color.rgb(105,105,116), Paint.Align.CENTER);
            } else if (stage == RESULT) {
                drawText(canvas, String.format(Locale.US, "%02d", score), w/2, dp(186), dp(76),
                        Color.rgb(240,240,243), Paint.Align.CENTER);
                drawText(canvas, "VOICE CONTROL", w/2, dp(220), dp(10),
                        Color.rgb(108,108,118), Paint.Align.CENTER);

                drawMetric(canvas, "PITCH", pitchScore, left, right, dp(270));
                drawMetric(canvas, "STABILITY", stabilityScore, left, right, dp(314));
                drawMetric(canvas, "GLIDE", glideScore, left, right, dp(358));

                String vibratoText = vibratoDetected
                        ? String.format(Locale.US, "VIBRATO %.1f Hz · %.0f cents", vibratoRate, vibratoExtent)
                        : "VIBRATO · ainda não consistente";
                drawText(canvas, vibratoText, left, dp(410), dp(10),
                        Color.rgb(112,112,123), Paint.Align.LEFT);

                drawText(canvas, "ADAPTIVE COACH · ON DEVICE", left, dp(454), dp(9),
                        Color.rgb(86,86,97), Paint.Align.LEFT);
                drawWrapped(canvas, coach, left, right, dp(480), dp(13), Color.rgb(203,203,210));
            } else {
                drawPitchWorld(canvas, left, right, worldTop, worldBottom);

                drawText(canvas, stageLabel(), left, worldBottom + dp(38), dp(10),
                        Color.rgb(91,91,102), Paint.Align.LEFT);
                drawWrapped(canvas, message, left, right, worldBottom + dp(63), dp(13),
                        Color.rgb(203,203,210));

                String hint = liveHint();
                if (!hint.isEmpty()) {
                    drawText(canvas, hint, left, worldBottom + dp(105), dp(10),
                            Color.rgb(118,118,129), Paint.Align.LEFT);
                }

                if (hz > 0) {
                    double cents = (midi - target) * 100.0;
                    drawText(canvas,
                            note(midi) + "  ·  " + String.format(Locale.US, "%+.0f cents", cents),
                            right, worldBottom + dp(105), dp(10),
                            Color.rgb(118,118,129), Paint.Align.RIGHT);
                }
            }

            if (stage == RESULT) {
                drawText(canvas, message, left, h - dp(126), dp(11),
                        Color.rgb(126,126,137), Paint.Align.LEFT);
            } else if (stage != INTRO && stage != CALIBRATE) {
                drawText(canvas, coach, left, h - dp(126), dp(10),
                        Color.rgb(88,88,98), Paint.Align.LEFT);
            } else {
                drawText(canvas, message, left, h - dp(126), dp(12),
                        Color.rgb(188,188,196), Paint.Align.LEFT);
            }

            action.set(left, h - dp(86), right, h - dp(34));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.rgb(61,61,68));
            canvas.drawRoundRect(action, dp(26), dp(26), paint);

            String button = stage == INTRO ? "BEGIN" : (stage == RESULT ? "TRAIN AGAIN" : "LISTENING");
            drawText(canvas, button, action.centerX(), action.centerY() + dp(4), dp(11),
                    Color.rgb(224,224,229), Paint.Align.CENTER);
        }

        void drawWrapped(Canvas canvas, String text, float left, float right, float top, float size, int color) {
            paint.setTextSize(size);
            paint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            String[] words = text.split(" ");
            StringBuilder line = new StringBuilder();
            float y = top;
            for (String word : words) {
                String test = line.length() == 0 ? word : line + " " + word;
                if (paint.measureText(test) > right - left && line.length() > 0) {
                    drawText(canvas, line.toString(), left, y, size, color, Paint.Align.LEFT);
                    y += size * 1.35f;
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(test);
                }
            }
            if (line.length() > 0) drawText(canvas, line.toString(), left, y, size, color, Paint.Align.LEFT);
        }

        String stageLabel() {
            if (stage == HOLD) return "01 · CENTER";
            if (stage == GLIDE) return "02 · GLIDE";
            if (stage == INTERVAL) return "03 · PORTALS";
            if (stage == VIBRATO) return "04 · VIBRATO SCAN";
            return "";
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP
                    && action.contains(event.getX(), event.getY())
                    && (stage == INTRO || stage == RESULT)) {
                begin();
                performClick();
            }
            return true;
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }

        float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }

        final class Frame {
            final double sungMidi;
            final double targetMidi;
            Frame(double sungMidi, double targetMidi) {
                this.sungMidi = sungMidi;
                this.targetMidi = targetMidi;
            }
        }
    }
}
