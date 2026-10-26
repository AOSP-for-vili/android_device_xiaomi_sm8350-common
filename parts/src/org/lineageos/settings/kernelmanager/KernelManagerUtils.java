/*
 * Copyright (C) 2025 KamiKaonashi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.lineageos.settings.kernelmanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.TreeSet;

public class KernelManagerUtils {

    private static final String TAG = "KernelManager";

    public static final int EFFICIENCY_CLUSTER = 0;  // Policy 0 - Little cores (A55)
    public static final int PERFORMANCE_CLUSTER = 4; // Policy 4 - Big cores (A78)
    public static final int PRIME_CLUSTER = 7;       // Policy 7 - Prime core (X1)

    private static final int[] POLICIES = {EFFICIENCY_CLUSTER, PERFORMANCE_CLUSTER, PRIME_CLUSTER};

    // CPU frequency and governor paths
    private static final String CPU_BASE_PATH = "/sys/devices/system/cpu/cpufreq/policy";
    private static final String SCALING_GOVERNOR = "/scaling_governor";
    private static final String SCALING_MIN_FREQ = "/scaling_min_freq";
    private static final String SCALING_MAX_FREQ = "/scaling_max_freq";
    private static final String SCALING_AVAILABLE_GOVERNORS = "/scaling_available_governors";
    private static final String SCALING_AVAILABLE_FREQUENCIES = "/scaling_available_frequencies";
    private static final String CPUINFO_MIN_FREQ = "/cpuinfo_min_freq";
    private static final String CPUINFO_MAX_FREQ = "/cpuinfo_max_freq";

    // The reset target is derived from hardware values, never from the live policy limits.
    // scaling_min_freq/scaling_max_freq report the aggregate of every PM QoS request: input
    // boost (cpu_boost) raises the minimum after every touch, and thermal/perf code can
    // lower the maximum. Sampling them, even over a window, can therefore store a boost or
    // a thermal cap as the "stock" value and make Reset restore the wrong limits.
    //
    // init.kernel.post_boot-lahaina.sh sets policy0/scaling_min_freq to 691200. The gold
    // clusters are left at their hardware minimum, and no cluster gets an explicit maximum,
    // so cpuinfo_max_freq is the supported maximum.
    private static final long EFFICIENCY_DEFAULT_MIN_FREQ = 691200;
    private static final String DEFAULT_GOVERNOR = "schedutil";

    // Values requested by the user. These are restored after a reboot until reset.
    private static final String APPLIED_PREFS = "kernel_manager_applied";
    private static final String KEY_APPLIED = "saved";
    private static final String KEY_APPLIED_GOVERNOR = "governor";
    private static final String KEY_APPLIED_MIN_FREQ = "min_freq_";
    private static final String KEY_APPLIED_MAX_FREQ = "max_freq_";
    // Version 1 stored the policy limits read back after applying, version 2 could store
    // values taken from the old boot snapshot. Both included input boost and thermal caps,
    // so they are discarded once instead of being restored.
    private static final String KEY_APPLIED_VERSION = "format_version";
    private static final int APPLIED_FORMAT_VERSION = 3;

    public String[] getAvailableGovernors() {
        String[] governors = readList(CPU_BASE_PATH + EFFICIENCY_CLUSTER
                + SCALING_AVAILABLE_GOVERNORS);
        if (governors != null && governors.length > 0) {
            return governors;
        }
        return new String[]{"schedutil", "performance", "powersave", "ondemand", "conservative"};
    }

    /**
     * Returns all frequencies exposed by the kernel, sorted from lowest to highest.
     */
    public String[] getAvailableFrequencies(int cluster) {
        return getFrequencies(cluster, null, null);
    }

    /**
     * Returns the selectable frequencies. Only the regular frequency table is used:
     * scaling_boost_frequencies contains OPPs that are not part of the table, and the
     * kernel silently clamps policy limits to cpuinfo_min_freq..cpuinfo_max_freq, so
     * offering them as minimum/maximum would produce values that cannot be applied.
     */
    public String[] getSelectableFrequencies(int cluster, String currentMin, String currentMax) {
        return getFrequencies(cluster, currentMin, currentMax);
    }

    private String[] getFrequencies(int cluster, String currentMin, String currentMax) {
        TreeSet<Long> frequencies = new TreeSet<>();
        addFrequencies(frequencies, CPU_BASE_PATH + cluster + SCALING_AVAILABLE_FREQUENCIES);
        addFrequency(frequencies, readValue(CPU_BASE_PATH + cluster + CPUINFO_MIN_FREQ));
        addFrequency(frequencies, readValue(CPU_BASE_PATH + cluster + CPUINFO_MAX_FREQ));
        addFrequency(frequencies, currentMin);
        addFrequency(frequencies, currentMax);

        if (frequencies.isEmpty()) {
            return null;
        }

        String[] result = new String[frequencies.size()];
        int index = 0;
        for (Long frequency : frequencies) {
            result[index++] = String.valueOf(frequency);
        }
        return result;
    }

    public String getCurrentGovernor(int cluster) {
        return readValue(CPU_BASE_PATH + cluster + SCALING_GOVERNOR);
    }

    /**
     * Returns the currently configured minimum frequency, or {@code null} if it is unavailable.
     */
    public String getCurrentMinFrequency(int cluster) {
        return readFrequency(CPU_BASE_PATH + cluster + SCALING_MIN_FREQ);
    }

    /**
     * Returns the currently configured maximum frequency, or {@code null} if it is unavailable.
     */
    public String getCurrentMaxFrequency(int cluster) {
        return readFrequency(CPU_BASE_PATH + cluster + SCALING_MAX_FREQ);
    }

    /**
     * Returns the governor used by the kernel after boot.
     */
    public String getDefaultGovernor() {
        String[] governors = readList(CPU_BASE_PATH + EFFICIENCY_CLUSTER
                + SCALING_AVAILABLE_GOVERNORS);
        if (governors != null) {
            for (String governor : governors) {
                if (DEFAULT_GOVERNOR.equals(governor)) {
                    return DEFAULT_GOVERNOR;
                }
            }
        }

        // schedutil is not available on this kernel, so keep whatever is running instead
        // of switching to an arbitrary governor.
        return getCurrentGovernor(EFFICIENCY_CLUSTER);
    }

    /**
     * Returns the minimum frequency the kernel uses after boot.
     */
    public String getDefaultMinFrequency(int cluster) {
        if (cluster == EFFICIENCY_CLUSTER) {
            return clampToCpuLimits(cluster, EFFICIENCY_DEFAULT_MIN_FREQ);
        }
        return readFrequency(CPU_BASE_PATH + cluster + CPUINFO_MIN_FREQ);
    }

    /**
     * Returns the highest frequency the cluster supports.
     */
    public String getDefaultMaxFrequency(int cluster) {
        return readFrequency(CPU_BASE_PATH + cluster + CPUINFO_MAX_FREQ);
    }

    /**
     * Returns the value that should be shown for the governor: the value the user applied
     * or the kernel default.
     */
    public String getDisplayedGovernor(Context context) {
        SharedPreferences applied = getMigratedAppliedPreferences(context);
        if (applied.getBoolean(KEY_APPLIED, false)) {
            String value = applied.getString(KEY_APPLIED_GOVERNOR, null);
            if (value != null) {
                return value;
            }
        }

        String defaultValue = getDefaultGovernor();
        if (defaultValue != null) {
            return defaultValue;
        }

        return getCurrentGovernor(EFFICIENCY_CLUSTER);
    }

    /**
     * Returns the value that should be shown as the minimum frequency for a cluster.
     */
    public String getDisplayedMinFrequency(Context context, int cluster) {
        SharedPreferences applied = getMigratedAppliedPreferences(context);
        if (applied.getBoolean(KEY_APPLIED, false)) {
            String value = applied.getString(KEY_APPLIED_MIN_FREQ + cluster, null);
            if (value != null) {
                return value;
            }
        }

        String defaultValue = getDefaultMinFrequency(cluster);
        if (defaultValue != null) {
            return defaultValue;
        }

        return getCurrentMinFrequency(cluster);
    }

    /**
     * Returns the value that should be shown as the maximum frequency for a cluster.
     */
    public String getDisplayedMaxFrequency(Context context, int cluster) {
        SharedPreferences applied = getMigratedAppliedPreferences(context);
        if (applied.getBoolean(KEY_APPLIED, false)) {
            String value = applied.getString(KEY_APPLIED_MAX_FREQ + cluster, null);
            if (value != null) {
                return value;
            }
        }

        String defaultValue = getDefaultMaxFrequency(cluster);
        if (defaultValue != null) {
            return defaultValue;
        }

        return getCurrentMaxFrequency(cluster);
    }

    public boolean setGovernor(String governor) {
        if (governor == null || governor.isEmpty()) {
            return false;
        }

        boolean success = true;
        for (int cluster : POLICIES) {
            success &= setGovernor(cluster, governor);
        }
        return success;
    }

    public boolean setGovernor(int cluster, String governor) {
        if (governor == null || governor.isEmpty()) {
            return false;
        }
        return writeFile(CPU_BASE_PATH + cluster + SCALING_GOVERNOR, governor);
    }

    /**
     * Applies a frequency range, clamping it to the range the kernel accepts. The limits are
     * requests, so the values written here are the values that will be reported by the
     * "displayed" accessors until the user changes them again.
     */
    public boolean setFrequencyRange(int cluster, String minFreq, String maxFreq) {
        Long requestedMin = parseFrequency(minFreq);
        Long requestedMax = parseFrequency(maxFreq);
        if (requestedMin == null || requestedMax == null) {
            return false;
        }

        Long lowerBound = parseFrequency(readValue(CPU_BASE_PATH + cluster + CPUINFO_MIN_FREQ));
        Long upperBound = parseFrequency(readValue(CPU_BASE_PATH + cluster + CPUINFO_MAX_FREQ));
        if (lowerBound != null && requestedMin < lowerBound) {
            requestedMin = lowerBound;
        }
        if (upperBound != null && requestedMin > upperBound) {
            requestedMin = upperBound;
        }
        if (lowerBound != null && requestedMax < lowerBound) {
            requestedMax = lowerBound;
        }
        if (upperBound != null && requestedMax > upperBound) {
            requestedMax = upperBound;
        }
        if (requestedMin > requestedMax) {
            requestedMax = requestedMin;
        }

        String min = String.valueOf(requestedMin);
        String max = String.valueOf(requestedMax);

        String basePath = CPU_BASE_PATH + cluster;
        Long currentMax = parseFrequency(readValue(basePath + SCALING_MAX_FREQ));
        boolean success;
        if (currentMax != null && requestedMin > currentMax) {
            // Raise the maximum first, otherwise the new minimum is clamped to the old one.
            success = writeFile(basePath + SCALING_MAX_FREQ, max)
                    && writeFile(basePath + SCALING_MIN_FREQ, min);
        } else {
            // Lower the minimum first, otherwise the new maximum is clamped to the old one.
            success = writeFile(basePath + SCALING_MIN_FREQ, min)
                    && writeFile(basePath + SCALING_MAX_FREQ, max);
        }
        if (!success) {
            return false;
        }

        // Verify the result. A boost or a cap can hold one of the limits while the writes
        // happen, so a mismatch is not treated as a failure: the kernel keeps the requested
        // values and uses them again when the transient request goes away. A single retry
        // covers another writer changing the request between our two writes.
        Long effectiveMax = parseFrequency(readValue(basePath + SCALING_MAX_FREQ));
        if (effectiveMax != null && effectiveMax > requestedMax) {
            success &= writeFile(basePath + SCALING_MAX_FREQ, max);
        }
        Long effectiveMin = parseFrequency(readValue(basePath + SCALING_MIN_FREQ));
        if (effectiveMin != null && effectiveMin > requestedMin) {
            success &= writeFile(basePath + SCALING_MIN_FREQ, min);
        }

        Long finalMin = parseFrequency(readValue(basePath + SCALING_MIN_FREQ));
        Long finalMax = parseFrequency(readValue(basePath + SCALING_MAX_FREQ));
        if (finalMin != null && finalMin > requestedMin) {
            Log.w(TAG, "CPU" + cluster + " minimum is still " + finalMin
                    + " kHz after requesting " + requestedMin + " kHz");
        }
        Log.d(TAG, "CPU" + cluster + " limits requested " + min + "-" + max + ", effective "
                + finalMin + "-" + finalMax);
        return success;
    }

    /**
     * Restores the kernel defaults: schedutil, the post-boot minimum on the little cluster,
     * the hardware minimum on the gold clusters and the supported maximum on every cluster.
     */
    public boolean resetToDefaults(Context context) {
        // Resetting also stops the saved settings from being restored on the next boot.
        clearAppliedSettings(context);

        boolean success = true;
        String governor = getDefaultGovernor();
        if (governor != null) {
            success &= setGovernor(governor);
        }

        for (int cluster : POLICIES) {
            String minFreq = getDefaultMinFrequency(cluster);
            String maxFreq = getDefaultMaxFrequency(cluster);
            if (minFreq != null && maxFreq != null) {
                success &= setFrequencyRange(cluster, minFreq, maxFreq);
            } else {
                Log.w(TAG, "No default limits available for CPU" + cluster);
                success = false;
            }
        }
        return success;
    }

    /**
     * Saves the values selected by the user. The requested values are stored instead of
     * reading back the policy limits, because those include transient requests (input
     * boost, thermal caps) and would make the saved values drift on every apply.
     *
     * @param minFreqs minimum frequency per cluster, in {@link #POLICIES} order
     * @param maxFreqs maximum frequency per cluster, in {@link #POLICIES} order
     */
    public boolean saveAppliedSettings(Context context, String governor,
            String[] minFreqs, String[] maxFreqs) {
        SharedPreferences.Editor editor = getMigratedAppliedPreferences(context).edit().clear();
        boolean saved = false;

        if (governor != null && !governor.isEmpty()) {
            editor.putString(KEY_APPLIED_GOVERNOR, governor);
            saved = true;
        }

        for (int i = 0; i < POLICIES.length; i++) {
            int cluster = POLICIES[i];

            String minFreq = minFreqs != null && i < minFreqs.length ? minFreqs[i] : null;
            String maxFreq = maxFreqs != null && i < maxFreqs.length ? maxFreqs[i] : null;

            Long minValue = parseFrequency(minFreq);
            Long maxValue = parseFrequency(maxFreq);
            if (minValue == null || maxValue == null) {
                continue;
            }

            Long min = parseFrequency(clampToCpuLimits(cluster, minValue));
            Long max = parseFrequency(clampToCpuLimits(cluster, maxValue));
            if (min == null || max == null) {
                continue;
            }
            if (min > max) {
                max = min;
            }

            editor.putString(KEY_APPLIED_MIN_FREQ + cluster, String.valueOf(min));
            editor.putString(KEY_APPLIED_MAX_FREQ + cluster, String.valueOf(max));
            saved = true;
        }

        if (!saved) {
            editor.commit();
            return false;
        }
        return editor.putBoolean(KEY_APPLIED, true)
                .putInt(KEY_APPLIED_VERSION, APPLIED_FORMAT_VERSION)
                .commit();
    }

    public boolean hasAppliedSettings(Context context) {
        return getMigratedAppliedPreferences(context).getBoolean(KEY_APPLIED, false);
    }

    /**
     * Applies the settings saved by {@link #saveAppliedSettings(Context, String, String[], String[])}.
     */
    public boolean applySavedSettings(Context context) {
        SharedPreferences prefs = getMigratedAppliedPreferences(context);
        if (!prefs.getBoolean(KEY_APPLIED, false)) {
            return false;
        }

        boolean success = true;
        String governor = prefs.getString(KEY_APPLIED_GOVERNOR, null);
        if (governor != null) {
            success &= setGovernor(governor);
        }

        for (int cluster : POLICIES) {
            String minFreq = prefs.getString(KEY_APPLIED_MIN_FREQ + cluster, null);
            String maxFreq = prefs.getString(KEY_APPLIED_MAX_FREQ + cluster, null);
            if (minFreq != null && maxFreq != null) {
                success &= setFrequencyRange(cluster, minFreq, maxFreq);
            }
        }
        return success;
    }

    /**
     * Called at boot after the kernel scripts have run, to restore the user-applied settings.
     */
    public static void restoreSavedSettingsOnBoot(Context context) {
        KernelManagerUtils utils = new KernelManagerUtils();
        if (!utils.hasAppliedSettings(context)) {
            return;
        }
        if (!utils.applySavedSettings(context)) {
            Log.w(TAG, "Could not restore the saved CPU settings");
        }
    }

    /**
     * Applies the saved settings again. Used to win the race against a slow kernel
     * post-boot script.
     */
    public static void reapplySavedSettings(Context context) {
        KernelManagerUtils utils = new KernelManagerUtils();
        if (utils.hasAppliedSettings(context) && !utils.applySavedSettings(context)) {
            Log.w(TAG, "Could not re-apply the saved CPU settings");
        }
    }

    public static void clearAppliedSettings(Context context) {
        getAppliedPreferences(context).edit().clear()
                .putInt(KEY_APPLIED_VERSION, APPLIED_FORMAT_VERSION)
                .commit();
    }

    private static SharedPreferences getAppliedPreferences(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(APPLIED_PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Returns the applied settings, discarding them once if they were written by a version
     * that could store input boost and thermal caps instead of the requested values.
     */
    private static SharedPreferences getMigratedAppliedPreferences(Context context) {
        SharedPreferences prefs = getAppliedPreferences(context);
        if (prefs.getInt(KEY_APPLIED_VERSION, 0) >= APPLIED_FORMAT_VERSION) {
            return prefs;
        }
        if (prefs.getBoolean(KEY_APPLIED, false)) {
            Log.i(TAG, "Discarding CPU settings saved by an older version");
        }
        prefs.edit().clear()
                .putInt(KEY_APPLIED_VERSION, APPLIED_FORMAT_VERSION)
                .commit();
        return prefs;
    }

    private static String clampToCpuLimits(int cluster, long frequency) {
        long value = frequency;
        Long lowerBound = parseFrequency(readValue(CPU_BASE_PATH + cluster + CPUINFO_MIN_FREQ));
        Long upperBound = parseFrequency(readValue(CPU_BASE_PATH + cluster + CPUINFO_MAX_FREQ));
        if (lowerBound != null && value < lowerBound) {
            value = lowerBound;
        }
        if (upperBound != null && value > upperBound) {
            value = upperBound;
        }
        return String.valueOf(value);
    }

    private static Long parseFrequency(String frequency) {
        if (frequency == null) {
            return null;
        }
        try {
            long value = Long.parseLong(frequency.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void addFrequencies(TreeSet<Long> frequencies, String path) {
        try {
            String contents = readFile(path);
            if (contents == null) {
                return;
            }
            for (String frequency : contents.trim().split("\\s+")) {
                addFrequency(frequencies, frequency);
            }
        } catch (IOException e) {
            Log.d(TAG, "No frequencies at " + path + ": " + e);
        }
    }

    private static void addFrequency(TreeSet<Long> frequencies, String frequency) {
        Long value = parseFrequency(frequency);
        if (value != null) {
            frequencies.add(value);
        }
    }

    private String readFrequency(String path) {
        String value = readValue(path);
        return parseFrequency(value) != null ? value : null;
    }

    private static String readValue(String path) {
        try {
            return readFile(path).trim();
        } catch (Exception e) {
            Log.d(TAG, "Failed to read " + path + ": " + e);
            return null;
        }
    }

    private static String[] readList(String path) {
        String contents = readValue(path);
        if (contents == null || contents.isEmpty()) {
            return null;
        }
        return contents.split("\\s+");
    }

    private static String readFile(String path) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            return line != null ? line : "";
        }
    }

    private static boolean writeFile(String path, String value) {
        try (FileWriter writer = new FileWriter(path)) {
            writer.write(value);
            writer.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to write \"" + value + "\" to " + path + ": " + e);
            return false;
        }
    }
}
