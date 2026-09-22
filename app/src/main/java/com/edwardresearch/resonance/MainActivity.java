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
        static final int SCREEN_HOME=0, SCREEN_STATS=1, SCREEN_COACH=2, SCREEN_TRAINING=3;

        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF action = new RectF();
        final RectF navHome=new RectF(), navStats=new RectF(), navCoach=new RectF(), navLibrary=new RectF();
        final RectF homeBegin=new RectF();
        final RectF[] homeCards={new RectF(),new RectF(),new RectF(),new RectF(),new RectF(),new RectF()};
        int screen=SCREEN_HOME;
        boolean libraryTab=false;
        final int BG=Color.rgb(7,11,14), PANEL=Color.rgb(14,20,23), PANEL2=Color.rgb(18,24,27);
        final int GOLD=Color.rgb(235,194,137), CREAM=Color.rgb(239,225,205), MUTED=Color.rgb(137,142,147), LINE=Color.rgb(48,56,60);
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
        double intervalGoodMs;

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
            plan = LocalCoachEngine.createPlan(prefs, level);
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
            coach=plan.rationale;
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


        void fillGradient(Canvas c,int topColor,int bottomColor){
            Paint g=new Paint();
            g.setShader(new LinearGradient(0,0,0,getHeight(),topColor,bottomColor,Shader.TileMode.CLAMP));
            c.drawRect(0,0,getWidth(),getHeight(),g);
        }

        void roundPanel(Canvas c,RectF r,float radius,int fill,int border){
            paint.setStyle(Paint.Style.FILL); paint.setColor(fill); c.drawRoundRect(r,radius,radius,paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(0.7f)); paint.setColor(border); c.drawRoundRect(r,radius,radius,paint);
        }

        void multiText(Canvas c,String txt,float x,float y,float size,int color,Paint.Align align,float lineGap){
            String[] lines=txt.split("\\n");
            for(int i=0;i<lines.length;i++) text(c,lines[i],x,y+i*(size+lineGap),size,color,align);
        }

        void drawMountainBackdrop(Canvas c,float top,float bottom){
            float w=getWidth();
            Paint halo=new Paint(Paint.ANTI_ALIAS_FLAG);
            halo.setShader(new RadialGradient(w*0.53f,top+(bottom-top)*0.38f,dp(66),
                    new int[]{Color.argb(130,235,194,137),Color.argb(28,235,194,137),Color.TRANSPARENT},
                    new float[]{0f,0.40f,1f},Shader.TileMode.CLAMP));
            c.drawCircle(w*0.53f,top+(bottom-top)*0.38f,dp(66),halo);
            for(int layer=0;layer<4;layer++){
                Path p=new Path(); float baseY=bottom-dp(layer*13); int pts=11;
                for(int i=0;i<=pts;i++){
                    float x=w*i/(float)pts;
                    float wave=(float)(Math.sin(i*1.71+layer*0.72)*0.5+0.5);
                    float y=baseY-dp(18+layer*10)-wave*dp(25+layer*6);
                    if(i==0)p.moveTo(x,y); else p.lineTo(x,y);
                }
                p.lineTo(w,bottom);p.lineTo(0,bottom);p.close();
                int v=13+layer*5;paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(v,v+3,v+5));c.drawPath(p,paint);
            }
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(Color.argb(100,235,194,137));
            Path river=new Path();river.moveTo(w*0.53f,bottom);river.cubicTo(w*0.38f,bottom-dp(35),w*0.64f,bottom-dp(70),w*0.52f,top+dp(36));c.drawPath(river,paint);
        }

        void drawWaveMark(Canvas c,float cx,float cy){
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(0.8f));
            for(int i=-7;i<=7;i++){
                float hh=dp(3+Math.abs(i%4)*2+(7-Math.abs(i))*1.0f);
                paint.setColor(Color.argb(78+Math.max(0,7-Math.abs(i))*13,235,194,137));
                c.drawLine(cx+i*dp(3.5f),cy-hh/2,cx+i*dp(3.5f),cy+hh/2,paint);
            }
        }

        void drawHeader(Canvas c,String subtitle){
            float w=getWidth();
            drawWaveMark(c,w/2,dp(24));
            paint.setTypeface(Typeface.create("serif",Typeface.NORMAL));
            text(c,"R E S O N A N C E",w/2,dp(54),dp(15),CREAM,Paint.Align.CENTER);
            paint.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
            text(c,subtitle,w/2,dp(73),dp(7.5f),Color.rgb(184,184,186),Paint.Align.CENTER);
            text(c,"Lv. "+Math.max(1,prefs.getInt("level",level)),w-dp(22),dp(42),dp(9.5f),CREAM,Paint.Align.RIGHT);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(GOLD);c.drawCircle(w-dp(58),dp(38),dp(14),paint);
            Path crown=new Path();crown.moveTo(w-dp(65),dp(42));crown.lineTo(w-dp(63),dp(34));crown.lineTo(w-dp(58),dp(39));crown.lineTo(w-dp(53),dp(33));crown.lineTo(w-dp(51),dp(42));crown.close();
            paint.setStyle(Paint.Style.FILL);paint.setColor(GOLD);c.drawPath(crown,paint);
        }

        void drawBottomNav(Canvas c,int active){
            float h=getHeight(),w=getWidth();
            RectF nav=new RectF(dp(15),h-dp(69),w-dp(15),h-dp(10));roundPanel(c,nav,dp(17),Color.rgb(9,14,17),Color.rgb(39,47,51));
            float[] xs={w*0.16f,w*0.39f,w*0.62f,w*0.84f};String[] labels={"Home","Stats","Coach","Library"};RectF[] rs={navHome,navStats,navCoach,navLibrary};
            for(int i=0;i<4;i++){
                rs[i].set(xs[i]-dp(40),h-dp(69),xs[i]+dp(40),h-dp(8));int col=i==active?CREAM:Color.rgb(111,118,123);
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.25f));paint.setColor(col);
                if(i==0){Path q=new Path();q.moveTo(xs[i]-dp(7),h-dp(43));q.lineTo(xs[i],h-dp(51));q.lineTo(xs[i]+dp(7),h-dp(43));q.lineTo(xs[i]+dp(7),h-dp(33));q.lineTo(xs[i]-dp(7),h-dp(33));q.close();c.drawPath(q,paint);}
                else if(i==1){for(int b=0;b<3;b++)c.drawRect(xs[i]-dp(8)+b*dp(6),h-dp(36)-b*dp(5),xs[i]-dp(4)+b*dp(6),h-dp(32),paint);}
                else if(i==2){c.drawCircle(xs[i],h-dp(41),dp(7),paint);c.drawLine(xs[i]-dp(4),h-dp(37),xs[i]+dp(5),h-dp(46),paint);}
                else {c.drawRect(xs[i]-dp(8),h-dp(50),xs[i]-dp(1),h-dp(34),paint);c.drawRect(xs[i]+dp(1),h-dp(50),xs[i]+dp(8),h-dp(34),paint);}
                text(c,labels[i],xs[i],h-dp(17),dp(7.8f),col,Paint.Align.CENTER);
            }
        }

        void drawIsoPlatform(Canvas c,float cx,float cy,float scale,int kind){
            float sw=dp(50)*scale,sh=dp(28)*scale;
            Path top=new Path();top.moveTo(cx,cy-sh*0.55f);top.lineTo(cx+sw*0.52f,cy-sh*0.12f);top.lineTo(cx,cy+sh*0.30f);top.lineTo(cx-sw*0.52f,cy-sh*0.12f);top.close();
            paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(66,64,60));c.drawPath(top,paint);
            Path left=new Path();left.moveTo(cx-sw*0.52f,cy-sh*0.12f);left.lineTo(cx,cy+sh*0.30f);left.lineTo(cx,cy+sh*0.55f);left.lineTo(cx-sw*0.52f,cy+sh*0.12f);left.close();paint.setColor(Color.rgb(27,30,32));c.drawPath(left,paint);
            Path right=new Path();right.moveTo(cx+sw*0.52f,cy-sh*0.12f);right.lineTo(cx,cy+sh*0.30f);right.lineTo(cx,cy+sh*0.55f);right.lineTo(cx+sw*0.52f,cy+sh*0.12f);right.close();paint.setColor(Color.rgb(38,39,38));c.drawPath(right,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.3f));paint.setColor(GOLD);float y=cy-sh*0.35f;
            if(kind==0){c.drawRoundRect(new RectF(cx-dp(5)*scale,y-dp(12)*scale,cx+dp(5)*scale,y+dp(5)*scale),dp(5),dp(5),paint);c.drawLine(cx,y+dp(5)*scale,cx,cy,paint);c.drawLine(cx-dp(7)*scale,cy,cx+dp(7)*scale,cy,paint);}
            else if(kind==1){for(int i=0;i<3;i++)c.drawRoundRect(new RectF(cx-dp(13)*scale+i*dp(8)*scale,y-dp(11)*scale+i*dp(2)*scale,cx+dp(1)*scale+i*dp(8)*scale,y+dp(6)*scale+i*dp(2)*scale),dp(2),dp(2),paint);Path wv=new Path();wv.moveTo(cx-dp(14)*scale,y);wv.cubicTo(cx-dp(6)*scale,y-dp(7)*scale,cx+dp(4)*scale,y+dp(7)*scale,cx+dp(14)*scale,y-dp(3)*scale);c.drawPath(wv,paint);}
            else if(kind==2){for(int i=0;i<4;i++)c.drawRect(cx-dp(14)*scale+i*dp(7)*scale,cy-dp(2)*scale-i*dp(4)*scale,cx-dp(9)*scale+i*dp(7)*scale,cy+dp(3)*scale,paint);text(c,"♪",cx+dp(9)*scale,y,dp(16)*scale,GOLD,Paint.Align.CENTER);}
            else if(kind==3){c.drawCircle(cx,y-dp(2)*scale,dp(9)*scale,paint);for(int k=0;k<3;k++)c.drawArc(new RectF(cx+dp(4+k*4)*scale,y-dp(8+k*3)*scale,cx+dp(16+k*4)*scale,y+dp(4+k*3)*scale),-50,100,false,paint);}
            else if(kind==4){Path rock=new Path();rock.moveTo(cx-dp(11)*scale,cy);rock.lineTo(cx-dp(5)*scale,y-dp(11)*scale);rock.lineTo(cx+dp(8)*scale,y-dp(8)*scale);rock.lineTo(cx+dp(12)*scale,cy);rock.lineTo(cx,cy+dp(6)*scale);rock.close();c.drawPath(rock,paint);c.drawLine(cx,y-dp(8)*scale,cx+dp(2)*scale,cy+dp(3)*scale,paint);}
            else {for(int i=0;i<4;i++){float bh=dp(6+5*i)*scale;c.drawRect(cx-dp(13)*scale+i*dp(7)*scale,cy-bh,cx-dp(8)*scale+i*dp(7)*scale,cy,paint);}text(c,"★",cx+dp(13)*scale,y-dp(8)*scale,dp(10)*scale,GOLD,Paint.Align.CENTER);}
        }

        void drawHomeCard(Canvas c,int idx,float l,float t,float r,float b,String title,String sub){
            RectF card=homeCards[idx];card.set(l,t,r,b);roundPanel(c,card,dp(12),Color.argb(236,13,19,22),Color.rgb(54,62,66));drawIsoPlatform(c,(l+r)/2,t+(b-t)*0.41f,0.86f,idx);
            text(c,title,l+dp(9),b-dp(29),dp(10),Color.WHITE,Paint.Align.LEFT);text(c,sub,l+dp(9),b-dp(13),dp(5.8f),Color.rgb(162,164,166),Paint.Align.LEFT);text(c,"›",r-dp(10),b-dp(26),dp(16),GOLD,Paint.Align.CENTER);
        }

        void drawHome(Canvas c){
            float w=getWidth(),h=getHeight();drawHeader(c,"Adaptive Vocal Coach");drawMountainBackdrop(c,dp(84),dp(210));
            multiText(c,"SMALL\\nPRACTICE\\nREAL CHANGE",dp(20),dp(112),dp(5.4f),Color.rgb(181,182,184),Paint.Align.LEFT,dp(4));
            multiText(c,"VOICE\\nBUILDS\\nPOSSIBILITIES",w-dp(20),dp(112),dp(5.4f),Color.rgb(181,182,184),Paint.Align.RIGHT,dp(4));
            float gap=dp(7),l=dp(16),r=w-dp(16),cardW=(r-l-gap*2)/3f,cardH=Math.min(dp(132),(h-dp(390))/2f),top=dp(221);
            String[] titles={"Warm Up","Pitch","Intervals","Echo Recall","Repair","Progress"};
            String[] subs={"Prepare · Breathe · Flow","Find · Match · Control","Hear · Climb · Master","Listen · Remember · Repeat","Fix · Stabilize · Grow","Track · Unlock · Belong"};
            for(int row=0;row<2;row++)for(int col=0;col<3;col++){int i=row*3+col;float x=l+col*(cardW+gap),y=top+row*(cardH+gap);drawHomeCard(c,i,x,y,x+cardW,y+cardH,titles[i],subs[i]);}
            float by=top+2*(cardH+gap)+dp(5);homeBegin.set(dp(42),by,w-dp(42),by+dp(48));
            Paint glow=new Paint(Paint.ANTI_ALIAS_FLAG);glow.setShader(new LinearGradient(homeBegin.left,by,homeBegin.right,by,Color.rgb(190,147,99),Color.rgb(245,214,171),Shader.TileMode.CLAMP));c.drawRoundRect(homeBegin,dp(24),dp(24),glow);
            text(c,"Begin Session   →",w/2,by+dp(30),dp(13.5f),Color.rgb(17,16,15),Paint.Align.CENTER);text(c,"On-device. Private. Offline-first.",w/2,by+dp(68),dp(6.7f),Color.rgb(190,191,193),Paint.Align.CENTER);
            if(by+dp(92)<h-dp(80)){RectF quote=new RectF(dp(26),by+dp(82),w-dp(26),Math.min(by+dp(126),h-dp(78)));roundPanel(c,quote,dp(12),Color.rgb(11,17,20),Color.rgb(45,53,57));paint.setTypeface(Typeface.create("serif",Typeface.ITALIC));text(c,"“A kinder, stronger voice lives in you.”",w/2,quote.centerY()+dp(3),dp(8.5f),Color.rgb(174,174,177),Paint.Align.CENTER);}
            drawBottomNav(c,0);
        }

        int prefSkill(String key,int fallback){return Math.round(prefs.getFloat(key,fallback));}

        void drawSmallStat(Canvas c,float l,float t,float r,float b,String big,String label,String micro){
            RectF q=new RectF(l,t,r,b);roundPanel(c,q,dp(10),Color.rgb(11,17,20),Color.rgb(45,53,57));text(c,big,(l+r)/2,t+dp(30),dp(15),CREAM,Paint.Align.CENTER);text(c,label,(l+r)/2,t+dp(49),dp(7.5f),Color.WHITE,Paint.Align.CENTER);text(c,micro,(l+r)/2,t+dp(66),dp(5),MUTED,Paint.Align.CENTER);
        }

        void drawStats(Canvas c){
            float w=getWidth(),h=getHeight();drawHeader(c,"Progress");drawMountainBackdrop(c,dp(80),dp(150));paint.setTypeface(Typeface.create("serif",Typeface.NORMAL));text(c,"Progress",w/2,dp(166),dp(22),CREAM,Paint.Align.CENTER);text(c,"R E A L  P R A C T I C E  ·  R E A L  C H A N G E",w/2,dp(185),dp(4.8f),MUTED,Paint.Align.CENTER);
            float l=dp(15),gap=dp(5),top=dp(200),cw=(w-dp(30)-gap*3)/4;int sessions=prefs.getInt("session_count",0);
            drawSmallStat(c,l,top,l+cw,top+dp(78),String.valueOf(Math.max(1,sessions/2)),"Day Streak","SHOWING UP");drawSmallStat(c,l+cw+gap,top,l+2*cw+gap,top+dp(78),String.valueOf(sessions),"Sessions","CONSISTENCY");drawSmallStat(c,l+2*(cw+gap),top,l+3*cw+2*gap,top+dp(78),String.format(Locale.US,"%.1fh",sessions*.16),"Time","SMALL STEPS");drawSmallStat(c,l+3*(cw+gap),top,w-dp(15),top+dp(78),"+"+Math.min(99,sessions*3)+"%","Growth","STRONGER YOU");
            RectF skill=new RectF(dp(15),dp(289),w-dp(15),Math.min(dp(500),h-dp(260)));roundPanel(c,skill,dp(12),Color.rgb(11,17,20),Color.rgb(45,53,57));text(c,"Skill Development",skill.left+dp(11),skill.top+dp(22),dp(10),Color.WHITE,Paint.Align.LEFT);
            String[] labs={"Pitch","Stability","Memory","Repair","Intervals"};int[] vals={prefSkill("skill_pitch",55),prefSkill("skill_stability",55),prefSkill("skill_memory",55),prefSkill("skill_repair",55),prefSkill("skill_interval",50)};float baseY=skill.bottom-dp(43);
            for(int i=0;i<5;i++){float x=skill.left+dp(28)+i*(skill.width()-dp(56))/4f;RectF bg=new RectF(x-dp(7),skill.top+dp(47),x+dp(7),baseY);paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(23,29,32));c.drawRoundRect(bg,dp(4),dp(4),paint);float fh=(baseY-(skill.top+dp(47)))*vals[i]/100f;Paint bar=new Paint(Paint.ANTI_ALIAS_FLAG);bar.setShader(new LinearGradient(0,baseY-fh,0,baseY,Color.rgb(247,216,173),Color.rgb(117,94,70),Shader.TileMode.CLAMP));c.drawRoundRect(new RectF(x-dp(7),baseY-fh,x+dp(7),baseY),dp(4),dp(4),bar);text(c,labs[i],x,baseY+dp(15),dp(6),Color.WHITE,Paint.Align.CENTER);text(c,vals[i]+"%",x,baseY+dp(29),dp(7),GOLD,Paint.Align.CENTER);}
            float recTop=skill.bottom+dp(10);if(recTop<h-dp(82)){RectF recent=new RectF(dp(15),recTop,w-dp(15),h-dp(82));roundPanel(c,recent,dp(12),Color.rgb(11,17,20),Color.rgb(45,53,57));text(c,"Recent Session",recent.left+dp(11),recent.top+dp(22),dp(10),Color.WHITE,Paint.Align.LEFT);int best=prefs.getInt("best",0);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(4));paint.setColor(Color.rgb(48,54,57));c.drawCircle(recent.left+dp(43),recent.centerY()+dp(8),dp(24),paint);paint.setColor(GOLD);c.drawArc(new RectF(recent.left+dp(19),recent.centerY()-dp(16),recent.left+dp(67),recent.centerY()+dp(32)),-90,Math.max(20,best)*3.6f,false,paint);text(c,String.valueOf(best),recent.left+dp(43),recent.centerY()+dp(13),dp(15),CREAM,Paint.Align.CENTER);text(c,plan.focus+" Practice",recent.left+dp(82),recent.centerY(),dp(9),CREAM,Paint.Align.LEFT);text(c,"Adaptive session · local profile",recent.left+dp(82),recent.centerY()+dp(19),dp(6),MUTED,Paint.Align.LEFT);}
            drawBottomNav(c,1);
        }

        void drawRadar(Canvas c,float cx,float cy,float radius){
            int[] vals={prefSkill("skill_pitch",55),prefSkill("skill_interval",50),prefSkill("skill_repair",55),prefSkill("skill_memory",55),prefSkill("skill_stability",55)};
            for(int ring=1;ring<=4;ring++){Path p=new Path();for(int i=0;i<5;i++){double a=-Math.PI/2+i*2*Math.PI/5;float x=cx+(float)Math.cos(a)*radius*ring/4f,y=cy+(float)Math.sin(a)*radius*ring/4f;if(i==0)p.moveTo(x,y);else p.lineTo(x,y);}p.close();paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(.6f));paint.setColor(Color.rgb(72,65,58));c.drawPath(p,paint);}
            Path data=new Path();for(int i=0;i<5;i++){double a=-Math.PI/2+i*2*Math.PI/5;float rr=radius*vals[i]/100f,x=cx+(float)Math.cos(a)*rr,y=cy+(float)Math.sin(a)*rr;if(i==0)data.moveTo(x,y);else data.lineTo(x,y);}data.close();paint.setStyle(Paint.Style.FILL);paint.setColor(Color.argb(72,235,194,137));c.drawPath(data,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.2f));paint.setColor(GOLD);c.drawPath(data,paint);
        }

        void drawCoach(Canvas c){
            float w=getWidth(),h=getHeight();drawHeader(c,"Adaptive Vocal Coach");drawMountainBackdrop(c,dp(80),dp(143));RectF tabs=new RectF(dp(22),dp(95),w-dp(22),dp(131));roundPanel(c,tabs,dp(12),Color.rgb(11,17,20),Color.rgb(45,53,57));float mid=tabs.centerX();RectF sel=libraryTab?new RectF(mid,tabs.top,tabs.right,tabs.bottom):new RectF(tabs.left,tabs.top,mid,tabs.bottom);Paint sg=new Paint(Paint.ANTI_ALIAS_FLAG);sg.setShader(new LinearGradient(sel.left,0,sel.right,0,Color.rgb(92,69,49),Color.rgb(58,46,37),Shader.TileMode.CLAMP));c.drawRoundRect(sel,dp(12),dp(12),sg);text(c,"Coach",tabs.left+tabs.width()*.25f,tabs.centerY()+dp(3),dp(8.5f),CREAM,Paint.Align.CENTER);text(c,"Library",tabs.left+tabs.width()*.75f,tabs.centerY()+dp(3),dp(8.5f),CREAM,Paint.Align.CENTER);
            RectF pc=new RectF(dp(15),dp(145),w-dp(15),dp(326));roundPanel(c,pc,dp(12),Color.argb(240,13,19,22),Color.rgb(85,69,54));text(c,"Y O U R   A D A P T I V E   P L A N",pc.left+dp(12),pc.top+dp(22),dp(5.2f),Color.rgb(187,187,189),Paint.Align.LEFT);paint.setTypeface(Typeface.create("serif",Typeface.NORMAL));text(c,libraryTab?"Lesson Library":"Today’s Focus",pc.left+dp(12),pc.top+dp(51),dp(18),CREAM,Paint.Align.LEFT);text(c,libraryTab?"Choose a skill to explore":plan.focus+" Control",pc.left+dp(12),pc.top+dp(73),dp(11),Color.WHITE,Paint.Align.LEFT);drawMountainBackdrop(c,pc.top+dp(83),pc.bottom-dp(7));
            if(!libraryTab){drawWrapped(c,plan.rationale,pc.left+dp(12),pc.right-dp(120),pc.top+dp(98),dp(7),Color.rgb(187,187,190));RectF btn=new RectF(pc.left+dp(12),pc.bottom-dp(46),pc.left+dp(174),pc.bottom-dp(12));Paint bg=new Paint(Paint.ANTI_ALIAS_FLAG);bg.setShader(new LinearGradient(btn.left,0,btn.right,0,Color.rgb(201,159,112),Color.rgb(245,216,176),Shader.TileMode.CLAMP));c.drawRoundRect(btn,dp(17),dp(17),bg);text(c,"Start Today’s Plan  →",btn.centerX(),btn.centerY()+dp(3),dp(9),Color.rgb(17,17,17),Paint.Align.CENTER);}
            RectF sc=new RectF(dp(15),dp(337),w-dp(15),dp(493));roundPanel(c,sc,dp(12),Color.rgb(11,17,20),Color.rgb(45,53,57));text(c,"Y O U R   S K I L L   P R O F I L E",sc.left+dp(12),sc.top+dp(21),dp(5.2f),MUTED,Paint.Align.LEFT);drawRadar(c,sc.left+dp(92),sc.top+dp(94),dp(51));text(c,"STEADY GROWTH",sc.left+dp(178),sc.top+dp(54),dp(7.5f),CREAM,Paint.Align.LEFT);text(c,LocalCoachEngine.profileLine(prefs),sc.left+dp(178),sc.top+dp(76),dp(6.4f),GOLD,Paint.Align.LEFT);drawWrapped(c,"Your profile updates after every local session.",sc.left+dp(178),sc.right-dp(10),sc.top+dp(96),dp(6.3f),MUTED);
            text(c,"L E S S O N   L I B R A R Y",dp(19),dp(520),dp(5.3f),MUTED,Paint.Align.LEFT);float gap=dp(5),l=dp(15),top=dp(535),cw=(w-dp(30)-gap*4)/5;String[] labs={"Breath","Pitch","Intervals","Echo","Repair"};for(int i=0;i<5;i++){RectF cr=new RectF(l+i*(cw+gap),top,l+i*(cw+gap)+cw,Math.min(top+dp(102),h-dp(80)));roundPanel(c,cr,dp(9),Color.rgb(11,17,20),Color.rgb(45,53,57));drawIsoPlatform(c,cr.centerX(),cr.top+dp(43),.52f,i);text(c,labs[i],cr.centerX(),cr.bottom-dp(11),dp(5.8f),Color.WHITE,Paint.Align.CENTER);}
            drawBottomNav(c,libraryTab?3:2);
        }

        int metricPitch(){return stage==RESULT?pitchScore:prefSkill("skill_pitch",55);}
        int metricStability(){return stage==RESULT?stabilityScore:prefSkill("skill_stability",55);}
        int metricMemory(){return stage==RESULT?memoryScore:prefSkill("skill_memory",55);}
        int metricInterval(){return stage==RESULT?intervalScore:prefSkill("skill_interval",50);}

        void drawRingMetric(Canvas c,float cx,float cy,int value,String label){
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(3.5f));paint.setColor(Color.rgb(47,54,57));c.drawCircle(cx,cy,dp(23),paint);paint.setColor(GOLD);paint.setStrokeWidth(dp(2.7f));c.drawArc(new RectF(cx-dp(23),cy-dp(23),cx+dp(23),cy+dp(23)),-90,Math.max(0,Math.min(100,value))*3.6f,false,paint);text(c,String.valueOf(value),cx,cy+dp(5),dp(12),CREAM,Paint.Align.CENTER);text(c,label,cx,cy+dp(38),dp(7.5f),Color.WHITE,Paint.Align.CENTER);
        }

        void drawTraining(Canvas c,long now){
            float w=getWidth(),h=getHeight();drawHeader(c,"Live Training");drawMountainBackdrop(c,dp(80),dp(165));
            String title=stage==CALIBRATE?"Find Your Center":stage==CENTER?"Warm Up":stage==INTERVAL?"Intervals":stage==LISTEN?"Echo Recall · Listen":stage==RECALL?"Echo Recall":stage==REPAIR?"Repair":stage==RESULT?"Session Complete":"Adaptive Session";
            paint.setTypeface(Typeface.create("serif",Typeface.NORMAL));text(c,title,dp(19),dp(153),dp(17),CREAM,Paint.Align.LEFT);text(c,plan.focus+" · adaptive practice",dp(19),dp(172),dp(5.8f),MUTED,Paint.Align.LEFT);
            RectF chart=new RectF(dp(15),dp(190),w-dp(15),Math.min(dp(441),h-dp(330)));roundPanel(c,chart,dp(13),Color.rgb(10,16,19),Color.rgb(45,53,57));float gl=chart.left+dp(21),gr=chart.right-dp(14),gt=chart.top+dp(17),gb=chart.bottom-dp(30);
            if(stage==CALIBRATE||stage==CENTER||stage==INTERVAL)drawCenter(c,gl,gr,gt,gb);else if(stage==LISTEN)drawPhraseShape(c,gl,gr,gt,gb,true);else if(stage==RECALL)drawPhraseShape(c,gl,gr,gt,gb,false);else if(stage==REPAIR)drawRepairSegment(c,gl,gr,gt,gb);else{drawGrid(c,gl,gr,gt,gb);text(c,String.valueOf(score),chart.centerX(),chart.centerY(),dp(36),CREAM,Paint.Align.CENTER);}
            text(c,"Target",chart.left+dp(16),chart.bottom-dp(11),dp(5.8f),GOLD,Paint.Align.LEFT);text(c,"Your Voice",chart.left+dp(65),chart.bottom-dp(11),dp(5.8f),Color.rgb(205,214,222),Paint.Align.LEFT);
            float my=chart.bottom+dp(57);drawRingMetric(c,w*.14f,my,metricPitch(),"Pitch");drawRingMetric(c,w*.38f,my,metricStability(),"Stability");drawRingMetric(c,w*.62f,my,metricMemory(),"Memory");drawRingMetric(c,w*.86f,my,metricInterval(),"Interval");
            float hintTop=my+dp(54);RectF hint=new RectF(dp(19),hintTop,w-dp(19),hintTop+dp(53));roundPanel(c,hint,dp(12),Color.rgb(11,17,20),Color.rgb(45,53,57));paint.setTypeface(Typeface.create("serif",Typeface.ITALIC));text(c,stage==RESULT?coach:(moment.isEmpty()?message:moment),hint.centerX(),hint.centerY()+dp(3),dp(9.5f),CREAM,Paint.Align.CENTER);
            float ctl=Math.min(h-dp(146),hint.bottom+dp(57));paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(36,32,28));c.drawCircle(w/2,ctl,dp(28),paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.2f));paint.setColor(GOLD);c.drawCircle(w/2,ctl,dp(28),paint);if(stage==RESULT)text(c,"↻",w/2,ctl+dp(6),dp(21),CREAM,Paint.Align.CENTER);else{paint.setStyle(Paint.Style.FILL);paint.setColor(CREAM);c.drawRect(w/2-dp(6),ctl-dp(8),w/2-dp(2),ctl+dp(8),paint);c.drawRect(w/2+dp(2),ctl-dp(8),w/2+dp(6),ctl+dp(8),paint);}
            int step=stage<=CENTER?1:stage==INTERVAL?2:(stage==LISTEN||stage==RECALL)?3:stage==REPAIR?4:5;text(c,"Session "+step+" of 5",dp(28),ctl+dp(59),dp(6.8f),MUTED,Paint.Align.LEFT);paint.setStyle(Paint.Style.FILL);paint.setColor(Color.rgb(45,52,55));c.drawRoundRect(new RectF(dp(105),ctl+dp(53),w-dp(42),ctl+dp(58)),dp(3),dp(3),paint);paint.setColor(GOLD);c.drawRoundRect(new RectF(dp(105),ctl+dp(53),dp(105)+(w-dp(147))*step/5f,ctl+dp(58)),dp(3),dp(3),paint);drawBottomNav(c,0);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            fillGradient(c,Color.rgb(8,13,16),Color.rgb(3,6,8));
            long now=System.currentTimeMillis();
            if(screen==SCREEN_TRAINING) updateTimed(now);
            if(screen==SCREEN_HOME) drawHome(c);
            else if(screen==SCREEN_STATS) drawStats(c);
            else if(screen==SCREEN_COACH) drawCoach(c);
            else drawTraining(c,now);
            if(running && screen==SCREEN_TRAINING && stage!=RESULT) postInvalidateOnAnimation();
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
            if(e.getAction()!=MotionEvent.ACTION_UP) return true;
            float x=e.getX(),y=e.getY();
            if(navHome.contains(x,y)){stopAll();screen=SCREEN_HOME;stage=INTRO;invalidate();performClick();return true;}
            if(navStats.contains(x,y)){stopAll();screen=SCREEN_STATS;invalidate();performClick();return true;}
            if(navCoach.contains(x,y)){stopAll();screen=SCREEN_COACH;libraryTab=false;invalidate();performClick();return true;}
            if(navLibrary.contains(x,y)){stopAll();screen=SCREEN_COACH;libraryTab=true;invalidate();performClick();return true;}
            if(screen==SCREEN_HOME){
                if(homeBegin.contains(x,y)){begin();performClick();return true;}
                for(int i=0;i<homeCards.length;i++)if(homeCards[i].contains(x,y)){if(i==5){screen=SCREEN_STATS;invalidate();}else begin();performClick();return true;}
            } else if(screen==SCREEN_COACH){
                if(y>=dp(95)&&y<=dp(131)){libraryTab=x>getWidth()/2f;invalidate();performClick();return true;}
                if(!libraryTab&&y>=dp(145)&&y<=dp(326)){begin();performClick();return true;}
            } else if(screen==SCREEN_TRAINING&&stage==RESULT){begin();performClick();return true;}
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
