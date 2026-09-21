# Resonance — 0.2.0

Local-first Android singing game.

## What changed in 0.2
- Continuous pitch trail: your sung contour is drawn against the target contour.
- Adaptive levels 1–5 saved locally.
- Center/hold training with tighter tolerance as the user improves.
- Glide world: a continuous rising/falling target path.
- Interval portals that grow with level.
- Initial vibrato scan: rate, extent and regularity are estimated locally.
- On-device adaptive coach chooses the next training priority from session metrics.
- Pitch, stability and glide scores.
- Local voice range history.
- Custom Resonance launcher icon.
- Smaller 2048-sample audio frames for more responsive feedback.

## Privacy
- No INTERNET permission.
- Raw microphone audio is never stored or transmitted.
- Progress and voice metrics are stored in Android SharedPreferences on-device.
- No account or cloud dependency.

## AI strategy
0.2 deliberately keeps the critical loop deterministic and local: DSP + adaptive rules.
A future opt-in generative coach can use OpenAI from derived metrics, without requiring raw audio upload.
