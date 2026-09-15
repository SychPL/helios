package io.homeassistant.companion.android.microwakeword;

import java.nio.ByteBuffer;

/** Java JNI declarations matching the pinned HA native library. */
public final class MicroWakeWord {
    public static native long nativeCreate(ByteBuffer model, int rate, int step, float cutoff, int window);
    public static native boolean nativeProcessAudio(long handle, short[] samples);
    public static native void nativeReset(long handle);
    public static native void nativeDestroy(long handle);
}
