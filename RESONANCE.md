# Resonance — 0.4.0 Echo Recall

Local-first Android singing game focused on ear training, vocal memory and targeted correction.

## Echo Recall loop
1. Calibrate a comfortable center note.
2. LISTEN: the app synthesizes a short melody entirely on-device.
3. RECALL: the visual target disappears and the user sings the phrase from memory.
4. DIAGNOSE: the app compares the attempt against each phrase segment.
5. REPAIR: only the weakest local segment is replayed and practiced with the guide visible.
6. Repeat across three phrases, then update the local voice-learning profile.

## 0.4 additions
- Offline melody synthesis with Android AudioTrack.
- Hidden-target recall mode.
- Per-step error analysis.
- Automatic weakest-segment detection.
- Focused repair loop using the difficult step plus one adjacent step.
- Memory score.
- Repair score.
- Pitch and stability scores retained.
- Levels 1–5 still adapt phrase complexity and tempo.
- All data remains local.

## Privacy
- No INTERNET permission.
- Microphone audio is analyzed in memory and never uploaded.
- Raw recordings are not stored.
- Progress and metrics remain in Android SharedPreferences.

## AI strategy
The critical loop is still local DSP + adaptive logic.
OpenAI remains reserved for future opt-in generative coaching, imported-song decomposition and advanced explanations.
