package com.mrtien.autoskip;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class ClipboardHistoryStore {
    private static final String PREFS = "clipboard_history";
    private static final String KEY_ITEMS = "items";
    private static final int MAX_ITEMS = 50;

    public static final class Item {
        public String type;
        public String text;
        public String uri;
        public String intentUri;
        public String mime;
        public long time;

        public String preview() {
            if ("text".equals(type)) {
                String v = text == null ? "" : text.replace('\n', ' ').trim();
                if (v.length() > 64) v = v.substring(0, 64) + "…";
                return v.isEmpty() ? "[Văn bản trống]" : v;
            }
            if ("uri".equals(type)) {
                if (mime != null && mime.startsWith("image/")) return "🖼  Hình ảnh";
                return "📎  " + (mime == null || mime.isEmpty() ? "Tệp / nội dung" : mime);
            }
            if ("intent".equals(type)) return "🔗  Nội dung ứng dụng";
            return "Nội dung đã sao chép";
        }
    }

    private ClipboardHistoryStore() {}

    public static synchronized void addClip(Context context, ClipData clip) {
        if (context == null || clip == null || clip.getItemCount() <= 0) return;

        try {
            ClipData.Item source = clip.getItemAt(0);
            Item item = new Item();
            item.time = System.currentTimeMillis();

            CharSequence direct = source.getText();
            if (direct != null) {
                item.type = "text";
                item.text = direct.toString();
            } else if (source.getUri() != null) {
                item.type = "uri";
                item.uri = source.getUri().toString();
                ClipDescription d = clip.getDescription();
                if (d != null && d.getMimeTypeCount() > 0) item.mime = d.getMimeType(0);
            } else if (source.getIntent() != null) {
                item.type = "intent";
                item.intentUri = source.getIntent().toUri(Intent.URI_INTENT_SCHEME);
            } else {
                CharSequence coerced = source.coerceToText(context);
                if (coerced == null) return;
                item.type = "text";
                item.text = coerced.toString();
            }

            addItem(context, item);
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void addText(Context context, String text) {
        if (context == null || text == null || text.isEmpty()) return;
        Item item = new Item();
        item.type = "text";
        item.text = text;
        item.time = System.currentTimeMillis();
        addItem(context, item);
    }

    private static void addItem(Context context, Item item) {
        try {
            List<Item> items = load(context);
            String key = signature(item);
            ArrayList<Item> out = new ArrayList<>();
            out.add(item);
            for (Item old : items) {
                if (!key.equals(signature(old))) out.add(old);
                if (out.size() >= MAX_ITEMS) break;
            }
            save(context, out);
        } catch (Throwable ignored) {
        }
    }

    public static synchronized List<Item> load(Context context) {
        ArrayList<Item> out = new ArrayList<>();
        if (context == null) return out;
        try {
            String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_ITEMS, "[]");
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Item item = new Item();
                item.type = o.optString("type", "text");
                item.text = o.optString("text", "");
                item.uri = o.optString("uri", "");
                item.intentUri = o.optString("intent", "");
                item.mime = o.optString("mime", "");
                item.time = o.optLong("time", 0L);
                out.add(item);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void save(Context context, List<Item> items) {
        try {
            JSONArray arr = new JSONArray();
            for (Item item : items) {
                JSONObject o = new JSONObject();
                o.put("type", item.type == null ? "text" : item.type);
                o.put("text", item.text == null ? "" : item.text);
                o.put("uri", item.uri == null ? "" : item.uri);
                o.put("intent", item.intentUri == null ? "" : item.intentUri);
                o.put("mime", item.mime == null ? "" : item.mime);
                o.put("time", item.time);
                arr.put(o);
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_ITEMS, arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void clear(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ITEMS, "[]").apply();
    }

    public static ClipData toClipData(Context context, Item item) {
        if (item == null) return null;
        try {
            if ("text".equals(item.type)) {
                return ClipData.newPlainText("Lịch sử", item.text == null ? "" : item.text);
            }
            if ("uri".equals(item.type) && item.uri != null && !item.uri.isEmpty()) {
                Uri uri = Uri.parse(item.uri);
                return ClipData.newUri(context.getContentResolver(), "Lịch sử", uri);
            }
            if ("intent".equals(item.type) && item.intentUri != null && !item.intentUri.isEmpty()) {
                Intent intent = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
                return ClipData.newIntent("Lịch sử", intent);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String signature(Item item) {
        if (item == null) return "";
        return (item.type == null ? "" : item.type) + "|"
                + (item.text == null ? "" : item.text) + "|"
                + (item.uri == null ? "" : item.uri) + "|"
                + (item.intentUri == null ? "" : item.intentUri);
    }
}
