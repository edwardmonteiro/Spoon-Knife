package com.edwardresearch.resonance;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

final class LocalMelodyPlayer {
    private static final int SAMPLE_RATE = 22050;
    private final Object lock = new Object();
    private AudioTrack currentTrack;
    private Thread currentThread;
    private int generation = 0;

    void stop() {
        synchronized (lock) {
            generation++;
            if (currentTrack != null) {
                try { currentTrack.stop(); } catch (Exception ignored) {}
                try { currentTrack.flush(); } catch (Exception ignored) {}
                try { currentTrack.release(); } catch (Exception ignored) {}
                currentTrack = null;
            }
            if (currentThread != null) currentThread.interrupt();
            currentThread = null;
        }
    }

    void playPhrase(SongPathEngine song, double baseMidi, int phraseIndex) {
        playWindow(song, baseMidi, phraseIndex, 0, song.durationMs(phraseIndex));
    }

    void playSegment(
            SongPathEngine song,
            double baseMidi,
            int phraseIndex,
            int startStep,
            int endStep) {
        long startMs = song.stepStartMs(phraseIndex, startStep);
        long endMs = song.stepEndMs(phraseIndex, endStep);
        playWindow(song, baseMidi, phraseIndex, startMs, Math.max(1, endMs - startMs));
    }

    private void playWindow(
            SongPathEngine song,
            double baseMidi,
            int phraseIndex,
            long globalStartMs,
            long durationMs) {
        stop();

        final int token;
        synchronized (lock) {
            token = ++generation;
        }

        currentThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            AudioTrack track = null;
            try {
                int sampleCount = Math.max(1, (int)Math.ceil(durationMs * SAMPLE_RATE / 1000.0));
                short[] pcm = new short[sampleCount];
                double phase = 0;
                int fadeSamples = Math.max(1, SAMPLE_RATE / 80);

                for (int i = 0; i < sampleCount; i++) {
                    long elapsedMs = Math.round(i * 1000.0 / SAMPLE_RATE);
                    double midi = song.targetMidi(
                            baseMidi,
                            phraseIndex,
                            globalStartMs + elapsedMs);
                    double frequency = 440.0 * Math.pow(2.0, (midi - 69.0) / 12.0);
                    phase += 2.0 * Math.PI * frequency / SAMPLE_RATE;

                    double envelope = 1.0;
                    if (i < fadeSamples) envelope = i / (double)fadeSamples;
                    if (sampleCount - i < fadeSamples) {
                        envelope = Math.min(envelope, (sampleCount - i) / (double)fadeSamples);
                    }

                    double sample = Math.sin(phase) * 0.22
                            + Math.sin(phase * 2.0) * 0.055
                            + Math.sin(phase * 3.0) * 0.018;
                    pcm[i] = (short)(32767.0 * envelope * sample);
                }

                AudioAttributes attributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();

                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build();

                track = new AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(pcm.length * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();

                synchronized (lock) {
                    if (token != generation) {
                        track.release();
                        return;
                    }
                    currentTrack = track;
                }

                track.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
                track.setVolume(0.70f);
                track.play();

                try {
                    Thread.sleep(durationMs + 120);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            } catch (Exception ignored) {
            } finally {
                synchronized (lock) {
                    if (track != null && currentTrack == track) {
                        try { track.stop(); } catch (Exception ignored) {}
                        try { track.release(); } catch (Exception ignored) {}
                        currentTrack = null;
                    }
                    if (currentThread == Thread.currentThread()) currentThread = null;
                }
            }
        }, "ResonanceMelody");

        currentThread.start();
    }
}
