/*
 * Copyright (C) 2026 IchthysOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ichthys.hearing;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * Presents the test tones.
 *
 * <p>One {@link AudioTrack} stays open and playing for the whole test, and silence is written into
 * it between presentations. Opening a track per beep would be simpler, but every open/close pair
 * risks a click at the route level, and a click is a cue — the listener would be pressing the
 * button on the click rather than on the tone, and every threshold would come out far too good.
 *
 * <p>For the same reason each tone is gated with a raised-cosine ramp. A hard-edged sine burst
 * splatters energy across the whole spectrum, which makes a 250 Hz tone audible through the click
 * alone to someone who cannot hear 250 Hz at all.
 *
 * <p>Ear separation is done by writing the tone into one channel of a stereo buffer rather than by
 * touching balance or panning, so nothing outside this class can quietly undo it.
 */
final class TonePlayer {

    static final int SAMPLE_RATE = 48000;

    /** Three short pulses read more clearly against tinnitus than one long steady tone. */
    private static final int PULSE_MS = 180;
    private static final int GAP_MS = 120;
    private static final int PULSES = 3;
    private static final int RAMP_MS = 20;

    static final int TONE_MS = PULSES * PULSE_MS + (PULSES - 1) * GAP_MS;

    private AudioTrack mTrack;
    private volatile boolean mAborted;

    void start() {
        mAborted = false;
        if (mTrack != null) {
            return;
        }
        final int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT);
        // Roughly 120 ms of slack. Small enough that a blocking write returns close to real time
        // — the response window is timed off those writes, so a deep buffer would let taps land
        // against the wrong presentation.
        final int bufBytes = Math.max(minBuf, SAMPLE_RATE * 2 * 4 * 120 / 1000);
        mTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        mTrack.play();
    }

    /**
     * Unblocks whichever thread is sitting in a blocking write, without releasing anything.
     *
     * <p>This has to be a separate step from {@link #stop()}: releasing an AudioTrack while
     * another thread is inside write() on it frees the native object out from under that call.
     * The caller aborts, joins the writer, and only then stops.
     */
    void abort() {
        mAborted = true;
        final AudioTrack track = mTrack;
        if (track == null) {
            return;
        }
        try {
            track.pause();
            track.flush();
        } catch (IllegalStateException ignored) {
            // Already torn down.
        }
    }

    void stop() {
        abort();
        if (mTrack == null) {
            return;
        }
        try {
            mTrack.stop();
        } catch (IllegalStateException ignored) {
            // Already torn down.
        }
        mTrack.release();
        mTrack = null;
    }

    /**
     * Writes one pulsed tone and blocks until the audio system has taken it.
     *
     * @param ear {@link HearingProfile#EAR_LEFT} or {@link HearingProfile#EAR_RIGHT}.
     * @param silent true for a catch trial: same timing, same everything, no sound.
     */
    void writeTone(int freqHz, double levelDbfs, int ear, boolean silent) {
        write(buildTone(freqHz, levelDbfs, ear, silent));
    }

    /**
     * Writes a steady tone in both ears, used for the volume-setting step before the test. The
     * caller loops over this, so the ramps are opt-in: gating every chunk would turn a steady
     * reference tone into a pulsing one.
     *
     * <p>Chunk lengths that are a whole number of cycles keep the phase continuous from one call
     * to the next, so the seam is inaudible without any ramp at all.
     */
    void writeSteadyTone(int freqHz, double levelDbfs, int ms, boolean rampIn, boolean rampOut) {
        final int frames = SAMPLE_RATE * ms / 1000;
        final int rampFrames = SAMPLE_RATE * RAMP_MS / 1000;
        final float[] buf = new float[frames * 2];
        final double amp = Math.pow(10.0, levelDbfs / 20.0);
        final double w = 2 * Math.PI * freqHz / SAMPLE_RATE;
        for (int i = 0; i < frames; i++) {
            double env = 1.0;
            if (rampIn && i < rampFrames) {
                env = 0.5 * (1 - Math.cos(Math.PI * i / rampFrames));
            } else if (rampOut && i >= frames - rampFrames) {
                env = 0.5 * (1 - Math.cos(Math.PI * (frames - 1 - i) / rampFrames));
            }
            final float s = (float) (Math.sin(w * i) * amp * env);
            buf[i * 2] = s;
            buf[i * 2 + 1] = s;
        }
        write(buf);
    }

    void writeSilence(int ms) {
        write(new float[(SAMPLE_RATE * ms / 1000) * 2]);
    }

    private void write(float[] buf) {
        final AudioTrack track = mTrack;
        if (track == null || mAborted || buf.length == 0) {
            return;
        }
        int off = 0;
        while (off < buf.length && !mAborted) {
            final int n = track.write(buf, off, buf.length - off, AudioTrack.WRITE_BLOCKING);
            if (n <= 0) {
                break;
            }
            off += n;
        }
    }

    private static float[] buildTone(int freqHz, double levelDbfs, int ear, boolean silent) {
        final int pulseFrames = SAMPLE_RATE * PULSE_MS / 1000;
        final int gapFrames = SAMPLE_RATE * GAP_MS / 1000;
        final int rampFrames = SAMPLE_RATE * RAMP_MS / 1000;
        final int total = PULSES * pulseFrames + (PULSES - 1) * gapFrames;
        final float[] buf = new float[total * 2];
        if (silent) {
            return buf;
        }
        final double amp = Math.pow(10.0, levelDbfs / 20.0);
        final double w = 2 * Math.PI * freqHz / SAMPLE_RATE;
        final int channel = (ear == HearingProfile.EAR_RIGHT) ? 1 : 0;
        int at = 0;
        for (int p = 0; p < PULSES; p++) {
            for (int i = 0; i < pulseFrames; i++) {
                buf[(at + i) * 2 + channel] =
                        (float) (Math.sin(w * i) * amp * envelope(i, pulseFrames, rampFrames));
            }
            at += pulseFrames + gapFrames;
        }
        return buf;
    }

    /** Raised-cosine gate: 0 at both edges, 1 in the middle. */
    private static double envelope(int i, int frames, int rampFrames) {
        if (i < rampFrames) {
            return 0.5 * (1 - Math.cos(Math.PI * i / rampFrames));
        }
        if (i >= frames - rampFrames) {
            return 0.5 * (1 - Math.cos(Math.PI * (frames - 1 - i) / rampFrames));
        }
        return 1.0;
    }
}
