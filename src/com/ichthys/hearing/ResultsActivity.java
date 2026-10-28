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
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Shows what the test found and what it proposes to do about it, then applies it — or not. The
 * curve is never written to {@link android.provider.Settings.Global} until the user says so, so a
 * run that felt wrong can simply be thrown away.
 */
public class ResultsActivity extends Activity {

    private float[][] mThresholds;
    private EqCurveView mCurve;
    private TextView mStrengthValue;
    private int mStrength = HearingProfile.DEFAULT_STRENGTH_PERCENT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_results);

        mThresholds = HearingProfile.decodeThresholds(
                getIntent().getStringExtra(TestActivity.EXTRA_THRESHOLDS));
        if (mThresholds == null) {
            Toast.makeText(this, R.string.error_no_results, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        final AudiogramView audiogram = findViewById(R.id.audiogram);
        audiogram.setThresholds(mThresholds);

        mCurve = findViewById(R.id.curve);
        mStrengthValue = findViewById(R.id.strength_value);

        final SeekBar strength = findViewById(R.id.seek_strength);
        strength.setMax(100);
        strength.setOnSeekBarChangeListener(new MainActivity.SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                mStrength = progress;
                mStrengthValue.setText(getString(R.string.percent_format, progress));
                mCurve.setGains(HearingProfile.prescribe(mThresholds, progress));
            }
        });
        strength.setProgress(mStrength);

        ((TextView) findViewById(R.id.summary)).setText(summarise());
        showWarning();

        findViewById(R.id.apply).setOnClickListener(v -> {
            HearingSettings.saveProfile(getContentResolver(), mThresholds, mStrength);
            HearingSettings.setMakeupMb(getContentResolver(), HearingProfile.suggestedMakeupMb(
                    HearingProfile.prescribe(mThresholds, mStrength)));
            HearingSettings.setEnabled(getContentResolver(), true);
            Toast.makeText(this, R.string.applied, Toast.LENGTH_SHORT).show();
            final Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        });
        findViewById(R.id.discard).setOnClickListener(v -> finish());
    }

    /**
     * Two things are worth saying out loud: where the biggest gap between the ears is, and where
     * the biggest gap between bands is. Those are the two ways audio stops sounding even, and they
     * are the two things the correction is about to change.
     */
    private String summarise() {
        int worstAsymmetryFreq = 0;
        float worstAsymmetry = 0f;
        for (int f = 0; f < HearingProfile.NUM_TEST_FREQS; f++) {
            final float diff = Math.abs(mThresholds[HearingProfile.EAR_LEFT][f]
                    - mThresholds[HearingProfile.EAR_RIGHT][f]);
            if (diff > worstAsymmetry) {
                worstAsymmetry = diff;
                worstAsymmetryFreq = HearingProfile.TEST_FREQS[f];
            }
        }

        float best = Float.MAX_VALUE;
        float worst = -Float.MAX_VALUE;
        int worstFreq = 0;
        for (int e = 0; e < 2; e++) {
            for (int f = 0; f < HearingProfile.NUM_TEST_FREQS; f++) {
                final float v = mThresholds[e][f];
                if (v < best) {
                    best = v;
                }
                if (v > worst) {
                    worst = v;
                    worstFreq = HearingProfile.TEST_FREQS[f];
                }
            }
        }

        final StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.summary_spread, Math.round(worst - best),
                MainActivity.formatHz(worstFreq)));
        if (worstAsymmetry >= 5f) {
            sb.append("\n\n");
            sb.append(getString(R.string.summary_asymmetry, Math.round(worstAsymmetry),
                    MainActivity.formatHz(worstAsymmetryFreq)));
        }
        return sb.toString();
    }

    private void showWarning() {
        final int catchTrials = getIntent().getIntExtra(TestActivity.EXTRA_CATCH_TRIALS, 0);
        final int falsePositives = getIntent().getIntExtra(TestActivity.EXTRA_FALSE_POSITIVES, 0);
        final boolean volumeChanged =
                getIntent().getBooleanExtra(TestActivity.EXTRA_VOLUME_CHANGED, false);

        final String text;
        if (volumeChanged) {
            text = getString(R.string.warning_volume_changed);
        } else if (catchTrials > 0 && falsePositives * 100 / catchTrials > 33) {
            text = getString(R.string.warning_false_positives, falsePositives, catchTrials);
        } else {
            return;
        }
        final TextView warning = findViewById(R.id.warning);
        warning.setText(text);
        warning.setVisibility(View.VISIBLE);
    }
}
