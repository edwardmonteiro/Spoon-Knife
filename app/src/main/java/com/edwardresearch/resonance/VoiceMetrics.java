package com.edwardresearch.resonance;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;

final class VoiceMetrics {
    static final class Vibrato {
        final double rateHz;
        final double extentCents;
        final double regularity;
        final boolean detected;

        Vibrato(double rateHz, double extentCents, double regularity, boolean detected) {
            this.rateHz = rateHz;
            this.extentCents = extentCents;
            this.regularity = regularity;
            this.detected = detected;
        }
    }

    private static final class Sample {
        final long timeMs;
        final double cents;
        Sample(long timeMs, double cents) {
            this.timeMs = timeMs;
            this.cents = cents;
        }
    }

    private final ArrayDeque<Sample> vibratoSamples = new ArrayDeque<>();

    void resetVibrato() {
        vibratoSamples.clear();
    }

    void addVibratoSample(long timeMs, double cents) {
        vibratoSamples.addLast(new Sample(timeMs, cents));
        while (!vibratoSamples.isEmpty() && timeMs - vibratoSamples.peekFirst().timeMs > 2600) {
            vibratoSamples.removeFirst();
        }
    }

    Vibrato analyzeVibrato() {
        if (vibratoSamples.size() < 18) return new Vibrato(0, 0, 0, false);

        ArrayList<Sample> samples = new ArrayList<>(vibratoSamples);
        long durationMs = samples.get(samples.size() - 1).timeMs - samples.get(0).timeMs;
        if (durationMs < 1200) return new Vibrato(0, 0, 0, false);

        ArrayList<Double> smoothed = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            double sum = 0;
            int count = 0;
            for (int j = Math.max(0, i - 1); j <= Math.min(samples.size() - 1, i + 1); j++) {
                sum += samples.get(j).cents;
                count++;
            }
            smoothed.add(sum / count);
        }

        double mean = 0;
        for (double v : smoothed) mean += v;
        mean /= smoothed.size();

        ArrayList<Double> centered = new ArrayList<>();
        for (double v : smoothed) centered.add(v - mean);

        int crossings = 0;
        int previousSign = 0;
        ArrayList<Long> crossingTimes = new ArrayList<>();
        for (int i = 0; i < centered.size(); i++) {
            double v = centered.get(i);
            int sign = v > 2.0 ? 1 : (v < -2.0 ? -1 : 0);
            if (sign != 0) {
                if (previousSign != 0 && sign != previousSign) {
                    crossings++;
                    crossingTimes.add(samples.get(i).timeMs);
                }
                previousSign = sign;
            }
        }

        double durationSec = durationMs / 1000.0;
        double rateHz = durationSec > 0 ? (crossings / 2.0) / durationSec : 0;

        ArrayList<Double> sorted = new ArrayList<>(centered);
        Collections.sort(sorted);
        int lo = Math.max(0, (int)Math.floor(sorted.size() * 0.10));
        int hi = Math.min(sorted.size() - 1, (int)Math.ceil(sorted.size() * 0.90));
        double extent = Math.max(0, (sorted.get(hi) - sorted.get(lo)) / 2.0);

        double regularity = 0;
        if (crossingTimes.size() >= 5) {
            ArrayList<Double> periods = new ArrayList<>();
            for (int i = 2; i < crossingTimes.size(); i++) {
                double fullCycle = (crossingTimes.get(i) - crossingTimes.get(i - 2)) / 1000.0;
                if (fullCycle > 0.05 && fullCycle < 0.50) periods.add(fullCycle);
            }
            if (periods.size() >= 2) {
                double m = 0;
                for (double v : periods) m += v;
                m /= periods.size();
                double variance = 0;
                for (double v : periods) {
                    double d = v - m;
                    variance += d * d;
                }
                variance /= periods.size();
                double cv = m > 0 ? Math.sqrt(variance) / m : 1;
                regularity = clamp(1.0 - cv, 0, 1);
            }
        }

        boolean detected = rateHz >= 4.0 && rateHz <= 8.5
                && extent >= 8.0 && extent <= 110.0
                && regularity >= 0.30;

        return new Vibrato(rateHz, extent, regularity, detected);
    }

    static double standardDeviation(ArrayList<Double> values) {
        if (values.size() < 2) return 60;
        double mean = 0;
        for (double v : values) mean += v;
        mean /= values.size();

        double sum = 0;
        for (double v : values) {
            double d = v - mean;
            sum += d * d;
        }
        return Math.sqrt(sum / (values.size() - 1));
    }

    static double meanAbsolute(ArrayList<Double> values, double fallback) {
        if (values.isEmpty()) return fallback;
        double sum = 0;
        for (double v : values) sum += Math.abs(v);
        return sum / values.size();
    }

    static double mean(ArrayList<Double> values, double fallback) {
        if (values.isEmpty()) return fallback;
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.size();
    }

    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
