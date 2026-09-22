package com.edwardresearchlabs.blue;

import java.util.Arrays;

final class PitchDetector {
    static final class Result {
        final double frequency;
        final double rms;

        Result(double frequency, double rms) {
            this.frequency = frequency;
            this.rms = rms;
        }
    }

    private final int sampleRate;
    private final int frameSize;
    private final double[] diff;
    private final double[] cmndf;
    private final double[] x;

    PitchDetector(int sampleRate, int frameSize) {
        this.sampleRate = sampleRate;
        this.frameSize = frameSize;
        this.diff = new double[frameSize / 2];
        this.cmndf = new double[frameSize / 2];
        this.x = new double[frameSize];
    }

    Result detect(short[] input, int valid) {
        if (valid < frameSize) return new Result(-1, 0);

        double mean = 0;
        for (int i = 0; i < frameSize; i++) mean += input[i];
        mean /= frameSize;

        double energy = 0;
        for (int i = 0; i < frameSize; i++) {
            double v = (input[i] - mean) / 32768.0;
            x[i] = v;
            energy += v * v;
        }

        double rms = Math.sqrt(energy / frameSize);

        // Keep a low floor here. BLUE performs adaptive room gating after calibration.
        if (rms < 0.004) return new Result(-1, rms);

        Arrays.fill(diff, 0);
        Arrays.fill(cmndf, 1);

        int minTau = Math.max(2, sampleRate / 1100);
        int maxTau = Math.min(diff.length - 1, sampleRate / 220);

        for (int tau = minTau; tau <= maxTau; tau++) {
            double sum = 0;
            int limit = frameSize - tau;
            for (int i = 0; i < limit; i++) {
                double d = x[i] - x[i + tau];
                sum += d * d;
            }
            diff[tau] = sum;
        }

        double running = 0;
        for (int tau = minTau; tau <= maxTau; tau++) {
            running += diff[tau];
            cmndf[tau] = running == 0 ? 1 : diff[tau] * (tau - minTau + 1) / running;
        }

        final double threshold = 0.14;
        int tauEstimate = -1;

        for (int tau = minTau + 1; tau < maxTau - 1; tau++) {
            if (cmndf[tau] < threshold) {
                while (tau + 1 < maxTau && cmndf[tau + 1] < cmndf[tau]) tau++;
                tauEstimate = tau;
                break;
            }
        }

        if (tauEstimate < 0) {
            double best = 1;
            for (int tau = minTau; tau <= maxTau; tau++) {
                if (cmndf[tau] < best) {
                    best = cmndf[tau];
                    tauEstimate = tau;
                }
            }
            if (best > 0.30) return new Result(-1, rms);
        }

        double betterTau = tauEstimate;
        if (tauEstimate > 1 && tauEstimate < cmndf.length - 1) {
            double s0 = cmndf[tauEstimate - 1];
            double s1 = cmndf[tauEstimate];
            double s2 = cmndf[tauEstimate + 1];
            double denom = 2.0 * (2.0 * s1 - s2 - s0);
            if (Math.abs(denom) > 1e-9) {
                betterTau += (s2 - s0) / denom;
            }
        }

        double frequency = sampleRate / betterTau;
        if (frequency < 220 || frequency > 1100) frequency = -1;

        return new Result(frequency, rms);
    }

    static double midiFromFrequency(double hz) {
        return 69.0 + 12.0 * (Math.log(hz / 440.0) / Math.log(2.0));
    }

    static double centsFromTarget(double hz, double targetHz) {
        return 1200.0 * (Math.log(hz / targetHz) / Math.log(2.0));
    }

    static String noteName(double hz) {
        if (hz <= 0) return "—";

        int midi = (int) Math.round(midiFromFrequency(hz));
        String[] names = {"C","C♯","D","D♯","E","F","F♯","G","G♯","A","A♯","B"};
        int pc = Math.floorMod(midi, 12);
        int octave = midi / 12 - 1;
        return names[pc] + octave;
    }
}
