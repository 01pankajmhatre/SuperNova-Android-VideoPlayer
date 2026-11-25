package com.archos.mediacenter.video.browser.dlna;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.PriorityQueue;

/**
 * Live remuxing / transcoding of one video into an MPEG transport stream, written straight to the
 * HTTP connection (so it starts playing at once and needs no temporary file).
 *
 * <ul>
 *   <li>Video H.264, H.265 and MPEG-2 are copied (remux) unless "re-encode video" is on; any other
 *       codec (VP8/VP9/AV1/MPEG-4/VC-1 ...) or a forced re-encode goes decoder -&gt; H.264 encoder.</li>
 *   <li>Audio AAC-LC, AC-3, E-AC-3, MP3 and MP2 are copied unless "re-encode audio" is on; any other
 *       codec with a decoder on this device (Vorbis, Opus, FLAC, PCM ...) is converted to stereo AAC.</li>
 *   <li>Time seeking: a start position can be given (DLNA TimeSeekRange).</li>
 * </ul>
 * Uses only the Android framework (MediaExtractor + MediaCodec); no extra library.
 */
final class DlnaTranscoder implements DlnaTranscodeSession {

    static final class Options {
        boolean reencodeVideo;
        boolean reencodeAudio;
        int videoKbps = 4000;
        int audioKbps = 160;
        int maxHeight;          // 0 = keep the original size (FFmpeg only)
        long startUs;
    }

    interface Running {
        boolean isRunning();
    }

    private static final String VIDEO_AVC = "video/avc";
    private static final String VIDEO_HEVC = "video/hevc";
    private static final String VIDEO_MPEG2 = "video/mpeg2";
    private static final String AUDIO_AAC = "audio/mp4a-latm";
    private static final long PAD_US = 2_000_000L;          // keeps every timestamp positive
    private static final int[] AAC_RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350};

    private final Context mContext;
    private final Uri mUri;
    private final Options mOptions;
    private final Running mRunning;
    private final MediaExtractor mExtractor = new MediaExtractor();
    private ParcelFileDescriptor mPfd;

    private int mVideoTrack = -1;
    private int mAudioTrack = -1;
    private MediaFormat mVideoFormat;
    private MediaFormat mAudioFormat;
    private boolean mVideoCopy;
    private boolean mAudioCopy;
    private long mDurationUs;
    private long mBaseUs;
    private TsWriter mTs;

    DlnaTranscoder(Context context, Uri uri, Options options, Running running) {
        mContext = context.getApplicationContext();
        mUri = uri;
        mOptions = options;
        mRunning = running;
    }

    @Override
    public long getDurationUs() {
        return mDurationUs;
    }

    /** Opens the file and chooses the tracks; throws if there is no video that can be sent. */
    @Override
    public void open() throws IOException {
        mPfd = mContext.getContentResolver().openFileDescriptor(mUri, "r");
        if (mPfd == null) throw new IOException("cannot open file");
        mExtractor.setDataSource(mPfd.getFileDescriptor());
        for (int i = 0; i < mExtractor.getTrackCount(); i++) {
            MediaFormat f = mExtractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime == null) continue;
            if (f.containsKey(MediaFormat.KEY_DURATION)) {
                mDurationUs = Math.max(mDurationUs, f.getLong(MediaFormat.KEY_DURATION));
            }
            if (mime.startsWith("video/") && mVideoTrack < 0) {
                boolean copyable = isVideoCopyable(mime);
                if (copyable && !mOptions.reencodeVideo) {
                    mVideoTrack = i; mVideoFormat = f; mVideoCopy = true;
                } else if (hasDecoder(f)) {
                    mVideoTrack = i; mVideoFormat = f; mVideoCopy = false;
                } else if (copyable) {
                    mVideoTrack = i; mVideoFormat = f; mVideoCopy = true;
                }
            } else if (mime.startsWith("audio/") && mAudioTrack < 0) {
                boolean copyable = isAudioCopyable(mime, f);
                if (copyable && !mOptions.reencodeAudio) {
                    mAudioTrack = i; mAudioFormat = f; mAudioCopy = true;
                } else if (hasDecoder(f)) {
                    mAudioTrack = i; mAudioFormat = f; mAudioCopy = false;
                } else if (copyable) {
                    mAudioTrack = i; mAudioFormat = f; mAudioCopy = true;
                }
            }
        }
        if (mVideoTrack < 0) throw new IOException("no video track that can be sent");
    }

    private static boolean isVideoCopyable(String mime) {
        return VIDEO_AVC.equals(mime) || VIDEO_HEVC.equals(mime) || VIDEO_MPEG2.equals(mime);
    }

    private static boolean isAudioCopyable(String mime, MediaFormat f) {
        if (AUDIO_AAC.equals(mime)) {
            ByteBuffer csd = f.containsKey("csd-0") ? f.getByteBuffer("csd-0") : null;
            if (csd == null || csd.remaining() < 2) return true;
            int objectType = (csd.get(csd.position()) & 0xFF) >> 3;
            return objectType >= 1 && objectType <= 4;          // plain AAC (no SBR / PS extension)
        }
        return "audio/ac3".equals(mime) || "audio/eac3".equals(mime)
                || "audio/mpeg".equals(mime) || "audio/mpeg-L2".equals(mime);
    }

    private static boolean hasDecoder(MediaFormat format) {
        try {
            return new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------ run

    /** Writes the whole stream; returns when the file ends, the client leaves or the server stops. */
    @Override
    public void stream(OutputStream rawOut) throws IOException {
        BufferedOutputStream out = new BufferedOutputStream(rawOut, 188 * 64);
        int audioType = 0;
        int audioStreamId = 0xC0;
        if (mAudioTrack >= 0) {
            if (!mAudioCopy) {
                audioType = 0x0F;
            } else {
                String mime = mAudioFormat.getString(MediaFormat.KEY_MIME);
                if (AUDIO_AAC.equals(mime)) audioType = 0x0F;
                else if ("audio/ac3".equals(mime)) { audioType = 0x81; audioStreamId = 0xBD; }
                else if ("audio/eac3".equals(mime)) { audioType = 0x87; audioStreamId = 0xBD; }
                else if ("audio/mpeg-L2".equals(mime)) audioType = 0x04;
                else audioType = 0x03;
            }
        }
        int videoType = 0x1B;
        if (mVideoCopy) {
            String mime = mVideoFormat.getString(MediaFormat.KEY_MIME);
            videoType = VIDEO_HEVC.equals(mime) ? 0x24 : VIDEO_MPEG2.equals(mime) ? 0x02 : 0x1B;
        }
        mTs = new TsWriter(out, videoType, audioType, audioStreamId);

        mExtractor.selectTrack(mVideoTrack);
        if (mAudioTrack >= 0) mExtractor.selectTrack(mAudioTrack);
        if (mOptions.startUs > 0) mExtractor.seekTo(mOptions.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        mBaseUs = Math.max(0, mExtractor.getSampleTime());

        Sink video = null;
        Sink audio = null;
        try {
            video = mVideoCopy ? new VideoCopy(mVideoFormat) : new VideoEncode(mVideoFormat);
            if (mAudioTrack >= 0) audio = mAudioCopy ? new AudioCopy(mAudioFormat) : new AudioEncode(mAudioFormat);

            ByteBuffer buffer = ByteBuffer.allocate(8 * 1024 * 1024);
            byte[] bytes = new byte[buffer.capacity()];
            while (mRunning.isRunning()) {
                int track = mExtractor.getSampleTrackIndex();
                if (track < 0) break;
                buffer.clear();
                int n = mExtractor.readSampleData(buffer, 0);
                if (n < 0) break;
                long pts = mExtractor.getSampleTime();
                int flags = mExtractor.getSampleFlags();
                buffer.position(0);
                buffer.get(bytes, 0, n);
                if (track == mVideoTrack) video.sample(bytes, n, pts, flags);
                else if (track == mAudioTrack && audio != null) audio.sample(bytes, n, pts, flags);
                mExtractor.advance();
            }
            if (mRunning.isRunning()) {
                video.finish();
                if (audio != null) audio.finish();
            }
            out.flush();
        } catch (RuntimeException e) {
            throw new IOException("transcoding failed: " + e);
        } finally {
            if (video != null) video.release();
            if (audio != null) audio.release();
        }
    }

    @Override
    public void close() {
        try {
            mExtractor.release();
        } catch (RuntimeException ignored) {
            // already released
        }
        if (mPfd != null) {
            try {
                mPfd.close();
            } catch (IOException ignored) {
                // closing
            }
            mPfd = null;
        }
    }

    /** 90 kHz transport stream time of a sample time. */
    private long t90(long us) {
        return Math.max(0, us - mBaseUs + PAD_US) * 9 / 100;
    }

    private interface Sink {
        void sample(byte[] data, int size, long ptsUs, int flags) throws IOException;

        void finish() throws IOException;

        void release();
    }

    private static long frameDurationUs(MediaFormat f) {
        float fps = 0;
        try {
            if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                try {
                    fps = f.getInteger(MediaFormat.KEY_FRAME_RATE);
                } catch (ClassCastException e) {
                    fps = f.getFloat(MediaFormat.KEY_FRAME_RATE);
                }
            }
        } catch (RuntimeException ignored) {
            fps = 0;
        }
        if (fps < 5 || fps > 240) fps = 24;
        return (long) (1_000_000f / fps);
    }

    private static byte[] bytesOf(MediaFormat f, String key) {
        if (!f.containsKey(key)) return null;
        ByteBuffer b = f.getByteBuffer(key);
        if (b == null) return null;
        byte[] data = new byte[b.remaining()];
        b.duplicate().get(data);
        return data;
    }

    private static boolean startsWithStartCode(byte[] d, int n) {
        return n > 4 && d[0] == 0 && d[1] == 0 && (d[2] == 1 || (d[2] == 0 && d[3] == 1));
    }

    // ------------------------------------------------------------------------- video: remux

    /** Copies H.264 / H.265 / MPEG-2 samples; converts length-prefixed NAL units to Annex B. */
    private final class VideoCopy implements Sink {
        private final boolean mHevc;
        private final boolean mMpeg2;
        private final byte[] mCsd0;
        private final byte[] mCsd1;
        private final long mFrameUs;
        private int mLengthSize;
        private final ArrayDeque<Object[]> mQueue = new ArrayDeque<>();
        private final PriorityQueue<Long> mTimes = new PriorityQueue<>();

        VideoCopy(MediaFormat f) {
            String mime = f.getString(MediaFormat.KEY_MIME);
            mHevc = VIDEO_HEVC.equals(mime);
            mMpeg2 = VIDEO_MPEG2.equals(mime);
            mCsd0 = bytesOf(f, "csd-0");
            mCsd1 = bytesOf(f, "csd-1");
            mFrameUs = frameDurationUs(f);
        }

        @Override
        public void sample(byte[] d, int n, long ptsUs, int flags) throws IOException {
            boolean key = (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
            byte[] unit = mMpeg2 ? java.util.Arrays.copyOf(d, n) : toAnnexB(d, n, key);
            mQueue.add(new Object[]{unit, ptsUs, key});
            mTimes.add(ptsUs);
            if (mQueue.size() >= 6) emit();
        }

        /** Samples are in decoding order but carry only a presentation time: DTS is rebuilt from the sorted times. */
        private void emit() throws IOException {
            Object[] f = mQueue.poll();
            long pts = (Long) f[1];
            long dts = Math.min(mTimes.poll() - 5 * mFrameUs, pts);
            byte[] unit = (byte[]) f[0];
            mTs.video(unit, unit.length, t90(pts), t90(dts), (Boolean) f[2]);
        }

        @Override
        public void finish() throws IOException {
            while (!mQueue.isEmpty()) emit();
        }

        @Override
        public void release() {
            mQueue.clear();
        }

        private byte[] toAnnexB(byte[] d, int n, boolean key) {
            if (startsWithStartCode(d, n)) return java.util.Arrays.copyOf(d, n);   // already Annex B (TS source)
            ByteArrayOutputStream o = new ByteArrayOutputStream(n + 256);
            if (mHevc) {
                o.write(0); o.write(0); o.write(0); o.write(1); o.write(0x46); o.write(0x01); o.write(0x50);
            } else {
                o.write(0); o.write(0); o.write(0); o.write(1); o.write(0x09); o.write(0xF0);
            }
            if (key) {
                if (mCsd0 != null) o.write(mCsd0, 0, mCsd0.length);
                if (mCsd1 != null) o.write(mCsd1, 0, mCsd1.length);
            }
            if (mLengthSize == 0) mLengthSize = detectLengthSize(d, n);
            int pos = 0;
            while (pos + mLengthSize <= n) {
                int len = 0;
                for (int i = 0; i < mLengthSize; i++) len = (len << 8) | (d[pos + i] & 0xFF);
                pos += mLengthSize;
                if (len <= 0 || pos + len > n) break;
                o.write(0); o.write(0); o.write(0); o.write(1);
                o.write(d, pos, len);
                pos += len;
            }
            return o.toByteArray();
        }

        private int detectLengthSize(byte[] d, int n) {
            int[] sizes = {4, 2, 1, 3};
            for (int size : sizes) {
                int pos = 0;
                boolean ok = true;
                while (pos + size <= n) {
                    int len = 0;
                    for (int i = 0; i < size; i++) len = (len << 8) | (d[pos + i] & 0xFF);
                    pos += size;
                    if (len <= 0 || pos + len > n) { ok = false; break; }
                    pos += len;
                }
                if (ok && pos == n) return size;
            }
            return 4;
        }
    }

    // ------------------------------------------------------------------------ video: transcode

    /** Decodes to the input surface of an H.264 encoder. */
    private final class VideoEncode implements Sink {
        private final MediaCodec mDecoder;
        private final MediaCodec mEncoder;
        private final Surface mSurface;
        private final MediaCodec.BufferInfo mDecInfo = new MediaCodec.BufferInfo();
        private final MediaCodec.BufferInfo mEncInfo = new MediaCodec.BufferInfo();
        private byte[] mConfig;
        private boolean mEosSignalled;
        private boolean mDone;

        VideoEncode(MediaFormat f) throws IOException {
            int width = f.getInteger(MediaFormat.KEY_WIDTH) & ~1;
            int height = f.getInteger(MediaFormat.KEY_HEIGHT) & ~1;
            int fps = (int) Math.max(5, Math.min(60, 1_000_000L / frameDurationUs(f)));
            MediaFormat ef = MediaFormat.createVideoFormat(VIDEO_AVC, width, height);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, mOptions.videoKbps * 1000);
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            mEncoder = MediaCodec.createEncoderByType(VIDEO_AVC);
            mEncoder.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mSurface = mEncoder.createInputSurface();
            mEncoder.start();
            mDecoder = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
            mDecoder.configure(f, mSurface, null, 0);
            mDecoder.start();
        }

        @Override
        public void sample(byte[] d, int n, long ptsUs, int flags) throws IOException {
            int index = nextInput();
            ByteBuffer in = mDecoder.getInputBuffer(index);
            in.clear();
            in.put(d, 0, n);
            mDecoder.queueInputBuffer(index, 0, n, ptsUs, 0);
            drain();
        }

        private int nextInput() throws IOException {
            int index;
            while ((index = mDecoder.dequeueInputBuffer(10_000)) < 0) {
                drain();
                if (!mRunning.isRunning()) throw new IOException("stopped");
            }
            return index;
        }

        private void drain() throws IOException {
            while (true) {
                int o = mDecoder.dequeueOutputBuffer(mDecInfo, 0);
                if (o < 0) break;
                if (mDecInfo.size > 0) mDecoder.releaseOutputBuffer(o, mDecInfo.presentationTimeUs * 1000L);
                else mDecoder.releaseOutputBuffer(o, false);
                if ((mDecInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 && !mEosSignalled) {
                    mEosSignalled = true;
                    mEncoder.signalEndOfInputStream();
                }
            }
            while (true) {
                int e = mEncoder.dequeueOutputBuffer(mEncInfo, 0);
                if (e == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                if (e < 0) continue;                           // format / buffers changed
                ByteBuffer b = mEncoder.getOutputBuffer(e);
                byte[] data = new byte[mEncInfo.size];
                if (mEncInfo.size > 0) {
                    b.position(mEncInfo.offset);
                    b.limit(mEncInfo.offset + mEncInfo.size);
                    b.get(data);
                }
                int flags = mEncInfo.flags;
                long pts = mEncInfo.presentationTimeUs;
                mEncoder.releaseOutputBuffer(e, false);
                if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    mConfig = data;
                } else if (data.length > 0) {
                    boolean key = (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    ByteArrayOutputStream o = new ByteArrayOutputStream(data.length + 128);
                    o.write(0); o.write(0); o.write(0); o.write(1); o.write(0x09); o.write(0xF0);
                    boolean hasSps = data.length > 4 && (data[4] & 0x1F) == 7;
                    if (key && mConfig != null && !hasSps) o.write(mConfig, 0, mConfig.length);
                    o.write(data, 0, data.length);
                    byte[] unit = o.toByteArray();
                    mTs.video(unit, unit.length, t90(pts), t90(pts), key);
                }
                if ((flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) mDone = true;
            }
        }

        @Override
        public void finish() throws IOException {
            int index = nextInput();
            mDecoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            for (int i = 0; i < 4000 && !mDone && mRunning.isRunning(); i++) {
                drain();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }

        @Override
        public void release() {
            try { mDecoder.stop(); } catch (RuntimeException ignored) { /* released */ }
            try { mDecoder.release(); } catch (RuntimeException ignored) { /* released */ }
            try { mEncoder.stop(); } catch (RuntimeException ignored) { /* released */ }
            try { mEncoder.release(); } catch (RuntimeException ignored) { /* released */ }
            mSurface.release();
        }
    }

    // ------------------------------------------------------------------------- audio: remux

    private final class AudioCopy implements Sink {
        private final boolean mAac;
        private final int mProfile;
        private final int mRateIndex;
        private final int mChannels;

        AudioCopy(MediaFormat f) {
            mAac = AUDIO_AAC.equals(f.getString(MediaFormat.KEY_MIME));
            int objectType = 2;
            int rate = f.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? f.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
            int channels = f.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            byte[] csd = bytesOf(f, "csd-0");
            if (csd != null && csd.length >= 2) {
                objectType = (csd[0] & 0xFF) >> 3;
                int index = ((csd[0] & 7) << 1) | ((csd[1] & 0xFF) >> 7);
                if (index < AAC_RATES.length) rate = AAC_RATES[index];
                int config = (csd[1] >> 3) & 0x0F;
                if (config > 0 && config < 8) channels = config == 7 ? 8 : config;
            }
            mProfile = Math.max(0, Math.min(3, objectType - 1));
            mRateIndex = rateIndex(rate);
            mChannels = channels == 8 ? 7 : channels;
        }

        @Override
        public void sample(byte[] d, int n, long ptsUs, int flags) throws IOException {
            if (!mAac || (n > 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xF0) == 0xF0)) {
                mTs.audio(d, n, t90(ptsUs));
                return;
            }
            byte[] frame = new byte[n + 7];
            writeAdts(frame, n + 7, mProfile, mRateIndex, mChannels);
            System.arraycopy(d, 0, frame, 7, n);
            mTs.audio(frame, frame.length, t90(ptsUs));
        }

        @Override
        public void finish() {
        }

        @Override
        public void release() {
        }
    }

    private static int rateIndex(int rate) {
        for (int i = 0; i < AAC_RATES.length; i++) if (AAC_RATES[i] == rate) return i;
        return 3;
    }

    private static void writeAdts(byte[] b, int frameLength, int profile, int rateIndex, int channels) {
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xF1;
        b[2] = (byte) ((profile << 6) | (rateIndex << 2) | (channels >> 2));
        b[3] = (byte) (((channels & 3) << 6) | (frameLength >> 11));
        b[4] = (byte) ((frameLength >> 3) & 0xFF);
        b[5] = (byte) (((frameLength & 7) << 5) | 0x1F);
        b[6] = (byte) 0xFC;
    }

    // ----------------------------------------------------------------------- audio: transcode

    /** Decodes to PCM, mixes down to stereo when needed and encodes AAC-LC. */
    private final class AudioEncode implements Sink {
        private final MediaCodec mDecoder;
        private MediaCodec mEncoder;
        private final MediaCodec.BufferInfo mDecInfo = new MediaCodec.BufferInfo();
        private final MediaCodec.BufferInfo mEncInfo = new MediaCodec.BufferInfo();
        private int mRate = 48000;
        private int mInChannels = 2;
        private int mOutChannels = 2;
        private long mStartPts = -1;
        private long mFrames;
        private boolean mEncoderEos;

        AudioEncode(MediaFormat f) throws IOException {
            mDecoder = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
            mDecoder.configure(f, null, null, 0);
            mDecoder.start();
        }

        private void setupEncoder(MediaFormat out) throws IOException {
            if (mEncoder != null) return;
            if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) mRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) mInChannels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            mOutChannels = mInChannels >= 2 ? 2 : 1;
            MediaFormat ef = MediaFormat.createAudioFormat(AUDIO_AAC, mRate, mOutChannels);
            ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, mOptions.audioKbps * 1000);
            ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768);
            mEncoder = MediaCodec.createEncoderByType(AUDIO_AAC);
            mEncoder.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mEncoder.start();
        }

        @Override
        public void sample(byte[] d, int n, long ptsUs, int flags) throws IOException {
            int index;
            while ((index = mDecoder.dequeueInputBuffer(10_000)) < 0) {
                drain();
                if (!mRunning.isRunning()) throw new IOException("stopped");
            }
            ByteBuffer in = mDecoder.getInputBuffer(index);
            in.clear();
            in.put(d, 0, n);
            mDecoder.queueInputBuffer(index, 0, n, ptsUs, 0);
            drain();
        }

        private void drain() throws IOException {
            while (true) {
                int o = mDecoder.dequeueOutputBuffer(mDecInfo, 0);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    setupEncoder(mDecoder.getOutputFormat());
                    continue;
                }
                if (o < 0) break;
                if (mEncoder == null) setupEncoder(mDecoder.getOutputFormat());
                if (mDecInfo.size > 0) {
                    ByteBuffer ob = mDecoder.getOutputBuffer(o);
                    ob.position(mDecInfo.offset);
                    ob.limit(mDecInfo.offset + mDecInfo.size);
                    ShortBuffer sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer();
                    short[] pcm = new short[sb.remaining()];
                    sb.get(pcm);
                    if (mStartPts < 0) mStartPts = mDecInfo.presentationTimeUs;
                    feedEncoder(mInChannels > 2 ? downmix(pcm, mInChannels) : pcm);
                }
                boolean eos = (mDecInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                mDecoder.releaseOutputBuffer(o, false);
                if (eos) endEncoder();
            }
            drainEncoder();
        }

        private short[] downmix(short[] in, int ch) {
            int frames = in.length / ch;
            short[] out = new short[frames * 2];
            for (int i = 0; i < frames; i++) {
                int b = i * ch;
                float l = in[b];
                float r = in[b + 1];
                if (ch == 6 || ch == 8) {              // FL FR FC LFE BL BR [SL SR]
                    float c = in[b + 2] * 0.39f;
                    l = l * 0.55f + c + in[b + 4] * 0.39f + (ch == 8 ? in[b + 6] * 0.39f : 0);
                    r = r * 0.55f + c + in[b + 5] * 0.39f + (ch == 8 ? in[b + 7] * 0.39f : 0);
                }
                out[i * 2] = clip(l);
                out[i * 2 + 1] = clip(r);
            }
            return out;
        }

        private short clip(float v) {
            return (short) Math.max(-32768, Math.min(32767, Math.round(v)));
        }

        private void feedEncoder(short[] pcm) throws IOException {
            int pos = 0;
            while (pos < pcm.length) {
                int index;
                while ((index = mEncoder.dequeueInputBuffer(10_000)) < 0) {
                    drainEncoder();
                    if (!mRunning.isRunning()) throw new IOException("stopped");
                }
                ByteBuffer ib = mEncoder.getInputBuffer(index);
                ib.clear();
                int shorts = Math.min(pcm.length - pos, (ib.capacity() / 2) / mOutChannels * mOutChannels);
                ib.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, pos, shorts);
                long pts = mStartPts + mFrames * 1_000_000L / mRate;
                mEncoder.queueInputBuffer(index, 0, shorts * 2, pts, 0);
                mFrames += shorts / mOutChannels;
                pos += shorts;
            }
        }

        private void endEncoder() throws IOException {
            if (mEncoder == null) {
                mEncoderEos = true;
                return;
            }
            int index;
            while ((index = mEncoder.dequeueInputBuffer(10_000)) < 0) {
                drainEncoder();
                if (!mRunning.isRunning()) return;
            }
            mEncoder.queueInputBuffer(index, 0, 0, mStartPts < 0 ? 0 : mStartPts + mFrames * 1_000_000L / mRate,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }

        private void drainEncoder() throws IOException {
            if (mEncoder == null) return;
            while (true) {
                int e = mEncoder.dequeueOutputBuffer(mEncInfo, 0);
                if (e == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                if (e < 0) continue;
                int flags = mEncInfo.flags;
                if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && mEncInfo.size > 0) {
                    ByteBuffer b = mEncoder.getOutputBuffer(e);
                    byte[] frame = new byte[mEncInfo.size + 7];
                    b.position(mEncInfo.offset);
                    b.limit(mEncInfo.offset + mEncInfo.size);
                    b.get(frame, 7, mEncInfo.size);
                    writeAdts(frame, frame.length, 1, rateIndex(mRate), mOutChannels);
                    mTs.audio(frame, frame.length, t90(mEncInfo.presentationTimeUs));
                }
                mEncoder.releaseOutputBuffer(e, false);
                if ((flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) mEncoderEos = true;
            }
        }

        @Override
        public void finish() throws IOException {
            int index;
            while ((index = mDecoder.dequeueInputBuffer(10_000)) < 0) {
                drain();
                if (!mRunning.isRunning()) return;
            }
            mDecoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            for (int i = 0; i < 2000 && !mEncoderEos && mRunning.isRunning(); i++) {
                drain();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }

        @Override
        public void release() {
            try { mDecoder.stop(); } catch (RuntimeException ignored) { /* released */ }
            try { mDecoder.release(); } catch (RuntimeException ignored) { /* released */ }
            if (mEncoder != null) {
                try { mEncoder.stop(); } catch (RuntimeException ignored) { /* released */ }
                try { mEncoder.release(); } catch (RuntimeException ignored) { /* released */ }
            }
        }
    }

    // -------------------------------------------------------------------- MPEG-TS packetizer

    private static final class TsWriter {
        private static final int PMT_PID = 0x1000;
        private static final int VIDEO_PID = 0x100;
        private static final int AUDIO_PID = 0x101;

        private final OutputStream mOut;
        private final int mVideoType;
        private final int mAudioType;
        private final int mAudioStreamId;
        private final int[] mCounters = new int[0x2000];
        private final byte[] mPacket = new byte[188];
        private long mLastDts = -1;
        private long mLastPsi = Long.MIN_VALUE / 2;

        TsWriter(OutputStream out, int videoType, int audioType, int audioStreamId) {
            mOut = out;
            mVideoType = videoType;
            mAudioType = audioType;
            mAudioStreamId = audioStreamId;
        }

        void video(byte[] data, int length, long pts, long dts, boolean key) throws IOException {
            if (dts <= mLastDts) dts = mLastDts + 1;
            if (pts < dts) pts = dts;
            mLastDts = dts;
            if (key || dts - mLastPsi > 27000) {
                writeTables();
                mLastPsi = dts;
            }
            writePes(VIDEO_PID, 0xE0, data, length, pts, dts != pts ? dts : -1, Math.max(0, dts - 45000), key);
        }

        void audio(byte[] data, int length, long pts) throws IOException {
            writePes(AUDIO_PID, mAudioStreamId, data, length, pts, -1, -1, false);
        }

        private void writeTables() throws IOException {
            byte[] pat = new byte[16];
            pat[0] = 0x00;
            pat[1] = (byte) 0xB0;
            pat[2] = 13;
            pat[3] = 0; pat[4] = 1;
            pat[5] = (byte) 0xC1;
            pat[6] = 0; pat[7] = 0;
            pat[8] = 0; pat[9] = 1;
            pat[10] = (byte) (0xE0 | (PMT_PID >> 8));
            pat[11] = (byte) PMT_PID;
            putCrc(pat, 12);
            writeSection(0, pat);

            int streams = mAudioType != 0 ? 2 : 1;
            int length = 9 + 5 * streams + 4;
            byte[] pmt = new byte[3 + length];
            pmt[0] = 0x02;
            pmt[1] = (byte) (0xB0 | (length >> 8));
            pmt[2] = (byte) length;
            pmt[3] = 0; pmt[4] = 1;
            pmt[5] = (byte) 0xC1;
            pmt[6] = 0; pmt[7] = 0;
            pmt[8] = (byte) (0xE0 | (VIDEO_PID >> 8));
            pmt[9] = (byte) VIDEO_PID;
            pmt[10] = (byte) 0xF0; pmt[11] = 0;
            int p = 12;
            pmt[p++] = (byte) mVideoType;
            pmt[p++] = (byte) (0xE0 | (VIDEO_PID >> 8));
            pmt[p++] = (byte) VIDEO_PID;
            pmt[p++] = (byte) 0xF0; pmt[p++] = 0;
            if (mAudioType != 0) {
                pmt[p++] = (byte) mAudioType;
                pmt[p++] = (byte) (0xE0 | (AUDIO_PID >> 8));
                pmt[p++] = (byte) AUDIO_PID;
                pmt[p++] = (byte) 0xF0; pmt[p++] = 0;
            }
            putCrc(pmt, p);
            writeSection(PMT_PID, pmt);
        }

        private static void putCrc(byte[] b, int at) {
            int crc = 0xFFFFFFFF;
            for (int i = 0; i < at; i++) {
                crc ^= (b[i] & 0xFF) << 24;
                for (int k = 0; k < 8; k++) crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
            }
            b[at] = (byte) (crc >> 24);
            b[at + 1] = (byte) (crc >> 16);
            b[at + 2] = (byte) (crc >> 8);
            b[at + 3] = (byte) crc;
        }

        private void writeSection(int pid, byte[] section) throws IOException {
            java.util.Arrays.fill(mPacket, (byte) 0xFF);
            mPacket[0] = 0x47;
            mPacket[1] = (byte) (0x40 | (pid >> 8));
            mPacket[2] = (byte) pid;
            mPacket[3] = (byte) (0x10 | (mCounters[pid]++ & 0x0F));
            mPacket[4] = 0;
            System.arraycopy(section, 0, mPacket, 5, section.length);
            mOut.write(mPacket, 0, 188);
        }

        private void writePes(int pid, int streamId, byte[] data, int length, long pts, long dts, long pcr,
                              boolean randomAccess) throws IOException {
            boolean hasDts = dts >= 0;
            int optional = hasDts ? 10 : 5;
            byte[] pes = new byte[9 + optional + length];
            pes[2] = 1;
            pes[3] = (byte) streamId;
            int pesLength = pid == VIDEO_PID ? 0 : 3 + optional + length;
            if (pesLength > 0xFFFF) pesLength = 0;
            pes[4] = (byte) (pesLength >> 8);
            pes[5] = (byte) pesLength;
            pes[6] = (byte) 0x84;
            pes[7] = (byte) (hasDts ? 0xC0 : 0x80);
            pes[8] = (byte) optional;
            putTimestamp(pes, 9, hasDts ? 3 : 2, pts);
            if (hasDts) putTimestamp(pes, 14, 1, dts);
            System.arraycopy(data, 0, pes, 9 + optional, length);
            packetize(pid, pes, pcr, randomAccess);
        }

        private static void putTimestamp(byte[] b, int o, int marker, long v) {
            b[o] = (byte) ((marker << 4) | (((v >> 30) & 7) << 1) | 1);
            b[o + 1] = (byte) (v >> 22);
            b[o + 2] = (byte) (((v >> 14) & 0xFE) | 1);
            b[o + 3] = (byte) (v >> 7);
            b[o + 4] = (byte) (((v << 1) & 0xFE) | 1);
        }

        private void packetize(int pid, byte[] pes, long pcr, boolean randomAccess) throws IOException {
            int pos = 0;
            boolean first = true;
            while (pos < pes.length) {
                boolean withPcr = first && pcr >= 0;
                boolean withFlags = withPcr || (first && randomAccess);
                int adaptation = withPcr ? 8 : withFlags ? 2 : 0;       // bytes including the length byte
                int capacity = 184 - adaptation;
                int n = Math.min(capacity, pes.length - pos);
                adaptation += capacity - n;                              // stuffing
                mPacket[0] = 0x47;
                mPacket[1] = (byte) ((first ? 0x40 : 0) | (pid >> 8));
                mPacket[2] = (byte) pid;
                mPacket[3] = (byte) ((adaptation > 0 ? 0x30 : 0x10) | (mCounters[pid]++ & 0x0F));
                int p = 4;
                if (adaptation > 0) {
                    mPacket[p++] = (byte) (adaptation - 1);
                    if (adaptation > 1) {
                        mPacket[p++] = (byte) ((first && randomAccess ? 0x40 : 0) | (withPcr ? 0x10 : 0));
                        if (withPcr) {
                            long base = pcr & ((1L << 33) - 1);
                            mPacket[p++] = (byte) (base >> 25);
                            mPacket[p++] = (byte) (base >> 17);
                            mPacket[p++] = (byte) (base >> 9);
                            mPacket[p++] = (byte) (base >> 1);
                            mPacket[p++] = (byte) (((base & 1) << 7) | 0x7E);
                            mPacket[p++] = 0;
                        }
                        while (p < 4 + adaptation) mPacket[p++] = (byte) 0xFF;
                    }
                }
                System.arraycopy(pes, pos, mPacket, p, n);
                mOut.write(mPacket, 0, 188);
                pos += n;
                first = false;
            }
        }
    }
}
