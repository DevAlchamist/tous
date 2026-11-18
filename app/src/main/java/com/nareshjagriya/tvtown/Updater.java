package com.nareshjagriya.tvtown;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Updates from the project's GitHub Releases, so a TV never needs a computer again: once a day TV Town asks GitHub
 * for the latest release, and Settings → Updates shows it. Installing downloads the APK straight into Android's
 * package installer, which asks the viewer to confirm (the first time, Android asks to allow TV Town to install
 * apps). The update keeps every setting, as long as it is signed with the same key as the copy on the TV.
 */
final class Updater {

    static String REPO = "DevAlchamist/tous";
    private static final long DAY = 24 * 3600_000L;

    static String cleanRepo(String r) {
        if (r == null) return REPO;
        String s = r.trim();
        if (s.startsWith("https://github.com/")) s = s.substring(19);
        else if (s.startsWith("http://github.com/")) s = s.substring(18);
        else if (s.startsWith("github.com/")) s = s.substring(11);
        if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? REPO : s;
    }

    static String getRepo(Context ctx) {
        return cleanRepo(prefs(ctx).getString("update_repo", REPO));
    }

    static void setRepo(Context ctx, String repo) {
        if (repo != null && !repo.trim().isEmpty()) {
            prefs(ctx).edit().putString("update_repo", cleanRepo(repo)).apply();
            available = null;
        }
    }

    /** A newer release: its version, its APK, and what's new. */
    static final class Release {
        String version = "", apkUrl = "", notes = "";
    }

    @Nullable
    static volatile Release available;

    private Updater() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    /** Asks GitHub (at most once a day unless forced); `done` gets a newer release or null, on a background thread. */
    static void check(Context ctx, boolean force, @Nullable Consumer<Release> done) {
        long last = prefs(ctx).getLong("update_checked", 0);
        if (!force && System.currentTimeMillis() - last < DAY) {
            if (available == null) available = saved(ctx);   // found earlier today: still waiting
            if (done != null) done.accept(available);
            return;
        }
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            Release r = null;
            try {
                java.util.Map<String, String> h = new java.util.HashMap<>();
                h.put("Accept", "application/vnd.github+json");
                JSONObject o = new JSONObject(Net.text(Net.get("https://api.github.com/repos/" + getRepo(app) + "/releases/latest", h, 10_000, 1)));
                r = parse(o, BuildConfig.VERSION_NAME);
                prefs(app).edit().putLong("update_checked", System.currentTimeMillis()).apply();
            } catch (Exception ignored) {
                // offline, or no release yet: try again next time
            }
            available = r;
            save(app, r);
            if (done != null) done.accept(r);
        }, "update-check").start();
    }

    /** The newer release found last, kept so the dot and What's new stay after a restart. */
    private static void save(Context ctx, @Nullable Release r) {
        String v = "";
        if (r != null) {
            try {
                v = new JSONObject().put("v", r.version).put("apk", r.apkUrl).put("notes", r.notes).toString();
            } catch (org.json.JSONException ignored) {
                // strings only
            }
        }
        prefs(ctx).edit().putString("update_found", v).apply();
    }

    @Nullable
    private static Release saved(Context ctx) {
        try {
            JSONObject o = new JSONObject(prefs(ctx).getString("update_found", ""));
            if (!newer(o.optString("v"), BuildConfig.VERSION_NAME)) return null;   // installed since
            Release r = new Release();
            r.version = o.optString("v");
            r.apkUrl = o.optString("apk");
            r.notes = o.optString("notes");
            return r.apkUrl.startsWith("https://") ? r : null;
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /**
     * The release notes as plain lines for the TV, about `width` letters each: headings as they are, "- **Name:** words"
     * as "•  Name: words", wrapped at a word; links and markdown marks taken out.
     */
    static java.util.List<String> noteLines(String notes, int width) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String raw : notes.split("\n")) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            boolean head = l.startsWith("#");
            l = l.replaceAll("^#+\\s*", "").replaceAll("\\*\\*", "").replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1");
            if (l.startsWith("- ")) l = "\u2022  " + l.substring(2);
            if (head) {
                out.add("");
                out.add(l.toUpperCase(java.util.Locale.getDefault()));
                continue;
            }
            String indent = l.startsWith("\u2022") ? "    " : "";
            while (l.length() > width) {
                int cut = l.lastIndexOf(' ', width);
                if (cut <= indent.length()) cut = width;
                out.add(l.substring(0, cut));
                l = indent + l.substring(cut).trim();
            }
            out.add(l);
        }
        while (!out.isEmpty() && out.get(0).isEmpty()) out.remove(0);
        return out;
    }

    /** The release when it is newer than `current` and has an APK (not a debug build), else null. */
    @Nullable
    static Release parse(JSONObject o, String current) {
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null;
        String v = o.optString("tag_name", "").replaceFirst("^[vV]", "");
        if (!newer(v, current)) return null;
        JSONArray assets = o.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            String name = a == null ? "" : a.optString("name", "");
            String url = a == null ? "" : a.optString("browser_download_url", "");
            if (name.endsWith(".apk") && !name.contains("debug") && url.startsWith("https://")) {
                Release r = new Release();
                r.version = v;
                r.apkUrl = url;
                r.notes = o.optString("body", "").trim();
                return r;
            }
        }
        return null;
    }

    /** "1.10" is newer than "1.9"; "1.2.1" newer than "1.2". */
    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int number(String s) {
        String d = s.replaceAll("[^0-9].*$", "");
        try {
            return d.isEmpty() ? 0 : Integer.parseInt(d);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Downloads the release into Android's installer, which then asks the viewer to confirm. When TV Town may not
     * install apps yet, opens the switch for it instead (and returns false).
     */
    static boolean install(Context ctx, Release r, Consumer<String> say) {
        if (!ctx.getPackageManager().canRequestPackageInstalls()) {
            try {
                ctx.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
                // some TVs have no such screen; the installer asks instead
            }
            say.accept(ctx.getString(R.string.update_allow));
            return false;
        }
        Context app = ctx.getApplicationContext();
        say.accept(ctx.getString(R.string.update_downloading, r.version));
        new Thread(() -> {
            File tempApk = new File(app.getCacheDir(), "pending_update_" + System.currentTimeMillis() + ".apk");
            int id = -1;
            try {
                downloadToFile(r.apkUrl, tempApk);
                verifyPackageAndSignature(app, tempApk);

                PackageInstaller pi = app.getPackageManager().getPackageInstaller();
                id = pi.createSession(new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL));
                try (PackageInstaller.Session s = pi.openSession(id);
                     InputStream in = new FileInputStream(tempApk);
                     OutputStream out = s.openWrite("tvtown.apk", 0, tempApk.length())) {
                    byte[] buf = new byte[64 * 1024];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                    }
                    s.fsync(out);
                    int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
                    PendingIntent done = PendingIntent.getBroadcast(app, id, new Intent(app, Result.class), flags | PendingIntent.FLAG_UPDATE_CURRENT);
                    s.commit(done.getIntentSender());
                }
            } catch (Exception e) {
                if (id >= 0) {
                    try {
                        app.getPackageManager().getPackageInstaller().abandonSession(id);
                    } catch (Exception ignored) {
                    }
                }
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        Toast.makeText(app, app.getString(R.string.update_failed, String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show());
            } finally {
                if (tempApk.exists()) tempApk.delete();
            }
        }, "update").start();
        return true;
    }

    private static void downloadToFile(String url, File dest) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();   // GitHub sends it on to its file host
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        if (c.getResponseCode() != 200) throw new IOException(L10n.t("HTTP ") + c.getResponseCode());
        if (!c.getURL().getProtocol().equals("https")) throw new IOException("Only HTTPS is allowed");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > 200L * 1024 * 1024) throw new IOException(L10n.t("Too large"));
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            c.disconnect();
        }
    }

    /**
     * Verifies that the downloaded APK:
     * 1) Has the exact same package name as this application (Package Identity check).
     * 2) Is signed with a compatible signing certificate (Signing-Key Compatibility check).
     * Throws SecurityException if verification fails before passing to PackageInstaller.
     */
    private static void verifyPackageAndSignature(Context ctx, File apkFile) throws SecurityException {
        PackageManager pm = ctx.getPackageManager();
        int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;

        PackageInfo archiveInfo = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), flags);
        if (archiveInfo == null) {
            throw new SecurityException("Invalid APK: package archive could not be parsed.");
        }

        // 1. Verify Package Identity
        String expectedPackage = ctx.getPackageName();
        if (!expectedPackage.equals(archiveInfo.packageName)) {
            throw new SecurityException("Package identity mismatch! Expected '" + expectedPackage + "' but archive is '" + archiveInfo.packageName + "'.");
        }

        // 2. Verify Signing-Key Compatibility
        boolean compatible = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            SigningInfo signingInfo = archiveInfo.signingInfo;
            if (signingInfo != null) {
                Signature[] signatures = signingInfo.hasMultipleSigners()
                        ? signingInfo.getApkContentsSigners()
                        : signingInfo.getSigningCertificateHistory();
                if (signatures != null) {
                    for (Signature sig : signatures) {
                        if (pm.hasSigningCertificate(expectedPackage, sig.toByteArray(), PackageManager.CERT_INPUT_RAW_X509)) {
                            compatible = true;
                            break;
                        }
                    }
                }
            }
        }

        if (!compatible) {
            try {
                PackageInfo currentInfo = pm.getPackageInfo(expectedPackage, flags);
                compatible = signaturesMatch(currentInfo, archiveInfo);
            } catch (Exception e) {
                throw new SecurityException("Failed to read current package signing certificates: " + e.getMessage());
            }
        }

        if (!compatible) {
            throw new SecurityException("Signing-key conflict! The downloaded update is signed with an incompatible key.");
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean signaturesMatch(PackageInfo current, PackageInfo archive) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (current.signingInfo != null && archive.signingInfo != null) {
                Signature[] curSigners = current.signingInfo.hasMultipleSigners()
                        ? current.signingInfo.getApkContentsSigners()
                        : current.signingInfo.getSigningCertificateHistory();
                Signature[] archSigners = archive.signingInfo.hasMultipleSigners()
                        ? archive.signingInfo.getApkContentsSigners()
                        : archive.signingInfo.getSigningCertificateHistory();
                if (curSigners != null && archSigners != null) {
                    for (Signature c : curSigners) {
                        for (Signature a : archSigners) {
                            if (Arrays.equals(c.toByteArray(), a.toByteArray())) return true;
                        }
                    }
                }
            }
        }
        if (current.signatures != null && archive.signatures != null) {
            for (Signature c : current.signatures) {
                for (Signature a : archive.signatures) {
                    if (Arrays.equals(c.toByteArray(), a.toByteArray())) return true;
                }
            }
        }
        return false;
    }

    /** What the installer says: ask the viewer to confirm, or report a failure. */
    public static class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(ctx, ctx.getString(status == PackageInstaller.STATUS_FAILURE_CONFLICT
                        ? R.string.update_conflict : R.string.update_failed, String.valueOf(msg)), Toast.LENGTH_LONG).show();
            }
        }
    }
}
