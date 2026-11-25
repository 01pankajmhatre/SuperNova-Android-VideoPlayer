package com.archos.mediacenter.video.player;

import java.util.Arrays;

/** Gain order matches the native PCM processor. */
final class AudioChannelSettings {
    static final int COUNT = 6;
    private final int[] gains = {100, 100, 100, 100, 100, 100};
    private boolean downmix;

    int getGain(int group) { return gains[group]; }
    void setGain(int group, int percent) { gains[group] = Math.max(0, Math.min(200, percent)); }
    int[] snapshot() { return gains.clone(); }
    boolean isDownmix() { return downmix; }
    void setDownmix(boolean value) { downmix = value; }
    void resetGains() { Arrays.fill(gains, 100); }

    boolean requiresProcessing() {
        if (downmix) return true;
        for (int gain : gains) if (gain != 100) return true;
        return false;
    }
}
