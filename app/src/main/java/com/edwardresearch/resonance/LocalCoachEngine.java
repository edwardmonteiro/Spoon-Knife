package com.edwardresearch.resonance;

import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

final class LocalCoachEngine {
    static final String PITCH = "PITCH";
    static final String STABILITY = "STABILITY";
    static final String MEMORY = "MEMORY";
    static final String REPAIR = "REPAIR";
    static final String INTERVAL = "INTERVAL";

    static final class Skill {
        final String name;
        final double mastery;
        Skill(String name, double mastery) {
            this.name = name;
            this.mastery = mastery;
        }
    }

    static final class Plan {
        final String focus;
        final String secondary;
        final String rationale;
        final long warmupHoldMs;
        final int intervalSemitones;
        final int intervalTrials;
        final int phraseLevel;

        Plan(
                String focus,
                String secondary,
                String rationale,
                long warmupHoldMs,
                int intervalSemitones,
                int intervalTrials,
                int phraseLevel) {
            this.focus = focus;
            this.secondary = secondary;
            this.rationale = rationale;
            this.warmupHoldMs = warmupHoldMs;
            this.intervalSemitones = intervalSemitones;
            this.intervalTrials = intervalTrials;
            this.phraseLevel = phraseLevel;
        }
    }

    static Plan createPlan(SharedPreferences prefs, int level) {
        double pitch = mastery(prefs, "skill_pitch", prefs.getInt("last_pitch_score", 55));
        double stability = mastery(prefs, "skill_stability", 55);
        double memory = mastery(prefs, "skill_memory", prefs.getInt("last_memory_score", 55));
        double repair = mastery(prefs, "skill_repair", prefs.getInt("last_repair_score", 55));
        double interval = mastery(prefs, "skill_interval", prefs.getInt("last_interval_score", 50));

        Skill[] skills = new Skill[]{
                new Skill(PITCH, pitch),
                new Skill(STABILITY, stability),
                new Skill(MEMORY, memory),
                new Skill(REPAIR, repair),
                new Skill(INTERVAL, interval)
        };
        Arrays.sort(skills, Comparator.comparingDouble(s -> s.mastery));

        String focus = skills[0].name;
        String secondary = skills[1].name;

        long warmup = stability < 45 ? 2300 : stability < 65 ? 1800 : 1400;
        int intervalSemitones = interval < 45 ? 2 : interval < 65 ? 3 : interval < 80 ? 4 : 5;
        int trials = interval < 50 ? 5 : 4;

        int phraseLevel = level;
        if (memory < 45) phraseLevel = Math.max(1, level - 1);
        else if (memory > 82 && pitch > 75) phraseLevel = Math.min(5, level + 1);

        String rationale;
        switch (focus) {
            case STABILITY:
                rationale = "Sua maior oportunidade está em sustentar o centro da nota.";
                break;
            case MEMORY:
                rationale = "Hoje o foco é ouvir, guardar o contorno e reproduzir sem guia.";
                break;
            case REPAIR:
                rationale = "Você ganha mais quando isola pequenos trechos difíceis.";
                break;
            case INTERVAL:
                rationale = "Vamos fortalecer a distância entre notas antes das frases.";
                break;
            default:
                rationale = "Hoje vamos reduzir o erro fino de afinação.";
                break;
        }

        return new Plan(focus, secondary, rationale, warmup, intervalSemitones, trials, phraseLevel);
    }

    static void updateProfile(
            SharedPreferences prefs,
            int pitch,
            int stability,
            int memory,
            int repair,
            int interval) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putFloat("skill_pitch", ema(prefs.getFloat("skill_pitch", -1), pitch));
        editor.putFloat("skill_stability", ema(prefs.getFloat("skill_stability", -1), stability));
        editor.putFloat("skill_memory", ema(prefs.getFloat("skill_memory", -1), memory));
        editor.putFloat("skill_repair", ema(prefs.getFloat("skill_repair", -1), repair));
        editor.putFloat("skill_interval", ema(prefs.getFloat("skill_interval", -1), interval));
        editor.apply();
    }

    static String profileLine(SharedPreferences prefs) {
        return String.format(
                Locale.US,
                "P%02d · S%02d · M%02d · R%02d · I%02d",
                Math.round(mastery(prefs, "skill_pitch", prefs.getInt("last_pitch_score", 55))),
                Math.round(mastery(prefs, "skill_stability", 55)),
                Math.round(mastery(prefs, "skill_memory", prefs.getInt("last_memory_score", 55))),
                Math.round(mastery(prefs, "skill_repair", prefs.getInt("last_repair_score", 55))),
                Math.round(mastery(prefs, "skill_interval", prefs.getInt("last_interval_score", 50))));
    }

    private static float ema(float previous, int latest) {
        if (previous < 0) return latest;
        return (float)(previous * 0.68 + latest * 0.32);
    }

    private static double mastery(SharedPreferences prefs, String key, double fallback) {
        float value = prefs.getFloat(key, -1);
        return value < 0 ? fallback : value;
    }
}
