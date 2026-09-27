package io.github.didiadclean;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** 设置页只读查询被 hook 进程的上报状态。 */
public final class StatusProvider extends ContentProvider {
    static final Uri URI = Uri.parse("content://io.github.didiadclean.status");

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"get".equals(method)) return null;
        SharedPreferences p = prefs();
        Bundle result = new Bundle();
        result.putString("token", p.getString("token", ""));
        result.putLong("run", p.getLong("run", 0));
        result.putString("phase", p.getString("phase", ""));
        result.putLong("time", p.getLong("time", 0));
        result.putString("host", p.getString("host", ""));
        result.putString("compat", p.getString("compat", ""));
        result.putString("compat_detail", p.getString("compat_detail", ""));
        for (String key : Config.FEATURES) {
            result.putString(key, p.getString(key, ""));
            result.putString(key + "_detail", p.getString(key + "_detail", ""));
        }
        return result;
    }

    static void record(Context context, Bundle extras) {
        if (context == null || extras == null) return;
        SharedPreferences p = context.getSharedPreferences(Config.STATUS_FILE, 0);
        String token = extras.getString("token", "");
        long run = extras.getLong("run", 0);
        long previousRun = p.getLong("run", 0);
        if (run < previousRun) return;
        boolean sameRun = run == previousRun && token.equals(p.getString("token", ""));
        SharedPreferences.Editor edit = p.edit();
        if (!sameRun) edit.clear();
        edit.putString("token", token).putLong("run", run)
                .putString("phase", extras.getString("phase", ""))
                .putString("host", extras.getString("host", ""))
                .putLong("time", System.currentTimeMillis());
        String feature = extras.getString("feature", "");
        if (feature != null && !feature.isEmpty()) {
            edit.putString(feature, extras.getString("state", ""));
            edit.putString(feature + "_detail", extras.getString("detail", ""));
        }
        edit.commit();
    }

    private SharedPreferences prefs() { return getContext().getSharedPreferences(Config.STATUS_FILE, 0); }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
