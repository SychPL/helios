package pl.mateusz.helios;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * AudioTrack-backed PCM sink: 16-bit interleaved, streaming mode, ~500 ms device buffer. Focus handling lives in the service.
 * write() holds the monitor for the duration of the blocking write, so flush()/stop() from the WebSocket thread wait at most one
 * chunk and a chunk polled before a flush is never written after it (generation check and write are one critical section).
 */
final class AudioTrackSink implements AudioSink {
    private AudioTrack track;
    private float gain=1f;
    private boolean muted,paused;
    private long frames,generation;
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
        frames=0;generation++;applyVolume();
        if(!paused)track.play(); // a track opened during a transient focus loss waits for resume()
    }
    @Override public synchronized boolean isOpen(){return track!=null;}
    @Override public synchronized long generation(){return generation;}
    @Override public synchronized boolean paused(){return paused;}
    @Override public synchronized boolean write(byte[] pcm,int offset,int length,long expected){
        if(track==null||paused||expected!=generation)return false;
        int written=track.write(pcm,offset,length,AudioTrack.WRITE_BLOCKING);
        if(written!=length)return false;
        frames+=written/frameBytes;return true;
    }
    @Override public synchronized void flush(){generation++;if(track!=null){track.pause();track.flush();if(!paused)track.play();}}
    @Override public synchronized void stop(){
        generation++;
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
