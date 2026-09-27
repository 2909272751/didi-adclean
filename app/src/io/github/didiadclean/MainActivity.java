package io.github.didiadclean;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设置页：参考 video-lsposed（Codex 修复版）的形态——
 * 标题 → 状态卡（含「查看兼容结果」）→ 顶部分类页签 → 每页 section + card + toggle（标题 / 对应页面 / 说明 / 状态）。
 */
public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(245, 247, 250);
    private static final int INK = Color.rgb(30, 43, 48);
    private static final int MUTED = Color.rgb(101, 116, 124);
    private static final int OK = Color.rgb(0, 140, 100);
    private static final int WARN = Color.rgb(196, 108, 0);
    private static final int ACCENT = Color.rgb(0, 122, 255);
    private static final int SECTION = Color.rgb(26, 111, 160);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Switch> switches = new LinkedHashMap<>();
    private final Map<String, TextView> states = new LinkedHashMap<>();
    private final Map<String, Boolean> defaults = new LinkedHashMap<>();
    private final Button[] pageButtons = new Button[Config.CATEGORIES.length];
    private final ScrollView[] pages = new ScrollView[Config.CATEGORIES.length];

    private TextView status;
    private TextView compatLine;
    private boolean refreshing;
    private boolean appliedPrefs;
    private int currentPage;

    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refreshStatus();
            handler.postDelayed(this, 2500);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(16), dp(14), dp(16), 0);
        setContentView(root);

        root.addView(text("滴滴去广告 + 界面简化", 24, INK, true));
        TextView subtitle = text("作用域：" + Config.PACKAGE + " · schema=" + Config.REPORT_SCHEMA
                + " · 按分类查看，每项都标注对应页面", 12, MUTED, false);
        subtitle.setPadding(0, dp(4), 0, dp(10));
        root.addView(subtitle);

        LinearLayout statusCard = card();
        status = text("LSPosed 服务：检测中…", 14, INK, false);
        statusCard.addView(status);
        compatLine = text("兼容结果：等待滴滴进程上报…", 12, MUTED, false);
        compatLine.setPadding(0, dp(4), 0, dp(2));
        statusCard.addView(compatLine);
        Button checkCompatibility = new Button(this);
        checkCompatibility.setText("查看兼容结果");
        checkCompatibility.setAllCaps(false);
        checkCompatibility.setTextColor(ACCENT);
        checkCompatibility.setBackgroundColor(Color.TRANSPARENT);
        checkCompatibility.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showCompatibility(); }
        });
        statusCard.addView(checkCompatibility);
        root.addView(statusCard);

        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        navigation.setPadding(0, dp(12), 0, dp(8));
        for (int index = 0; index < Config.CATEGORIES.length; index++) {
            final int selected = index;
            Button button = new Button(this);
            button.setText(Config.categoryTitle(Config.CATEGORIES[index]));
            button.setTextSize(13);
            button.setAllCaps(false);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setPadding(0, 0, 0, 0);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { showPage(selected); }
            });
            pageButtons[index] = button;
            navigation.addView(button, new LinearLayout.LayoutParams(0, dp(46), 1));
        }
        root.addView(navigation);

        FrameLayout pageHost = new FrameLayout(this);
        SharedPreferences prefs = App.preferences();
        for (int index = 0; index < Config.CATEGORIES.length; index++) {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(0, 0, 0, dp(30));
            scroll.addView(body);
            pages[index] = scroll;
            pageHost.addView(scroll);
            buildPage(body, Config.CATEGORIES[index], prefs);
        }
        root.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));
        showPage(0);
    }

    private void buildPage(LinearLayout body, String category, SharedPreferences prefs) {
        section(body, Config.categoryTitle(category));
        addNote(body, Config.categoryNote(category), 12);
        LinearLayout card = card();
        for (String key : Config.FEATURES) {
            if (!category.equals(Config.categoryOf(key))) continue;
            toggle(card, key, Config.labelOf(key), "对应页面：" + Config.pageOf(key) + "\n" + Config.noteOf(key),
                    Config.read(prefs, key, Config.defaultOf(key)));
        }
        if (card.getChildCount() > 0) body.addView(card);
        if ("diagnostic".equals(category)) {
            section(body, "操作");
            LinearLayout actions = card();
            Button restart = new Button(this);
            restart.setText("强制停止滴滴（立即生效）");
            restart.setAllCaps(false);
            restart.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { restartTarget(); }
            });
            actions.addView(restart);
            Button reset = new Button(this);
            reset.setText("恢复默认");
            reset.setAllCaps(false);
            reset.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { App.reset(); recreate(); }
            });
            actions.addView(reset);
            body.addView(actions);
        }
    }

    private void showPage(int index) {
        currentPage = index;
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] == null) continue;
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            Button button = pageButtons[i];
            if (button == null) continue;
            button.setTextColor(i == index ? Color.WHITE : INK);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(10));
            shape.setColor(i == index ? SECTION : Color.TRANSPARENT);
            button.setBackground(shape);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(refreshTask);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshTask);
    }

    private void showCompatibility() {
        String report = CompatibilityScanner.scan(this);
        new AlertDialog.Builder(this)
                .setTitle("兼容结果（滴滴 " + Config.PACKAGE + "）")
                .setMessage(report)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void refreshStatus() {
        SharedPreferences prefs = App.preferences();
        boolean bound = prefs != null;
        status.setText(bound
                ? "LSPosed 服务：已连接 · 设置可保存"
                : "LSPosed 服务：未连接（请在 LSPosed 管理器里启用本模块并勾选作用域）");
        status.setTextColor(bound ? OK : WARN);

        refreshing = true;
        try {
            if (bound && !appliedPrefs) {
                for (Map.Entry<String, Switch> entry : switches.entrySet()) {
                    Boolean initial = defaults.get(entry.getKey());
                    entry.getValue().setChecked(Config.read(prefs, entry.getKey(),
                            initial != null && initial));
                }
                appliedPrefs = true;
            }
            for (Switch control : switches.values()) control.setEnabled(bound);
        } finally {
            refreshing = false;
        }

        Bundle report = null;
        try {
            report = getContentResolver().call(StatusProvider.URI, "get", null, null);
        } catch (Throwable ignored) {}
        if (report == null) {
            compatLine.setText("兼容结果：还没收到上报（强停滴滴后重新打开）");
            compatLine.setTextColor(MUTED);
            return;
        }
        String host = report.getString("host", "");
        String compat = report.getString("compat_detail", "");
        if (compat == null || compat.isEmpty()) {
            compatLine.setText("宿主：" + (host == null || host.isEmpty() ? "未知" : host)
                    + " · 兼容结果：等待上报");
            compatLine.setTextColor(MUTED);
        } else {
            compatLine.setText("宿主：" + host + " · " + compat);
            compatLine.setTextColor(compat.contains("全部匹配") ? OK : WARN);
        }
        for (String key : Config.FEATURES) {
            TextView view = states.get(key);
            if (view == null) continue;
            String state = report.getString(key, "");
            String detail = report.getString(key + "_detail", "");
            if (state == null || state.isEmpty()) {
                view.setText("状态：待上报（强停滴滴后重新打开）");
                view.setTextColor(MUTED);
            } else if ("matched".equals(state)) {
                view.setText("状态：已生效" + empty(detail) + detail);
                view.setTextColor(OK);
            } else if ("off".equals(state)) {
                view.setText("状态：已关闭");
                view.setTextColor(MUTED);
            } else if ("miss".equals(state)) {
                view.setText("状态：未匹配" + empty(detail) + detail);
                view.setTextColor(WARN);
            } else {
                view.setText("状态：" + state + empty(detail) + detail);
                view.setTextColor(MUTED);
            }
        }
    }

    private static String empty(String value) {
        return value == null || value.isEmpty() ? "" : " · ";
    }

    private void restartTarget() {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "am force-stop " + Config.PACKAGE});
            int code = process.waitFor();
            Toast.makeText(this, code == 0 ? "已强停滴滴，重新打开即生效" : "强停失败（需要 root 授权？）", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "强停失败：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ 视图组件

    private void toggle(LinearLayout parent, final String key, String title, String detail, boolean initial) {
        if (parent.getChildCount() > 0) separator(parent);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 15, INK, false));
        if (detail != null) {
            TextView hint = text(detail, 12, MUTED, false);
            hint.setPadding(0, dp(3), dp(8), 0);
            labels.addView(hint);
        }
        TextView stateView = text("状态：待上报", 12, MUTED, true);
        stateView.setPadding(0, dp(3), dp(8), 0);
        labels.addView(stateView);
        states.put(key, stateView);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        Switch control = new Switch(this);
        control.setContentDescription(title);
        control.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ACCENT, Color.rgb(222, 226, 229)}));
        control.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Color.rgb(168, 205, 245), Color.rgb(190, 196, 199)}));
        control.setChecked(initial);
        control.setEnabled(App.preferences() != null);
        control.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (refreshing) return;
                if (!App.write(key, checked)) {
                    button.setOnCheckedChangeListener(null);
                    button.setChecked(!checked);
                    button.setOnCheckedChangeListener(this);
                    Toast.makeText(MainActivity.this, "保存失败：未连接到 LSPosed 服务", Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(MainActivity.this,
                        checked ? "已开启，点「强制停止滴滴」后生效" : "已关闭，点「强制停止滴滴」后生效",
                        Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(control);
        parent.addView(row);
        switches.put(key, control);
        defaults.put(key, initial);
    }

    private void section(LinearLayout body, String title) {
        TextView label = text(title, 14, SECTION, true);
        label.setPadding(dp(4), dp(18), 0, dp(8));
        body.addView(label);
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(13), dp(9), dp(13), dp(9));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.WHITE);
        shape.setCornerRadius(dp(13));
        box.setBackground(shape);
        return box;
    }

    private void addNote(LinearLayout box, String message, int size) {
        TextView note = text(message, size, MUTED, false);
        note.setPadding(dp(4), 0, dp(4), dp(6));
        box.addView(note);
    }

    private void separator(LinearLayout box) {
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(239, 242, 244));
        box.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private TextView text(String content, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
