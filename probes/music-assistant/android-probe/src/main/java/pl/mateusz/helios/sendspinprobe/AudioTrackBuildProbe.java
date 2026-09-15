package pl.mateusz.helios.sendspinprobe;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import com.sendspin.protocol.AudioPlayer;
import com.sendspin.protocol.ClientState;
import com.sendspin.protocol.SendSpinClient;

public abstract class AudioTrackBuildProbe implements AudioPlayer {
    public static ClientState readState(SendSpinClient client) {
        return client.getState().getValue();
    }

    public static AudioTrack buildPcmTrack() {
        int bufferSize = AudioTrack.getMinBufferSize(48000,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (bufferSize <= 0) throw new IllegalStateException("PCM format unavailable");
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize).build();
    }
}
