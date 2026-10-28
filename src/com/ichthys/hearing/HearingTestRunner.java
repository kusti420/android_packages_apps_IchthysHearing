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

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The measurement itself: a modified Hughson–Westlake staircase — down 10 dB after every response,
 * up 5 dB after every miss — run once per ear per frequency, on its own thread.
 *
 * <p>The threshold is taken from the first response on an <em>ascending</em> run. Descending
 * responses are ignored on purpose: coming down from a clearly audible tone the listener knows
 * what to listen for and keeps reporting it below where they can actually detect it cold.
 *
 * <p>Two things guard against the listener simply pressing the button:
 * <ul>
 *   <li>the gap between presentations is randomised, so there is no rhythm to press along with;
 *   <li>roughly one presentation in nine is a catch trial — identical in every way except that no
 *       tone is played. A response there is a false positive, and enough of them mean the run
 *       should not be trusted.
 * </ul>
 *
 * <p>Nothing here reports when a tone is playing. The UI must not light up, animate or vibrate per
 * presentation: any such cue is answered instead of the tone and the whole measurement collapses.
 */
final class HearingTestRunner implements Runnable {

    interface Listener {
        /** Called on the runner's thread whenever a new ear/frequency pair starts. */
        void onProgress(int ear, int completedSearches, int totalSearches);

        void onFinished(Result result);

        void onCancelled();
    }

    static final class Result {
        /** [ear][test frequency index] detection threshold in dBFS. */
        final float[][] thresholdsDbfs;
        final int catchTrials;
        final int falsePositives;

        Result(float[][] thresholdsDbfs, int catchTrials, int falsePositives) {
            this.thresholdsDbfs = thresholdsDbfs;
            this.catchTrials = catchTrials;
            this.falsePositives = falsePositives;
        }

        /** True when the listener pressed through enough catch trials to cast doubt on the run. */
        boolean isUnreliable() {
            return catchTrials > 0 && falsePositives * 100 / catchTrials > 33;
        }
    }

    /** Where the first search starts: the level the listener just called comfortable. */
    static final float START_LEVEL_DBFS = -30f;
    /** The quietest tone worth presenting; below this the device's own noise floor takes over. */
    static final float MIN_LEVEL_DBFS = -90f;
    /**
     * The loudest tone the test will present — 15 dB above the level the listener set as
     * comfortable, which bounds how loud this can get no matter what the headphones are.
     */
    static final float MAX_LEVEL_DBFS = -15f;

    private static final float DOWN_STEP_DB = 10f;
    private static final float UP_STEP_DB = 5f;
    /** Enough for a full bracket from either end of the range, and a hard stop on a stuck search. */
    private static final int MAX_PRESENTATIONS = 14;

    private static final int RESPONSE_WINDOW_MS = 900;
    /** Covers the AudioTrack buffer, so the window closes after the audio really has stopped. */
    private static final int LATENCY_SLACK_MS = 150;
    private static final int MIN_GAP_MS = 250;
    private static final int MAX_GAP_MS = 900;
    private static final int CATCH_TRIAL_ONE_IN = 9;

    /**
     * Standard audiometric order: start at 1 kHz where listeners are most consistent, work up,
     * then come back for the low frequencies.
     */
    private static final int[] FREQ_ORDER = {2, 3, 4, 5, 1, 0};

    /** How far above the previous threshold the next search starts. */
    private static final float PRIOR_HEADROOM_DB = 15f;

    private final TonePlayer mPlayer;
    private final Listener mListener;
    private final Random mRandom = new Random();

    private final AtomicBoolean mResponded = new AtomicBoolean(false);
    private volatile boolean mArmed;
    private volatile boolean mCancelled;

    private int mCatchTrials;
    private int mFalsePositives;

    HearingTestRunner(TonePlayer player, Listener listener) {
        mPlayer = player;
        mListener = listener;
    }

    /** Called from the UI thread when the listener presses the "I heard it" button. */
    void onResponse() {
        if (mArmed) {
            mResponded.set(true);
        }
    }

    void cancel() {
        mCancelled = true;
    }

    @Override
    public void run() {
        final float[][] thresholds = new float[2][HearingProfile.NUM_TEST_FREQS];
        final int total = 2 * HearingProfile.NUM_TEST_FREQS;
        int done = 0;

        for (int ear = 0; ear < 2; ear++) {
            float previous = Float.NaN;
            for (int step = 0; step < FREQ_ORDER.length; step++) {
                if (mCancelled) {
                    mListener.onCancelled();
                    return;
                }
                final int f = FREQ_ORDER[step];
                mListener.onProgress(ear, done, total);

                // Start from what we already know: the previous frequency in this ear, or the
                // same frequency in the other ear. Saves several presentations per search, which
                // over twenty-four searches is the difference between a test people finish and
                // one they abandon.
                final float start;
                if (ear == HearingProfile.EAR_RIGHT) {
                    start = thresholds[HearingProfile.EAR_LEFT][f] + PRIOR_HEADROOM_DB;
                } else if (!Float.isNaN(previous)) {
                    start = previous + PRIOR_HEADROOM_DB;
                } else {
                    start = START_LEVEL_DBFS;
                }

                thresholds[ear][f] = search(ear, HearingProfile.TEST_FREQS[f], start);
                previous = thresholds[ear][f];
                done++;
            }
        }

        if (mCancelled) {
            mListener.onCancelled();
            return;
        }
        mListener.onProgress(HearingProfile.EAR_RIGHT, total, total);
        mListener.onFinished(new Result(thresholds, mCatchTrials, mFalsePositives));
    }

    private float search(int ear, int freqHz, float startLevel) {
        float level = clamp(startLevel);
        boolean descending = true;
        float lowestHeard = Float.NaN;

        for (int n = 0; n < MAX_PRESENTATIONS && !mCancelled; n++) {
            maybeCatchTrial(ear, freqHz);
            if (mCancelled) {
                break;
            }
            final boolean heard = present(freqHz, level, ear, false);
            if (heard) {
                if (Float.isNaN(lowestHeard) || level < lowestHeard) {
                    lowestHeard = level;
                }
                if (!descending) {
                    return level;
                }
                level -= DOWN_STEP_DB;
                if (level < MIN_LEVEL_DBFS) {
                    // Still audible at the quietest tone this device can usefully produce. The
                    // true threshold is lower; the floor is as precise as we can honestly be.
                    return MIN_LEVEL_DBFS;
                }
            } else {
                level += UP_STEP_DB;
                descending = false;
                if (level > MAX_LEVEL_DBFS) {
                    // Inaudible at the safety ceiling. Report the ceiling rather than guessing at
                    // how much worse it really is.
                    return Float.isNaN(lowestHeard) ? MAX_LEVEL_DBFS : lowestHeard;
                }
            }
        }
        return Float.isNaN(lowestHeard) ? MAX_LEVEL_DBFS : lowestHeard;
    }

    private void maybeCatchTrial(int ear, int freqHz) {
        if (mRandom.nextInt(CATCH_TRIAL_ONE_IN) != 0) {
            return;
        }
        mCatchTrials++;
        if (present(freqHz, MIN_LEVEL_DBFS, ear, true)) {
            mFalsePositives++;
        }
    }

    /**
     * Plays one presentation and reports whether the listener answered it. The window opens as the
     * tone starts — a response during the tone counts — and closes once the silence after it has
     * actually been heard rather than merely queued.
     */
    private boolean present(int freqHz, float levelDbfs, int ear, boolean silent) {
        mResponded.set(false);
        mArmed = true;
        mPlayer.writeTone(freqHz, levelDbfs, ear, silent);
        mPlayer.writeSilence(RESPONSE_WINDOW_MS);
        sleep(LATENCY_SLACK_MS);
        mArmed = false;
        final boolean heard = mResponded.get();

        // Randomised gap: without it the presentations arrive on a beat and get answered on the
        // beat, tone or no tone.
        mPlayer.writeSilence(MIN_GAP_MS + mRandom.nextInt(MAX_GAP_MS - MIN_GAP_MS + 1));
        return heard;
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mCancelled = true;
        }
    }

    private static float clamp(float v) {
        if (Float.isNaN(v)) {
            return START_LEVEL_DBFS;
        }
        return v < MIN_LEVEL_DBFS ? MIN_LEVEL_DBFS : (v > MAX_LEVEL_DBFS ? MAX_LEVEL_DBFS : v);
    }
}
