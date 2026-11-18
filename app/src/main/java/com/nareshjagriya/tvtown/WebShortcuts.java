package com.nareshjagriya.tvtown;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages user-defined custom website shortcuts for the Home screen and the phone remote.
 * Stored in SharedPreferences as a JSON array.
 */
public final class WebShortcuts {

    private static final String PREF_NAME = "web_shortcuts";
    private static final String KEY_LIST = "shortcuts_list";

    public interface ChangeListener {
        void onShortcutsChanged();
    }

    private static final List<ChangeListener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static final class Shortcut {
        public final String id;
        public String title;
        public String url;
        public int color;

        public Shortcut(String id, String title, String url, int color) {
            this.id = id;
            this.title = title;
            this.url = url;
            this.color = color;
        }

        public JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("title", title);
                o.put("url", url);
                o.put("color", color);
                return o;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public static Shortcut fromJson(JSONObject o) {
            String id = o.optString("id", "web_" + System.currentTimeMillis());
            String title = o.optString("title", "Website");
            String url = o.optString("url", "");
            int color = o.optInt("color", colorFor(title));
            return new Shortcut(id, title, url, color);
        }
    }

    private WebShortcuts() {
    }

    public static void addListener(ChangeListener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(ChangeListener l) {
        if (l != null) listeners.remove(l);
    }

    private static void notifyListeners() {
        mainHandler.post(() -> {
            for (ChangeListener l : listeners) {
                try {
                    l.onShortcutsChanged();
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** Clean normalized URL (prepends https:// if protocol is omitted). */
    public static String cleanUrl(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        if (trimmed.isEmpty()) return "";
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "https://" + trimmed;
        }
        return trimmed;
    }

    public static synchronized List<Shortcut> all(Context c) {
        String json = prefs(c).getString(KEY_LIST, null);
        if (json == null || json.trim().isEmpty()) return new ArrayList<>();
        List<Shortcut> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(Shortcut.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    @Nullable
    public static synchronized Shortcut get(Context c, String id) {
        for (Shortcut s : all(c)) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }

    public static synchronized Shortcut add(Context c, String title, String url) {
        String cleanTitle = title == null ? "Website" : title.trim();
        if (cleanTitle.isEmpty()) cleanTitle = "Website";
        String cleanU = cleanUrl(url);
        String id = "web_" + System.currentTimeMillis();
        int col = colorFor(cleanTitle);

        List<Shortcut> current = all(c);
        Shortcut created = new Shortcut(id, cleanTitle, cleanU, col);
        current.add(created);
        saveList(c, current);
        notifyListeners();
        return created;
    }

    public static synchronized boolean update(Context c, String id, String title, String url) {
        List<Shortcut> current = all(c);
        boolean found = false;
        for (Shortcut s : current) {
            if (s.id.equals(id)) {
                if (title != null && !title.trim().isEmpty()) s.title = title.trim();
                if (url != null && !url.trim().isEmpty()) s.url = cleanUrl(url);
                s.color = colorFor(s.title);
                found = true;
                break;
            }
        }
        if (found) {
            saveList(c, current);
            notifyListeners();
        }
        return found;
    }

    public static synchronized boolean delete(Context c, String id) {
        List<Shortcut> current = all(c);
        boolean removed = current.removeIf(s -> s.id.equals(id));
        if (removed) {
            saveList(c, current);
            notifyListeners();
        }
        return removed;
    }

    private static void saveList(Context c, List<Shortcut> list) {
        JSONArray arr = new JSONArray();
        for (Shortcut s : list) arr.put(s.toJson());
        prefs(c).edit().putString(KEY_LIST, arr.toString()).apply();
    }

    /** Returns JSON string array representation for the phone API. */
    public static synchronized String json(Context c) {
        JSONArray arr = new JSONArray();
        for (Shortcut s : all(c)) arr.put(s.toJson());
        return arr.toString();
    }

    private static final int[] PALETTE = {
            0xFF2563EB, 0xFF7C3AED, 0xFFDB2777, 0xFFDC2626,
            0xFFD97706, 0xFF059669, 0xFF0891B2, 0xFF4F46E5
    };

    public static int colorFor(String name) {
        int hash = Math.abs(name == null ? 0 : name.toLowerCase(Locale.ROOT).hashCode());
        return PALETTE[hash % PALETTE.length];
    }

    /** Circular disc for a custom website with its primary letter. */
    public static Bitmap disc(String name, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float r = size / 2f;
        int col = colorFor(name);

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, size, size, col | 0xFF000000, darker(col, 0.7f), Shader.TileMode.CLAMP));
        c.drawCircle(r, r, r - 2, bg);

        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(2, size / 32f));
        rim.setColor(0x66FFFFFF);
        c.drawCircle(r, r, r - 3, rim);

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        text.setTextSize(size * 0.46f);
        text.setShadowLayer(4, 0, 2, 0x80000000);

        String letter = name == null || name.trim().isEmpty() ? "W" : name.trim().substring(0, 1).toUpperCase(Locale.ROOT);
        Paint.FontMetrics fm = text.getFontMetrics();
        float y = r - (fm.ascent + fm.descent) / 2f;
        c.drawText(letter, r, y, text);
        return b;
    }

    /** Circular disc for the "+" Add Website tile. */
    public static Bitmap addDisc(int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float r = size / 2f;

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, 0, size, 0xFF3B82F6, 0xFF1D4ED8, Shader.TileMode.CLAMP));
        c.drawCircle(r, r, r - 2, bg);

        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(2, size / 32f));
        rim.setColor(0x88FFFFFF);
        c.drawCircle(r, r, r - 3, rim);

        Paint plus = new Paint(Paint.ANTI_ALIAS_FLAG);
        plus.setColor(Color.WHITE);
        plus.setStyle(Paint.Style.STROKE);
        plus.setStrokeCap(Paint.Cap.ROUND);
        plus.setStrokeWidth(Math.max(4, size * 0.09f));
        float arm = size * 0.22f;
        c.drawLine(r - arm, r, r + arm, r, plus);
        c.drawLine(r, r - arm, r, r + arm, plus);
        return b;
    }

    private static int darker(int color, float factor) {
        int a = Color.alpha(color);
        int r = Math.round(Color.red(color) * factor);
        int g = Math.round(Color.green(color) * factor);
        int b = Math.round(Color.blue(color) * factor);
        return Color.argb(a, Math.max(r, 0), Math.max(g, 0), Math.max(b, 0));
    }
}

