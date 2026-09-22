package com.edwardresearch.resonance;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.media.*;
import android.os.*;
import android.view.*;
import java.util.*;

public final class MainActivity extends Activity {
    private GameView game;
    private static final int MIC = 42;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(9,9,10));
        getWindow().setNavigationBarColor(Color.rgb(9,9,10));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        game = new GameView();
        setContentView(game);
    }

    void begin() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            game.startSession();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == MIC && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            game.startSession();
        } else {
            game.message = "Microfone necessário. O áudio nunca sai do aparelho.";
            game.invalidate();
        }
    }

    @Override protected void onStop() {
        game.stopAll();
        super.onStop();
    }

    final class GameView extends View {
        static final int INTRO=0, CALIBRATE=1, CENTER=2, INTERVAL=3, LISTEN=4, RECALL=5, REPAIR=6, RESULT=7;

        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF action = new RectF();
        final SharedPreferences prefs = getSharedPreferences("resonance_local",0);
        final String[] noteNames={"C","C♯","D","D♯","E","F","F♯","G","G♯","A","A♯","B"};
        final LocalMelodyPlayer melodyPlayer = new LocalMelodyPlayer();
        LocalCoachEngine.Plan plan;

        final ArrayDeque<Double> hzMedian = new ArrayDeque<>();
        final ArrayDeque<SungFrame> trail = new ArrayDeque<>();
        final ArrayList<Double> calibration = new ArrayList<>();
        final ArrayList<Double> centerErrors = new ArrayList<>();
        final ArrayList<Double> recallErrors = new ArrayList<>();
        final ArrayList<Double> repairErrors = new ArrayList<>();
        final ArrayList<Integer> phraseScores = new ArrayList<>();
        final ArrayList<Integer> repairScores = new ArrayList<>();
        final ArrayList<Double> intervalErrors = new ArrayList<>();
        ArrayList<Double>[] stepErrors;

        volatile boolean running=false;
        Thread audioThread;

        int stage=INTRO;
        int level, nextLevel, sessionNumber;
        int phraseIndex;
        int intervalTrial, intervalScore;
        int score, memoryScore, pitchScore, repairScore, stabilityScore;
        int weakestStep, repairStartStep, repairEndStep;
        int repairPhase; // 0 listen, 1 count-in, 2 sing, 3 feedback

        double hz, midi=60, rawMidi=60, base=60, target=60, confidence;
        double centerGoodMs;
        long voicedAt, lastPitchAt, lastAcceptedAt, stageStartedAt;
        long recallStartAt, recallEndAt, phaseUntil;
        long voicedMs;
        long intervalGoodMs;

        String message="Sua voz fica no aparelho.";
        String coach="Tudo funciona offline.";
        String moment="";

        SongPathEngine song;

        GameView() {
            super(MainActivity.this);
            setBackgroundColor(Color.rgb(9,9,10));
            setClickable(true);
            stroke.setStyle(Paint.Style.STROKE);
            level = clampLevel(prefs.getInt("level",1));
        }

        void startSession() {
            stopAll();

            level = clampLevel(prefs.getInt("level",1));
            nextLevel = level;
            plan = LocalCoachEngine.createPlan(prefs, level);
            sessionNumber = prefs.getInt("session_count",0);
            phraseIndex = 0;
            score=memoryScore=pitchScore=repairScore=stabilityScore=0;

            hz=0; midi=rawMidi=60; base=target=60; confidence=0; centerGoodMs=0;
            voicedAt=lastPitchAt=lastAcceptedAt=0;
            recallStartAt=recallEndAt=phaseUntil=0;
            voicedMs=0;
            intervalGoodMs=0;

            calibration.clear();
            centerErrors.clear();
            recallErrors.clear();
            repairErrors.clear();
            phraseScores.clear();
            repairScores.clear();
            intervalErrors.clear();
            hzMedian.clear();
            trail.clear();

            message="Cante uma nota confortável. Sem força.";
            coach=plan.rationale
            moment="";
            stage=CALIBRATE;
            stageStartedAt=System.currentTimeMillis();

            running=true;
            audioThread=new Thread(this::audioLoop,"ResonanceAudio");
            audioThread.start();
            invalidate();
        }

        void stopAll() {
            running=false;
            melodyPlayer.stop();
            if(audioThread!=null) audioThread.interrupt();
            audioThread=null;
        }

        void audioLoop() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            final int sr=44100, samples=2048;
            int min=AudioRecord.getMinBufferSize(sr,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord rec=null;
            try {
                rec=new AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sr,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(min,samples*6));
                if(rec.getState()!=AudioRecord.STATE_INITIALIZED) return;

                PitchDetector detector=new PitchDetector(sr);
                short[] buffer=new short[samples];
                rec.startRecording();

                while(running) {
                    int n=rec.read(buffer,0,buffer.length,AudioRecord.READ_BLOCKING);
                    if(n>0) {
                        PitchDetector.Result result=detector.detect(buffer,n);
                        post(()->accept(result));
                    }
                }
            } catch(Exception e) {
                post(()->{ message="Não consegui acessar o microfone."; invalidate(); });
            } finally {
                if(rec!=null) {
                    try { rec.stop(); } catch(Exception ignored) {}
                    rec.release();
                }
            }
        }

        void accept(PitchDetector.Result result) {
            long now=System.currentTimeMillis();
            updateTimed(now);

            if(stage==INTRO || stage==RESULT || stage==LISTEN || (stage==REPAIR && repairPhase!=2)) return;

            if(result.frequency<=0) {
                if(now-lastPitchAt>260) {
                    hz=0;
                    if(stage==CALIBRATE) voicedAt=0;
                }
                invalidate();
                return;
            }

            lastPitchAt=now;
            confidence=result.confidence;
            rawMidi=frequencyToMidi(result.frequency);

            hzMedian.addLast(result.frequency);
            while(hzMedian.size()>3) hzMedian.removeFirst();
            ArrayList<Double> sortedHz=new ArrayList<>(hzMedian);
            Collections.sort(sortedHz);
            hz=sortedHz.get(sortedHz.size()/2);
            midi=frequencyToMidi(hz);

            long frameMs=lastAcceptedAt==0?46:Math.max(20,Math.min(100,now-lastAcceptedAt));
            lastAcceptedAt=now;

            if(stage==CALIBRATE) handleCalibration(now);
            else if(stage==CENTER) handleCenter(frameMs,now);
            else if(stage==INTERVAL) handleInterval(frameMs,now);
            else if(stage==RECALL) handleRecall(frameMs,now);
            else if(stage==REPAIR && repairPhase==2) handleRepair(frameMs,now);

            invalidate();
        }

        void handleCalibration(long now) {
            if(confidence<0.68) return;
            if(voicedAt==0) voicedAt=now;
            calibration.add(rawMidi);

            if(now-voicedAt>=2200 && calibration.size()>=22) {
                ArrayList<Double> values=new ArrayList<>(calibration);
                Collections.sort(values);
                base=Math.rint(values.get(values.size()/2));
                target=base;
                stage=CENTER;
                stageStartedAt=now;
                centerGoodMs=0;
                message="Segure "+note(base)+" no centro.";
                coach="Essa será a referência das frases.";
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
        }

        void handleCenter(long frameMs,long now) {
            double cents=(midi-base)*100.0;
            centerErrors.add(cents);
            if(Math.abs(cents)<=centerTolerance() && confidence>=0.62) centerGoodMs+=frameMs;
            else centerGoodMs=Math.max(0,centerGoodMs-frameMs*0.7);

            if(centerGoodMs>=plan.warmupHoldMs) {
                beginInterval(now);
            }
        }

        void beginInterval(long now) {
            stage=INTERVAL;
            stageStartedAt=now;
            intervalTrial=0;
            intervalGoodMs=0;
            intervalErrors.clear();
            message="INTERVAL · encontre a distância";
            coach="Foco local: "+plan.focus+" · salto de "+plan.intervalSemitones+" semitons.";
            target=base+plan.intervalSemitones;
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }

        void handleInterval(long frameMs,long now) {
            double cents=(midi-target)*100.0;
            intervalErrors.add(cents);
            if(Math.abs(cents)<=Math.max(35,centerTolerance()+8) && confidence>=0.60) intervalGoodMs+=frameMs;
            else intervalGoodMs=Math.max(0,intervalGoodMs-frameMs*0.6);

            if(intervalGoodMs>=650) {
                intervalTrial++;
                intervalGoodMs=0;
                if(intervalTrial>=plan.intervalTrials) {
                    intervalScore=(int)Math.round(VoiceMetrics.clamp(
                            100-VoiceMetrics.meanAbsolute(intervalErrors,120)*0.80,0,100));
                    song=SongPathEngine.create(plan.phraseLevel,sessionNumber);
                    phraseIndex=0;
                    beginListen(now);
                } else {
                    target=(intervalTrial%2==0)
                            ?base+plan.intervalSemitones
                            :base-plan.intervalSemitones;
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                }
            }
        }

        void beginListen(long now) {
            stage=LISTEN;
            stageStartedAt=now;
            trail.clear();
            moment="";
            SongPathEngine.Phrase phrase=song.phrase(phraseIndex);
            message="LISTEN · "+phrase.name;
            coach="Só ouça. Memorize a forma da frase.";
            melodyPlayer.playPhrase(song,base,phraseIndex);
            phaseUntil=now+song.durationMs(phraseIndex)+650;
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            invalidate();
        }

        void beginRecall(long now) {
            stage=RECALL;
            stageStartedAt=now;
            long countIn=song.countInMs(phraseIndex);
            recallStartAt=now+countIn;
            recallEndAt=recallStartAt+song.durationMs(phraseIndex);
            phaseUntil=0;
            voicedMs=0;
            recallErrors.clear();
            trail.clear();
            stepErrors=new ArrayList[song.stepCount(phraseIndex)];
            for(int i=0;i<stepErrors.length;i++) stepErrors[i]=new ArrayList<>();
            message="RECALL · cante de memória";
            coach="A trilha está escondida. Cante em “ah”.";
            moment="";
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }

        void handleRecall(long frameMs,long now) {
            if(now<recallStartAt || now>=recallEndAt) return;

            long elapsed=now-recallStartAt;
            target=song.targetMidi(base,phraseIndex,elapsed);
            double cents=(midi-target)*100.0;
            recallErrors.add(cents);
            int step=song.stepIndexAt(phraseIndex,elapsed);
            stepErrors[step].add(cents);
            voicedMs+=frameMs;

            trail.addLast(new SungFrame(now,midi));
            while(!trail.isEmpty() && now-trail.peekFirst().timeMs>6000) trail.removeFirst();
        }

        void finishRecall(long now) {
            double meanAbs=VoiceMetrics.meanAbsolute(recallErrors,140);
            double coverage=VoiceMetrics.clamp(100.0*voicedMs/Math.max(1,song.durationMs(phraseIndex)),0,100);
            int recallScore=(int)Math.round(
                    VoiceMetrics.clamp(100-meanAbs*0.80,0,100)*0.78 + coverage*0.22);
            phraseScores.add(recallScore);

            weakestStep=0;
            double worst=-1;
            for(int i=0;i<stepErrors.length;i++) {
                double e=VoiceMetrics.meanAbsolute(stepErrors[i],180);
                if(e>worst) { worst=e; weakestStep=i; }
            }

            repairStartStep=Math.max(0,weakestStep-1);
            repairEndStep=Math.min(song.stepCount(phraseIndex)-1,weakestStep+1);

            moment="MEMORY "+String.format(Locale.US,"%02d",recallScore);
            message="Pior trecho identificado";
            coach="Agora vamos corrigir somente esse pedaço.";
            phaseUntil=now+1200;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }

        void beginRepair(long now) {
            stage=REPAIR;
            repairPhase=0;
            repairErrors.clear();
            trail.clear();
            moment="REPAIR";
            message="Ouça apenas o trecho difícil.";
            coach="Depois repita com a guia visível.";

            melodyPlayer.playSegment(song,base,phraseIndex,repairStartStep,repairEndStep);
            phaseUntil=now+song.segmentDurationMs(phraseIndex,repairStartStep,repairEndStep)+550;
        }

        void beginRepairCount(long now) {
            repairPhase=1;
            long count=Math.max(1000,song.beatMs(phraseIndex)*2);
            phaseUntil=now+count;
            message="Prepare-se";
            coach="Agora cante o mesmo trecho.";
        }

        void beginRepairSing(long now) {
            repairPhase=2;
            stageStartedAt=now;
            phaseUntil=now+song.segmentDurationMs(phraseIndex,repairStartStep,repairEndStep);
            repairErrors.clear();
            trail.clear();
            message="REPAIR · siga a linha";
            coach="Só o trecho que mais precisa.";
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }

        void handleRepair(long frameMs,long now) {
            long elapsed=now-stageStartedAt;
            target=song.targetMidiForSegment(base,phraseIndex,repairStartStep,elapsed);
            double cents=(midi-target)*100.0;
            repairErrors.add(cents);
            trail.addLast(new SungFrame(now,midi));
            while(!trail.isEmpty() && now-trail.peekFirst().timeMs>4500) trail.removeFirst();
        }

        void finishRepair(long now) {
            repairPhase=3;
            double meanAbs=VoiceMetrics.meanAbsolute(repairErrors,150);
            int score=(int)Math.round(VoiceMetrics.clamp(100-meanAbs*0.86,0,100));
            repairScores.add(score);
            moment=score>=80?"REPAIRED":"REPAIR "+String.format(Locale.US,"%02d",score);
            message="Trecho corrigido";
            coach=score>=80?"Boa correção. Seguimos para a próxima frase.":"A correção já ficou registrada no seu perfil local.";
            phaseUntil=now+1150;
            performHapticFeedback(score>=80?HapticFeedbackConstants.LONG_PRESS:HapticFeedbackConstants.KEYBOARD_TAP);
        }

        void advancePhrase(long now) {
            phraseIndex++;
            if(phraseIndex>=song.phraseCount()) finishSession();
            else beginListen(now);
        }

        void updateTimed(long now) {
            if(stage==LISTEN && phaseUntil>0 && now>=phaseUntil) {
                beginRecall(now);
            } else if(stage==RECALL && recallEndAt>0 && now>=recallEndAt) {
                recallEndAt=0;
                finishRecall(now);
            } else if(stage==RECALL && phaseUntil>0 && now>=phaseUntil) {
                phaseUntil=0;
                beginRepair(now);
            } else if(stage==REPAIR) {
                if(repairPhase==0 && phaseUntil>0 && now>=phaseUntil) beginRepairCount(now);
                else if(repairPhase==1 && phaseUntil>0 && now>=phaseUntil) beginRepairSing(now);
                else if(repairPhase==2 && phaseUntil>0 && now>=phaseUntil) finishRepair(now);
                else if(repairPhase==3 && phaseUntil>0 && now>=phaseUntil) advancePhrase(now);
            }
        }

        void finishSession() {
            stage=RESULT;
            melodyPlayer.stop();
            running=false;
            if(audioThread!=null) audioThread.interrupt();
            audioThread=null;

            memoryScore=averageInt(phraseScores);
            repairScore=averageInt(repairScores);
            pitchScore=(int)Math.round(VoiceMetrics.clamp(
                    100-VoiceMetrics.meanAbsolute(recallErrors,100)*0.82,0,100));
            stabilityScore=(int)Math.round(VoiceMetrics.clamp(
                    100-Math.max(0,VoiceMetrics.standardDeviation(centerErrors)-7)*1.5,0,100));

            score=(int)Math.round(memoryScore*0.38 + repairScore*0.20 + pitchScore*0.17 + stabilityScore*0.10 + intervalScore*0.15);

            nextLevel=level;
            if(score>=78 && memoryScore>=70 && repairScore>=68 && level<5) nextLevel=level+1;

            if(memoryScore<55) coach="Seu ouvido ainda perde a forma da frase. Ouça menos notas e memorize o contorno.";
            else if(repairScore-memoryScore>=15) coach="Você melhora muito quando pratica o trecho isolado. Essa é sua alavanca principal.";
            else if(pitchScore<60) coach="Você lembra a melodia, mas precisa centralizar melhor cada nota.";
            else if(stabilityScore<55) coach="Boa memória musical. Agora estabilize as notas sustentadas.";
            else coach="Memória e afinação estão convergindo. Próxima sessão aumenta a dificuldade.";

            message=nextLevel>level
                    ?"LEVEL "+String.format(Locale.US,"%02d",nextLevel)+" UNLOCKED"
                    :"Echo Recall concluído";

            int best=Math.max(score,prefs.getInt("best",0));
            prefs.edit()
                    .putInt("best",best)
                    .putInt("level",nextLevel)
                    .putInt("session_count",sessionNumber+1)
                    .putInt("last_memory_score",memoryScore)
                    .putInt("last_repair_score",repairScore)
                    .putInt("last_pitch_score",pitchScore)
                    .putInt("last_interval_score",intervalScore)
                    .apply();

            LocalCoachEngine.updateProfile(prefs,pitchScore,stabilityScore,memoryScore,repairScore,intervalScore);

            invalidate();
        }

        int averageInt(ArrayList<Integer> values) {
            if(values.isEmpty()) return 0;
            int sum=0;
            for(int v:values) sum+=v;
            return Math.round(sum/(float)values.size());
        }

        double frequencyToMidi(double frequency) {
            return 69.0+12.0*(Math.log(frequency/440.0)/Math.log(2.0));
        }

        int clampLevel(int value) {
            return Math.max(1,Math.min(5,value));
        }

        double centerTolerance() {
            return Math.max(28,50-(level-1)*5);
        }

        String note(double midiValue) {
            int number=(int)Math.round(midiValue);
            int pc=((number%12)+12)%12;
            int octave=number/12-1;
            return noteNames[pc]+octave;
        }

        float yFor(double midiValue,float top,float bottom) {
            double rel=VoiceMetrics.clamp((midiValue-base)/8.0,-1,1);
            return (float)((top+bottom)/2.0-rel*(bottom-top)*0.46);
        }

        void text(Canvas canvas,String text,float x,float y,float size,int color,Paint.Align align) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setTextAlign(align);
            paint.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
            canvas.drawText(text,x,y,paint);
        }

        void drawGrid(Canvas c,float left,float right,float top,float bottom) {
            for(int o=-7;o<=7;o++) {
                float y=yFor(base+o,top,bottom);
                stroke.setStrokeWidth(dp(o==0?1.0f:0.55f));
                stroke.setColor(o==0?Color.rgb(50,50,58):Color.rgb(27,27,32));
                c.drawLine(left,y,right,y,stroke);
            }
        }

        void drawCenter(Canvas c,float left,float right,float top,float bottom) {
            drawGrid(c,left,right,top,bottom);
            float cy=yFor(base,top,bottom);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            paint.setColor(Color.rgb(185,185,194));
            c.drawCircle((left+right)/2,cy,dp(30),paint);
            if(hz>0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.rgb(244,244,247));
                c.drawCircle((left+right)/2,yFor(midi,top,bottom),dp(7),paint);
            }
        }

        void drawPhraseShape(Canvas c,float left,float right,float top,float bottom,boolean reveal) {
            drawGrid(c,left,right,top,bottom);
            if(song==null || phraseIndex>=song.phraseCount()) return;

            Path pth=new Path();
            int points=120;
            long duration=song.durationMs(phraseIndex);
            for(int i=0;i<points;i++) {
                long t=Math.round(duration*i/(double)(points-1));
                float x=left+(right-left)*i/(float)(points-1);
                float y=yFor(song.targetMidi(base,phraseIndex,t),top,bottom);
                if(i==0) pth.moveTo(x,y); else pth.lineTo(x,y);
            }

            if(reveal) {
                stroke.setStrokeWidth(dp(2.1f));
                stroke.setStrokeCap(Paint.Cap.ROUND);
                stroke.setStrokeJoin(Paint.Join.ROUND);
                stroke.setColor(Color.rgb(106,106,117));
                c.drawPath(pth,stroke);
            }

            if(stage==RECALL || (stage==REPAIR && repairPhase==2)) {
                Path sung=new Path();
                boolean started=false;
                long start=stage==RECALL?recallStartAt:stageStartedAt;
                long total=stage==RECALL?song.durationMs(phraseIndex):
                        song.segmentDurationMs(phraseIndex,repairStartStep,repairEndStep);
                long now=System.currentTimeMillis();

                for(SungFrame f:trail) {
                    if(f.timeMs<start) continue;
                    float x=left+(right-left)*(float)VoiceMetrics.clamp(
                            (f.timeMs-start)/(double)Math.max(1,total),0,1);
                    float y=yFor(f.midi,top,bottom);
                    if(!started) { sung.moveTo(x,y); started=true; }
                    else sung.lineTo(x,y);
                }

                stroke.setStrokeWidth(dp(2.7f));
                stroke.setColor(Color.rgb(240,240,243));
                c.drawPath(sung,stroke);

                if(hz>0 && now>=start && now<=start+total) {
                    float progress=(float)VoiceMetrics.clamp((now-start)/(double)Math.max(1,total),0,1);
                    float x=left+(right-left)*progress;
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(Color.rgb(248,248,250));
                    c.drawCircle(x,yFor(midi,top,bottom),dp(5.5f),paint);
                }
            }
        }

        void drawRepairSegment(Canvas c,float left,float right,float top,float bottom) {
            drawGrid(c,left,right,top,bottom);
            if(song==null) return;
            long duration=song.segmentDurationMs(phraseIndex,repairStartStep,repairEndStep);
            Path targetPath=new Path();
            int points=80;

            for(int i=0;i<points;i++) {
                long t=Math.round(duration*i/(double)(points-1));
                float x=left+(right-left)*i/(float)(points-1);
                float y=yFor(song.targetMidiForSegment(base,phraseIndex,repairStartStep,t),top,bottom);
                if(i==0) targetPath.moveTo(x,y); else targetPath.lineTo(x,y);
            }

            stroke.setStrokeWidth(dp(2.2f));
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setColor(Color.rgb(110,110,121));
            c.drawPath(targetPath,stroke);

            if(repairPhase==2) drawPhraseShape(c,left,right,top,bottom,false);
        }

        void drawMetric(Canvas c,String label,int value,float left,float right,float y) {
            text(c,label,left,y,dp(10),Color.rgb(112,112,123),Paint.Align.LEFT);
            text(c,String.format(Locale.US,"%02d",value),right,y,dp(10),Color.rgb(202,202,209),Paint.Align.RIGHT);
            float by=y+dp(8);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(31,31,36));
            c.drawRoundRect(left,by,right,by+dp(3),dp(2),dp(2),paint);
            paint.setColor(Color.rgb(205,205,213));
            c.drawRoundRect(left,by,left+(right-left)*value/100f,by+dp(3),dp(2),dp(2),paint);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            long now=System.currentTimeMillis();
            updateTimed(now);

            float w=getWidth(),h=getHeight();
            float left=dp(26),right=w-dp(26),top=dp(92),bottom=h-dp(245);

            text(c,"RESONANCE",left,dp(42),dp(13),Color.rgb(225,225,229),Paint.Align.LEFT);
            text(c,"0.5 · LEVEL "+String.format(Locale.US,"%02d",level),right,dp(42),dp(10),Color.rgb(115,115,126),Paint.Align.RIGHT);

            if(stage==INTRO) {
                text(c,"ADAPTIVE COACH",w/2,h*0.34f,dp(40),Color.rgb(239,239,242),Paint.Align.CENTER);
                text(c,"today · "+plan.focus.toLowerCase(Locale.US),w/2,h*0.34f+dp(39),dp(11),Color.rgb(104,104,115),Paint.Align.CENTER);
                text(c,LocalCoachEngine.profileLine(prefs),w/2,h*0.34f+dp(72),dp(9),Color.rgb(82,82,92),Paint.Align.CENTER);
            } else if(stage==CALIBRATE) {
                text(c,hz>0?note(midi):"—",w/2,h*0.35f,dp(58),Color.rgb(239,239,242),Paint.Align.CENTER);
                text(c,"FIND YOUR CENTER",w/2,h*0.35f+dp(43),dp(10),Color.rgb(105,105,116),Paint.Align.CENTER);
            } else if(stage==CENTER) {
                drawCenter(c,left,right,top,bottom);
                text(c,"WARM UP",left,bottom+dp(38),dp(10),Color.rgb(91,91,102),Paint.Align.LEFT);
            } else if(stage==INTERVAL) {
                drawCenter(c,left,right,top,bottom);
                float targetY=yFor(target,top,bottom);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.3f));
                paint.setColor(Color.rgb(118,118,130));
                c.drawCircle((left+right)/2,targetY,dp(18),paint);
                text(c,"INTERVAL "+(intervalTrial+1)+"/"+plan.intervalTrials,left,bottom+dp(38),dp(10),Color.rgb(190,190,198),Paint.Align.LEFT);
                text(c,(target>=base?"+":"")+String.format(Locale.US,"%.0f",target-base)+" semitones",right,bottom+dp(38),dp(10),Color.rgb(110,110,121),Paint.Align.RIGHT);
            } else if(stage==LISTEN) {
                drawPhraseShape(c,left,right,top,bottom,true);
                text(c,"LISTEN ONLY",left,bottom+dp(38),dp(10),Color.rgb(190,190,198),Paint.Align.LEFT);
                text(c,message,left,bottom+dp(66),dp(12),Color.rgb(116,116,127),Paint.Align.LEFT);
            } else if(stage==RECALL) {
                drawPhraseShape(c,left,right,top,bottom,false);
                if(now<recallStartAt) {
                    long beat=song.beatMs(phraseIndex);
                    int count=(int)Math.ceil((recallStartAt-now)/(double)beat);
                    text(c,String.valueOf(Math.max(1,count)),w/2,(top+bottom)/2,dp(52),Color.rgb(232,232,236),Paint.Align.CENTER);
                }
                if(phaseUntil>0 && !moment.isEmpty()) {
                    text(c,moment,w/2,(top+bottom)/2,dp(26),Color.rgb(236,236,240),Paint.Align.CENTER);
                }
                text(c,"RECALL · TARGET HIDDEN",left,bottom+dp(38),dp(10),Color.rgb(190,190,198),Paint.Align.LEFT);
            } else if(stage==REPAIR) {
                drawRepairSegment(c,left,right,top,bottom);
                String label=repairPhase==0?"LISTEN TO WEAK SPOT":repairPhase==1?"PREPARE":repairPhase==2?"REPAIR NOW":"RESULT";
                text(c,label,left,bottom+dp(38),dp(10),Color.rgb(190,190,198),Paint.Align.LEFT);
                if(repairPhase==3 && !moment.isEmpty()) text(c,moment,w/2,(top+bottom)/2,dp(26),Color.rgb(236,236,240),Paint.Align.CENTER);
            } else if(stage==RESULT) {
                text(c,String.format(Locale.US,"%02d",score),w/2,dp(168),dp(70),Color.rgb(240,240,243),Paint.Align.CENTER);
                text(c,"ECHO SCORE",w/2,dp(202),dp(10),Color.rgb(105,105,116),Paint.Align.CENTER);
                drawMetric(c,"MEMORY",memoryScore,left,right,dp(255));
                drawMetric(c,"REPAIR",repairScore,left,right,dp(299));
                drawMetric(c,"PITCH",pitchScore,left,right,dp(343));
                drawMetric(c,"STABILITY",stabilityScore,left,right,dp(387));
                drawMetric(c,"INTERVAL",intervalScore,left,right,dp(431));
                text(c,"LOCAL COACH",left,dp(477),dp(9),Color.rgb(81,81,92),Paint.Align.LEFT);
                drawWrapped(c,coach,left,right,dp(501),dp(13),Color.rgb(203,203,210));
            }

            if(stage!=RESULT && stage!=INTRO) {
                drawWrapped(c,coach,left,right,h-dp(128),dp(10),Color.rgb(92,92,103));
            } else if(stage==RESULT) {
                text(c,message,left,h-dp(126),dp(11),Color.rgb(126,126,137),Paint.Align.LEFT);
            } else {
                text(c,message,left,h-dp(126),dp(11),Color.rgb(168,168,177),Paint.Align.LEFT);
            }

            action.set(left,h-dp(86),right,h-dp(34));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.rgb(61,61,68));
            c.drawRoundRect(action,dp(26),dp(26),paint);
            String button=stage==INTRO?"BEGIN":stage==RESULT?"TRAIN AGAIN":"ACTIVE";
            text(c,button,action.centerX(),action.centerY()+dp(4),dp(11),Color.rgb(224,224,229),Paint.Align.CENTER);

            if(running && stage!=RESULT) postInvalidateOnAnimation();
        }

        void drawWrapped(Canvas c,String txt,float left,float right,float y,float size,int color) {
            paint.setTextSize(size);
            paint.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
            String[] words=txt.split(" ");
            StringBuilder line=new StringBuilder();
            for(String word:words) {
                String test=line.length()==0?word:line+" "+word;
                if(paint.measureText(test)>right-left && line.length()>0) {
                    text(c,line.toString(),left,y,size,color,Paint.Align.LEFT);
                    y+=size*1.35f;
                    line=new StringBuilder(word);
                } else line=new StringBuilder(test);
            }
            if(line.length()>0) text(c,line.toString(),left,y,size,color,Paint.Align.LEFT);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if(e.getAction()==MotionEvent.ACTION_UP && action.contains(e.getX(),e.getY()) && (stage==INTRO || stage==RESULT)) {
                begin();
                performClick();
            }
            return true;
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }

        float dp(float value) {
            return value*getResources().getDisplayMetrics().density;
        }

        final class SungFrame {
            final long timeMs;
            final double midi;
            SungFrame(long timeMs,double midi) {
                this.timeMs=timeMs;
                this.midi=midi;
            }
        }
    }
}
