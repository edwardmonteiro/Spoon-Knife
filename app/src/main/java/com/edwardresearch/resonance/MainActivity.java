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
        static final int CENTER = 2;
        static final int SONG = 3;
        static final int VIBRATO = 4;
        static final int RESULT = 5;

        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF action = new RectF();

        final ArrayDeque<Double> hzMedian = new ArrayDeque<>();
        final ArrayDeque<SungFrame> songTrail = new ArrayDeque<>();
        final ArrayList<Double> calibration = new ArrayList<>();
        final ArrayList<Double> centerErrors = new ArrayList<>();
        final ArrayList<Double> songErrors = new ArrayList<>();
        final ArrayList<Double> phraseErrors = new ArrayList<>();
        final ArrayList<Integer> phraseScores = new ArrayList<>();
        final ArrayList<Integer> phraseSyncScores = new ArrayList<>();

        final VoiceMetrics metrics = new VoiceMetrics();
        final SharedPreferences prefs = getSharedPreferences("resonance_local", 0);
        final String[] noteNames = {"C","C♯","D","D♯","E","F","F♯","G","G♯","A","A♯","B"};

        volatile boolean running = false;
        Thread audioThread;

        int stage = INTRO;
        int level;
        int nextLevel;
        int score;
        int pitchScore;
        int stabilityScore;
        int songScore;
        int syncScore;
        int phraseIndex;
        int sessionNumber;

        double hz;
        double midi = 60;
        double rawMidi = 60;
        double base = 60;
        double target = 60;
        double confidence;
        double centerGoodMs;
        double observedMin = 200;
        double observedMax = 0;
        double vibratoRate;
        double vibratoExtent;
        double vibratoRegularity;
        boolean vibratoDetected;

        long voicedAt;
        long lastPitchAt;
        long lastAcceptedAt;
        long stageStartedAt;

        long phraseStartAt;
        long phraseEndAt;
        long phraseResultUntil;
        long phraseVoicedMs;
        long phraseInSyncMs;

        String message = "Sua voz fica no aparelho.";
        String coach = "O coach adaptativo aprende apenas com métricas locais.";
        String phraseMoment = "";

        SongPathEngine song;

        GameView() {
            super(MainActivity.this);
            setBackgroundColor(Color.rgb(9, 9, 10));
            setClickable(true);
            stroke.setStyle(Paint.Style.STROKE);
            level = clampLevel(prefs.getInt("level", 1));
        }

        void startSession() {
            stopAudio();

            level = clampLevel(prefs.getInt("level", 1));
            nextLevel = level;
            sessionNumber = prefs.getInt("session_count", 0);

            stage = CALIBRATE;
            score = pitchScore = stabilityScore = songScore = syncScore = 0;
            phraseIndex = 0;

            hz = 0;
            midi = rawMidi = 60;
            base = target = 60;
            confidence = 0;
            centerGoodMs = 0;
            observedMin = 200;
            observedMax = 0;
            vibratoRate = vibratoExtent = vibratoRegularity = 0;
            vibratoDetected = false;

            voicedAt = lastPitchAt = lastAcceptedAt = 0;
            phraseStartAt = phraseEndAt = phraseResultUntil = 0;
            phraseVoicedMs = phraseInSyncMs = 0;

            calibration.clear();
            centerErrors.clear();
            songErrors.clear();
            phraseErrors.clear();
            phraseScores.clear();
            phraseSyncScores.clear();
            hzMedian.clear();
            songTrail.clear();
            metrics.resetVibrato();

            message = "Cante uma nota confortável. Sem força.";
            coach = "Pare se sentir dor ou esforço.";
            phraseMoment = "";
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

            updateTimed(now);

            if (result.frequency <= 0) {
                if (now - lastPitchAt > 260) {
                    hz = 0;
                    if (stage == CALIBRATE) voicedAt = 0;
                }
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

            long frameMs = lastAcceptedAt == 0 ? 46 : Math.max(20, Math.min(100, now - lastAcceptedAt));
            lastAcceptedAt = now;

            observedMin = Math.min(observedMin, midi);
            observedMax = Math.max(observedMax, midi);

            if (stage == CALIBRATE) {
                handleCalibration(now);
            } else if (stage == CENTER) {
                handleCenter(frameMs, now);
            } else if (stage == SONG) {
                handleSong(frameMs, now);
            } else if (stage == VIBRATO) {
                handleVibrato(now);
            }

            invalidate();
        }

        void handleCalibration(long now) {
            if (confidence < 0.68) return;
            if (voicedAt == 0) voicedAt = now;
            calibration.add(rawMidi);

            if (now - voicedAt >= 2200 && calibration.size() >= 22) {
                ArrayList<Double> sorted = new ArrayList<>(calibration);
                Collections.sort(sorted);
                base = Math.rint(sorted.get(sorted.size() / 2));
                target = base;
                observedMin = Math.min(observedMin, base);
                observedMax = Math.max(observedMax, base);
                enterCenter(now);
            }
        }

        void enterCenter(long now) {
            stage = CENTER;
            stageStartedAt = now;
            centerGoodMs = 0;
            message = "Segure " + note(base) + " no centro.";
            coach = "Essa nota será o centro das melodias.";
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }

        void handleCenter(long frameMs, long now) {
            target = base;
            double cents = (midi - target) * 100.0;
            centerErrors.add(cents);

            if (Math.abs(cents) <= toleranceCents() && confidence >= 0.62) {
                centerGoodMs += frameMs;
            } else {
                centerGoodMs = Math.max(0, centerGoodMs - frameMs * 0.7);
            }

            if (centerGoodMs >= 1500) enterSong(now);
        }

        void enterSong(long now) {
            stage = SONG;
            stageStartedAt = now;
            song = SongPathEngine.create(level, sessionNumber);
            phraseIndex = 0;
            phraseScores.clear();
            phraseSyncScores.clear();
            beginPhrase(now);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }

        void beginPhrase(long now) {
            long countIn = song.countInMs(phraseIndex);
            phraseStartAt = now + countIn;
            phraseEndAt = phraseStartAt + song.durationMs(phraseIndex);
            phraseResultUntil = 0;
            phraseVoicedMs = 0;
            phraseInSyncMs = 0;
            phraseErrors.clear();
            songTrail.clear();
            phraseMoment = "";

            SongPathEngine.Phrase phrase = song.phrase(phraseIndex);
            message = "PHRASE " + (phraseIndex + 1) + "/" + song.phraseCount() + " · " + phrase.name;
            coach = "Cante em “ah”. Faça sua linha encontrar a linha-alvo.";
        }

        void handleSong(long frameMs, long now) {
            if (phraseResultUntil > 0 || now < phraseStartAt || now >= phraseEndAt) return;

            target = song.targetMidi(base, phraseIndex, now - phraseStartAt);
            double cents = (midi - target) * 100.0;

            phraseVoicedMs += frameMs;
            phraseErrors.add(cents);
            songErrors.add(cents);

            if (Math.abs(cents) <= songToleranceCents()) phraseInSyncMs += frameMs;

            songTrail.addLast(new SungFrame(now, midi));
            while (!songTrail.isEmpty() && now - songTrail.peekFirst().timeMs > 5200) {
                songTrail.removeFirst();
            }
        }

        void finishPhrase(long now) {
            double meanAbs = VoiceMetrics.meanAbsolute(phraseErrors, 120);
            double accuracy = VoiceMetrics.clamp(100 - meanAbs * 0.95, 0, 100);
            double coverage = VoiceMetrics.clamp(
                    100.0 * phraseVoicedMs / Math.max(1, song.durationMs(phraseIndex)), 0, 100);
            double sync = phraseVoicedMs > 0
                    ? VoiceMetrics.clamp(100.0 * phraseInSyncMs / phraseVoicedMs, 0, 100)
                    : 0;

            int phraseScore = (int)Math.round(accuracy * 0.58 + sync * 0.27 + coverage * 0.15);
            int phraseSync = (int)Math.round(sync);
            phraseScores.add(phraseScore);
            phraseSyncScores.add(phraseSync);

            boolean perfect = phraseScore >= 88 && phraseSync >= 72 && coverage >= 72;
            phraseMoment = perfect
                    ? "PERFECT SYNC"
                    : "SYNC " + String.format(Locale.US, "%02d", phraseSync);

            phraseResultUntil = now + 1200;
            performHapticFeedback(perfect
                    ? HapticFeedbackConstants.LONG_PRESS
                    : HapticFeedbackConstants.KEYBOARD_TAP);
        }

        void advanceSong(long now) {
            phraseIndex++;
            if (phraseIndex >= song.phraseCount()) {
                enterVibrato(now);
            } else {
                beginPhrase(now);
            }
        }

        void enterVibrato(long now) {
            stage = VIBRATO;
            stageStartedAt = now;
            target = base;
            metrics.resetVibrato();
            message = "Sustente " + note(base) + ". Deixe a voz oscilar naturalmente.";
            coach = "Não force vibrato. Ele não aumenta seu score nesta versão.";
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }

        void handleVibrato(long now) {
            metrics.addVibratoSample(now, (rawMidi - base) * 100.0);
        }

        void updateTimed(long now) {
            if (stage == SONG) {
                if (phraseResultUntil > 0) {
                    if (now >= phraseResultUntil) advanceSong(now);
                } else if (now >= phraseEndAt && phraseEndAt > 0) {
                    finishPhrase(now);
                }
            } else if (stage == VIBRATO && now - stageStartedAt >= 4600) {
                VoiceMetrics.Vibrato v = metrics.analyzeVibrato();
                vibratoRate = v.rateHz;
                vibratoExtent = v.extentCents;
                vibratoRegularity = v.regularity;
                vibratoDetected = v.detected;
                finishSession();
            }
        }

        void finishSession() {
            stage = RESULT;
            stopAudio();

            double songMeanAbs = VoiceMetrics.meanAbsolute(songErrors, 100);
            double songSigned = VoiceMetrics.mean(songErrors, 0);
            double stabilitySd = VoiceMetrics.standardDeviation(centerErrors);

            pitchScore = (int)Math.round(VoiceMetrics.clamp(100 - songMeanAbs * 0.90, 0, 100));
            stabilityScore = (int)Math.round(
                    VoiceMetrics.clamp(100 - Math.max(0, stabilitySd - 7) * 1.55, 0, 100));
            songScore = averageInt(phraseScores);
            syncScore = averageInt(phraseSyncScores);

            score = (int)Math.round(
                    pitchScore * 0.25
                    + stabilityScore * 0.20
                    + songScore * 0.40
                    + syncScore * 0.15);

            nextLevel = level;
            if (score >= 78 && songScore >= 72 && syncScore >= 58 && level < 5) {
                nextLevel = level + 1;
            }

            if (songSigned < -18) {
                coach = "Você segue a melodia, mas costuma chegar por baixo das notas.";
            } else if (songSigned > 18) {
                coach = "Você costuma chegar acima. Entre nas notas com menos impulso.";
            } else if (stabilityScore < 55) {
                coach = "A melodia está vindo. Seu maior ganho agora está em sustentar o centro.";
            } else if (syncScore < 55) {
                coach = "Você encontra as notas, mas passa pouco tempo exatamente sobre a trilha.";
            } else if (songScore < 68) {
                coach = "Treine a forma da frase inteira, não cada nota isoladamente.";
            } else if (vibratoDetected) {
                coach = "Bom controle melódico. Vibrato natural detectado sem precisar forçar.";
            } else {
                coach = "Boa leitura da trilha. A próxima sessão aumenta a complexidade melódica.";
            }

            message = nextLevel > level
                    ? "LEVEL " + String.format(Locale.US, "%02d", nextLevel) + " UNLOCKED"
                    : "Sessão concluída · perfil vocal atualizado";

            int best = Math.max(score, prefs.getInt("best", 0));
            prefs.edit()
                    .putInt("best", best)
                    .putInt("level", nextLevel)
                    .putInt("session_count", sessionNumber + 1)
                    .putInt("last_song_score", songScore)
                    .putInt("last_sync_score", syncScore)
                    .putFloat("last_pitch", pitchScore)
                    .putFloat("last_stability", stabilityScore)
                    .putFloat("range_low", (float)(observedMin < 150 ? observedMin : base))
                    .putFloat("range_high", (float)(observedMax > 0 ? observedMax : base))
                    .putFloat("vibrato_rate", (float)vibratoRate)
                    .putFloat("vibrato_extent", (float)vibratoExtent)
                    .apply();

            invalidate();
        }

        int averageInt(ArrayList<Integer> values) {
            if (values.isEmpty()) return 0;
            int sum = 0;
            for (int v : values) sum += v;
            return Math.round(sum / (float) values.size());
        }

        double frequencyToMidi(double frequency) {
            return 69.0 + 12.0 * (Math.log(frequency / 440.0) / Math.log(2.0));
        }

        double toleranceCents() {
            return Math.max(26, 48 - (level - 1) * 5);
        }

        double songToleranceCents() {
            return Math.max(34, 58 - (level - 1) * 5);
        }

        int clampLevel(int value) {
            return Math.max(1, Math.min(5, value));
        }

        String note(double midiValue) {
            int number = (int)Math.round(midiValue);
            int pitchClass = ((number % 12) + 12) % 12;
            int octave = number / 12 - 1;
            return noteNames[pitchClass] + octave;
        }

        float yFor(double midiValue, float top, float bottom) {
            double range = 8.0;
            double relative = VoiceMetrics.clamp((midiValue - base) / range, -1, 1);
            return (float)((top + bottom) / 2.0 - relative * (bottom - top) * 0.46);
        }

        float xForTime(long absoluteTime, long now, float left, float playhead, float right) {
            long delta = absoluteTime - now;
            if (delta <= 0) {
                double p = VoiceMetrics.clamp((-delta) / 2600.0, 0, 1);
                return (float)(playhead - p * (playhead - left));
            } else {
                double p = VoiceMetrics.clamp(delta / 5600.0, 0, 1);
                return (float)(playhead + p * (right - playhead));
            }
        }

        void drawText(Canvas canvas, String text, float x, float y, float size, int color, Paint.Align align) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setTextAlign(align);
            paint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            canvas.drawText(text, x, y, paint);
        }

        void drawSongWorld(Canvas canvas, float left, float right, float top, float bottom, long now) {
            float width = right - left;
            float playhead = left + width * 0.34f;

            for (int offset = -7; offset <= 7; offset++) {
                float y = yFor(base + offset, top, bottom);
                stroke.setStrokeWidth(dp(offset == 0 ? 1.0f : 0.55f));
                stroke.setColor(offset == 0 ? Color.rgb(51,51,59) : Color.rgb(27,27,32));
                canvas.drawLine(left, y, right, y, stroke);
            }

            stroke.setStrokeWidth(dp(1));
            stroke.setColor(Color.rgb(72,72,82));
            canvas.drawLine(playhead, top, playhead, bottom, stroke);

            if (song != null && phraseIndex < song.phraseCount()) {
                Path targetPath = new Path();
                boolean started = false;

                long from = Math.max(phraseStartAt, now - 2600);
                long to = Math.min(phraseEndAt, now + 5600);

                for (long t = from; t <= to; t += 70) {
                    float x = xForTime(t, now, left, playhead, right);
                    double targetMidi = song.targetMidi(base, phraseIndex, t - phraseStartAt);
                    float y = yFor(targetMidi, top, bottom);
                    if (!started) {
                        targetPath.moveTo(x, y);
                        started = true;
                    } else {
                        targetPath.lineTo(x, y);
                    }
                }

                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(dp(2.2f));
                stroke.setStrokeCap(Paint.Cap.ROUND);
                stroke.setStrokeJoin(Paint.Join.ROUND);
                stroke.setColor(Color.rgb(105,105,116));
                canvas.drawPath(targetPath, stroke);

                Path sungPath = new Path();
                started = false;
                for (SungFrame frame : songTrail) {
                    float x = xForTime(frame.timeMs, now, left, playhead, right);
                    if (x < left || x > right) continue;
                    float y = yFor(frame.midi, top, bottom);
                    if (!started) {
                        sungPath.moveTo(x, y);
                        started = true;
                    } else {
                        sungPath.lineTo(x, y);
                    }
                }

                stroke.setStrokeWidth(dp(2.8f));
                stroke.setColor(Color.rgb(239,239,242));
                canvas.drawPath(sungPath, stroke);

                if (hz > 0 && now >= phraseStartAt && now < phraseEndAt && phraseResultUntil == 0) {
                    target = song.targetMidi(base, phraseIndex, now - phraseStartAt);
                    float voiceY = yFor(midi, top, bottom);
                    float targetY = yFor(target, top, bottom);

                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(1.3f));
                    paint.setColor(Color.rgb(178,178,187));
                    canvas.drawCircle(playhead, targetY, dp(15), paint);

                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(Color.rgb(248,248,250));
                    canvas.drawCircle(playhead, voiceY, dp(5.5f), paint);
                }

                if (now < phraseStartAt) {
                    long beat = song.beatMs(phraseIndex);
                    int count = (int)Math.ceil((phraseStartAt - now) / (double)beat);
                    drawText(canvas, String.valueOf(Math.max(1, count)), playhead, (top+bottom)/2,
                            dp(54), Color.rgb(228,228,232), Paint.Align.CENTER);
                }

                if (phraseResultUntil > now && !phraseMoment.isEmpty()) {
                    drawText(canvas, phraseMoment, playhead, (top+bottom)/2,
                            dp(25), Color.rgb(235,235,239), Paint.Align.CENTER);
                    int lastScore = phraseScores.isEmpty() ? 0 : phraseScores.get(phraseScores.size()-1);
                    drawText(canvas, "PHRASE " + lastScore, playhead, (top+bottom)/2 + dp(32),
                            dp(10), Color.rgb(112,112,123), Paint.Align.CENTER);
                }
            }
        }

        void drawCenterWorld(Canvas canvas, float left, float right, float top, float bottom) {
            float centerY = yFor(base, top, bottom);
            stroke.setStrokeWidth(dp(1));
            stroke.setColor(Color.rgb(52,52,60));
            canvas.drawLine(left, centerY, right, centerY, stroke);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            paint.setColor(Color.rgb(186,186,195));
            canvas.drawCircle((left+right)/2, centerY, dp(30), paint);

            if (hz > 0) {
                float voiceY = yFor(midi, top, bottom);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.rgb(243,243,246));
                canvas.drawCircle((left+right)/2, voiceY, dp(7), paint);
            }

            drawText(canvas, note(base), (left+right)/2, centerY - dp(48), dp(11),
                    Color.rgb(122,122,133), Paint.Align.CENTER);
        }

        void drawMetric(Canvas canvas, String label, int value, float left, float right, float y) {
            drawText(canvas, label, left, y, dp(10), Color.rgb(112,112,123), Paint.Align.LEFT);
            drawText(canvas, String.format(Locale.US, "%02d", value), right, y, dp(10),
                    Color.rgb(202,202,209), Paint.Align.RIGHT);

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

            long now = System.currentTimeMillis();
            updateTimed(now);

            float w = getWidth();
            float h = getHeight();
            float left = dp(26);
            float right = w - dp(26);
            float worldTop = dp(92);
            float worldBottom = h - dp(245);

            drawText(canvas, "RESONANCE", left, dp(42), dp(13),
                    Color.rgb(225,225,229), Paint.Align.LEFT);
            drawText(canvas, "0.3 · LEVEL " + String.format(Locale.US, "%02d", level),
                    right, dp(42), dp(10), Color.rgb(115,115,126), Paint.Align.RIGHT);

            if (stage == INTRO) {
                drawText(canvas, "SONG PATH", w/2, h*0.35f, dp(44),
                        Color.rgb(239,239,242), Paint.Align.CENTER);
                drawText(canvas, "make two lines become one", w/2, h*0.35f + dp(39), dp(11),
                        Color.rgb(104,104,115), Paint.Align.CENTER);

                int best = prefs.getInt("best", 0);
                if (best > 0) {
                    drawText(canvas, "BEST " + best + " · " + "LEVEL " + level,
                            w/2, h*0.35f + dp(75), dp(10),
                            Color.rgb(82,82,92), Paint.Align.CENTER);
                }
            } else if (stage == CALIBRATE) {
                drawText(canvas, hz > 0 ? note(midi) : "—", w/2, h*0.35f, dp(58),
                        Color.rgb(239,239,242), Paint.Align.CENTER);
                drawText(canvas, "FIND YOUR CENTER", w/2, h*0.35f + dp(43), dp(10),
                        Color.rgb(105,105,116), Paint.Align.CENTER);
            } else if (stage == CENTER) {
                drawCenterWorld(canvas, left, right, worldTop, worldBottom);
                drawText(canvas, "WARM UP · CENTER", left, worldBottom + dp(39), dp(10),
                        Color.rgb(90,90,101), Paint.Align.LEFT);
                drawWrapped(canvas, message, left, right, worldBottom + dp(65), dp(13),
                        Color.rgb(203,203,210));
            } else if (stage == SONG) {
                drawSongWorld(canvas, left, right, worldTop, worldBottom, now);
                drawText(canvas, message, left, worldBottom + dp(38), dp(10),
                        Color.rgb(111,111,122), Paint.Align.LEFT);

                if (phraseResultUntil == 0 && now >= phraseStartAt && now < phraseEndAt && hz > 0) {
                    target = song.targetMidi(base, phraseIndex, now - phraseStartAt);
                    double cents = (midi - target) * 100.0;
                    String live = Math.abs(cents) <= songToleranceCents()
                            ? "SYNC"
                            : (cents < 0 ? "LOW · RISE" : "HIGH · LOWER");
                    drawText(canvas, live, left, worldBottom + dp(67), dp(11),
                            Color.rgb(190,190,198), Paint.Align.LEFT);
                    drawText(canvas, String.format(Locale.US, "%+.0f cents", cents),
                            right, worldBottom + dp(67), dp(10),
                            Color.rgb(106,106,117), Paint.Align.RIGHT);
                }

                drawText(canvas, coach, left, worldBottom + dp(100), dp(10),
                        Color.rgb(82,82,93), Paint.Align.LEFT);
            } else if (stage == VIBRATO) {
                drawCenterWorld(canvas, left, right, worldTop, worldBottom);
                drawText(canvas, "VIBRATO SCAN · OPTIONAL", left, worldBottom + dp(39), dp(10),
                        Color.rgb(90,90,101), Paint.Align.LEFT);
                drawWrapped(canvas, message, left, right, worldBottom + dp(65), dp(13),
                        Color.rgb(203,203,210));
            } else if (stage == RESULT) {
                drawText(canvas, String.format(Locale.US, "%02d", score), w/2, dp(170), dp(70),
                        Color.rgb(240,240,243), Paint.Align.CENTER);
                drawText(canvas, "SESSION SCORE", w/2, dp(204), dp(10),
                        Color.rgb(105,105,116), Paint.Align.CENTER);

                drawMetric(canvas, "SONG PATH", songScore, left, right, dp(255));
                drawMetric(canvas, "PITCH", pitchScore, left, right, dp(299));
                drawMetric(canvas, "STABILITY", stabilityScore, left, right, dp(343));
                drawMetric(canvas, "SYNC", syncScore, left, right, dp(387));

                String vibratoText = vibratoDetected
                        ? String.format(Locale.US, "VIBRATO %.1f Hz · %.0f cents", vibratoRate, vibratoExtent)
                        : "VIBRATO · not yet consistent";
                drawText(canvas, vibratoText, left, dp(438), dp(10),
                        Color.rgb(106,106,117), Paint.Align.LEFT);

                drawText(canvas, "LOCAL COACH", left, dp(474), dp(9),
                        Color.rgb(81,81,92), Paint.Align.LEFT);
                drawWrapped(canvas, coach, left, right, dp(498), dp(13),
                        Color.rgb(203,203,210));
            }

            if (stage == RESULT) {
                drawText(canvas, message, left, h - dp(126), dp(11),
                        Color.rgb(126,126,137), Paint.Align.LEFT);
            } else if (stage == CALIBRATE || stage == INTRO) {
                drawText(canvas, message, left, h - dp(126), dp(11),
                        Color.rgb(168,168,177), Paint.Align.LEFT);
            }

            action.set(left, h - dp(86), right, h - dp(34));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.rgb(61,61,68));
            canvas.drawRoundRect(action, dp(26), dp(26), paint);

            String button = stage == INTRO ? "BEGIN"
                    : (stage == RESULT ? "PLAY AGAIN" : "LISTENING");
            drawText(canvas, button, action.centerX(), action.centerY() + dp(4), dp(11),
                    Color.rgb(224,224,229), Paint.Align.CENTER);

            if (stage == SONG && running) postInvalidateOnAnimation();
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

            if (line.length() > 0) {
                drawText(canvas, line.toString(), left, y, size, color, Paint.Align.LEFT);
            }
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

        final class SungFrame {
            final long timeMs;
            final double midi;

            SungFrame(long timeMs, double midi) {
                this.timeMs = timeMs;
                this.midi = midi;
            }
        }
    }
}
