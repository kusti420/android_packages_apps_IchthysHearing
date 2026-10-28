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
import android.graphics.Color;
import android.util.TypedValue;

/**
 * Chart colours. Left blue and right red is the convention every audiogram uses, so it is worth
 * keeping even on a pitch-black theme; the greys come from the active theme so the charts follow
 * light/dark like everything else.
 */
final class Palette {

    static final int LEFT = 0xFF4FA3FF;
    static final int RIGHT = 0xFFFF6B6B;

    private Palette() {}

    static int grid(Context context) {
        return withAlpha(resolve(context, android.R.attr.textColorSecondary, 0xFF888888), 0x55);
    }

    static int label(Context context) {
        // IchthysOS: the app's opaque secondary text colour (values/colors.xml, -night for dark).
        // textColorSecondary is itself translucent, and another 0xCC on top was too faint on black.
        return context.getColor(R.color.hearing_text_secondary);
    }

    private static int resolve(Context context, int attr, int fallback) {
        final TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) {
                return context.getColor(tv.resourceId);
            }
            if (tv.data != 0) {
                return tv.data;
            }
        }
        return fallback;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
