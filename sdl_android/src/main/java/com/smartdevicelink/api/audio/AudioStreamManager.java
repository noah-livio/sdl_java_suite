package com.smartdevicelink.api.audio;

import android.net.rtp.AudioStream;
import android.os.Build;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.annotation.StringDef;
import android.util.Log;

import com.smartdevicelink.SdlConnection.SdlSession;
import com.smartdevicelink.api.BaseSubManager;
import com.smartdevicelink.api.CompletionListener;
import com.smartdevicelink.api.StreamingStateMachine;
import com.smartdevicelink.protocol.enums.SessionType;
import com.smartdevicelink.proxy.interfaces.IAudioStreamListener;
import com.smartdevicelink.proxy.interfaces.ISdl;
import com.smartdevicelink.proxy.interfaces.ISdlServiceListener;
import com.smartdevicelink.proxy.rpc.AudioPassThruCapabilities;
import com.smartdevicelink.proxy.rpc.enums.AudioType;
import com.smartdevicelink.proxy.rpc.enums.BitsPerSample;
import com.smartdevicelink.proxy.rpc.enums.SamplingRate;
import com.smartdevicelink.proxy.rpc.enums.SystemCapabilityType;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.LinkedList;
import java.util.Queue;

@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
public class AudioStreamManager extends BaseSubManager {
    private static final String TAG = AudioStreamManager.class.getSimpleName();

    private IAudioStreamListener sdlAudioStream;
    private int sdlSampleRate;
    private @SampleType int sdlSampleType;
    private final Queue<BaseAudioDecoder> queue;

    private StreamingStateMachine streamingStateMachine;

    // INTERNAL INTERFACE

    private final ISdlServiceListener serviceListener = new ISdlServiceListener() {
        @Override
        public void onServiceStarted(SdlSession session, SessionType type, boolean isEncrypted) {
            if (SessionType.PCM.equals(type)) {
                sdlAudioStream = session.startAudioStream();
                streamingStateMachine.transitionToState(StreamingStateMachine.STARTED);
            }
        }

        @Override
        public void onServiceEnded(SdlSession session, SessionType type) {
            if (SessionType.PCM.equals(type)) {
                if (internalInterface != null) {
                    session.stopAudioStream();
                    sdlAudioStream = null;
                    internalInterface.removeServiceListener(SessionType.PCM, this);
                    streamingStateMachine.transitionToState(StreamingStateMachine.NONE);
                }
            }
        }

        @Override
        public void onServiceError(SdlSession session, SessionType type, String reason) {
            Log.e(TAG, "OnServiceError: " + reason);
        }
    };

    public AudioStreamManager(@NonNull ISdl internalInterface) {
        super(internalInterface);
        this.queue = new LinkedList<>();

        streamingStateMachine = new StreamingStateMachine();
        transitionToState(BaseSubManager.READY);
    }

    @Override
    public void dispose() {
        stopAudioStream();

        streamingStateMachine.transitionToState(StreamingStateMachine.NONE);
    }

    public void startAudioStream(boolean encrypted) {
        // audio stream cannot be started without a connected internal interface
        if (internalInterface == null || !internalInterface.isConnected()) {
            return;
        }

        // streaming state must be NONE (starting the service is ready. starting stream is started)
        if (streamingStateMachine.getState() != StreamingStateMachine.NONE) {
            return;
        }

        try {
            AudioPassThruCapabilities capabilities = (AudioPassThruCapabilities)internalInterface.getCapability(SystemCapabilityType.PCM_STREAMING);

            switch (capabilities.getSamplingRate()) {
                case _8KHZ:
                    sdlSampleRate = 8000;
                    break;
                case _16KHZ:
                    sdlSampleRate = 16000;
                    break;
                case _22KHZ:
                    // common sample rate is 22050, not 22000
                    // see https://en.wikipedia.org/wiki/Sampling_(signal_processing)#Audio_sampling
                    sdlSampleRate = 22050;
                    break;
                case _44KHZ:
                    // 2x 22050 is 44100
                    // see https://en.wikipedia.org/wiki/Sampling_(signal_processing)#Audio_sampling
                    sdlSampleRate = 44100;
                    break;
            }

            switch (capabilities.getBitsPerSample()) {
                case _8_BIT:
                    sdlSampleType = SampleType.UNSIGNED_8_BIT;
                    break;
                case _16_BIT:
                    sdlSampleType = SampleType.SIGNED_16_BIT;
                    break;
            }

            streamingStateMachine.transitionToState(StreamingStateMachine.READY);

            internalInterface.addServiceListener(SessionType.PCM, this.serviceListener);
            internalInterface.startAudioService(encrypted);
        } catch (Exception e) {
            Log.e(TAG, "error starting audio stream", e);
            streamingStateMachine.transitionToState(StreamingStateMachine.ERROR);
        }
    }

    public void stopAudioStream() {
        if (internalInterface == null || !internalInterface.isConnected()) {
            return;
        }

        // streaming state must be STARTED (starting the service is ready. starting stream is started)
        if (streamingStateMachine.getState() != StreamingStateMachine.STARTED) {
            return;
        }

        internalInterface.stopAudioService();

        streamingStateMachine.transitionToState(StreamingStateMachine.STOPPED);
    }

    public void pushAudioFile(File audioFile, final CompletionListener completionListener) {
        // streaming state must be STARTED (starting the service is ready. starting stream is started)
        if (streamingStateMachine.getState() != StreamingStateMachine.STARTED) {
            return;
        }

        BaseAudioDecoder decoder;
        AudioDecoderListener decoderListener = new AudioDecoderListener() {
            @Override
            public void onAudioDataAvailable(SampleBuffer buffer) {
                sdlAudioStream.sendAudio(buffer.getByteBuffer(), buffer.getPresentationTimeUs());
            }

            @Override
            public void onDecoderFinish(boolean success) {
                completionListener.onComplete(success);

                synchronized (queue) {
                    // remove throws an exception if the queue is empty. The decoder of this listener
                    // should still be in this queue so we should be fine by just removing it
                    // if the queue is empty than we have a bug somewhere in the code
                    // and we deserve the crash...
                    queue.remove();

                    // if the queue contains more items then start the first one (without removing it)
                    if (queue.size() > 0) {
                        queue.element().start();
                    }
                }
            }

            @Override
            public void onDecoderError(Exception e) {
                Log.e(TAG, "decoder error", e);
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {

            decoder = new AudioDecoder(audioFile, sdlSampleRate, sdlSampleType, decoderListener);
        } else {
            // this BaseAudioDecoder subclass uses methods deprecated with api 21
            decoder = new AudioDecoderCompat(audioFile, sdlSampleRate, sdlSampleType, decoderListener);
        }

        synchronized (queue) {
            queue.add(decoder);

            if (queue.size() == 1) {
                decoder.start();
            }
        }
    }

    @IntDef({SampleType.UNSIGNED_8_BIT, SampleType.SIGNED_16_BIT, SampleType.FLOAT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SampleType {
        // ref https://developer.android.com/reference/android/media/AudioFormat "Encoding" section
        // The audio sample is a 8 bit unsigned integer in the range [0, 255], with a 128 offset for zero.
        // This is typically stored as a Java byte in a byte array or ByteBuffer. Since the Java byte is
        // signed, be careful with math operations and conversions as the most significant bit is inverted.
        //
        // The unsigned byte range is [0, 255] and should be converted to double [-1.0, 1.0]
        // The 8 bits of the byte are easily converted to int by using bitwise operator
        int UNSIGNED_8_BIT = Byte.SIZE >> 3;

        // ref https://developer.android.com/reference/android/media/AudioFormat "Encoding" section
        // The audio sample is a 16 bit signed integer typically stored as a Java short in a short array,
        // but when the short is stored in a ByteBuffer, it is native endian (as compared to the default Java big endian).
        // The short has full range from [-32768, 32767], and is sometimes interpreted as fixed point Q.15 data.
        //
        // the conversion is slightly easier from [-32768, 32767] to [-1.0, 1.0]
        int SIGNED_16_BIT = Short.SIZE >> 3;

        // ref https://developer.android.com/reference/android/media/AudioFormat "Encoding" section
        // Introduced in API Build.VERSION_CODES.LOLLIPOP, this encoding specifies that the audio sample
        // is a 32 bit IEEE single precision float. The sample can be manipulated as a Java float in a
        // float array, though within a ByteBuffer it is stored in native endian byte order. The nominal
        // range of ENCODING_PCM_FLOAT audio data is [-1.0, 1.0].
        int FLOAT = Float.SIZE >> 3;
    }

    /*
    @IntDef({BitsPerSample.EIGHT_BIT, BitsPerSample.SIXTEEN_BIT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface BitsPerSample {
        int EIGHT_BIT = 8;
        int SIXTEEN_BIT = 16;
    }

    @StringDef({SamplingRate.EIGHT_KHZ, SamplingRate.SIXTEEN_KHZ, SamplingRate.TWENTY_TWO_KHZ, SamplingRate.FOURTY_FOUR_KHX})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SamplingRate {
        String EIGHT_KHZ = "8KHZ";
        String SIXTEEN_KHZ = "16KHZ";
        String TWENTY_TWO_KHZ = "22KHZ";
        String FOURTY_FOUR_KHX = "44KHZ";
    }*/
}
