package com.edwardresearch.resonance;

final class PitchDetector {
    static final class Result {
        final double frequency, confidence, rms;
        Result(double frequency, double confidence, double rms) {
            this.frequency = frequency; this.confidence = confidence; this.rms = rms;
        }
    }

    private final int sampleRate;
    private static final int DOWNSAMPLE = 2;
    private static final double MIN_FREQ = 80.0, MAX_FREQ = 800.0, YIN_THRESHOLD = 0.16;

    PitchDetector(int sampleRate) { this.sampleRate = sampleRate; }

    Result detect(short[] pcm, int length) {
        if (length < 1024) return new Result(0,0,0);
        int n = length / DOWNSAMPLE;
        double[] x = new double[n];
        double sumSq = 0, mean = 0;
        for (int i=0;i<n;i++) {
            double v = pcm[i*DOWNSAMPLE] / 32768.0;
            x[i]=v; mean+=v; sumSq+=v*v;
        }
        mean/=n;
        double rms=Math.sqrt(sumSq/n);
        if (rms<0.008) return new Result(0,0,rms);
        for (int i=0;i<n;i++) x[i]-=mean;

        double rate=sampleRate/(double)DOWNSAMPLE;
        int minTau=Math.max(2,(int)Math.floor(rate/MAX_FREQ));
        int maxTau=Math.min(n/2,(int)Math.ceil(rate/MIN_FREQ));
        double[] diff=new double[maxTau+1], cmnd=new double[maxTau+1];

        for(int tau=minTau;tau<=maxTau;tau++){
            double d=0;
            for(int i=0;i<n-tau;i++){ double q=x[i]-x[i+tau]; d+=q*q; }
            diff[tau]=d;
        }
        double running=0, best=Double.MAX_VALUE;
        int bestTau=-1;
        for(int tau=1;tau<=maxTau;tau++){
            running+=diff[tau];
            cmnd[tau]=running==0?1:diff[tau]*tau/running;
            if(tau>=minTau && cmnd[tau]<best){ best=cmnd[tau]; bestTau=tau; }
        }
        for(int tau=minTau;tau<maxTau;tau++){
            if(cmnd[tau]<YIN_THRESHOLD){
                while(tau+1<=maxTau && cmnd[tau+1]<cmnd[tau]) tau++;
                bestTau=tau; best=cmnd[tau]; break;
            }
        }
        if(bestTau<0) return new Result(0,0,rms);
        double refined=bestTau;
        if(bestTau>minTau && bestTau<maxTau){
            double s0=cmnd[bestTau-1], s1=cmnd[bestTau], s2=cmnd[bestTau+1];
            double den=2*(2*s1-s2-s0);
            if(Math.abs(den)>1e-9) refined+=(s2-s0)/den;
        }
        double freq=rate/refined, conf=Math.max(0,Math.min(1,1-best));
        if(freq<MIN_FREQ || freq>MAX_FREQ || conf<0.55) return new Result(0,conf,rms);
        return new Result(freq,conf,rms);
    }
}
