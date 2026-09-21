package com.edwardresearch.resonance;

final class PitchDetector {
    static final class Result {
        final double frequency;
        final double confidence;
        final double rms;

        Result(double frequency, double confidence, double rms) {
            this.frequency = frequency;
            this.confidence = confidence;
            this.rms = rms;
        }
    }

    private final int sampleRate;
    private static final int DOWNSAMPLE = 2;
    private static final double MIN_FREQ = 65.0;
    private static final double MAX_FREQ = 900.0;
    private static final double YIN_THRESHOLD = 0.16;

    PitchDetector(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    Result detect(short[] pcm, int length) {
        if (length < 1024) return new Result(0, 0, 0);

        int n = length / DOWNSAMPLE;
        double[] x = new double[n];
        double sumSq = 0;
        double mean = 0;

        for (int i = 0; i < n; i++) {
            double value = pcm[i * DOWNSAMPLE] / 32768.0;
            x[i] = value;
            mean += value;
            sumSq += value * value;
        }

        mean /= n;
        double rms = Math.sqrt(sumSq / n);
        if (rms < 0.007) return new Result(0, 0, rms);
        for (int i = 0; i < n; i++) x[i] -= mean;

        double rate = sampleRate / (double)DOWNSAMPLE;
        int minTau = Math.max(2, (int)Math.floor(rate / MAX_FREQ));
        int maxTau = Math.min(n / 2, (int)Math.ceil(rate / MIN_FREQ));

        double[] difference = new double[maxTau + 1];
        double[] cmnd = new double[maxTau + 1];

        for (int tau = minTau; tau <= maxTau; tau++) {
            double sum = 0;
            for (int i = 0; i < n - tau; i++) {
                double delta = x[i] - x[i + tau];
                sum += delta * delta;
            }
            difference[tau] = sum;
        }

        double running = 0;
        double best = Double.MAX_VALUE;
        int bestTau = -1;

        for (int tau = 1; tau <= maxTau; tau++) {
            running += difference[tau];
            cmnd[tau] = running == 0 ? 1 : difference[tau] * tau / running;
            if (tau >= minTau && cmnd[tau] < best) {
                best = cmnd[tau];
                bestTau = tau;
            }
        }

        for (int tau = minTau; tau < maxTau; tau++) {
            if (cmnd[tau] < YIN_THRESHOLD) {
                while (tau + 1 <= maxTau && cmnd[tau + 1] < cmnd[tau]) tau++;
                bestTau = tau;
                best = cmnd[tau];
                break;
            }
        }

        if (bestTau < 0) return new Result(0, 0, rms);

        double refined = bestTau;
        if (bestTau > minTau && bestTau < maxTau) {
            double left = cmnd[bestTau - 1];
            double center = cmnd[bestTau];
            double right = cmnd[bestTau + 1];
            double denominator = 2 * (2 * center - right - left);
            if (Math.abs(denominator) > 1e-9) refined += (right - left) / denominator;
        }

        double frequency = rate / refined;
        double confidence = Math.max(0, Math.min(1, 1 - best));

        if (frequency < MIN_FREQ || frequency > MAX_FREQ || confidence < 0.52) {
            return new Result(0, confidence, rms);
        }
        return new Result(frequency, confidence, rms);
    }
}
