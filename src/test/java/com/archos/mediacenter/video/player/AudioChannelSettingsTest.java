package com.archos.mediacenter.video.player;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioChannelSettingsTest {
    @Test public void neutralGainsDoNotRequireProcessing() {
        AudioChannelSettings settings = new AudioChannelSettings();
        assertFalse(settings.requiresProcessing());
        for (int i = 0; i < AudioChannelSettings.COUNT; i++) assertEquals(100, settings.getGain(i));
    }

    @Test public void resetRestoresAllGainsAndPreservesDownmixChoice() {
        AudioChannelSettings settings = new AudioChannelSettings();
        settings.setDownmix(true);
        for (int i = 0; i < AudioChannelSettings.COUNT; i++) settings.setGain(i, i * 30);
        settings.resetGains();
        for (int i = 0; i < AudioChannelSettings.COUNT; i++) assertEquals(100, settings.getGain(i));
        assertTrue(settings.isDownmix());
        assertTrue(settings.requiresProcessing());
        settings.setDownmix(false);
        assertFalse(settings.requiresProcessing());
    }

    @Test public void gainRangeIsClampedAndSnapshotIsIndependent() {
        AudioChannelSettings settings = new AudioChannelSettings();
        settings.setGain(1, -50);
        settings.setGain(3, 300);
        assertEquals(0, settings.getGain(1));
        assertEquals(200, settings.getGain(3));
        int[] snapshot = settings.snapshot();
        snapshot[0] = 0;
        assertEquals(100, settings.getGain(0));
        assertTrue(settings.requiresProcessing());
    }
}
