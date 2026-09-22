package com.edwardresearchlabs.blue;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

final class BluesCallPlayer {
    private static final int SAMPLE_RATE = 44100;
    private volatile AudioTrack activeTrack;
    private volatile boolean cancelled;

    void stop() {
        cancelled = true;
        AudioTrack track = activeTrack;
        activeTrack = null;
        if (track != null) {
            try { track.stop(); } catch (Throwable ignored) {}
            try { track.flush(); } catch (Throwable ignored) {}
            try { track.release(); } catch (Throwable ignored) {}
        }
    }

    void play(double[] notesHz, int noteMs, int gapMs, Runnable whenDone) {
        stop();
        cancelled = false;

        new Thread(() -> {
            final int countInMs = 700;
            final int tailMs = 320;
            final int totalMs = countInMs + notesHz.length * (noteMs + gapMs) + tailMs;
            final int frames = (int) ((long) SAMPLE_RATE * totalMs / 1000L);
            final short[] pcm = new short[frames];

            // Soft 12/8-inspired pulse. It is deliberately sparse so the phone speaker
            // does not overpower the harmonica or feel like a metronome app.
            int beatMs = noteMs + gapMs;
            for (int beat = 0; beat < notesHz.length + 2; beat++) {
                int startMs = 180 + beat * beatMs;
                addKick(pcm, startMs, 95);
                addBass(pcm, startMs, 115, beat % 4 == 3 ? 130.81 : 98.00);
            }

            // The call itself. A soft two-harmonic voice is clearer than a pure sine
            // but still neutral enough not to impersonate a sampled harmonica.
            int cursorMs = countInMs;
            for (double hz : notesHz) {
                addNote(pcm, cursorMs, noteMs, hz);
                cursorMs += noteMs + gapMs;
            }

            AudioTrack track = null;
            try {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(pcm.length * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();

                activeTrack = track;
                track.setVolume(0.48f);
                track.write(pcm, 0, pcm.length);
                track.play();

                long end = System.currentTimeMillis() + totalMs + 80L;
                while (!cancelled && System.currentTimeMillis() < end) {
                    try { Thread.sleep(25); } catch (InterruptedException ignored) { break; }
                }
            } catch (Throwable ignored) {
            } finally {
                if (track != null) {
                    try { track.stop(); } catch (Throwable ignored) {}
                    try { track.release(); } catch (Throwable ignored) {}
                }
                if (activeTrack == track) activeTrack = null;
            }

            if (!cancelled && whenDone != null) whenDone.run();
        }, "blue-call-player").start();
    }

    private static void addNote(short[] pcm, int startMs, int durationMs, double hz) {
        int start = msToFrame(startMs);
        int count = msToFrame(durationMs);
        int end = Math.min(pcm.length, start + count);

        for (int i = start; i < end; i++) {
            double t = (i - start) / (double) SAMPLE_RATE;
            double pos = (i - start) / (double) Math.max(1, count);
            double attack = Math.min(1.0, pos / 0.08);
            double release = Math.min(1.0, (1.0 - pos) / 0.16);
            double env = Math.max(0, Math.min(attack, release));

            double fundamental = Math.sin(2.0 * Math.PI * hz * t);
            double second = 0.23 * Math.sin(2.0 * Math.PI * hz * 2.0 * t);
            double third = 0.08 * Math.sin(2.0 * Math.PI * hz * 3.0 * t);
            addSample(pcm, i, (fundamental + second + third) * env * 0.34);
        }
    }

    private static void addBass(short[] pcm, int startMs, int durationMs, double hz) {
        int start = msToFrame(startMs);
        int count = msToFrame(durationMs);
        int end = Math.min(pcm.length, start + count);

        for (int i = start; i < end; i++) {
            double t = (i - start) / (double) SAMPLE_RATE;
            double pos = (i - start) / (double) Math.max(1, count);
            double env = Math.exp(-4.5 * pos);
            addSample(pcm, i, Math.sin(2.0 * Math.PI * hz * t) * env * 0.10);
        }
    }

    private static void addKick(short[] pcm, int startMs, int durationMs) {
        int start = msToFrame(startMs);
        int count = msToFrame(durationMs);
        int end = Math.min(pcm.length, start + count);

        for (int i = start; i < end; i++) {
            double t = (i - start) / (double) SAMPLE_RATE;
            double pos = (i - start) / (double) Math.max(1, count);
            double hz = 90.0 - 40.0 * pos;
            double env = Math.exp(-7.0 * pos);
            addSample(pcm, i, Math.sin(2.0 * Math.PI * hz * t) * env * 0.12);
        }
    }

    private static int msToFrame(int ms) {
        return (int) ((long) SAMPLE_RATE * ms / 1000L);
    }

    private static void addSample(short[] pcm, int index, double sample) {
        int mixed = pcm[index] + (int) Math.round(sample * 32767.0);
        pcm[index] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mixed));
    }
}
