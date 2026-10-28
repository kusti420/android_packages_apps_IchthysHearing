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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Plots the measured thresholds the way an audiogram does — quieter (better) at the top, louder
 * (worse) at the bottom, left ear as blue crosses and right ear as red circles.
 *
 * <p>The vertical axis is labelled in dB relative to the level the listener called comfortable at
 * the start, not in dB HL. Nothing here is calibrated against a reference transducer, so printing
 * clinical units would be a lie that happens to look authoritative.
 */
public class AudiogramView extends View {

    private static final float TOP_DBFS = -95f;
    private static final float BOTTOM_DBFS = -10f;

    private final Paint mGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLeftLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRightLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();

    private float[][] mThresholds;

    public AudiogramView(Context context, AttributeSet attrs) {
        super(context, attrs);
        final float density = getResources().getDisplayMetrics().density;

        mGrid.setStyle(Paint.Style.STROKE);
        mGrid.setStrokeWidth(density);
        mGrid.setColor(Palette.grid(context));

        mLabel.setColor(Palette.label(context));
        mLabel.setTextSize(11f * density);

        for (Paint p : new Paint[] {mLeftLine, mRightLine}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f * density);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        mLeftLine.setColor(Palette.LEFT);
        mRightLine.setColor(Palette.RIGHT);
    }

    /** @param thresholds [ear][test frequency] in dBFS, or null for the empty state. */
    public void setThresholds(float[][] thresholds) {
        mThresholds = thresholds;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float density = getResources().getDisplayMetrics().density;
        final float x0 = 40f * density;
        final float x1 = getWidth() - 10f * density;
        final float y0 = 10f * density;
        final float y1 = getHeight() - 20f * density;
        if (x1 <= x0 || y1 <= y0) {
            return;
        }

        for (int db = -90; db <= -10; db += 20) {
            final float y = yFor(db, y0, y1);
            canvas.drawLine(x0, y, x1, y, mGrid);
            // IchthysOS: label against the comfortable level (0), as the class comment and the
            // caption say; the raw dBFS figures read like an absolute (clinical) scale.
            final int rel = Math.round(db - HearingTestRunner.START_LEVEL_DBFS);
            canvas.drawText(rel > 0 ? "+" + rel : String.valueOf(rel), 2f * density,
                    y + 4f * density, mLabel);
        }

        final int n = HearingProfile.TEST_FREQS.length;
        for (int f = 0; f < n; f++) {
            final float x = xFor(f, n, x0, x1);
            canvas.drawLine(x, y0, x, y1, mGrid);
            final int hz = HearingProfile.TEST_FREQS[f];
            final String text = hz >= 1000 ? (hz / 1000) + "k" : String.valueOf(hz);
            canvas.drawText(text, x - 8f * density, getHeight() - 4f * density, mLabel);
        }

        if (mThresholds == null) {
            return;
        }
        drawEar(canvas, mThresholds[HearingProfile.EAR_LEFT], mLeftLine, true, n, x0, x1, y0, y1);
        drawEar(canvas, mThresholds[HearingProfile.EAR_RIGHT], mRightLine, false, n, x0, x1, y0, y1);
    }

    private void drawEar(Canvas canvas, float[] thresholds, Paint paint, boolean cross, int n,
            float x0, float x1, float y0, float y1) {
        final float density = getResources().getDisplayMetrics().density;
        final float r = 4f * density;

        mPath.reset();
        for (int f = 0; f < n; f++) {
            final float x = xFor(f, n, x0, x1);
            final float y = yFor(thresholds[f], y0, y1);
            if (f == 0) {
                mPath.moveTo(x, y);
            } else {
                mPath.lineTo(x, y);
            }
        }
        canvas.drawPath(mPath, paint);

        for (int f = 0; f < n; f++) {
            final float x = xFor(f, n, x0, x1);
            final float y = yFor(thresholds[f], y0, y1);
            if (cross) {
                canvas.drawLine(x - r, y - r, x + r, y + r, paint);
                canvas.drawLine(x - r, y + r, x + r, y - r, paint);
            } else {
                canvas.drawCircle(x, y, r, paint);
            }
        }
    }

    /** The test frequencies are one octave apart bar the last, so even spacing reads as log-f. */
    private static float xFor(int index, int count, float x0, float x1) {
        return x0 + (x1 - x0) * index / (count - 1);
    }

    private static float yFor(float dbfs, float y0, float y1) {
        final float t = (dbfs - TOP_DBFS) / (BOTTOM_DBFS - TOP_DBFS);
        return y0 + (y1 - y0) * Math.max(0f, Math.min(1f, t));
    }
}
