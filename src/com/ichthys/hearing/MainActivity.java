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
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Home screen: shows the profile in force and lets the user tune or replace it. */
public class MainActivity extends Activity {

    private static final int MAKEUP_MAX_DB = 12;

    private TextView mStatusHeadline;
    private TextView mStatus;
    private View mProfileGroup;
    private EqCurveView mCurve;
    private Switch mEnabled;
    private SeekBar mStrength;
    private TextView mStrengthValue;
    private SeekBar mMakeup;
    private TextView mMakeupValue;
    private Button mRunTest;
    private Button mReset;

    private float[][] mThresholds;
    /** Set while refresh() pushes stored values into the widgets, so their listeners stay quiet. */
    private boolean mBinding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mStatusHeadline = findViewById(R.id.status_headline);
        mStatus = findViewById(R.id.status);
        mProfileGroup = findViewById(R.id.profile_group);
        mCurve = findViewById(R.id.curve);
        mEnabled = findViewById(R.id.switch_enabled);
        mStrength = findViewById(R.id.seek_strength);
        mStrengthValue = findViewById(R.id.strength_value);
        mMakeup = findViewById(R.id.seek_makeup);
        mMakeupValue = findViewById(R.id.makeup_value);
        mRunTest = findViewById(R.id.run_test);
        mReset = findViewById(R.id.reset);

        mEnabled.setOnCheckedChangeListener((v, checked) -> {
            if (!mBinding) {
                HearingSettings.setEnabled(getContentResolver(), checked);
                // IchthysOS: the status line says whether the correction is applied.
                refresh();
            }
        });
        // IchthysOS: the whole row is the touch target, as in Settings.
        findViewById(R.id.enabled_row).setOnClickListener(v -> mEnabled.toggle());

        mStrength.setMax(100);
        mStrength.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                mStrengthValue.setText(getString(R.string.percent_format, progress));
                // Redraw as the finger moves, but only commit on release: every commit is a
                // settings write that walks all the way down to audioserver.
                if (mThresholds != null) {
                    final int[][] gains = HearingProfile.prescribe(mThresholds, progress);
                    mCurve.setGains(gains);
                    // IchthysOS: keep the status card in step with the curve.
                    showStatus(gains, mEnabled.isChecked());
                }
                // IchthysOS: D-pad, keyboard and accessibility (TalkBack volume-key) changes
                // never touch the bar, so there is no release to wait for: commit each step.
                if (fromUser && !bar.isPressed() && !mBinding) {
                    HearingSettings.updateStrength(getContentResolver(), progress);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                if (!mBinding) {
                    HearingSettings.updateStrength(getContentResolver(), bar.getProgress());
                }
            }
        });

        mMakeup.setMax(MAKEUP_MAX_DB);
        mMakeup.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                mMakeupValue.setText(getString(R.string.db_format, progress));
                // IchthysOS: as for strength, commit changes that don't come from a drag.
                if (fromUser && !bar.isPressed() && !mBinding) {
                    HearingSettings.setMakeupMb(getContentResolver(), progress * 100);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                if (!mBinding) {
                    HearingSettings.setMakeupMb(getContentResolver(), bar.getProgress() * 100);
                }
            }
        });

        mRunTest.setOnClickListener(v -> startActivity(new Intent(this, TestActivity.class)));
        mReset.setOnClickListener(v -> confirmReset());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // IchthysOS: the test switches the correction off while it plays its tones and back on
        // when it leaves; if it died meanwhile, the correction would stay off unannounced.
        HearingSettings.setBypass(getContentResolver(), false);
        refresh();
    }

    private void refresh() {
        mBinding = true;
        try {
            bind();
        } finally {
            mBinding = false;
        }
    }

    private void bind() {
        final ContentResolver cr = getContentResolver();
        mThresholds = HearingSettings.getThresholds(cr);

        if (mThresholds == null) {
            mStatusHeadline.setText(R.string.status_headline_none);
            mStatus.setText(R.string.status_no_profile);
            mProfileGroup.setVisibility(View.GONE);
            // IchthysOS: there is nothing to delete without a profile.
            mReset.setVisibility(View.GONE);
            mRunTest.setText(R.string.action_run_test);
            return;
        }

        mProfileGroup.setVisibility(View.VISIBLE);
        mReset.setVisibility(View.VISIBLE);
        mRunTest.setText(R.string.action_retest);

        final int strength = HearingSettings.getStrength(cr);
        final int[][] gains = HearingProfile.prescribe(mThresholds, strength);
        mCurve.setGains(gains);
        final boolean enabled = HearingSettings.isEnabled(cr);
        showStatus(gains, enabled);

        mEnabled.setChecked(enabled);
        mStrength.setProgress(strength);
        mStrengthValue.setText(getString(R.string.percent_format, strength));
        final int makeupDb = HearingSettings.getMakeupMb(cr) / 100;
        mMakeup.setProgress(makeupDb);
        mMakeupValue.setText(getString(R.string.db_format, makeupDb));
    }

    /**
     * IchthysOS: fills the status card: a headline (on, off, even) and a one-line plain-language
     * read of the curve, so the chart is not the only explanation.
     */
    private void showStatus(int[][] gainsMb, boolean enabled) {
        // Where the weak point is, and whether there is anything to even out at all, are facts of
        // the measurement, so they are read from the full-strength curve. Only the size of what is
        // corrected right now depends on the strength slider.
        final int[][] full = HearingProfile.prescribe(mThresholds, 100);
        if (spreadDb(full) < 1) {
            mStatusHeadline.setText(R.string.status_headline_even);
            mStatus.setText(R.string.status_flat);
            return;
        }
        mStatusHeadline.setText(enabled ? R.string.status_headline_on
                : R.string.status_headline_off);

        // The curve is normalised so its highest point is 0 mB, and that point is wherever
        // hearing is weakest — it is the band left untouched while everything the user hears
        // better is pulled down to meet it. The depth of the curve is how much correction that
        // costs in total.
        int weakestBand = 0;
        int weakestEar = 0;
        int highestMb = Integer.MIN_VALUE;
        for (int e = 0; e < 2; e++) {
            for (int b = 0; b < HearingProfile.NUM_BANDS; b++) {
                if (full[e][b] > highestMb) {
                    highestMb = full[e][b];
                    weakestBand = b;
                    weakestEar = e;
                }
            }
        }
        final String ear = getString(weakestEar == HearingProfile.EAR_LEFT ? R.string.ear_left
                : R.string.ear_right);
        final String hz = formatHz(HearingProfile.BAND_FREQS[weakestBand]);
        final int spreadDb = spreadDb(gainsMb);
        if (!enabled) {
            mStatus.setText(getString(R.string.status_profile_off, ear, hz));
        } else if (spreadDb < 1) {
            mStatus.setText(getString(R.string.status_profile_zero, ear, hz));
        } else {
            mStatus.setText(getString(R.string.status_profile, spreadDb, ear, hz));
        }
    }

    /** Depth of a curve in whole dB: its highest band minus its lowest, over both ears. */
    private static int spreadDb(int[][] gainsMb) {
        int highestMb = Integer.MIN_VALUE;
        int lowestMb = Integer.MAX_VALUE;
        for (int e = 0; e < 2; e++) {
            for (int b = 0; b < HearingProfile.NUM_BANDS; b++) {
                highestMb = Math.max(highestMb, gainsMb[e][b]);
                lowestMb = Math.min(lowestMb, gainsMb[e][b]);
            }
        }
        return (highestMb - lowestMb) / 100;
    }

    static String formatHz(int hz) {
        return hz >= 1000 ? (hz / 1000) + " kHz" : hz + " Hz";
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.reset_title)
                .setMessage(R.string.reset_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_reset, (d, w) -> {
                    HearingSettings.clearProfile(getContentResolver());
                    refresh();
                })
                .show();
    }

    /** SeekBar.OnSeekBarChangeListener with the two callbacks we never need already stubbed. */
    abstract static class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar bar) {}

        @Override
        public void onStopTrackingTouch(SeekBar bar) {}
    }
}
