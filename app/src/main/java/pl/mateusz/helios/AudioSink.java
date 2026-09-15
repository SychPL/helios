package pl.mateusz.helios;

/** PCM output boundary of the Sendspin client; the Android implementation wraps AudioTrack, tests use an in-memory sink. */
interface AudioSink {
    void open(String codec,int sampleRate,int channels,int bitDepth) throws Exception;
    boolean isOpen();
    /** Blocking write of interleaved PCM in the opened format. */
    void write(byte[] pcm,int offset,int length);
    /** Drops anything still queued in the output but keeps it open (stream/clear, seek). */
    void flush();
    void stop();
    void setGain(float gain);
    void setMuted(boolean muted);
    /** Only the transport's pause: output is silenced but the stream and buffer stay (audio focus loss). */
    void pause();
    void resume();
    long writtenFrames();
}
