/*
 * Copyright (C) 2025 KamiKaonashi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.lineageos.settings.gpumanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.TreeSet;

public class GpuManagerUtils {

    private static final String TAG = "GpuManager";

    private static final String GPU_BASE_PATH = "/sys/class/kgsl/kgsl-3d0";

    // GPU paths
    private static final String GPU_MODEL = "/gpu_model";
    private static final String GPU_AVAILABLE_FREQUENCIES = "/gpu_available_frequencies";
    private static final String GPU_CURRENT_FREQ = "/gpuclk";
    private static final String GPU_MIN_FREQ = "/devfreq/min_freq";
    private static final String GPU_MAX_FREQ = "/devfreq/max_freq";
    private static final String GPU_GOVERNOR = "/devfreq/governor";
    private static final String GPU_AVAILABLE_GOVERNORS = "/devfreq/available_governors";
    private static final String GPU_BUSY_PERCENTAGE = "/gpu_busy_percentage";
    private static final String GPU_TEMPERATURE = "/temp";
    private static final String GPU_THERMAL_PWRLEVEL = "/thermal_pwrlevel";
    private static final String GPU_FORCE_CLK_ON = "/force_clk_on";
    private static final String GPU_FORCE_BUS_ON = "/force_bus_on";
    private static final String GPU_FORCE_RAIL_ON = "/force_rail_on";
    private static final String GPU_FORCE_NO_NAP = "/force_no_nap";
    private static final String GPU_BUS_SPLIT = "/bus_split";

    // Snapshot of the kernel-provided values, taken before our first write.
    private static final String STOCK_PREFS = "gpu_manager_stock";
    private static final String KEY_STOCK_CAPTURED = "captured";
    private static final String KEY_STOCK_GOVERNOR = "governor";
    private static final String KEY_STOCK_MIN_FREQ = "min_freq";
    private static final String KEY_STOCK_MAX_FREQ = "max_freq";
    private static final String KEY_STOCK_FORCE_CLK_ON = "force_clk_on";
    private static final String KEY_STOCK_FORCE_BUS_ON = "force_bus_on";
    private static final String KEY_STOCK_FORCE_RAIL_ON = "force_rail_on";
    private static final String KEY_STOCK_FORCE_NO_NAP = "force_no_nap";
    private static final String KEY_STOCK_BUS_SPLIT = "bus_split";

    // Values applied by the user. These are restored after a reboot until reset.
    private static final String APPLIED_PREFS = "gpu_manager_applied";
    private static final String KEY_APPLIED = "saved";
    private static final String KEY_APPLIED_GOVERNOR = "governor";
    private static final String KEY_APPLIED_MIN_FREQ = "min_freq";
    private static final String KEY_APPLIED_MAX_FREQ = "max_freq";
    private static final String KEY_APPLIED_FORCE_CLK_ON = "force_clk_on";
    private static final String KEY_APPLIED_FORCE_BUS_ON = "force_bus_on";
    private static final String KEY_APPLIED_FORCE_RAIL_ON = "force_rail_on";
    private static final String KEY_APPLIED_FORCE_NO_NAP = "force_no_nap";
    private static final String KEY_APPLIED_BUS_SPLIT = "bus_split";

    public String getGpuModel() {
        String model = readValue(GPU_BASE_PATH + GPU_MODEL);
        return model != null ? model : "Unknown GPU";
    }

    public String[] getAvailableGovernors() {
        String[] governors = readList(GPU_BASE_PATH + GPU_AVAILABLE_GOVERNORS);
        if (governors != null && governors.length > 0) {
            return governors;
        }
        return new String[]{"msm-adreno-tz", "performance", "powersave", "simple_ondemand"};
    }

    /**
     * Returns all frequencies exposed by the kernel, sorted from lowest to highest.
     */
    public String[] getAvailableFrequencies() {
        return getFrequencies(null, null);
    }

    /**
     * Returns the selectable frequencies, including values that are currently in use but are
     * not part of the normally available list.
     */
    public String[] getSelectableFrequencies(String currentMin, String currentMax) {
        return getFrequencies(currentMin, currentMax);
    }

    private String[] getFrequencies(String currentMin, String currentMax) {
        TreeSet<Long> frequencies = new TreeSet<>();
        addFrequencies(frequencies, GPU_BASE_PATH + GPU_AVAILABLE_FREQUENCIES);
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

    public String getCurrentGovernor() {
        return readValue(GPU_BASE_PATH + GPU_GOVERNOR);
    }

    public String getCurrentFrequency() {
        return readFrequency(GPU_BASE_PATH + GPU_CURRENT_FREQ);
    }

    /**
     * Returns the currently configured minimum frequency, or {@code null} if it is unavailable.
     */
    public String getCurrentMinFrequency() {
        return readFrequency(GPU_BASE_PATH + GPU_MIN_FREQ);
    }

    /**
     * Returns the currently configured maximum frequency, or {@code null} if it is unavailable.
     */
    public String getCurrentMaxFrequency() {
        return readFrequency(GPU_BASE_PATH + GPU_MAX_FREQ);
    }

    public String getGpuBusyPercentage() {
        String value = readValue(GPU_BASE_PATH + GPU_BUSY_PERCENTAGE);
        return value != null ? value : "0";
    }

    public String getGpuTemperature() {
        try {
            String rawTemp = readFile(GPU_BASE_PATH + GPU_TEMPERATURE).trim();
            int tempMilliCelsius = Integer.parseInt(rawTemp);
            // Convert millidegrees Celsius to degrees Celsius
            double tempCelsius = tempMilliCelsius / 1000.0;
            return String.format("%.1f", tempCelsius);
        } catch (Exception e) {
            return "0";
        }
    }

    public String getThermalPowerLevel() {
        String value = readValue(GPU_BASE_PATH + GPU_THERMAL_PWRLEVEL);
        return value != null ? value : "0";
    }

    public boolean getForceClkOn() {
        return readBoolean(GPU_BASE_PATH + GPU_FORCE_CLK_ON);
    }

    public boolean getForceBusOn() {
        return readBoolean(GPU_BASE_PATH + GPU_FORCE_BUS_ON);
    }

    public boolean getForceRailOn() {
        return readBoolean(GPU_BASE_PATH + GPU_FORCE_RAIL_ON);
    }

    public boolean getForceNoNap() {
        return readBoolean(GPU_BASE_PATH + GPU_FORCE_NO_NAP);
    }

    public boolean getBusSplit() {
        return readBoolean(GPU_BASE_PATH + GPU_BUS_SPLIT);
    }

    public boolean setGovernor(String governor) {
        if (governor == null || governor.isEmpty()) {
            return false;
        }
        return writeFile(GPU_BASE_PATH + GPU_GOVERNOR, governor);
    }

    /**
     * Applies a frequency range in an order that keeps the range valid after every write.
     */
    public boolean setFrequencyRange(String minFreq, String maxFreq) {
        Long requestedMin = parseFrequency(minFreq);
        Long requestedMax = parseFrequency(maxFreq);
        if (requestedMin == null || requestedMax == null || requestedMin > requestedMax) {
            return false;
        }

        String minPath = GPU_BASE_PATH + GPU_MIN_FREQ;
        String maxPath = GPU_BASE_PATH + GPU_MAX_FREQ;
        Long currentMin = parseFrequency(readFrequency(minPath));
        Long currentMax = parseFrequency(readFrequency(maxPath));

        if (currentMin != null && currentMax != null && requestedMin > currentMax) {
            // Raise the maximum first, otherwise the new minimum would be rejected.
            return writeFile(maxPath, maxFreq) && writeFile(minPath, minFreq);
        }

        if (currentMin != null && currentMax != null && requestedMax < currentMin) {
            // Lower the minimum first, otherwise the new maximum would be rejected.
            return writeFile(minPath, minFreq) && writeFile(maxPath, maxFreq);
        }

        return writeFile(minPath, minFreq) && writeFile(maxPath, maxFreq);
    }

    public boolean setForceClkOn(boolean enabled) {
        return writeBoolean(GPU_BASE_PATH + GPU_FORCE_CLK_ON, enabled);
    }

    public boolean setForceBusOn(boolean enabled) {
        return writeBoolean(GPU_BASE_PATH + GPU_FORCE_BUS_ON, enabled);
    }

    public boolean setForceRailOn(boolean enabled) {
        return writeBoolean(GPU_BASE_PATH + GPU_FORCE_RAIL_ON, enabled);
    }

    public boolean setForceNoNap(boolean enabled) {
        return writeBoolean(GPU_BASE_PATH + GPU_FORCE_NO_NAP, enabled);
    }

    public boolean setBusSplit(boolean enabled) {
        return writeBoolean(GPU_BASE_PATH + GPU_BUS_SPLIT, enabled);
    }

    /**
     * Saves the values that were active before Kamis Stuff changed anything. The snapshot is
     * intentionally taken only once per boot; the boot receiver clears it again.
     */
    public boolean captureStockValues(Context context) {
        SharedPreferences prefs = getStockPreferences(context);
        if (prefs.getBoolean(KEY_STOCK_CAPTURED, false)) {
            return true;
        }

        SharedPreferences.Editor editor = prefs.edit();
        String governor = getCurrentGovernor();
        String minFreq = getCurrentMinFrequency();
        String maxFreq = getCurrentMaxFrequency();
        if (governor != null) {
            editor.putString(KEY_STOCK_GOVERNOR, governor);
        }
        if (minFreq != null) {
            editor.putString(KEY_STOCK_MIN_FREQ, minFreq);
        }
        if (maxFreq != null) {
            editor.putString(KEY_STOCK_MAX_FREQ, maxFreq);
        }

        editor.putBoolean(KEY_STOCK_FORCE_CLK_ON, getForceClkOn());
        editor.putBoolean(KEY_STOCK_FORCE_BUS_ON, getForceBusOn());
        editor.putBoolean(KEY_STOCK_FORCE_RAIL_ON, getForceRailOn());
        editor.putBoolean(KEY_STOCK_FORCE_NO_NAP, getForceNoNap());
        editor.putBoolean(KEY_STOCK_BUS_SPLIT, getBusSplit());
        return editor.putBoolean(KEY_STOCK_CAPTURED, true).commit();
    }

    public boolean hasStockValues(Context context) {
        return getStockPreferences(context).getBoolean(KEY_STOCK_CAPTURED, false);
    }

    /**
     * Restores the values captured before the first modification.
     */
    public boolean restoreStockValues(Context context) {
        SharedPreferences prefs = getStockPreferences(context);
        if (!prefs.getBoolean(KEY_STOCK_CAPTURED, false)) {
            return false;
        }

        boolean success = true;
        String governor = prefs.getString(KEY_STOCK_GOVERNOR, null);
        if (governor != null) {
            success &= setGovernor(governor);
        }

        String minFreq = prefs.getString(KEY_STOCK_MIN_FREQ, null);
        String maxFreq = prefs.getString(KEY_STOCK_MAX_FREQ, null);
        if (minFreq != null && maxFreq != null) {
            success &= setFrequencyRange(minFreq, maxFreq);
        }

        if (prefs.contains(KEY_STOCK_FORCE_CLK_ON)) {
            success &= setForceClkOn(prefs.getBoolean(KEY_STOCK_FORCE_CLK_ON, false));
        }
        if (prefs.contains(KEY_STOCK_FORCE_BUS_ON)) {
            success &= setForceBusOn(prefs.getBoolean(KEY_STOCK_FORCE_BUS_ON, false));
        }
        if (prefs.contains(KEY_STOCK_FORCE_RAIL_ON)) {
            success &= setForceRailOn(prefs.getBoolean(KEY_STOCK_FORCE_RAIL_ON, false));
        }
        if (prefs.contains(KEY_STOCK_FORCE_NO_NAP)) {
            success &= setForceNoNap(prefs.getBoolean(KEY_STOCK_FORCE_NO_NAP, false));
        }
        if (prefs.contains(KEY_STOCK_BUS_SPLIT)) {
            success &= setBusSplit(prefs.getBoolean(KEY_STOCK_BUS_SPLIT, false));
        }
        return success;
    }

    /**
     * Saves the currently active settings so they can be applied again after a reboot.
     */
    public boolean saveAppliedSettings(Context context) {
        SharedPreferences.Editor editor = getAppliedPreferences(context).edit().clear();
        boolean saved = false;

        String governor = getCurrentGovernor();
        if (governor != null) {
            editor.putString(KEY_APPLIED_GOVERNOR, governor);
            saved = true;
        }

        String minFreq = getCurrentMinFrequency();
        String maxFreq = getCurrentMaxFrequency();
        if (minFreq != null) {
            editor.putString(KEY_APPLIED_MIN_FREQ, minFreq);
            saved = true;
        }
        if (maxFreq != null) {
            editor.putString(KEY_APPLIED_MAX_FREQ, maxFreq);
            saved = true;
        }

        editor.putBoolean(KEY_APPLIED_FORCE_CLK_ON, getForceClkOn());
        editor.putBoolean(KEY_APPLIED_FORCE_BUS_ON, getForceBusOn());
        editor.putBoolean(KEY_APPLIED_FORCE_RAIL_ON, getForceRailOn());
        editor.putBoolean(KEY_APPLIED_FORCE_NO_NAP, getForceNoNap());
        editor.putBoolean(KEY_APPLIED_BUS_SPLIT, getBusSplit());

        if (!saved) {
            return false;
        }
        return editor.putBoolean(KEY_APPLIED, true).commit();
    }

    public boolean hasAppliedSettings(Context context) {
        return getAppliedPreferences(context).getBoolean(KEY_APPLIED, false);
    }

    /**
     * Applies the settings saved by {@link #saveAppliedSettings(Context)}.
     */
    public boolean applySavedSettings(Context context) {
        SharedPreferences prefs = getAppliedPreferences(context);
        if (!prefs.getBoolean(KEY_APPLIED, false)) {
            return false;
        }

        boolean success = true;
        String governor = prefs.getString(KEY_APPLIED_GOVERNOR, null);
        if (governor != null) {
            success &= setGovernor(governor);
        }

        String minFreq = prefs.getString(KEY_APPLIED_MIN_FREQ, null);
        String maxFreq = prefs.getString(KEY_APPLIED_MAX_FREQ, null);
        if (minFreq != null && maxFreq != null) {
            success &= setFrequencyRange(minFreq, maxFreq);
        }

        if (prefs.contains(KEY_APPLIED_FORCE_CLK_ON)) {
            success &= setForceClkOn(prefs.getBoolean(KEY_APPLIED_FORCE_CLK_ON, false));
        }
        if (prefs.contains(KEY_APPLIED_FORCE_BUS_ON)) {
            success &= setForceBusOn(prefs.getBoolean(KEY_APPLIED_FORCE_BUS_ON, false));
        }
        if (prefs.contains(KEY_APPLIED_FORCE_RAIL_ON)) {
            success &= setForceRailOn(prefs.getBoolean(KEY_APPLIED_FORCE_RAIL_ON, false));
        }
        if (prefs.contains(KEY_APPLIED_FORCE_NO_NAP)) {
            success &= setForceNoNap(prefs.getBoolean(KEY_APPLIED_FORCE_NO_NAP, false));
        }
        if (prefs.contains(KEY_APPLIED_BUS_SPLIT)) {
            success &= setBusSplit(prefs.getBoolean(KEY_APPLIED_BUS_SPLIT, false));
        }
        return success;
    }

    /**
     * Called at boot after the kernel scripts have run. The current kernel values become the
     * new reset target, then any user-applied settings are restored.
     */
    public static void restoreSavedSettingsOnBoot(Context context) {
        GpuManagerUtils utils = new GpuManagerUtils();
        clearStockValues(context);
        if (!utils.captureStockValues(context)) {
            Log.w(TAG, "Could not capture the stock GPU values after boot");
        }
        if (!utils.hasAppliedSettings(context)) {
            return;
        }
        if (!utils.applySavedSettings(context)) {
            Log.w(TAG, "Could not restore the saved GPU settings");
        }
    }

    /**
     * Applies the saved settings again without touching the stock snapshot. Used to win the
     * race against a slow kernel post-boot script.
     */
    public static void reapplySavedSettings(Context context) {
        GpuManagerUtils utils = new GpuManagerUtils();
        if (utils.hasAppliedSettings(context) && !utils.applySavedSettings(context)) {
            Log.w(TAG, "Could not re-apply the saved GPU settings");
        }
    }

    public static void clearAppliedSettings(Context context) {
        getAppliedPreferences(context).edit().clear().commit();
    }

    public static void clearStockValues(Context context) {
        getStockPreferences(context).edit().clear().commit();
    }

    private static SharedPreferences getStockPreferences(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(STOCK_PREFS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences getAppliedPreferences(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(APPLIED_PREFS, Context.MODE_PRIVATE);
    }

    private static Long parseFrequency(String frequency) {
        if (frequency == null) {
            return null;
        }
        try {
            long value = Long.parseLong(frequency);
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
            // The kernel may not expose this file.
        }
    }

    private static void addFrequency(TreeSet<Long> frequencies, String frequency) {
        Long value = parseFrequency(frequency);
        if (value != null) {
            frequencies.add(value);
        }
    }

    private static String readFrequency(String path) {
        String value = readValue(path);
        return parseFrequency(value) != null ? value : null;
    }

    private static boolean readBoolean(String path) {
        return "1".equals(readValue(path));
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

    private static boolean writeBoolean(String path, boolean enabled) {
        return writeFile(path, enabled ? "1" : "0");
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
