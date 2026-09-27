package io.github.didiadclean;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import java.util.ArrayList;

/** 接收被 hook 进程的回传：逐条状态（ACTION_REPORT）与兼容报告（ACTION_COMPAT）。 */
public final class StatusReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!fromTarget(context)) return;
        String action = intent.getAction();
        if (Config.ACTION_REPORT.equals(action)) {
            if (intent.getExtras() != null) StatusProvider.record(context, intent.getExtras());
        } else if (Config.ACTION_COMPAT.equals(action)) {
            storeCompat(context, intent);
        }
    }

    /** API 34+ 校验发送方 uid 必须属于目标应用；拿不到 uid 时不拦（LSPosed 派发场景）。 */
    private boolean fromTarget(Context context) {
        if (Build.VERSION.SDK_INT < 34) return true;
        int uid = getSentFromUid();
        if (uid == Process.INVALID_UID) return true;
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String name : packages) {
            if (Config.PACKAGE.equals(name)) return true;
        }
        return false;
    }

    /** 存兼容报告（rows 式，参考 video-lsposed ReportReceiver）。 */
    private void storeCompat(Context context, Intent intent) {
        if (!Config.PACKAGE.equals(intent.getStringExtra("package"))) return;
        if (intent.getIntExtra("schema", 0) < Config.REPORT_SCHEMA) return;
        ArrayList<String> rows = intent.getStringArrayListExtra("rows");
        if (rows == null || rows.size() > 120) return;
        StringBuilder body = new StringBuilder();
        for (String row : rows) {
            if (row == null || row.length() > 1200) return;
            body.append(row).append('\n');
        }
        SharedPreferences.Editor edit = context
                .getSharedPreferences(Config.COMPAT_FILE, Context.MODE_PRIVATE).edit();
        edit.putString("version", intent.getStringExtra("version"));
        edit.putString("source", intent.getStringExtra("source"));
        edit.putString("compat", intent.getStringExtra("compat"));
        edit.putInt("schema", intent.getIntExtra("schema", 0));
        edit.putLong("time", System.currentTimeMillis());
        edit.putString("rows", body.toString());
        edit.commit();
        // 让设置页状态卡也能显示宿主版本（Provider 只读 STATUS_FILE）
        context.getSharedPreferences(Config.STATUS_FILE, Context.MODE_PRIVATE).edit()
                .putString("host", intent.getStringExtra("version")).commit();
    }
}
