/*
 * Copyright (C) 2015 The CyanogenMod Project
 *               2017-2019 The LineageOS Project
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

package org.lineageos.settings;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.preference.PreferenceManager;

import org.lineageos.settings.doze.DozeUtils;
import org.lineageos.settings.gpumanager.GpuManagerUtils;
import org.lineageos.settings.kernelmanager.KernelManagerUtils;
import org.lineageos.settings.thermal.ThermalUtils;
import org.lineageos.settings.refreshrate.RefreshUtils;
import org.lineageos.settings.utils.FileUtils;

public class BootCompletedReceiver extends BroadcastReceiver {
    private static final boolean DEBUG = false;
    private static final String TAG = "XiaomiParts";
    private static final String DC_DIMMING_ENABLE_KEY = "dc_dimming_enable";
    private static final String DC_DIMMING_NODE = "/sys/devices/platform/soc/soc:qcom,dsi-display-primary/dimlayer_exposure";
    private static final String HTSR_ENABLE_KEY = "htsr_enable";
    private static final String HTSR_FILE = "/sys/devices/virtual/touch/touch_dev/bump_sample_rate";

    // init starts the kernel post-boot script when sys.boot_completed is set, which happens
    // around the same time ACTION_BOOT_COMPLETED is sent. Give it time to finish before the
    // Kamis Stuff settings are restored, then apply them a second time to be safe.
    private static final long POST_BOOT_SETTLE_MS = 2500;
    private static final long POST_BOOT_REAPPLY_DELAY_MS = 1500;

    @Override
    public void onReceive(final Context context, Intent intent) {
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (DEBUG)
            Log.d(TAG, "Received boot completed intent");

        // The kernel post-boot script rewrites the CPU/GPU limits, so restoring our values
        // has to wait until it has run.
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            restoreSettingsAfterPostBoot(context);
            return;
        }

        DozeUtils.onBootCompleted(context);
        ThermalUtils.startService(context);
        RefreshUtils.startService(context);    
        
        // Touch Sampling
        boolean HTSREnabled = sharedPrefs.getBoolean(HTSR_ENABLE_KEY, false);
        FileUtils.writeLine(HTSR_FILE, HTSREnabled ? "1" : "0");

        // DC Dimming
        FileUtils.enableService(context);
        boolean dcDimmingEnabled = sharedPrefs.getBoolean(DC_DIMMING_ENABLE_KEY, false);
        FileUtils.writeLine(DC_DIMMING_NODE, dcDimmingEnabled ? "1" : "0");
    }

    /**
     * Waits for the kernel post-boot script, then restores the CPU/GPU settings the user
     * applied through Kamis Stuff.
     */
    private void restoreSettingsAfterPostBoot(final Context context) {
        final PendingResult pendingResult = goAsync();
        final Context appContext = context.getApplicationContext();

        new Thread(() -> {
            try {
                SystemClock.sleep(POST_BOOT_SETTLE_MS);
                KernelManagerUtils.restoreSavedSettingsOnBoot(appContext);
                GpuManagerUtils.restoreSavedSettingsOnBoot(appContext);

                SystemClock.sleep(POST_BOOT_REAPPLY_DELAY_MS);
                KernelManagerUtils.reapplySavedSettings(appContext);
                GpuManagerUtils.reapplySavedSettings(appContext);

                Log.i(TAG, "Kamis Stuff settings restored after boot");
            } catch (Throwable t) {
                Log.e(TAG, "Failed to restore Kamis Stuff settings", t);
            } finally {
                pendingResult.finish();
            }
        }, "KamisSettingsRestore").start();
    }
}
