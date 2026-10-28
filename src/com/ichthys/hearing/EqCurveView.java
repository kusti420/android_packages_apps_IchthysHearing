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

/** Draws the prescribed per-ear EQ curve: gain in dB against band centre frequency. */
public class EqCurveView extends View {

    private static final float TOP_DB = 2f;
    private static final float BOTTOM_DB = -14f;

    private final Paint mGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLeft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();

    private int[][] mGainsMb;

    public EqCurveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        final float density = getResources().getDisplayMetrics().density;

        mGrid.setStyle(Paint.Style.STROKE);
        mGrid.setStrokeWidth(density);
        mGrid.setColor(Palette.grid(context));

        mLabel.setColor(Palette.label(context));
        mLabel.setTextSize(11f * density);

        for (Paint p : new Paint[] {mLeft, mRight}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f * density);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        mLeft.setColor(Palette.LEFT);
        mRight.setColor(Palette.RIGHT);
    }

    /** @param gainsMb [ear][band] gain in millibels, or null for the empty state. */
    public void setGains(int[][] gainsMb) {
        mGainsMb = gainsMb;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float density = getResources().getDisplayMetrics().density;
        final float padLeft = 34f * density;
        final float padRight = 8f * density;
        final float padTop = 8f * density;
        final float padBottom = 20f * density;

        final float x0 = padLeft;
        final float x1 = getWidth() - padRight;
        final float y0 = padTop;
        final float y1 = getHeight() - padBottom;
        if (x1 <= x0 || y1 <= y0) {
            return;
        }

        for (int db = (int) TOP_DB; db >= BOTTOM_DB; db -= 4) {
            final float y = yFor(db, y0, y1);
            canvas.drawLine(x0, y, x1, y, mGrid);
            canvas.drawText(db + " dB", 2f * density, y + 4f * density, mLabel);
        }

        final int n = HearingProfile.BAND_FREQS.length;
        for (int b = 0; b < n; b++) {
            final float x = xFor(b, n, x0, x1);
            canvas.drawLine(x, y0, x, y1, mGrid);
        }
        // Only the ends and the 1 kHz anchor get labelled; eight labels across a phone-width
        // chart collide into mush.
        canvas.drawText("125", x0 - 6f * density, getHeight() - 4f * density, mLabel);
        canvas.drawText("1k", xFor(3, n, x0, x1) - 6f * density, getHeight() - 4f * density, mLabel);
        canvas.drawText("8k", x1 - 14f * density, getHeight() - 4f * density, mLabel);

        if (mGainsMb == null) {
            return;
        }
        drawCurve(canvas, mGainsMb[HearingProfile.EAR_LEFT], mLeft, n, x0, x1, y0, y1);
        drawCurve(canvas, mGainsMb[HearingProfile.EAR_RIGHT], mRight, n, x0, x1, y0, y1);
    }

    private void drawCurve(Canvas canvas, int[] gainsMb, Paint paint, int n, float x0, float x1,
            float y0, float y1) {
        mPath.reset();
        for (int b = 0; b < n; b++) {
            final float x = xFor(b, n, x0, x1);
            final float y = yFor(gainsMb[b] / 100f, y0, y1);
            if (b == 0) {
                mPath.moveTo(x, y);
            } else {
                mPath.lineTo(x, y);
            }
        }
        canvas.drawPath(mPath, paint);
    }

    /** Bands are spaced evenly, which on an octave-spaced band set is already a log axis. */
    private static float xFor(int band, int bandCount, float x0, float x1) {
        return x0 + (x1 - x0) * band / (bandCount - 1);
    }

    private static float yFor(float db, float y0, float y1) {
        final float t = (TOP_DB - db) / (TOP_DB - BOTTOM_DB);
        return y0 + (y1 - y0) * Math.max(0f, Math.min(1f, t));
    }
}
