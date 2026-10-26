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
import android.os.Bundle;
import android.os.Handler;
import android.widget.Toast;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragment;
import androidx.preference.SwitchPreference;
import org.lineageos.settings.R;

public class GpuManagerFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_GPU_GOVERNOR = "gpu_governor";
    private static final String KEY_GPU_MIN_FREQ = "gpu_min_freq";
    private static final String KEY_GPU_MAX_FREQ = "gpu_max_freq";
    private static final String KEY_GPU_CURRENT_FREQ = "gpu_current_freq";
    private static final String KEY_GPU_MODEL = "gpu_model";
    private static final String KEY_GPU_BUSY_PERCENTAGE = "gpu_busy_percentage";
    private static final String KEY_GPU_TEMPERATURE = "gpu_temperature";
    private static final String KEY_GPU_THERMAL_PWRLEVEL = "gpu_thermal_pwrlevel";
    private static final String KEY_GPU_FORCE_CLK_ON = "gpu_force_clk_on";
    private static final String KEY_GPU_FORCE_BUS_ON = "gpu_force_bus_on";
    private static final String KEY_GPU_FORCE_RAIL_ON = "gpu_force_rail_on";
    private static final String KEY_GPU_FORCE_NO_NAP = "gpu_force_no_nap";
    private static final String KEY_GPU_BUS_SPLIT = "gpu_bus_split";
    private static final String KEY_APPLY_GPU_SETTINGS = "apply_gpu_settings";
    private static final String KEY_RESET_GPU_SETTINGS = "reset_gpu_settings";

    private GpuManagerUtils mGpuUtils;
    private Handler mHandler;
    private Runnable mUpdateRunnable;

    // Preferences
    private ListPreference mGovernorPreference;
    private ListPreference mMinFreqPreference, mMaxFreqPreference;
    private Preference mCurrentFreqPreference;
    private Preference mGpuModelPreference;
    private Preference mGpuBusyPreference;
    private Preference mGpuTemperaturePreference;
    private Preference mThermalPowerLevelPreference;
    private SwitchPreference mForceClkOnPreference;
    private SwitchPreference mForceBusOnPreference;
    private SwitchPreference mForceRailOnPreference;
    private SwitchPreference mForceNoNapPreference;
    private SwitchPreference mBusSplitPreference;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.gpu_manager_settings, rootKey);
        mGpuUtils = new GpuManagerUtils();
        mHandler = new Handler();

        initializePreferences();
        loadCurrentSettings();
        startPeriodicUpdates();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mGpuUtils != null) {
            loadCurrentSettings();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopPeriodicUpdates();
    }

    private void initializePreferences() {
        mGovernorPreference = (ListPreference) findPreference(KEY_GPU_GOVERNOR);
        mMinFreqPreference = (ListPreference) findPreference(KEY_GPU_MIN_FREQ);
        mMaxFreqPreference = (ListPreference) findPreference(KEY_GPU_MAX_FREQ);
        mCurrentFreqPreference = findPreference(KEY_GPU_CURRENT_FREQ);
        mGpuModelPreference = findPreference(KEY_GPU_MODEL);
        mGpuBusyPreference = findPreference(KEY_GPU_BUSY_PERCENTAGE);
        mGpuTemperaturePreference = findPreference(KEY_GPU_TEMPERATURE);
        mThermalPowerLevelPreference = findPreference(KEY_GPU_THERMAL_PWRLEVEL);
        mForceClkOnPreference = (SwitchPreference) findPreference(KEY_GPU_FORCE_CLK_ON);
        mForceBusOnPreference = (SwitchPreference) findPreference(KEY_GPU_FORCE_BUS_ON);
        mForceRailOnPreference = (SwitchPreference) findPreference(KEY_GPU_FORCE_RAIL_ON);
        mForceNoNapPreference = (SwitchPreference) findPreference(KEY_GPU_FORCE_NO_NAP);
        mBusSplitPreference = (SwitchPreference) findPreference(KEY_GPU_BUS_SPLIT);

        if (mGovernorPreference != null) {
            mGovernorPreference.setPersistent(false);
            mGovernorPreference.setOnPreferenceChangeListener(this);
        }
        if (mMinFreqPreference != null) {
            mMinFreqPreference.setPersistent(false);
            mMinFreqPreference.setOnPreferenceChangeListener(this);
        }
        if (mMaxFreqPreference != null) {
            mMaxFreqPreference.setPersistent(false);
            mMaxFreqPreference.setOnPreferenceChangeListener(this);
        }

        if (mForceClkOnPreference != null) {
            mForceClkOnPreference.setPersistent(false);
            mForceClkOnPreference.setOnPreferenceChangeListener(this);
        }
        if (mForceBusOnPreference != null) {
            mForceBusOnPreference.setPersistent(false);
            mForceBusOnPreference.setOnPreferenceChangeListener(this);
        }
        if (mForceRailOnPreference != null) {
            mForceRailOnPreference.setPersistent(false);
            mForceRailOnPreference.setOnPreferenceChangeListener(this);
        }
        if (mForceNoNapPreference != null) {
            mForceNoNapPreference.setPersistent(false);
            mForceNoNapPreference.setOnPreferenceChangeListener(this);
        }
        if (mBusSplitPreference != null) {
            mBusSplitPreference.setPersistent(false);
            mBusSplitPreference.setOnPreferenceChangeListener(this);
        }

        Preference applyPref = findPreference(KEY_APPLY_GPU_SETTINGS);
        if (applyPref != null) {
            applyPref.setOnPreferenceClickListener(preference -> {
                applySettings();
                return true;
            });
        }

        Preference resetPref = findPreference(KEY_RESET_GPU_SETTINGS);
        if (resetPref != null) {
            resetPref.setOnPreferenceClickListener(preference -> {
                resetSettings();
                return true;
            });
        }
    }

    private void loadCurrentSettings() {
        if (mGpuModelPreference != null) {
            mGpuModelPreference.setSummary(mGpuUtils.getGpuModel());
        }

        loadGovernor();
        loadFrequencies();
        loadSwitchStates();
        updateDynamicInfo();
    }

    private void loadGovernor() {
        if (mGovernorPreference == null) {
            return;
        }

        String[] governors = mGpuUtils.getAvailableGovernors();
        if (governors != null && governors.length > 0) {
            mGovernorPreference.setEntries(governors);
            mGovernorPreference.setEntryValues(governors);
        }

        String currentGovernor = mGpuUtils.getCurrentGovernor();
        mGovernorPreference.setValue(currentGovernor);
        if (currentGovernor != null) {
            mGovernorPreference.setSummary(
                    getString(R.string.gpu_governor_summary, currentGovernor));
        } else {
            mGovernorPreference.setSummary(R.string.value_unavailable);
        }
    }

    private void loadFrequencies() {
        String currentMin = mGpuUtils.getCurrentMinFrequency();
        String currentMax = mGpuUtils.getCurrentMaxFrequency();
        String[] frequencies = mGpuUtils.getSelectableFrequencies(currentMin, currentMax);

        if (frequencies != null && frequencies.length > 0) {
            String[] frequencyLabels = new String[frequencies.length];
            for (int i = 0; i < frequencies.length; i++) {
                frequencyLabels[i] = formatFrequency(frequencies[i]);
            }

            if (mMinFreqPreference != null) {
                mMinFreqPreference.setEntries(frequencyLabels);
                mMinFreqPreference.setEntryValues(frequencies);
            }
            if (mMaxFreqPreference != null) {
                mMaxFreqPreference.setEntries(frequencyLabels);
                mMaxFreqPreference.setEntryValues(frequencies);
            }
        }

        if (mMinFreqPreference != null) {
            mMinFreqPreference.setValue(currentMin);
            mMinFreqPreference.setSummary(formatFrequency(currentMin));
        }
        if (mMaxFreqPreference != null) {
            mMaxFreqPreference.setValue(currentMax);
            mMaxFreqPreference.setSummary(formatFrequency(currentMax));
        }
    }

    private void loadSwitchStates() {
        if (mForceClkOnPreference != null) {
            mForceClkOnPreference.setChecked(mGpuUtils.getForceClkOn());
        }
        if (mForceBusOnPreference != null) {
            mForceBusOnPreference.setChecked(mGpuUtils.getForceBusOn());
        }
        if (mForceRailOnPreference != null) {
            mForceRailOnPreference.setChecked(mGpuUtils.getForceRailOn());
        }
        if (mForceNoNapPreference != null) {
            mForceNoNapPreference.setChecked(mGpuUtils.getForceNoNap());
        }
        if (mBusSplitPreference != null) {
            mBusSplitPreference.setChecked(mGpuUtils.getBusSplit());
        }
    }

    private void updateDynamicInfo() {
        if (mCurrentFreqPreference != null) {
            mCurrentFreqPreference.setSummary(formatFrequency(mGpuUtils.getCurrentFrequency()));
        }

        if (mGpuBusyPreference != null) {
            mGpuBusyPreference.setSummary(mGpuUtils.getGpuBusyPercentage());
        }

        if (mGpuTemperaturePreference != null) {
            String temperature = mGpuUtils.getGpuTemperature();
            if (!"0".equals(temperature)) {
                mGpuTemperaturePreference.setSummary(temperature + "°C");
            } else {
                mGpuTemperaturePreference.setSummary(R.string.value_unavailable);
            }
        }

        if (mThermalPowerLevelPreference != null) {
            mThermalPowerLevelPreference.setSummary(
                    getString(R.string.gpu_thermal_pwrlevel_value,
                            mGpuUtils.getThermalPowerLevel()));
        }
    }

    private Long parseFrequency(String frequency) {
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

    private String formatFrequency(String frequency) {
        Long value = parseFrequency(frequency);
        if (value == null) {
            return getString(R.string.value_unavailable);
        }
        return (value / 1000000) + " MHz";
    }

    private void startPeriodicUpdates() {
        mUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateDynamicInfo();
                mHandler.postDelayed(this, 2000);
            }
        };
        mHandler.post(mUpdateRunnable);
    }

    private void stopPeriodicUpdates() {
        if (mHandler != null && mUpdateRunnable != null) {
            mHandler.removeCallbacks(mUpdateRunnable);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();

        if (KEY_GPU_GOVERNOR.equals(key)) {
            if (!(newValue instanceof String) || ((String) newValue).isEmpty()) {
                return false;
            }
            String value = (String) newValue;
            mGovernorPreference.setSummary(getString(R.string.gpu_governor_summary, value));
            return true;
        }

        if (KEY_GPU_MIN_FREQ.equals(key) || KEY_GPU_MAX_FREQ.equals(key)) {
            if (!(newValue instanceof String) || parseFrequency((String) newValue) == null) {
                return false;
            }
            String value = (String) newValue;
            String minValue = KEY_GPU_MIN_FREQ.equals(key)
                    ? value : mMinFreqPreference.getValue();
            String maxValue = KEY_GPU_MAX_FREQ.equals(key)
                    ? value : mMaxFreqPreference.getValue();
            Long minFreq = parseFrequency(minValue);
            Long maxFreq = parseFrequency(maxValue);
            if (minFreq != null && maxFreq != null && minFreq > maxFreq) {
                showToast(R.string.invalid_frequency_range);
                return false;
            }
            preference.setSummary(formatFrequency(value));
            return true;
        }

        if (newValue instanceof Boolean) {
            Context context = getContext();
            if (context == null) {
                return false;
            }

            mGpuUtils.captureStockValues(context);
            boolean enabled = (Boolean) newValue;
            boolean success;
            if (KEY_GPU_FORCE_CLK_ON.equals(key)) {
                success = mGpuUtils.setForceClkOn(enabled);
            } else if (KEY_GPU_FORCE_BUS_ON.equals(key)) {
                success = mGpuUtils.setForceBusOn(enabled);
            } else if (KEY_GPU_FORCE_RAIL_ON.equals(key)) {
                success = mGpuUtils.setForceRailOn(enabled);
            } else if (KEY_GPU_FORCE_NO_NAP.equals(key)) {
                success = mGpuUtils.setForceNoNap(enabled);
            } else if (KEY_GPU_BUS_SPLIT.equals(key)) {
                success = mGpuUtils.setBusSplit(enabled);
            } else {
                return false;
            }

            if (!success) {
                showToast(R.string.gpu_setting_update_failed);
                return false;
            }

            // Power switches are applied immediately, so persist the new state right away.
            mGpuUtils.saveAppliedSettings(context);
            return true;
        }

        return false;
    }

    private void applySettings() {
        Context context = getContext();
        if (context == null) {
            return;
        }

        mGpuUtils.captureStockValues(context);

        boolean success = true;
        if (mGovernorPreference != null) {
            String governor = mGovernorPreference.getValue();
            success &= governor != null && mGpuUtils.setGovernor(governor);
        }

        if (mMinFreqPreference != null && mMaxFreqPreference != null) {
            String minFreq = mMinFreqPreference.getValue();
            String maxFreq = mMaxFreqPreference.getValue();
            success &= minFreq != null && maxFreq != null
                    && mGpuUtils.setFrequencyRange(minFreq, maxFreq);
        }

        // Persist the values that are actually active so they survive a reboot.
        mGpuUtils.saveAppliedSettings(context);

        // The kernel may clamp or reject a value, so always show what is really active.
        loadCurrentSettings();
        showToast(success ? R.string.settings_applied : R.string.settings_apply_failed);
    }

    private void resetSettings() {
        Context context = getContext();
        if (context == null) {
            return;
        }

        // Resetting also stops the saved settings from being restored on the next boot.
        mGpuUtils.clearAppliedSettings(context);

        if (!mGpuUtils.hasStockValues(context)) {
            loadCurrentSettings();
            showToast(R.string.settings_no_changes);
            return;
        }

        boolean success = mGpuUtils.restoreStockValues(context);
        loadCurrentSettings();
        showToast(success ? R.string.settings_reset : R.string.settings_reset_failed);
    }

    private void showToast(int messageResId) {
        Context context = getContext();
        if (context != null) {
            Toast.makeText(context, messageResId, Toast.LENGTH_SHORT).show();
        }
    }
}
