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

import android.text.TextUtils;

/**
 * Turns a set of measured detection thresholds into the per-ear EQ curve that libichthyshearing
 * applies, and handles the string encodings both ends of that pipeline agree on.
 *
 * <p>Everything here is deliberately <em>relative</em>. Without a calibrated transducer there is
 * no way to say what a measured threshold is in dB HL, and this app makes no attempt to pretend
 * otherwise. What it can do honestly is compare the user's ears and bands against each other and
 * against the shape of a normal threshold curve, and flatten out the differences — which is
 * exactly what "make it sound even" asks for.
 */
public final class HearingProfile {

    /** Frequencies actually presented during the test, in Hz. */
    public static final int[] TEST_FREQS = {250, 500, 1000, 2000, 4000, 8000};
    /** EQ bands the effect implements, in Hz. Must match BAND_FREQS in the native effect. */
    public static final int[] BAND_FREQS = {125, 250, 500, 1000, 2000, 4000, 6000, 8000};

    public static final int NUM_TEST_FREQS = 6;
    public static final int NUM_BANDS = 8;
    public static final int EAR_LEFT = 0;
    public static final int EAR_RIGHT = 1;

    /**
     * ISO 226:2003 free-field threshold of hearing, in dB SPL, at each of {@link #BAND_FREQS}
     * (6 kHz log-interpolated between the standard's 5 kHz and 6.3 kHz points).
     *
     * <p>This is the shape a normal-hearing listener's thresholds would trace out if the
     * transducer were flat, so subtracting it is what separates "your ears are less sensitive
     * here" from "everybody's ears are less sensitive here". Without it the prescription would
     * dump a huge boost into 125 Hz and 8 kHz for a listener with perfectly ordinary hearing.
     */
    private static final float[] REFERENCE_SPL = {22.1f, 11.4f, 4.4f, 2.4f, -1.3f, -5.4f, 4.4f,
            12.6f};

    /**
     * At strength 100 the prescription is the textbook half-gain rule: correct half of the
     * measured loss, in dB. Correcting all of it is a known way to make everything sound harsh.
     */
    private static final float HALF_GAIN_RULE = 0.5f;

    /** Ceiling on how far any single band may be moved relative to the others. */
    private static final float MAX_CORRECTION_DB = 12f;

    public static final int DEFAULT_STRENGTH_PERCENT = 50;

    private HearingProfile() {}

    /**
     * Fills in the two EQ bands that are not measured directly. 125 Hz inherits the 250 Hz
     * threshold (it is the low shelf, so it carries the correction downwards anyway, and 125 Hz
     * is below what most headphones reproduce honestly); 6 kHz is interpolated on a log-frequency
     * axis between its measured neighbours.
     */
    static float[] expandToBands(float[] thresholdsDbfs) {
        final float t250 = thresholdsDbfs[0];
        final float t4k = thresholdsDbfs[4];
        final float t8k = thresholdsDbfs[5];
        final double w = Math.log(6000.0 / 4000.0) / Math.log(8000.0 / 4000.0);
        final float t6k = (float) (t4k + w * (t8k - t4k));
        return new float[] {t250, thresholdsDbfs[0], thresholdsDbfs[1], thresholdsDbfs[2],
                thresholdsDbfs[3], t4k, t6k, t8k};
    }

    /**
     * Derives the corrective curve.
     *
     * @param thresholdsDbfs [ear][test frequency] detection threshold in dBFS; more negative means
     *                       the user heard a quieter tone, i.e. better hearing.
     * @param strengthPercent how much of the measured loss to act on, 0..100.
     * @return [ear][band] gain in millibels. Always {@code <= 0}: the correction works by pulling
     *         down the bands the user hears best rather than by pushing up the ones they don't,
     *         which reaches the same balance without spending headroom or asking the limiter to
     *         rescue every transient.
     */
    public static int[][] prescribe(float[][] thresholdsDbfs, int strengthPercent) {
        final float[][] banded = new float[2][];
        banded[EAR_LEFT] = expandToBands(thresholdsDbfs[EAR_LEFT]);
        banded[EAR_RIGHT] = expandToBands(thresholdsDbfs[EAR_RIGHT]);

        // How far each ear/band sits above the best-performing ear/band once the normal threshold
        // shape is taken out. One shared baseline across both ears, so an asymmetry between the
        // ears survives into the prescription instead of being normalised away per ear.
        final float[][] excess = new float[2][NUM_BANDS];
        float best = Float.MAX_VALUE;
        for (int e = 0; e < 2; e++) {
            for (int b = 0; b < NUM_BANDS; b++) {
                excess[e][b] = banded[e][b] - REFERENCE_SPL[b];
                if (excess[e][b] < best) {
                    best = excess[e][b];
                }
            }
        }

        final float factor = HALF_GAIN_RULE * clamp(strengthPercent, 0, 100) / 100f;
        final float[][] gain = new float[2][NUM_BANDS];
        for (int e = 0; e < 2; e++) {
            for (int b = 0; b < NUM_BANDS; b++) {
                gain[e][b] = (excess[e][b] - best) * factor;
            }
        }

        // A threshold measured in a couple of minutes carries several dB of noise, and an
        // unsmoothed curve turns that noise into audible band-to-band ripple. A real loss is a
        // smooth function of frequency, so a light three-point blur costs nothing real.
        for (int e = 0; e < 2; e++) {
            gain[e] = smooth(gain[e]);
        }

        // Clamp, then slide the whole thing down so the loudest band sits at unity.
        float max = 0f;
        for (int e = 0; e < 2; e++) {
            for (int b = 0; b < NUM_BANDS; b++) {
                gain[e][b] = clamp(gain[e][b], 0f, MAX_CORRECTION_DB);
                if (gain[e][b] > max) {
                    max = gain[e][b];
                }
            }
        }

        final int[][] out = new int[2][NUM_BANDS];
        for (int e = 0; e < 2; e++) {
            for (int b = 0; b < NUM_BANDS; b++) {
                out[e][b] = Math.round((gain[e][b] - max) * 100f);
            }
        }
        return out;
    }

    /**
     * A sensible starting make-up gain for a freshly applied curve: half the depth of the
     * correction, capped.
     *
     * <p>The curve itself only cuts, which is the safe way to get the balance right but means
     * applying a profile otherwise just makes everything quieter — not what anyone expects from
     * something that is supposed to bring up what they cannot hear. Giving back half the depth
     * restores most of the loudness while leaving enough headroom that the limiter stays out of
     * the way on ordinary material. The user can take it to either extreme from the home screen:
     * at zero the correction is pure attenuation, and at the full depth it becomes the same curve
     * expressed entirely as boost.
     */
    public static int suggestedMakeupMb(int[][] gainsMb) {
        int deepestMb = 0;
        for (int e = 0; e < 2; e++) {
            for (int b = 0; b < NUM_BANDS; b++) {
                if (gainsMb[e][b] < deepestMb) {
                    deepestMb = gainsMb[e][b];
                }
            }
        }
        // Rounded to whole dB, because that is the resolution the slider on the home screen has;
        // otherwise the first touch of the slider would visibly change a value nobody asked it to.
        return Math.min(600, Math.round(-deepestMb / 200f) * 100);
    }

    private static float[] smooth(float[] in) {
        final float[] out = new float[in.length];
        for (int i = 0; i < in.length; i++) {
            final float prev = in[Math.max(0, i - 1)];
            final float next = in[Math.min(in.length - 1, i + 1)];
            out[i] = 0.25f * prev + 0.5f * in[i] + 0.25f * next;
        }
        return out;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    //
    //--- Encodings shared with IchthysHearingController
    //

    /** Encodes the prescription as "L:mB,...;R:mB,...". */
    public static String encodeGains(int[][] gainsMb) {
        final StringBuilder sb = new StringBuilder();
        for (int e = 0; e < 2; e++) {
            sb.append(e == 0 ? "L:" : "R:");
            for (int b = 0; b < NUM_BANDS; b++) {
                if (b > 0) {
                    sb.append(',');
                }
                sb.append(gainsMb[e][b]);
            }
            if (e == 0) {
                sb.append(';');
            }
        }
        return sb.toString();
    }

    /** Encodes the raw measurement as "L:dB*10,...;R:dB*10,...". */
    public static String encodeThresholds(float[][] thresholdsDbfs) {
        final StringBuilder sb = new StringBuilder();
        for (int e = 0; e < 2; e++) {
            sb.append(e == 0 ? "L:" : "R:");
            for (int f = 0; f < NUM_TEST_FREQS; f++) {
                if (f > 0) {
                    sb.append(',');
                }
                sb.append(Math.round(thresholdsDbfs[e][f] * 10f));
            }
            if (e == 0) {
                sb.append(';');
            }
        }
        return sb.toString();
    }

    /** Inverse of {@link #encodeThresholds}; null if the string is missing or incomplete. */
    public static float[][] decodeThresholds(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        final String[] ears = value.split(";");
        if (ears.length != 2) {
            return null;
        }
        final float[][] out = new float[2][NUM_TEST_FREQS];
        for (int e = 0; e < 2; e++) {
            final String prefix = (e == 0) ? "L:" : "R:";
            final String ear = ears[e].trim();
            if (!ear.startsWith(prefix)) {
                return null;
            }
            final String[] parts = ear.substring(prefix.length()).split(",");
            if (parts.length != NUM_TEST_FREQS) {
                return null;
            }
            for (int f = 0; f < NUM_TEST_FREQS; f++) {
                try {
                    out[e][f] = Integer.parseInt(parts[f].trim()) / 10f;
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
        }
        return out;
    }
}
