package com.nareshjagriya.tvtown;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages custom TV background photo uploaded directly from the phone remote web app.
 * Stores downsampled photo with caching and notifies listeners for live TV background redraws.
 */
final class CustomWallpaper {

    private static final String FILE_NAME = "custom_wallpaper.jpg";
    private static final Object LOCK = new Object();
    private static Bitmap cachedWallpaper;
    private static Bitmap cachedGlass;
    private static long cachedTimestamp;

    public interface Listener {
        void onWallpaperChanged();
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private CustomWallpaper() {
    }

    static File getFile(Context c) {
        return new File(c.getFilesDir(), FILE_NAME);
    }

    static boolean has(Context c) {
        File f = getFile(c);
        return f != null && f.exists() && f.length() > 0;
    }

    @Nullable
    static Bitmap get(Context c) {
        File f = getFile(c);
        if (f == null || !f.exists() || f.length() <= 0) {
            synchronized (LOCK) {
                cachedWallpaper = null;
                cachedGlass = null;
                cachedTimestamp = 0;
            }
            return null;
        }
        synchronized (LOCK) {
            if (cachedWallpaper != null && !cachedWallpaper.isRecycled() && f.lastModified() == cachedTimestamp) {
                return cachedWallpaper;
            }
        }
        Bitmap bmp = decodeSampled(f.getAbsolutePath(), 1920, 1080);
        if (bmp == null) return null;
        Bitmap glass = null;
        if (!Lite.on && !Frost.lighter) {
            try {
                glass = WheelArt.glassCopy(bmp, 320);
            } catch (Throwable ignored) {
            }
        }
        synchronized (LOCK) {
            cachedWallpaper = bmp;
            cachedGlass = glass;
            cachedTimestamp = f.lastModified();
        }
        return bmp;
    }

    @Nullable
    static Bitmap getGlass(Context c) {
        get(c);
        synchronized (LOCK) {
            return cachedGlass;
        }
    }

    private static Bitmap decodeSampled(String path, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
            int inSampleSize = 1;
            int width = opts.outWidth, height = opts.outHeight;
            while (width / (inSampleSize * 2) >= reqWidth && height / (inSampleSize * 2) >= reqHeight) {
                inSampleSize *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = inSampleSize;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean set(Context c, InputStream in, long length) {
        File dir = c.getFilesDir();
        File tmp = new File(dir, "custom_wallpaper.tmp");
        File target = getFile(c);
        long got = 0;
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[32768];
            while (length <= 0 || got < length) {
                int toRead = length <= 0 ? buf.length : (int) Math.min(buf.length, length - got);
                int n = in.read(buf, 0, toRead);
                if (n < 0) break;
                out.write(buf, 0, n);
                got += n;
            }
            out.flush();
        } catch (Exception e) {
            tmp.delete();
            return false;
        }
        if (length > 0 && got < length) {
            tmp.delete();
            return false;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(tmp.getAbsolutePath(), opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            tmp.delete();
            return false;
        }
        if (target.exists()) target.delete();
        if (!tmp.renameTo(target)) {
            tmp.delete();
            return false;
        }
        synchronized (LOCK) {
            cachedWallpaper = null;
            cachedGlass = null;
            cachedTimestamp = 0;
        }
        notifyListeners();
        return true;
    }

    static boolean clear(Context c) {
        File f = getFile(c);
        if (f.exists()) {
            f.delete();
        }
        synchronized (LOCK) {
            cachedWallpaper = null;
            cachedGlass = null;
            cachedTimestamp = 0;
        }
        notifyListeners();
        return true;
    }

    static void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void notifyListeners() {
        main.post(() -> {
            for (Listener l : listeners) {
                try {
                    l.onWallpaperChanged();
                } catch (Throwable ignored) {
                }
            }
        });
    }
}

