package com.edwardresearch.resonance;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.media.*;
import android.os.*;
import android.view.*;
import java.util.*;

public final class MainActivity extends Activity {
    private GameView game;
    private static final int MIC=42;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(10,10,11));
        getWindow().setNavigationBarColor(Color.rgb(10,10,11));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        game=new GameView();
        setContentView(game);
    }

    void begin(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) game.start();
        else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC);
    }

    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){
        super.onRequestPermissionsResult(r,p,g);
        if(r==MIC && g.length>0 && g[0]==PackageManager.PERMISSION_GRANTED) game.start();
        else game.message="Microfone necessário. O áudio nunca sai do aparelho.";
    }

    @Override protected void onStop(){ game.stop(); super.onStop(); }

    final class GameView extends View {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint line=new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF button=new RectF();
        final ArrayDeque<Double> hzWindow=new ArrayDeque<>();
        final ArrayList<Double> calibration=new ArrayList<>();
        final ArrayList<Double> errors=new ArrayList<>();
        final ArrayList<Double> holdErrors=new ArrayList<>();
        final String[] names={"C","C♯","D","D♯","E","F","F♯","G","G♯","A","A♯","B"};
        volatile boolean running=false;
        Thread audioThread;
        int stage=0; // 0 intro, 1 calibrate, 2 hold, 3 rise, 4 fall, 5 complete
        double hz=0,midi=60,base=60,target=60,confidence=0,goodMs=0;
        long voicedAt=0,lastFrame=0;
        int score=0;
        String message="Sua voz fica no aparelho.";

        GameView(){
            super(MainActivity.this);
            setBackgroundColor(Color.rgb(10,10,11));
            setClickable(true);
            line.setStyle(Paint.Style.STROKE);
        }

        void start(){
            stop();
            stage=1; hz=0; midi=60; confidence=0; goodMs=0; voicedAt=0; lastFrame=0; score=0;
            calibration.clear(); errors.clear(); holdErrors.clear(); hzWindow.clear();
            message="Cante uma nota confortável. Sem força.";
            running=true;
            audioThread=new Thread(this::audioLoop,"ResonanceAudio");
            audioThread.start();
            invalidate();
        }

        void stop(){ running=false; if(audioThread!=null) audioThread.interrupt(); audioThread=null; }

        void audioLoop(){
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            int sr=44100, samples=4096;
            int min=AudioRecord.getMinBufferSize(sr,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord rec=null;
            try{
                rec=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,sr,AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,Math.max(min,samples*4));
                if(rec.getState()!=AudioRecord.STATE_INITIALIZED) return;
                PitchDetector detector=new PitchDetector(sr);
                short[] buf=new short[samples];
                rec.startRecording();
                while(running){
                    int n=rec.read(buf,0,buf.length,AudioRecord.READ_BLOCKING);
                    if(n>0){
                        PitchDetector.Result r=detector.detect(buf,n);
                        post(()->accept(r));
                    }
                }
            }catch(Exception e){
                post(()->{message="Não consegui iniciar o microfone."; invalidate();});
            }finally{
                if(rec!=null){ try{rec.stop();}catch(Exception ignored){} rec.release(); }
            }
        }

        void accept(PitchDetector.Result r){
            long now=System.currentTimeMillis();
            if(r.frequency<=0){ if(now-lastFrame>350) hz=0; invalidate(); return; }
            lastFrame=now; confidence=r.confidence;
            hzWindow.add(r.frequency); while(hzWindow.size()>5) hzWindow.remove();
            ArrayList<Double> tmp=new ArrayList<>(hzWindow); Collections.sort(tmp);
            hz=tmp.get(tmp.size()/2);
            midi=69+12*(Math.log(hz/440.0)/Math.log(2));

            if(stage==1){
                if(confidence>0.68){
                    if(voicedAt==0) voicedAt=now;
                    calibration.add(midi);
                    if(now-voicedAt>2800 && calibration.size()>14){
                        ArrayList<Double> c=new ArrayList<>(calibration); Collections.sort(c);
                        base=Math.rint(c.get(c.size()/2)); target=base; stage=2; goodMs=0;
                        message="Encontrei "+note(base)+". Segure o centro da nota.";
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    }
                }
            }else if(stage>=2 && stage<=4){
                double cents=(midi-target)*100;
                errors.add(cents); if(stage==2) holdErrors.add(cents);
                if(Math.abs(cents)<=35 && confidence>0.65) goodMs+=90; else goodMs=Math.max(0,goodMs-60);
                double needed=stage==2?1800:1000;
                if(goodMs>=needed) advance();
            }
            invalidate();
        }

        void advance(){
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            goodMs=0;
            if(stage==2){ stage=3; target=base+2; message="Suba dois semitons. Não aumente o volume."; }
            else if(stage==3){ stage=4; target=base-2; message="Agora desça. Procure a nota sem empurrar."; }
            else finish();
        }

        void finish(){
            stage=5; stop();
            double abs=0,signed=0;
            for(double e:errors){ abs+=Math.abs(e); signed+=e; }
            double meanAbs=errors.isEmpty()?80:abs/errors.size();
            double meanSigned=errors.isEmpty()?0:signed/errors.size();
            double sd=std(holdErrors);
            score=(int)Math.round(clamp(100-meanAbs*1.1-Math.max(0,sd-15)*0.25,0,100));
            if(meanSigned<-16) message="Você tende a chegar por baixo. Ataque mais perto da nota.";
            else if(meanSigned>16) message="Você tende a chegar acima. Comece mais leve.";
            else if(sd>35) message="Boa direção. Agora estabilize o centro da nota.";
            else if(meanAbs>30) message="Você encontra a região. Vamos reduzir o erro fino.";
            else message="Controle consistente. Próximo: intervalos maiores e vibrato.";
            getSharedPreferences("resonance_local",0).edit()
                .putInt("best",Math.max(score,getSharedPreferences("resonance_local",0).getInt("best",0)))
                .apply();
        }

        double std(ArrayList<Double> xs){
            if(xs.size()<2) return 60;
            double m=0; for(double x:xs)m+=x; m/=xs.size();
            double s=0; for(double x:xs){double d=x-m;s+=d*d;}
            return Math.sqrt(s/(xs.size()-1));
        }

        String note(double m){
            int n=(int)Math.round(m), pc=((n%12)+12)%12, oct=n/12-1;
            return names[pc]+oct;
        }

        float yFor(double m,float top,float bottom){
            double rel=clamp((m-base)/8.0,-1,1);
            return (float)((top+bottom)/2.0-rel*(bottom-top)*0.45);
        }

        void text(Canvas c,String s,float x,float y,float size,int color,Paint.Align align){
            p.setStyle(Paint.Style.FILL); p.setColor(color); p.setTextSize(size); p.setTextAlign(align);
            p.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL)); c.drawText(s,x,y,p);
        }

        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            float w=getWidth(),h=getHeight(),L=dp(28),R=w-dp(28),top=dp(42),gTop=dp(118),gBottom=h-dp(178);
            text(c,"RESONANCE",L,top,dp(13),Color.rgb(220,220,224),Paint.Align.LEFT);
            text(c,label(),R,top,dp(11),Color.rgb(120,120,128),Paint.Align.RIGHT);
            float cx=w/2;

            line.setStrokeWidth(dp(1)); line.setColor(Color.rgb(40,40,45));
            c.drawLine(cx,gTop,cx,gBottom,line);

            if(stage>=2 && stage<=4){
                float ty=yFor(target,gTop,gBottom);
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1.5f));p.setColor(Color.rgb(195,195,202));
                c.drawCircle(cx,ty,dp(31),p);
                text(c,note(target),cx,ty-dp(43),dp(12),Color.rgb(135,135,144),Paint.Align.CENTER);
            }

            if(hz>0 && stage>=1 && stage<=4){
                float vy=stage==1?(gTop+gBottom)/2:yFor(midi,gTop,gBottom);
                p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(240,240,242)); c.drawCircle(cx,vy,dp(10),p);
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1));p.setColor(Color.rgb(95,95,105));c.drawCircle(cx,vy,dp(18),p);
            }

            if(stage==1){
                text(c,hz>0?note(midi):"—",cx,(gTop+gBottom)/2,dp(52),Color.rgb(238,238,241),Paint.Align.CENTER);
                text(c,"FIND YOUR VOICE",cx,(gTop+gBottom)/2+dp(42),dp(11),Color.rgb(115,115,124),Paint.Align.CENTER);
            }

            if(stage==5){
                text(c,String.format(Locale.US,"%02d",score),cx,(gTop+gBottom)*0.48f,dp(74),Color.rgb(240,240,242),Paint.Align.CENTER);
                text(c,"VOICE CONTROL",cx,(gTop+gBottom)*0.48f+dp(40),dp(11),Color.rgb(115,115,124),Paint.Align.CENTER);
            }

            text(c,message,L,h-dp(133),dp(13),Color.rgb(190,190,198),Paint.Align.LEFT);
            if(stage>=2 && stage<=4 && hz>0){
                double cents=(midi-target)*100;
                text(c,note(midi)+"  ·  "+String.format(Locale.US,"%+.0f cents",cents),L,h-dp(108),dp(11),Color.rgb(100,100,110),Paint.Align.LEFT);
            }

            button.set(L,h-dp(84),R,h-dp(34));
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1));p.setColor(Color.rgb(62,62,69));
            c.drawRoundRect(button,dp(25),dp(25),p);
            text(c,stage==0?"BEGIN":stage==5?"AGAIN":"LISTENING",button.centerX(),button.centerY()+dp(5),dp(12),Color.rgb(225,225,229),Paint.Align.CENTER);
        }

        String label(){
            if(stage==0)return "LEVEL 01";
            if(stage==1)return "01 · CALIBRATE";
            if(stage==2)return "02 · HOLD";
            if(stage==3)return "03 · RISE";
            if(stage==4)return "03 · FALL";
            return "SESSION 01";
        }

        @Override public boolean onTouchEvent(MotionEvent e){
            if(e.getAction()==MotionEvent.ACTION_UP && button.contains(e.getX(),e.getY()) && (stage==0||stage==5)){ begin(); performClick(); }
            return true;
        }
        @Override public boolean performClick(){ super.performClick(); return true; }

        float dp(float v){ return v*getResources().getDisplayMetrics().density; }
        double clamp(double v,double a,double b){ return Math.max(a,Math.min(b,v)); }
    }
}
