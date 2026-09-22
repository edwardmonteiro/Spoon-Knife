# Resonance — 0.5.0 Adaptive Vocal Coach

Local-first Android singing tutor that builds each session from the user's on-device skill profile.

## Adaptive session
- Reads rolling local mastery for Pitch, Stability, Memory, Repair and Intervals.
- Chooses a primary and secondary training focus before the session begins.
- Changes warm-up hold duration based on stability.
- Adds interval training with an automatically selected semitone distance.
- Changes phrase difficulty independently from the visible level when memory/pitch performance suggests it.
- Keeps Echo Recall: listen → recall without target → diagnose weakest section → focused repair.
- Updates each skill using an exponential moving average after the session.

## Local skill profile
The app persists a five-dimension profile:
- P: pitch accuracy
- S: stability
- M: melodic memory
- R: repair response
- I: interval control

The profile is used to decide the next training session without any network dependency.

## Privacy
- No INTERNET permission.
- Raw microphone audio is never stored or uploaded.
- Progress, skill estimates and session plans remain on-device.

## AI strategy
0.5 treats adaptation as local intelligence: DSP + statistics + a deterministic curriculum planner.
OpenAI remains optional for a future generative coach that can explain patterns or decompose imported songs from derived metrics.
