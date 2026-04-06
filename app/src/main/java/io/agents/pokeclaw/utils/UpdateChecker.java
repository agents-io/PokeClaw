// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;

/**
 * Checks GitHub Releases for a newer version of PokeClaw.
 * Call checkForUpdate() once in onCreate — it runs on a background thread,
 * shows a dialog on the main thread if a newer version exists.
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String GITHUB_API = "https://api.github.com/repos/agents-io/PokeClaw/releases/latest";
    private static final long CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000; // Once per day

    public static void checkForUpdate(Activity activity) {
        // Only check once per day
        long lastCheck = io.agents.pokeclaw.utils.KVUtils.INSTANCE.getLong("last_update_check", 0);
        long now = System.currentTimeMillis();
        if (now - lastCheck < CHECK_INTERVAL_MS) {
            XLog.d(TAG, "Skipping update check, last check was " + ((now - lastCheck) / 1000 / 60) + " min ago");
            return;
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String currentVersion = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0).versionName;

                HttpURLConnection conn = (HttpURLConnection) new URL(GITHUB_API).openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                if (conn.getResponseCode() != 200) {
                    XLog.w(TAG, "GitHub API returned " + conn.getResponseCode());
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject release = new JSONObject(sb.toString());
                String latestTag = release.getString("tag_name").replaceFirst("^v", "");
                String downloadUrl = release.getString("html_url");

                XLog.i(TAG, "Current: " + currentVersion + ", Latest: " + latestTag);

                // Save check time
                io.agents.pokeclaw.utils.KVUtils.INSTANCE.putLong("last_update_check", now);

                if (isNewer(latestTag, currentVersion)) {
                    activity.runOnUiThread(() -> showUpdateDialog(activity, latestTag, downloadUrl));
                }

            } catch (Exception e) {
                XLog.w(TAG, "Update check failed", e);
            }
        });
    }

    /**
     * Compare semantic versions. Returns true if remote > local.
     */
    private static boolean isNewer(String remote, String local) {
        try {
            String[] r = remote.split("\\.");
            String[] l = local.split("\\.");
            for (int i = 0; i < Math.max(r.length, l.length); i++) {
                int rv = i < r.length ? Integer.parseInt(r[i]) : 0;
                int lv = i < l.length ? Integer.parseInt(l[i]) : 0;
                if (rv > lv) return true;
                if (rv < lv) return false;
            }
        } catch (NumberFormatException e) {
            XLog.w(TAG, "Version parse error: remote=" + remote + " local=" + local);
        }
        return false;
    }

    private static void showUpdateDialog(Activity activity, String version, String url) {
        try {
            new AlertDialog.Builder(activity)
                    .setTitle("Update Available")
                    .setMessage("PokeClaw v" + version + " is available. You are running an older version.\n\nWould you like to download the update?")
                    .setPositiveButton("Download", (d, w) -> {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    })
                    .setNegativeButton("Later", null)
                    .show();
        } catch (Exception e) {
            XLog.w(TAG, "Failed to show update dialog", e);
        }
    }
}
