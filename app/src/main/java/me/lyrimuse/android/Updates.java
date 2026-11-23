package me.lyrimuse.android;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Sideloaded builds have no store auto-update, so once in a while we look at the project's
 * GitHub releases for a newer {@code android-v*} tag and offer its APK.
 */
public final class Updates {

    private static final String TAG = "LyrimuseUpdates";
    private static final String RELEASES =
            "https://api.github.com/repos/ahasnimsandu/lyrimuse/releases?per_page=30";
    private static final long CHECK_EVERY_MS = 6L * 60L * 60L * 1000L;

    public interface Callback {
        void onResult(String version, String url);
    }

    private Updates() {}

    public static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    public static void check(Context ctx, Callback callback) {
        final Context app = ctx.getApplicationContext();
        final String current = versionName(app);
        if (current.isEmpty()) return;

        String knownVersion = Prefs.updateVersion(app);
        String knownUrl = Prefs.updateUrl(app);
        if (!knownVersion.isEmpty() && !knownUrl.isEmpty() && isNewer(knownVersion, current)) {
            callback.onResult(knownVersion, knownUrl);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - Prefs.updateCheckedAt(app) < CHECK_EVERY_MS) return;
        Prefs.setUpdateCheckedAt(app, now);

        new Thread(() -> {
            String bestVersion = null;
            String bestUrl = null;
            try {
                Map<String, String> headers = new HashMap<>();
                headers.put("Accept", "application/vnd.github+json");
                JSONArray releases = new JSONArray(Http.get(RELEASES, headers));
                for (int i = 0; i < releases.length(); i++) {
                    JSONObject release = releases.optJSONObject(i);
                    if (release == null) continue;
                    String tag = release.optString("tag_name", "");
                    if (!tag.startsWith("android-v")) continue;
                    String version = tag.substring("android-v".length()).trim();
                    if (!isNewer(version, current)) continue;
                    JSONArray assets = release.optJSONArray("assets");
                    String url = "";
                    if (assets != null) {
                        for (int j = 0; j < assets.length(); j++) {
                            JSONObject asset = assets.optJSONObject(j);
                            if (asset == null) continue;
                            String name = asset.optString("name", "");
                            if (name.toLowerCase().endsWith(".apk")) {
                                url = asset.optString("browser_download_url", "");
                                break;
                            }
                        }
                    }
                    if (url.isEmpty()) continue;
                    if (bestVersion == null || isNewer(version, bestVersion)) {
                        bestVersion = version;
                        bestUrl = url;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "update check failed: " + e);
                return;
            }
            if (bestVersion == null) return;
            Prefs.setUpdate(app, bestVersion, bestUrl);
            final String v = bestVersion;
            final String u = bestUrl;
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(v, u));
        }, "lyriu-update").start();
    }

    /** True when {@code candidate} is a later dotted version than {@code current}. */
    static boolean isNewer(String candidate, String current) {
        int[] a = parts(candidate);
        int[] b = parts(current);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    private static int[] parts(String version) {
        int[] out = new int[3];
        String clean = version == null ? "" : version.trim().replaceAll("^[vV]", "");
        String[] bits = clean.split("[^0-9]+");
        int seen = 0;
        for (String bit : bits) {
            if (bit.isEmpty()) continue;
            if (seen >= 3) break;
            try {
                out[seen++] = Integer.parseInt(bit);
            } catch (NumberFormatException ignored) {
                seen++;
            }
        }
        return out;
    }
}
