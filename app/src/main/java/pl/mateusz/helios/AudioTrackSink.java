package pl.mateusz.helios;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** AudioTrack-backed PCM sink: 16-bit interleaved, streaming mode, ~500 ms device buffer. Focus handling lives in the service. */
final class AudioTrackSink implements AudioSink {
    private AudioTrack track;
    private float gain=1f;
    private boolean muted,paused;
    private long frames;
    private int frameBytes=4;
    @Override public synchronized void open(String codec,int sampleRate,int channels,int bitDepth) throws Exception {
        if(!"pcm".equals(codec)||bitDepth!=16||channels<1||channels>2)throw new IllegalArgumentException("Nieobsługiwany format: "+codec+"/"+bitDepth+"/"+channels);
        stop();
        int mask=channels==1?AudioFormat.CHANNEL_OUT_MONO:AudioFormat.CHANNEL_OUT_STEREO;
        frameBytes=2*channels;
        int min=AudioTrack.getMinBufferSize(sampleRate,mask,AudioFormat.ENCODING_PCM_16BIT);
        int buffer=Math.max(min,sampleRate*frameBytes/2);
        track=new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(mask).build())
            .setBufferSizeInBytes(buffer).setTransferMode(AudioTrack.MODE_STREAM).build();
        frames=0;applyVolume();
        if(!paused)track.play();
    }
    @Override public synchronized boolean isOpen(){return track!=null;}
    @Override public void write(byte[] pcm,int offset,int length){
        AudioTrack t;synchronized(this){t=track;}
        if(t==null)return;
        int written=t.write(pcm,offset,length,AudioTrack.WRITE_BLOCKING);
        if(written>0)synchronized(this){frames+=written/frameBytes;}
    }
    @Override public synchronized void flush(){if(track!=null){track.pause();track.flush();if(!paused)track.play();}}
    @Override public synchronized void stop(){
        if(track==null)return;
        try{track.pause();track.flush();track.stop();}catch(IllegalStateException ignored){}
        track.release();track=null;
    }
    @Override public synchronized void setGain(float value){gain=Math.max(0f,Math.min(1f,value));applyVolume();}
    @Override public synchronized void setMuted(boolean value){muted=value;applyVolume();}
    @Override public synchronized void pause(){paused=true;if(track!=null)try{track.pause();}catch(IllegalStateException ignored){}}
    @Override public synchronized void resume(){paused=false;if(track!=null)try{track.play();}catch(IllegalStateException ignored){}}
    @Override public synchronized long writtenFrames(){return frames;}
    int underruns(){AudioTrack t;synchronized(this){t=track;}return t==null?0:t.getUnderrunCount();}
    private void applyVolume(){if(track!=null)track.setVolume(muted?0f:gain);}
}
