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

import android.content.ContentResolver;
import android.provider.Settings;

/**
 * The app's half of the contract with {@code IchthysHearingController}: everything the correction
 * needs lives in {@link Settings.Global}, and writing there is the whole of "applying" a profile.
 * Nothing is handed to the system service directly, so the correction keeps working whatever
 * happens to this app.
 */
final class HearingSettings {

    static final int MAX_MAKEUP_MB = 1200;

    private HearingSettings() {}

    static boolean isEnabled(ContentResolver cr) {
        return Settings.Global.getInt(cr, Settings.Global.HEARING_COMPENSATION_ENABLED, 0) == 1;
    }

    static void setEnabled(ContentResolver cr, boolean enabled) {
        Settings.Global.putInt(cr, Settings.Global.HEARING_COMPENSATION_ENABLED, enabled ? 1 : 0);
    }

    static int getStrength(ContentResolver cr) {
        return Settings.Global.getInt(cr, Settings.Global.HEARING_COMPENSATION_STRENGTH,
                HearingProfile.DEFAULT_STRENGTH_PERCENT);
    }

    static int getMakeupMb(ContentResolver cr) {
        return Settings.Global.getInt(cr, Settings.Global.HEARING_COMPENSATION_MAKEUP, 0);
    }

    static void setMakeupMb(ContentResolver cr, int mb) {
        Settings.Global.putInt(cr, Settings.Global.HEARING_COMPENSATION_MAKEUP,
                Math.max(0, Math.min(MAX_MAKEUP_MB, mb)));
    }

    static float[][] getThresholds(ContentResolver cr) {
        return HearingProfile.decodeThresholds(
                Settings.Global.getString(cr, Settings.Global.HEARING_COMPENSATION_THRESHOLDS));
    }

    static boolean hasProfile(ContentResolver cr) {
        return getThresholds(cr) != null;
    }

    /**
     * Stores a measurement and the curve derived from it. The raw thresholds are kept alongside
     * the curve so the strength can be changed later without making the user sit through the
     * beeps again.
     */
    static void saveProfile(ContentResolver cr, float[][] thresholds, int strengthPercent) {
        Settings.Global.putString(cr, Settings.Global.HEARING_COMPENSATION_THRESHOLDS,
                HearingProfile.encodeThresholds(thresholds));
        Settings.Global.putInt(cr, Settings.Global.HEARING_COMPENSATION_STRENGTH, strengthPercent);
        Settings.Global.putString(cr, Settings.Global.HEARING_COMPENSATION_GAINS,
                HearingProfile.encodeGains(HearingProfile.prescribe(thresholds, strengthPercent)));
    }

    /** Re-derives the curve from the stored measurement at a new strength. */
    static void updateStrength(ContentResolver cr, int strengthPercent) {
        final float[][] thresholds = getThresholds(cr);
        if (thresholds == null) {
            return;
        }
        saveProfile(cr, thresholds, strengthPercent);
    }

    static void clearProfile(ContentResolver cr) {
        Settings.Global.putString(cr, Settings.Global.HEARING_COMPENSATION_THRESHOLDS, null);
        Settings.Global.putString(cr, Settings.Global.HEARING_COMPENSATION_GAINS, null);
        Settings.Global.putInt(cr, Settings.Global.HEARING_COMPENSATION_MAKEUP, 0);
        Settings.Global.putInt(cr, Settings.Global.HEARING_COMPENSATION_ENABLED, 0);
    }

    /**
     * Takes the correction out of the signal path while a measurement runs — otherwise the test
     * would be measuring the listener through the very curve it is trying to derive, and each run
     * would chase the last one.
     */
    static void setBypass(ContentResolver cr, boolean bypass) {
        Settings.Global.putInt(cr, Settings.Global.HEARING_COMPENSATION_BYPASS, bypass ? 1 : 0);
    }
}
