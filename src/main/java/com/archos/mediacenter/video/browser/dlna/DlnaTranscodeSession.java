package com.archos.mediacenter.video.browser.dlna;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;

/** One conversion of a video into a live MPEG-TS stream (FFmpeg or MediaCodec based). */
interface DlnaTranscodeSession extends Closeable {
    /** Opens the file and decides what is copied and what is re-encoded. */
    void open() throws IOException;

    long getDurationUs();

    /** Writes the stream until the file ends, the client leaves or the server stops. */
    void stream(OutputStream out) throws IOException;

    @Override
    void close();
}
