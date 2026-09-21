# Resonance — 0.3.0 Song Path

Local-first Android singing game.

## 0.3 core experience
- Song Path: three short melodic phrases generated locally for each session.
- Every phrase is automatically transposed around the user's calibrated center note.
- A fixed playhead shows the present while the target melody scrolls from right to left.
- The user's sung contour is drawn as a second line over the target.
- Visual 2-beat count-in before each phrase.
- Phrase scoring combines pitch accuracy, voiced coverage and time spent in sync.
- PERFECT SYNC appears when the user stays aligned through a strong phrase.
- Levels 1–5 increase interval size, phrase density and tempo.
- Local session rotation changes the phrase set without network access.
- Result screen now shows Song Path, Pitch, Stability and Sync scores.
- Local coach chooses the next priority from singing metrics.
- Optional vibrato scan remains local and does not affect the main score.

## Privacy
- No INTERNET permission.
- Raw microphone audio is never stored or transmitted.
- Voice profile, level and session metrics stay on the Android device.

## AI strategy
The critical loop remains deterministic and local.
OpenAI remains reserved for a future opt-in generative coach, song decomposition and advanced explanations.
