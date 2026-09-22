package com.edwardresearch.resonance;

final class SongPathEngine {
    static final class Step {
        final int semitones;
        final double beats;

        Step(int semitones, double beats) {
            this.semitones = semitones;
            this.beats = beats;
        }
    }

    static final class Phrase {
        final String name;
        final int bpm;
        final Step[] steps;

        Phrase(String name, int bpm, Step... steps) {
            this.name = name;
            this.bpm = bpm;
            this.steps = steps;
        }
    }

    private final Phrase[] phrases;

    private SongPathEngine(Phrase[] phrases) {
        this.phrases = phrases;
    }

    static SongPathEngine create(int level, int variant) {
        int l = Math.max(1, Math.min(5, level));
        int v = Math.abs(variant) % 3;
        int bpm = 64 + l * 3;

        Phrase[] set;
        if (l == 1) {
            set = new Phrase[]{
                    phrase("FIRST LIGHT", bpm, new int[]{0, 2, 0, -1, 0}, new double[]{1.5,1.5,1.5,1.5,2}),
                    phrase("SMALL HILL", bpm, new int[]{0, 1, 2, 1, 0}, new double[]{1.5,1.5,1.5,1.5,2}),
                    phrase("HOME", bpm, new int[]{0, -1, 0, 2, 0}, new double[]{1.5,1.5,1.5,1.5,2})
            };
        } else if (l == 2) {
            set = new Phrase[]{
                    phrase("ARC", bpm, new int[]{0,2,4,2,0,-1,0}, new double[]{1,1,1.5,1,1,1,1.5}),
                    phrase("RIVER", bpm, new int[]{0,-2,0,2,4,2,0}, new double[]{1,1,1,1,1,1,2}),
                    phrase("RETURN", bpm, new int[]{0,2,0,-2,0,2,0}, new double[]{1,1,1,1,1,1,2})
            };
        } else if (l == 3) {
            set = new Phrase[]{
                    phrase("SKYLINE", bpm, new int[]{0,2,4,5,4,2,0,-2,0}, new double[]{1,1,1,1,1,1,1,1,2}),
                    phrase("STEPS", bpm, new int[]{0,3,2,5,3,2,0,-2,0}, new double[]{1,1,1,1,1,1,1,1,2}),
                    phrase("WAVE", bpm, new int[]{0,2,5,2,0,-2,-3,-2,0}, new double[]{1,1,1,1,1,1,1,1,2})
            };
        } else if (l == 4) {
            set = new Phrase[]{
                    phrase("LANTERN", bpm, new int[]{0,4,2,5,7,5,4,2,0}, new double[]{1,1,0.75,1.25,1,1,0.75,1.25,2}),
                    phrase("TURN", bpm, new int[]{0,-2,2,5,4,2,-1,-3,0}, new double[]{1,1,1,1,0.75,1.25,1,1,2}),
                    phrase("BRIDGE", bpm, new int[]{0,2,4,7,4,2,0,-3,0}, new double[]{1,0.75,1.25,1,1,0.75,1.25,1,2})
            };
        } else {
            set = new Phrase[]{
                    phrase("FLIGHT", bpm, new int[]{0,4,7,5,2,5,7,4,2,0,-3,0}, new double[]{0.75,0.75,1,0.75,0.75,1,0.75,0.75,1,0.75,0.75,2}),
                    phrase("ORBIT", bpm, new int[]{0,-3,0,4,7,5,2,-1,2,5,2,0}, new double[]{0.75,0.75,0.75,1,1,0.75,0.75,0.75,1,0.75,0.75,2}),
                    phrase("RESOLVE", bpm, new int[]{0,2,5,7,4,2,-1,-3,0,4,2,0}, new double[]{0.75,0.75,1,0.75,0.75,1,0.75,0.75,1,0.75,0.75,2})
            };
        }

        if (v == 1) {
            Phrase t = set[0]; set[0] = set[1]; set[1] = set[2]; set[2] = t;
        } else if (v == 2) {
            Phrase t = set[0]; set[0] = set[2]; set[2] = set[1]; set[1] = t;
        }

        return new SongPathEngine(set);
    }

    private static Phrase phrase(String name, int bpm, int[] semitones, double[] beats) {
        Step[] steps = new Step[semitones.length];
        for (int i = 0; i < semitones.length; i++) {
            steps[i] = new Step(semitones[i], beats[i]);
        }
        return new Phrase(name, bpm, steps);
    }

    int phraseCount() {
        return phrases.length;
    }

    Phrase phrase(int index) {
        return phrases[Math.max(0, Math.min(phrases.length - 1, index))];
    }

    int stepCount(int phraseIndex) {
        return phrase(phraseIndex).steps.length;
    }

    long beatMs(int phraseIndex) {
        return Math.round(60000.0 / phrase(phraseIndex).bpm);
    }

    long countInMs(int phraseIndex) {
        return beatMs(phraseIndex) * 2;
    }

    long durationMs(int phraseIndex) {
        return stepEndMs(phraseIndex, stepCount(phraseIndex) - 1);
    }

    long stepStartMs(int phraseIndex, int stepIndex) {
        Phrase phrase = phrase(phraseIndex);
        int safe = Math.max(0, Math.min(phrase.steps.length, stepIndex));
        double beats = 0;
        for (int i = 0; i < safe; i++) beats += phrase.steps[i].beats;
        return Math.round(beats * beatMs(phraseIndex));
    }

    long stepEndMs(int phraseIndex, int stepIndex) {
        Phrase phrase = phrase(phraseIndex);
        int safe = Math.max(0, Math.min(phrase.steps.length - 1, stepIndex));
        return stepStartMs(phraseIndex, safe)
                + Math.round(phrase.steps[safe].beats * beatMs(phraseIndex));
    }

    int stepIndexAt(int phraseIndex, long elapsedMs) {
        Phrase phrase = phrase(phraseIndex);
        if (elapsedMs <= 0) return 0;
        for (int i = 0; i < phrase.steps.length; i++) {
            if (elapsedMs < stepEndMs(phraseIndex, i)) return i;
        }
        return phrase.steps.length - 1;
    }

    long segmentDurationMs(int phraseIndex, int startStep, int endStep) {
        int start = Math.max(0, startStep);
        int end = Math.min(stepCount(phraseIndex) - 1, Math.max(start, endStep));
        return stepEndMs(phraseIndex, end) - stepStartMs(phraseIndex, start);
    }

    double targetMidi(double baseMidi, int phraseIndex, long elapsedMs) {
        return baseMidi + targetOffset(phraseIndex, elapsedMs);
    }

    double targetMidiForSegment(
            double baseMidi,
            int phraseIndex,
            int startStep,
            long segmentElapsedMs) {
        return targetMidi(
                baseMidi,
                phraseIndex,
                stepStartMs(phraseIndex, startStep) + Math.max(0, segmentElapsedMs));
    }

    double targetOffset(int phraseIndex, long elapsedMs) {
        Phrase phrase = phrase(phraseIndex);
        if (elapsedMs <= 0) return phrase.steps[0].semitones;

        double beat = elapsedMs / (double) beatMs(phraseIndex);
        double cursor = 0;

        for (int i = 0; i < phrase.steps.length; i++) {
            Step current = phrase.steps[i];
            double end = cursor + current.beats;

            if (beat < end || i == phrase.steps.length - 1) {
                if (i < phrase.steps.length - 1) {
                    double transition = Math.min(0.16, current.beats * 0.22);
                    double transitionStart = end - transition;
                    if (beat >= transitionStart) {
                        double p = (beat - transitionStart) / Math.max(0.001, transition);
                        p = Math.max(0, Math.min(1, p));
                        return current.semitones
                                + (phrase.steps[i + 1].semitones - current.semitones) * p;
                    }
                }
                return current.semitones;
            }
            cursor = end;
        }
        return phrase.steps[phrase.steps.length - 1].semitones;
    }
}
