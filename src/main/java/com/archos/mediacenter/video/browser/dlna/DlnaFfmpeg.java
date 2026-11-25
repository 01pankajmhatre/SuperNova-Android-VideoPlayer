package com.archos.mediacenter.video.browser.dlna;

import android.content.Context;
import android.net.Uri;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * FFmpeg based conversion into a live MPEG-TS stream.
 *
 * <p>The FFmpeg Kit library (package {@code com.arthenica.ffmpegkit}) is reached through reflection, so the
 * project still builds without it; {@link #isAvailable()} tells whether it is in the app. Everything FFmpeg
 * can decode is accepted: streams that a TV can play (H.264 / H.265 / MPEG-2 video, AAC / AC-3 / E-AC-3 /
 * MP3 / MP2 audio) are copied, everything else (DTS, TrueHD, FLAC, Opus, VP9, AV1 ...) is re-encoded to
 * H.264 + stereo AAC, optionally scaled down. The output goes through a named pipe that is copied to the
 * HTTP connection.</p>
 */
final class DlnaFfmpeg implements DlnaTranscodeSession {

    private static final String PKG = "com.arthenica.ffmpegkit.";
    private static Boolean sAvailable;
    private static String sVideoEncoder;                 // "h264_mediacodec", "libx264" or ""

    private final Context mContext;
    private final Uri mUri;
    private final DlnaTranscoder.Options mOptions;
    private final DlnaTranscoder.Running mRunning;

    private String mInput;
    private long mDurationUs;
    private String mVideoCodec = "";
    private int mVideoHeight;
    private String mAudioCodec = "";
    private volatile Object mSession;
    private volatile String mPipe;
    private volatile boolean mClosed;

    DlnaFfmpeg(Context context, Uri uri, DlnaTranscoder.Options options, DlnaTranscoder.Running running) {
        mContext = context.getApplicationContext();
        mUri = uri;
        mOptions = options;
        mRunning = running;
    }

    static synchronized boolean isAvailable() {
        if (sAvailable == null) {
            try {
                Class.forName(PKG + "FFmpegKit");
                Class.forName(PKG + "FFprobeKit");
                Class.forName(PKG + "FFmpegKitConfig");
                sAvailable = true;
            } catch (Throwable t) {
                sAvailable = false;
            }
        }
        return sAvailable;
    }

    // ----------------------------------------------------------------------------- reflection

    private static Object callStatic(String className, String method, Class<?>[] types, Object... args) throws IOException {
        try {
            Method m = Class.forName(PKG + className).getMethod(method, types);
            return m.invoke(null, args);
        } catch (Throwable t) {
            throw new IOException("FFmpeg Kit call failed: " + className + "." + method + " " + t);
        }
    }

    private static Object call(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------- open

    @Override
    public void open() throws IOException {
        if (!isAvailable()) throw new IOException("FFmpeg is not in this app");
        mInput = (String) callStatic("FFmpegKitConfig", "getSafParameterForRead",
                new Class<?>[]{Context.class, Uri.class}, mContext, mUri);
        Object probe = callStatic("FFprobeKit", "getMediaInformation", new Class<?>[]{String.class}, mInput);
        Object info = call(probe, "getMediaInformation");
        if (info == null) throw new IOException("FFmpeg cannot read this file");
        Object duration = call(info, "getDuration");
        if (duration != null) {
            try {
                mDurationUs = (long) (Double.parseDouble(duration.toString()) * 1_000_000);
            } catch (NumberFormatException ignored) {
                mDurationUs = 0;
            }
        }
        Object streams = call(info, "getStreams");
        if (streams instanceof List) {
            for (Object stream : (List<?>) streams) {
                Object type = call(stream, "getType");
                Object codec = call(stream, "getCodec");
                if (type == null || codec == null) continue;
                if ("video".equals(type.toString()) && mVideoCodec.isEmpty()) {
                    mVideoCodec = codec.toString();
                    Object height = call(stream, "getHeight");
                    if (height instanceof Number) mVideoHeight = ((Number) height).intValue();
                } else if ("audio".equals(type.toString()) && mAudioCodec.isEmpty()) {
                    mAudioCodec = codec.toString();
                }
            }
        }
        if (mVideoCodec.isEmpty()) throw new IOException("no video stream");
    }

    @Override
    public long getDurationUs() {
        return mDurationUs;
    }

    // ---------------------------------------------------------------------------------- run

    private boolean videoCopy() {
        boolean ok = mVideoCodec.equals("h264") || mVideoCodec.equals("hevc") || mVideoCodec.equals("mpeg2video");
        boolean tooBig = mOptions.maxHeight > 0 && mVideoHeight > mOptions.maxHeight;
        return ok && !mOptions.reencodeVideo && !tooBig;
    }

    private boolean audioCopy() {
        boolean ok = mAudioCodec.equals("aac") || mAudioCodec.equals("ac3") || mAudioCodec.equals("eac3")
                || mAudioCodec.equals("mp3") || mAudioCodec.equals("mp2");
        return ok && !mOptions.reencodeAudio;
    }

    /** Hardware H.264 (MediaCodec) first, then x264; found out once from the encoder list of the library. */
    private static synchronized String videoEncoder() throws IOException {
        if (sVideoEncoder == null) {
            Object session = callStatic("FFmpegKit", "execute", new Class<?>[]{String.class}, "-hide_banner -encoders");
            Object output = call(session, "getOutput");
            String text = output == null ? "" : output.toString();
            sVideoEncoder = text.contains("h264_mediacodec") ? "h264_mediacodec"
                    : text.contains("libx264") ? "libx264" : "";
        }
        return sVideoEncoder;
    }

    private String buildCommand(String pipe) throws IOException {
        StringBuilder c = new StringBuilder("-hide_banner -loglevel error -y ");
        if (mOptions.startUs > 0) {
            c.append("-ss ").append(String.format(Locale.ROOT, "%.3f", mOptions.startUs / 1_000_000.0)).append(' ');
        }
        c.append("-i \"").append(mInput).append("\" -map 0:v:0 ");
        if (!mAudioCodec.isEmpty()) c.append("-map 0:a:0 ");
        if (videoCopy()) {
            c.append("-c:v copy ");
        } else {
            String encoder = videoEncoder();
            if (encoder.isEmpty()) throw new IOException("this FFmpeg build has no H.264 encoder");
            if (mOptions.maxHeight > 0 && mVideoHeight > mOptions.maxHeight) {
                c.append("-vf scale=-2:").append(mOptions.maxHeight).append(' ');
            }
            c.append("-c:v ").append(encoder).append(" -b:v ").append(mOptions.videoKbps).append("k ");
            if (encoder.equals("libx264")) c.append("-preset veryfast -pix_fmt yuv420p ");
            c.append("-g 60 ");
        }
        if (!mAudioCodec.isEmpty()) {
            if (audioCopy()) c.append("-c:a copy ");
            else c.append("-c:a aac -b:a ").append(mOptions.audioKbps).append("k -ac 2 ");
        }
        c.append("-sn -dn -f mpegts -muxdelay 0 -flush_packets 1 \"").append(pipe).append('"');
        return c.toString();
    }

    @Override
    public void stream(OutputStream out) throws IOException {
        final String pipe = (String) callStatic("FFmpegKitConfig", "registerNewFFmpegPipe",
                new Class<?>[]{Context.class}, mContext);
        mPipe = pipe;
        String command = buildCommand(pipe);
        try {
            Class<?> callback = Class.forName(PKG + "FFmpegSessionCompleteCallback");
            mSession = callStatic("FFmpegKit", "executeAsync", new Class<?>[]{String.class, callback}, command, null);
        } catch (ClassNotFoundException e) {
            throw new IOException("FFmpeg Kit callback class missing");
        }
        // If FFmpeg stops before it opened the pipe, the reader below would wait forever: open it from
        // here for a moment so the reader sees the end of the file.
        Thread watchdog = new Thread(() -> {
            while (!mClosed) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return;
                }
                Object state = mSession == null ? null : call(mSession, "getState");
                String name = state == null ? "" : state.toString();
                if (name.equals("COMPLETED") || name.equals("FAILED")) {
                    try {
                        new RandomAccessFile(pipe, "rw").close();
                    } catch (IOException ignored) {
                        // pipe already gone
                    }
                    return;
                }
                if (!mRunning.isRunning()) return;
            }
        }, "dlna-ffmpeg-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        byte[] buffer = new byte[64 * 1024];
        try (FileInputStream in = new FileInputStream(pipe)) {
            int n;
            while (mRunning.isRunning() && (n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            out.flush();
        }
    }

    @Override
    public void close() {
        mClosed = true;
        Object session = mSession;
        if (session != null) {
            try {
                session.getClass().getMethod("cancel").invoke(session);
            } catch (Throwable ignored) {
                // already finished
            }
        }
        String pipe = mPipe;
        if (pipe != null) {
            try {
                callStatic("FFmpegKitConfig", "closeFFmpegPipe", new Class<?>[]{String.class}, pipe);
            } catch (IOException ignored) {
                // already closed
            }
        }
    }
}
