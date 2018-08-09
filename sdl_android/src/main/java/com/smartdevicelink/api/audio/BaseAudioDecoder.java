package com.smartdevicelink.api.audio;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.util.Log;
import com.smartdevicelink.api.audio.AudioStreamManager.SampleType;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@SuppressWarnings("WeakerAccess")
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
public abstract class BaseAudioDecoder {
    private static final String TAG = AudioDecoder.class.getSimpleName();

    protected int targetSampleRate;
    protected @SampleType int targetSampleType;

    private int outputChannelCount;
    private int outputSampleRate;
    private @SampleType int outputSampleType;

    private double lastOutputSample = 0;

    private double lastOutputPresentationTimeUs = 0;
    private double lastTargetPresentationTimeUs = 0;

    protected MediaExtractor extractor;
    protected MediaCodec decoder;
    protected File audioFile;
    protected AudioDecoderListener listener;

    public BaseAudioDecoder(File audioFile, int sampleRate, @SampleType int sampleType, AudioDecoderListener listener) {
        this.audioFile = audioFile;
        this.listener = listener;
        targetSampleRate = sampleRate;
        targetSampleType = sampleType;
    }

    protected void initMediaComponents() throws Exception {
        extractor = new MediaExtractor();
        extractor.setDataSource(audioFile.getPath());
        MediaFormat format = null;
        String mime = null;

        // Select the first audio track we find.
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; ++i) {
            MediaFormat f = extractor.getTrackFormat(i);
            String m = f.getString(MediaFormat.KEY_MIME);
            if (m.startsWith("audio/")) {
                format = f;
                mime = m;
                extractor.selectTrack(i);
                break;
            }
        }

        if (mime == null || format == null) {
            throw new Exception("The audio file " + audioFile.getPath() + " doesn't contain an audio track.");
        }

        decoder = MediaCodec.createDecoderByType(mime);
        decoder.configure(format, null, null, 0);
    }

    private Double sampleAtTargetTime(double lastOutputSample, SampleBuffer outputSampleBuffer, double outputPresentationTimeUs, double outputDurationPerSampleUs, double targetPresentationTimeUs) {
        double timeDiff = targetPresentationTimeUs - outputPresentationTimeUs;
        double index = timeDiff / outputDurationPerSampleUs;

        // the "last known sample" allows an index from -1.0 to 0
        // the index cannot exceed the last sample. it must be stored to become the "last known sample" in the next iteration
        if (index < -1.0 || Math.ceil(index) >= outputSampleBuffer.limit()) {
            return null;
        }

        if (index == -1.0) {
            // the index points exactly to the last known sample
            return lastOutputSample;
        } else if (index % 1 == 0) {
            // index has no digits. therefore current index points to a known sample
            return outputSampleBuffer.get((int) index);
        } else {
            // the first sample can be the last known one
            double first = index < 0.0 ? lastOutputSample : outputSampleBuffer.get((int) index);
            double second = outputSampleBuffer.get((int) Math.ceil(index));
            double rel = index % 1;

            // if the relative is between -1 and 0
            if (rel < 0.0) {
                rel = 1 + rel;
            }

            return first + (second - first) * rel;
        }
    }

    protected MediaCodec.BufferInfo onInputBufferAvailable(@NonNull MediaExtractor extractor, @NonNull ByteBuffer inputBuffer) {
        long sampleTime = extractor.getSampleTime();
        int counter = 0;
        int result = 0;
        int maxresult = 0;
        boolean advanced = false;

        do {
            result = extractor.readSampleData(inputBuffer, counter);
            if (result >= 0) {
                advanced = extractor.advance();
                maxresult = Math.max(maxresult, result);
                counter += result;
            }
        } while (result >= 0 && advanced && inputBuffer.capacity() - inputBuffer.limit() > maxresult);
        // the remaining capacity should be more than enough for another sample data block

        // queue the input buffer. At end of file counter will be 0 and flags marks end of stream
        // offset MUST be 0. The output buffer code cannot handle offsets
        // result < 0 means the end of the file input is reached
        int flags = advanced ? 0 : MediaCodec.BUFFER_FLAG_END_OF_STREAM;

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        bufferInfo.set(0, counter, sampleTime, flags);

        return bufferInfo;
    }

    protected SampleBuffer onOutputBufferAvailable(@NonNull ByteBuffer outputBuffer) {
        double outputPresentationTimeUs = lastOutputPresentationTimeUs;
        double outputDurationPerSampleUs = 1000000.0 / (double)outputSampleRate;

        double targetPresentationTimeUs = lastTargetPresentationTimeUs;
        double targetDurationPerSampleUs = 1000000.0 / (double)targetSampleRate;

        // wrap the output buffer to make it provide audio samples
        SampleBuffer outputSampleBuffer = SampleBuffer.wrap(outputBuffer, outputSampleType, outputChannelCount, (long)outputPresentationTimeUs);
        outputSampleBuffer.position(0);

        // the buffer size is related to the output and target sample rate
        // add 2 samples to round up and add an extra sample
        int sampleSize = outputSampleBuffer.limit() * targetSampleRate / outputSampleRate + 2;

        SampleBuffer targetSampleBuffer = SampleBuffer.allocate(sampleSize, targetSampleType, ByteOrder.LITTLE_ENDIAN, (long)targetPresentationTimeUs);
        Double sample;

        do {
            sample = sampleAtTargetTime(lastOutputSample, outputSampleBuffer, outputPresentationTimeUs, outputDurationPerSampleUs, targetPresentationTimeUs);
            if (sample != null) {
                targetSampleBuffer.put(sample);
                targetPresentationTimeUs += targetDurationPerSampleUs;
            }
        } while (sample != null);

        lastTargetPresentationTimeUs = targetPresentationTimeUs;
        lastOutputPresentationTimeUs += outputSampleBuffer.limit() * outputDurationPerSampleUs;
        lastOutputSample = outputSampleBuffer.get(outputSampleBuffer.limit() - 1);

        targetSampleBuffer.limit(targetSampleBuffer.position());
        targetSampleBuffer.position(0);

        return targetSampleBuffer;
    }

    protected void onOutputFormatChanged(@NonNull MediaFormat mediaFormat) {
        if (mediaFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            outputChannelCount = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        }

        if (mediaFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            outputSampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && mediaFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            int key = mediaFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
            switch (key) {
                case AudioFormat.ENCODING_PCM_8BIT:
                    outputSampleType = SampleType.UNSIGNED_8_BIT;
                    break;
                case AudioFormat.ENCODING_PCM_FLOAT:
                    outputSampleType = SampleType.FLOAT;
                    break;
                case AudioFormat.ENCODING_PCM_16BIT:
                default:
                    // by default we fallback to signed 16 bit samples
                    outputSampleType = SampleType.SIGNED_16_BIT;
                    break;
            }
        } else {
            outputSampleType = SampleType.SIGNED_16_BIT;
        }
    }

    protected void onMediaCodecError(@NonNull MediaCodec.CodecException e) {
        Log.e(TAG, "MediaCodec.onError: " + e.getLocalizedMessage());
        if (listener != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                listener.onDecoderError(e);
            } else {
                listener.onDecoderError(new Exception("Error decoding audio file"));
            }
        }
    }

    public abstract void start();

    public void stop() {
        if (decoder != null) {
            decoder.stop();
            decoder.release();
            decoder = null;
        }

        if (extractor != null) {
            extractor = null;
        }
    }
}
