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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragment;
import org.lineageos.settings.R;

public class KernelManagerFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_CPU_GOVERNOR = "cpu_governor";
    private static final String KEY_EFFICIENCY_MIN_FREQ = "efficiency_min_freq";
    private static final String KEY_EFFICIENCY_MAX_FREQ = "efficiency_max_freq";
    private static final String KEY_PERFORMANCE_MIN_FREQ = "performance_min_freq";
    private static final String KEY_PERFORMANCE_MAX_FREQ = "performance_max_freq";
    private static final String KEY_PRIME_MIN_FREQ = "prime_min_freq";
    private static final String KEY_PRIME_MAX_FREQ = "prime_max_freq";
    private static final String KEY_APPLY_SETTINGS = "apply_settings";
    private static final String KEY_RESET_SETTINGS = "reset_settings";

    private KernelManagerUtils mKernelUtils;
    private Handler mHandler;
    private ListPreference mGovernorPreference;
    private ClusterPreferences[] mClusters;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.kernel_manager_settings, rootKey);
        mKernelUtils = new KernelManagerUtils();
        mHandler = new Handler(Looper.getMainLooper());

        initializePreferences();
        loadCurrentSettings();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mKernelUtils != null) {
            loadCurrentSettings();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }
    }

    private void initializePreferences() {
        mGovernorPreference = (ListPreference) findPreference(KEY_CPU_GOVERNOR);
        if (mGovernorPreference != null) {
            mGovernorPreference.setPersistent(false);
            mGovernorPreference.setOnPreferenceChangeListener(this);
        }

        mClusters = new ClusterPreferences[]{
                new ClusterPreferences(KernelManagerUtils.EFFICIENCY_CLUSTER,
                        (ListPreference) findPreference(KEY_EFFICIENCY_MIN_FREQ),
                        (ListPreference) findPreference(KEY_EFFICIENCY_MAX_FREQ)),
                new ClusterPreferences(KernelManagerUtils.PERFORMANCE_CLUSTER,
                        (ListPreference) findPreference(KEY_PERFORMANCE_MIN_FREQ),
                        (ListPreference) findPreference(KEY_PERFORMANCE_MAX_FREQ)),
                new ClusterPreferences(KernelManagerUtils.PRIME_CLUSTER,
                        (ListPreference) findPreference(KEY_PRIME_MIN_FREQ),
                        (ListPreference) findPreference(KEY_PRIME_MAX_FREQ))
        };

        for (ClusterPreferences cluster : mClusters) {
            if (cluster.minPreference != null) {
                cluster.minPreference.setPersistent(false);
                cluster.minPreference.setOnPreferenceChangeListener(this);
            }
            if (cluster.maxPreference != null) {
                cluster.maxPreference.setPersistent(false);
                cluster.maxPreference.setOnPreferenceChangeListener(this);
            }
        }

        Preference applyPref = findPreference(KEY_APPLY_SETTINGS);
        if (applyPref != null) {
            applyPref.setOnPreferenceClickListener(preference -> {
                applySettings();
                return true;
            });
        }

        Preference resetPref = findPreference(KEY_RESET_SETTINGS);
        if (resetPref != null) {
            resetPref.setOnPreferenceClickListener(preference -> {
                resetSettings();
                return true;
            });
        }
    }

    private void loadCurrentSettings() {
        Context context = getContext();
        if (context == null) {
            return;
        }

        loadGovernor(context);
        for (ClusterPreferences cluster : mClusters) {
            loadFrequenciesForCluster(context, cluster);
        }
    }

    private void loadGovernor(Context context) {
        if (mGovernorPreference == null) {
            return;
        }

        String[] governors = mKernelUtils.getAvailableGovernors();
        if (governors != null && governors.length > 0) {
            mGovernorPreference.setEntries(governors);
            mGovernorPreference.setEntryValues(governors);
        }

        // Show the value the user applied (or the boot default) rather than the live
        // kernel value, which is the aggregate of all PM QoS requests.
        String currentGovernor = mKernelUtils.getDisplayedGovernor(context);
        mGovernorPreference.setValue(currentGovernor);
        if (currentGovernor != null) {
            mGovernorPreference.setSummary(
                    getString(R.string.cpu_governor_summary, currentGovernor));
        } else {
            mGovernorPreference.setSummary(R.string.value_unavailable);
        }
    }

    private void loadFrequenciesForCluster(Context context, ClusterPreferences cluster) {
        String currentMin = mKernelUtils.getDisplayedMinFrequency(context, cluster.cluster);
        String currentMax = mKernelUtils.getDisplayedMaxFrequency(context, cluster.cluster);
        String[] frequencies = mKernelUtils.getSelectableFrequencies(
                cluster.cluster, currentMin, currentMax);

        if (frequencies != null && frequencies.length > 0) {
            String[] frequencyLabels = new String[frequencies.length];
            for (int i = 0; i < frequencies.length; i++) {
                frequencyLabels[i] = formatFrequency(frequencies[i]);
            }

            if (cluster.minPreference != null) {
                cluster.minPreference.setEntries(frequencyLabels);
                cluster.minPreference.setEntryValues(frequencies);
            }
            if (cluster.maxPreference != null) {
                cluster.maxPreference.setEntries(frequencyLabels);
                cluster.maxPreference.setEntryValues(frequencies);
            }
        }

        if (cluster.minPreference != null) {
            cluster.minPreference.setValue(currentMin);
            cluster.minPreference.setSummary(formatFrequency(currentMin));
        }
        if (cluster.maxPreference != null) {
            cluster.maxPreference.setValue(currentMax);
            cluster.maxPreference.setSummary(formatFrequency(currentMax));
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (!(newValue instanceof String)) {
            return false;
        }

        String value = (String) newValue;
        if (KEY_CPU_GOVERNOR.equals(preference.getKey())) {
            if (value.isEmpty()) {
                return false;
            }
            preference.setSummary(getString(R.string.cpu_governor_summary, value));
            return true;
        }

        ClusterPreferences cluster = findCluster(preference);
        if (cluster == null || parseFrequency(value) == null) {
            return false;
        }

        String minValue = cluster.minPreference == preference
                ? value : getValue(cluster.minPreference);
        String maxValue = cluster.maxPreference == preference
                ? value : getValue(cluster.maxPreference);
        Long minFreq = parseFrequency(minValue);
        Long maxFreq = parseFrequency(maxValue);
        if (minFreq != null && maxFreq != null && minFreq > maxFreq) {
            showToast(R.string.invalid_frequency_range);
            return false;
        }

        preference.setSummary(formatFrequency(value));
        return true;
    }

    private ClusterPreferences findCluster(Preference preference) {
        for (ClusterPreferences cluster : mClusters) {
            if (cluster.minPreference == preference || cluster.maxPreference == preference) {
                return cluster;
            }
        }
        return null;
    }

    private void applySettings() {
        final Context context = getContext();
        if (context == null) {
            return;
        }

        final Context appContext = context.getApplicationContext();
        final String governor = getValue(mGovernorPreference);
        final int clusterCount = mClusters.length;
        final int[] clusters = new int[clusterCount];
        final String[] minFreqs = new String[clusterCount];
        final String[] maxFreqs = new String[clusterCount];
        for (int i = 0; i < clusterCount; i++) {
            clusters[i] = mClusters[i].cluster;
            minFreqs[i] = getValue(mClusters[i].minPreference);
            maxFreqs[i] = getValue(mClusters[i].maxPreference);
        }

        // Write on a worker thread so the UI is not blocked by the sysfs writes.
        new Thread(() -> {
            boolean success = true;

            if (governor != null) {
                success &= mKernelUtils.setGovernor(governor);
            }
            for (int i = 0; i < clusterCount; i++) {
                if (minFreqs[i] != null && maxFreqs[i] != null) {
                    success &= mKernelUtils.setFrequencyRange(clusters[i],
                            minFreqs[i], maxFreqs[i]);
                }
            }

            // Persist exactly what was requested, and only if it was written. A failed
            // apply must not be restored on the next boot.
            if (success) {
                mKernelUtils.saveAppliedSettings(appContext, governor, minFreqs, maxFreqs);
            }

            final boolean result = success;
            mHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                loadCurrentSettings();
                showToast(result ? R.string.settings_applied : R.string.settings_apply_failed);
            });
        }, "KamisApplyCpu").start();
    }

    private void resetSettings() {
        Context context = getContext();
        if (context == null) {
            return;
        }

        // Resetting also stops the saved settings from being restored on the next boot.
        boolean success = mKernelUtils.resetToDefaults(context);
        loadCurrentSettings();
        showToast(success ? R.string.settings_reset : R.string.settings_reset_failed);
    }

    private static String getValue(ListPreference preference) {
        return preference != null ? preference.getValue() : null;
    }

    private Long parseFrequency(String frequency) {
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

    private String formatFrequency(String frequency) {
        Long value = parseFrequency(frequency);
        if (value == null) {
            return getString(R.string.value_unavailable);
        }
        return (value / 1000) + " MHz";
    }

    private void showToast(int messageResId) {
        Context context = getContext();
        if (context != null) {
            Toast.makeText(context, messageResId, Toast.LENGTH_SHORT).show();
        }
    }

    private static final class ClusterPreferences {
        final int cluster;
        final ListPreference minPreference;
        final ListPreference maxPreference;

        ClusterPreferences(int cluster, ListPreference minPreference,
                ListPreference maxPreference) {
            this.cluster = cluster;
            this.minPreference = minPreference;
            this.maxPreference = maxPreference;
        }
    }
}
