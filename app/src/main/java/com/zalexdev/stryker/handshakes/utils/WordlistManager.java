package com.zalexdev.stryker.handshakes.utils;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.zalexdev.stryker.utils.Core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Client-side wordlist management for the local handshake-crack workflow.
 *
 * <p>The local crack (BruteHandshake) runs {@code aircrack-ng -w <wordlist> <cap>} against the
 * wordlists living under {@code <shareRoot>/wordlists}. This class is the pipeline that gets
 * wordlists there: list what's available, download a bundled/remote list, and import one from
 * device storage.
 *
 * <p>Wired from the Handshakes page: when the user taps "local crack" and the wordlist folder is
 * empty, the UI offers "Download rockyou.txt" (or import) instead of a dead-end toast.
 */
public class WordlistManager {

    private static final String TAG = "WordlistManager";

    /** The canonical rockyou.txt mirror (~140 MB, 14M passwords) — GitHub-hosted, stable. */
    public static final String ROCKYOU_URL =
            "https://github.com/brannondorsey/naive-hashcat/releases/download/data/rockyou.txt";

    private final Core core;
    private final Context context;

    public WordlistManager(Context context, Core core) {
        this.context = context;
        this.core = core;
    }

    /** The wordlist directory (created on demand). */
    public File wordlistDir() {
        File dir = new File(core.getShareRoot(), "wordlists");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Names (not full paths) of the .txt wordlists currently available. */
    public ArrayList<String> listNames() {
        ArrayList<String> names = new ArrayList<>();
        File[] files = wordlistDir().listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase().endsWith(".txt")) {
                    names.add(f.getName());
                }
            }
        }
        return names;
    }

    /** True when there is at least one wordlist ready to crack with. */
    public boolean hasWordlists() {
        return !listNames().isEmpty();
    }

    /**
     * Downloads rockyou.txt into the wordlist dir over the guest/chroot curl.
     * Runs on the calling thread (call from a background thread). Progress is streamed via the
     * listener in bytes.
     *
     * @return the resulting File, or null on failure.
     */
    public File downloadRockyou(ProgressListener listener) {
        File dest = new File(wordlistDir(), "rockyou.txt");
        try {
            // curl -L follows redirects; -o writes directly to the share path inside the
            // chroot/guest. Reuse the same path mapping the rest of the handshake flow uses.
            String sharePath = sharePathFor(dest);
            String cmd = "curl -L --fail --silent --show-error -o '" + sharePath + "' '" + ROCKYOU_URL + "'";
            ArrayList<String> out = core.customChrootCommand(cmd);
            // customChrootCommand is blocking; if the file landed, report completion.
            if (dest.exists() && dest.length() > 0) {
                if (listener != null) listener.onProgress(dest.length(), dest.length());
                return dest;
            }
            Log.w(TAG, "rockyou download produced no file; output=" + out);
        } catch (Exception e) {
            Log.e(TAG, "downloadRockyou failed", e);
        }
        return null;
    }

    /**
     * Imports a wordlist from a content URI (SAF picker) by copying it into the wordlist dir.
     *
     * @param uri  content URI of the source file
     * @param name destination file name (e.g. "custom.txt")
     * @return the resulting File, or null on failure.
     */
    public File importFromUri(Uri uri, String name) {
        if (uri == null || name == null || name.trim().isEmpty()) return null;
        File dest = new File(wordlistDir(), sanitize(name));
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) return null;
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            out.flush();
            return dest.exists() && dest.length() > 0 ? dest : null;
        } catch (Exception e) {
            Log.e(TAG, "importFromUri failed", e);
            return null;
        }
    }

    /** Maps a host File under the share root to the in-chroot/guest /sdcard/Stryker path. */
    private String sharePathFor(File f) {
        String host = f.getAbsolutePath();
        String root = new File(core.getShareRoot()).getAbsolutePath();
        if (host.startsWith(root)) {
            return "/sdcard/Stryker" + host.substring(root.length());
        }
        return host;
    }

    private String sanitize(String name) {
        String s = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (!s.toLowerCase().endsWith(".txt")) s += ".txt";
        return s;
    }

    /** Byte progress callback for long downloads. */
    public interface ProgressListener {
        void onProgress(long doneBytes, long totalBytes);
    }
}
