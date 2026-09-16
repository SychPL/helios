package pl.mateusz.helios;

/** PCM output boundary of the Sendspin client; the Android implementation wraps AudioTrack, tests use an in-memory sink. */
interface AudioSink {
    void open(String codec,int sampleRate,int channels,int bitDepth) throws Exception;
    boolean isOpen();
    /** Grows on open(), flush() and stop(): a chunk polled before one of those must not be written after it. */
    long generation();
    /** True while the transport's pause holds the output (audio focus loss): writes are refused and idle time does not count. */
    boolean paused();
    /** Blocking write of interleaved PCM; true only when the whole chunk reached the open, unpaused output under the given generation. */
    boolean write(byte[] pcm,int offset,int length,long generation);
    /** Drops anything still queued in the output but keeps it open (stream/clear, seek). */
    void flush();
    void stop();
    void setGain(float gain);
    void setMuted(boolean muted);
    /** Only the transport's pause: output is silenced but the stream and buffer stay (audio focus loss); survives stop()/open() until resume(). */
    void pause();
    void resume();
    long writtenFrames();
}
