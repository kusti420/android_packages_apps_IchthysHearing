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

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Runs the measurement: first a step where the listener sets a comfortable level, then the beeps.
 *
 * <p>The level step is not a formality. Every threshold the test produces is relative to the level
 * the listener calls comfortable here, which is what lets the same procedure work on earbuds and
 * on studio headphones without a calibrated reference — and what bounds how loud the test is ever
 * allowed to get, since the ceiling is fixed at 15 dB above this point.
 */
public class TestActivity extends Activity implements HearingTestRunner.Listener {

    static final String EXTRA_THRESHOLDS = "thresholds";
    static final String EXTRA_CATCH_TRIALS = "catch_trials";
    static final String EXTRA_FALSE_POSITIVES = "false_positives";
    static final String EXTRA_VOLUME_CHANGED = "volume_changed";

    private static final int REFERENCE_FREQ_HZ = 1000;
    /** A whole number of cycles at 1 kHz, so looped chunks join without a seam. */
    private static final int REFERENCE_CHUNK_MS = 400;

    private View mSetupPane;
    private View mRunPane;
    private TextView mEarLabel;
    private ProgressBar mProgress;
    private Button mReference;

    private final TonePlayer mPlayer = new TonePlayer();
    private HearingTestRunner mRunner;
    private Thread mRunnerThread;

    private Thread mReferenceThread;
    private volatile boolean mReferencePlaying;

    private AudioManager mAudioManager;
    private int mStartVolume;
    private boolean mFinishing;

    // IchthysOS: the setup text says loudspeakers can't be used, so the check enforces it. Start
    // needs a head-worn output, and losing it mid-run (unplugged, Bluetooth gone) stops the test
    // before the tones move to the speaker and a speaker-measured curve gets applied device-wide.
    private Button mStart;
    private TextView mHeadphonesHint;
    private boolean mCallbackRegistered;
    /** Set once the run was stopped for lost headphones; a result already on its way is dropped. */
    private boolean mAborted;

    private final AudioDeviceCallback mDeviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            updateStartEnabled();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            updateStartEnabled();
            if (mRunner != null && !hasHeadphones()) {
                abortNoHeadphones();
            }
        }
    };

    /** Sent before the route falls back to the speaker (headset unplugged, A2DP dropped). */
    private final BroadcastReceiver mNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())
                    && (mRunner != null || mReferencePlaying)) {
                abortNoHeadphones();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mAudioManager = getSystemService(AudioManager.class);
        mSetupPane = findViewById(R.id.setup_pane);
        mRunPane = findViewById(R.id.run_pane);
        mEarLabel = findViewById(R.id.ear_label);
        mProgress = findViewById(R.id.progress);
        mReference = findViewById(R.id.reference_tone);
        mStart = findViewById(R.id.start);
        mHeadphonesHint = findViewById(R.id.headphones_hint);

        mReference.setOnClickListener(v -> toggleReferenceTone());
        mStart.setOnClickListener(v -> {
            if (hasHeadphones()) {
                startTest();
            } else {
                updateStartEnabled();
            }
        });
        findViewById(R.id.cancel).setOnClickListener(v -> finish());
        findViewById(R.id.respond).setOnClickListener(v -> {
            if (mRunner != null) {
                mRunner.onResponse();
            }
        });

        // Volume keys should reach the media stream here even though nothing is "playing" by the
        // usual definition — setting the media level is the whole job of the first screen.
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Measure through a clean path. Without this the test would be run through the previous
        // correction and each pass would compound the last.
        HearingSettings.setBypass(getContentResolver(), true);
        mAudioManager.registerAudioDeviceCallback(mDeviceCallback,
                new Handler(Looper.getMainLooper()));
        registerReceiver(mNoisyReceiver,
                new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                Context.RECEIVER_NOT_EXPORTED);
        mCallbackRegistered = true;
        updateStartEnabled();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mCallbackRegistered) {
            mAudioManager.unregisterAudioDeviceCallback(mDeviceCallback);
            unregisterReceiver(mNoisyReceiver);
            mCallbackRegistered = false;
        }
        // A backgrounded test is a ruined test: the listener is no longer watching for tones and
        // the thresholds would come out far too high. Stop rather than limp on.
        stopEverything();
        HearingSettings.setBypass(getContentResolver(), false);
        if (!mFinishing && !isFinishing()) {
            finish();
        }
    }

    private void toggleReferenceTone() {
        if (mReferencePlaying) {
            stopReferenceTone();
            return;
        }
        mReferencePlaying = true;
        mReference.setText(R.string.action_stop_tone);
        mPlayer.start();
        mReferenceThread = new Thread(() -> {
            boolean first = true;
            while (mReferencePlaying) {
                mPlayer.writeSteadyTone(REFERENCE_FREQ_HZ, HearingTestRunner.START_LEVEL_DBFS,
                        REFERENCE_CHUNK_MS, first, false);
                first = false;
            }
            mPlayer.writeSteadyTone(REFERENCE_FREQ_HZ, HearingTestRunner.START_LEVEL_DBFS,
                    REFERENCE_CHUNK_MS, false, true);
        }, "ReferenceTone");
        mReferenceThread.start();
    }

    private void stopReferenceTone() {
        mReferencePlaying = false;
        mReference.setText(R.string.action_play_tone);
        joinQuietly(mReferenceThread);
        mReferenceThread = null;
    }

    /**
     * IchthysOS: true when media can play into something worn on the head: wired or USB headphones
     * or headset, a USB audio device (a headphone DAC), BLE Audio or A2DP. The built-in speaker
     * and earpiece don't count.
     */
    private boolean hasHeadphones() {
        for (AudioDeviceInfo d : mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    private void updateStartEnabled() {
        final boolean ok = hasHeadphones();
        mStart.setEnabled(ok);
        mHeadphonesHint.setVisibility(ok ? View.GONE : View.VISIBLE);
    }

    /** The headphones went away: stop before more tones (or a result) come from the speaker. */
    private void abortNoHeadphones() {
        if (mRunPane.getVisibility() == View.VISIBLE) {
            mAborted = true;
        }
        stopEverything();
        Toast.makeText(this, R.string.error_headphones_lost, Toast.LENGTH_LONG).show();
        if (mRunPane.getVisibility() == View.VISIBLE) {
            mFinishing = true;
            finish();
        }
    }

    private void startTest() {
        stopReferenceTone();
        mStartVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

        mSetupPane.setVisibility(View.GONE);
        mRunPane.setVisibility(View.VISIBLE);
        mProgress.setMax(2 * HearingProfile.NUM_TEST_FREQS);
        mProgress.setProgress(0);
        setEarLabel(HearingProfile.EAR_LEFT);

        mPlayer.start();
        mRunner = new HearingTestRunner(mPlayer, this);
        mRunnerThread = new Thread(mRunner, "HearingTest");
        mRunnerThread.start();
    }

    private void stopEverything() {
        mReferencePlaying = false;
        if (mRunner != null) {
            mRunner.cancel();
        }
        // Order matters: abort first so both worker threads fall out of their blocking writes,
        // join them, and only then release the track. Releasing it while a write is in flight
        // pulls the native track out from under that call.
        mPlayer.abort();
        joinQuietly(mReferenceThread);
        joinQuietly(mRunnerThread);
        mPlayer.stop();
        mReferenceThread = null;
        mRunnerThread = null;
        mRunner = null;
        mReference.setText(R.string.action_play_tone);
    }

    private void setEarLabel(int ear) {
        mEarLabel.setText(getString(R.string.testing_ear,
                getString(ear == HearingProfile.EAR_LEFT ? R.string.ear_left_title
                        : R.string.ear_right_title)));
    }

    private static void joinQuietly(Thread t) {
        if (t == null) {
            return;
        }
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    //
    //--- HearingTestRunner.Listener (called on the test thread)
    //

    @Override
    public void onProgress(int ear, int completedSearches, int totalSearches) {
        runOnUiThread(() -> {
            mProgress.setProgress(completedSearches);
            setEarLabel(ear);
        });
    }

    @Override
    public void onFinished(HearingTestRunner.Result result) {
        final boolean volumeChanged =
                mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != mStartVolume;
        runOnUiThread(() -> {
            if (mAborted) {
                return;
            }
            mFinishing = true;
            final Intent intent = new Intent(this, ResultsActivity.class);
            intent.putExtra(EXTRA_THRESHOLDS,
                    HearingProfile.encodeThresholds(result.thresholdsDbfs));
            intent.putExtra(EXTRA_CATCH_TRIALS, result.catchTrials);
            intent.putExtra(EXTRA_FALSE_POSITIVES, result.falsePositives);
            intent.putExtra(EXTRA_VOLUME_CHANGED, volumeChanged);
            startActivity(intent);
            finish();
        });
    }

    @Override
    public void onCancelled() {
        // Nothing to do: whatever cancelled the run is already tearing the screen down.
    }
}
