package io.github.didiadclean;

import android.app.Notification;
import android.app.NotificationChannel;
import android.os.Bundle;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 推送通知广告闸门。
 *
 * <p><b>为什么挂在 {@code NotificationManager.notify} 上</b>：这是 App 进程内所有通知的<b>唯一</b>出口
 * —— 厂商推送通道、自建长连接、轮询、AlarmManager/WorkManager 拉回来的广告，最终都要调它。
 * 挂在平台类（非混淆）上意味着：换 App 版本不会失效，三个模块可以共用同一套判定逻辑，
 * 也不需要知道滴滴的广告 SDK 叫什么 obfuscated 名字。
 *
 * <p><b>判定顺序（先便宜后昂贵，命中即停）</b>：
 * <ol>
 *   <li>渠道 id —— 一次 {@code indexOf}，广告通常自带 {@code *_ad / *_promo} 这类渠道；</li>
 *   <li>渠道名/描述 —— 渠道是创建时一次性可判定的，强信号；</li>
 *   <li>标题 / 正文 / 长文 / 副标题 / 附加文本 / 滚动文本 / tag —— 广告文案关键词。</li>
 * </ol>
 *
 * <p><b>性能</b>：{@code notify} 是低频事件（按小时计），不是 {@code onDraw} 那种热路径。
 * {@code intercept} 内零反射、零分配（只做 {@code String.indexOf} 与取已有对象）、
 * 零逐条日志（每个原因只打一次，由调用方用 CAS 控制）。
 *
 * <p><b>误伤优先于漏拦</b>：所有判据都只可能<b>加强</b>拦截，不会把一条已判定的通知放行；
 * 而任何异常都走 fail-open（放行），保证"没生效"时行为与没装模块一致。
 *
 * <p><b>大小写</b>：关键词表按"原样包含"匹配。中文与渠道 id 本身大小写无关，
 * 表内英文词一律写成小写，因此无需 {@code toLowerCase()}，也就没有额外分配。
 */
public final class NotifyGate {

    private NotifyGate() {}

    /** 判定结果：{@code suppress=false} 表示放行。 */
    public static final class Decision {
        public final boolean suppress;
        public final String reason;

        private Decision(boolean suppress, String reason) {
            this.suppress = suppress;
            this.reason = reason;
        }

        static final Decision KEEP = new Decision(false, null);
    }

    // ── 规则表（可改；改完强停滴滴生效）──────────────────────────────────────
    // 渠道 id 里的广告标记
    private static final String[] CHANNEL_TOKENS = {
            "_ad", "ad_", "ads_", "ad.", ".ad", "promo", "marketing", "advert", "push_ad", "gdt", "gromore",
    };
    // 渠道名 / 描述里的广告字样
    private static final String[] CHANNEL_NAME_TOKENS = {"广告", "促销", "推广", "营销"};
    // 通知文案里的广告词。刻意避开行程类正常通知用词（行程 / 司机 / 派单 / 接驾 / 送达 / 取消）。
    private static final String[] TEXT_TOKENS = {
            "广告", "推广", "营销",
            "领券", "领红包", "优惠券", "打车券", "出行券", "神券", "红包", "补贴",
            "限时", "折扣", "优惠", "秒杀", "特价", "首单", "新人礼", "首免",
            "立即领取", "点击领取", "马上领", "限时领取", "免费领", "领取奖励",
            "助力", "邀请好友", "拉新", "邀请返", "分享得", "砍价", "助力金",
            "福利", "活动", "促销", "特惠",
    };

    // ── 观测计数（设置页兼容报告读这里）──────────────────────────────────────
    private static final AtomicLong scanned = new AtomicLong();
    private static final AtomicLong suppressed = new AtomicLong();
    private static final AtomicLong channelsSeen = new AtomicLong();
    private static final AtomicLong channelsAd = new AtomicLong();

    public static long scanned() { return scanned.get(); }
    public static long suppressed() { return suppressed.get(); }
    public static long channelsSeen() { return channelsSeen.get(); }
    public static long channelsAd() { return channelsAd.get(); }

    /** 关掉后计数仍保留，设置页可以显示"历史拦过多少条"。 */
    static void resetCounters() {
        scanned.set(0); suppressed.set(0); channelsSeen.set(0); channelsAd.set(0);
    }

    // ── 主判定 ──────────────────────────────────────────────────────────────

    /** 判定一条即将下发的通知。fail-open：任何异常都返回放行。 */
    public static Decision evaluate(Notification n, String tag) {
        if (n == null) return Decision.KEEP;
        try {
            scanned.incrementAndGet();
            String channelId = null;
            try { channelId = n.getChannelId(); } catch (Throwable ignored) {}
            Decision d = matchTokens(channelId, CHANNEL_TOKENS);
            if (d != null) return d;

            // Notification 没有 getExtras()，extras 是 public 字段；取已有 Bundle，不分配。
            Bundle extras = null;
            try { extras = n.extras; } catch (Throwable ignored) {}
            if (extras != null) {
                d = matchExtra(extras, Notification.EXTRA_TITLE);
                if (d == null) d = matchExtra(extras, Notification.EXTRA_TEXT);
                if (d == null) d = matchExtra(extras, Notification.EXTRA_BIG_TEXT);
                if (d == null) d = matchExtra(extras, Notification.EXTRA_SUB_TEXT);
                if (d == null) d = matchExtra(extras, Notification.EXTRA_INFO_TEXT);
            }
            if (d == null) {
                CharSequence ticker = null;
                try { ticker = n.tickerText; } catch (Throwable ignored) {}
                d = matchText(ticker);
            }
            if (d == null) d = matchText(tag);
            if (d == null) return Decision.KEEP;
            suppressed.incrementAndGet();
            return d;
        } catch (Throwable t) {
            return Decision.KEEP; // fail-open
        }
    }

    /**
     * 渠道注册时判定一次。渠道 id/名/描述在创建后就固定了，是最强的广告信号，
     * 命中就记下来（设置页可见），并让后续 notify 走第 1 步的快速判定。
     */
    public static Decision evaluateChannel(NotificationChannel ch) {
        if (ch == null) return Decision.KEEP;
        try {
            channelsSeen.incrementAndGet();
            Decision d = matchTokens(ch.getId(), CHANNEL_TOKENS);
            if (d == null) d = matchText(ch.getName());
            if (d == null) d = matchTokens(ch.getName(), CHANNEL_NAME_TOKENS);
            if (d == null) d = matchTokens(ch.getDescription(), CHANNEL_NAME_TOKENS);
            if (d == null) return Decision.KEEP;
            channelsAd.incrementAndGet();
            return d;
        } catch (Throwable t) {
            return Decision.KEEP;
        }
    }

    // ── 匹配原语 ────────────────────────────────────────────────────────────

    private static Decision matchExtra(Bundle extras, String key) {
        CharSequence value;
        try { value = extras.getCharSequence(key); } catch (Throwable t) { return null; }
        return matchText(value);
    }

    private static Decision matchText(CharSequence value) {
        if (value == null) return null;
        Decision d = matchTokens(value, TEXT_TOKENS);
        return d != null ? d : matchTokens(value, CHANNEL_NAME_TOKENS);
    }

    /** 收 CharSequence 而不是 String：NotificationChannel.getName()/getDescription() 返回 CharSequence。 */
    private static Decision matchTokens(CharSequence haystack, String[] tokens) {
        if (haystack == null) return null;
        // String.toString() 返回 this，不会产生新对象，所以这里没有额外分配。
        String s = haystack.toString();
        if (s.isEmpty()) return null;
        for (int i = 0; i < tokens.length; i++) {
            if (s.indexOf(tokens[i]) >= 0) {
                return new Decision(true, tokens[i]);
            }
        }
        return null;
    }

    // ── 自检 ────────────────────────────────────────────────────────────────
    // 没有真机广告通知时，"判定函数本身是否正确"仍然可以证实或证伪。
    // 安装时跑一遍并打日志，避免"装上了但不知道判定逻辑对不对"。

    private static final String[][] SELF_TEST = {
            // 输入, 期望是否拦截
            {"出行有礼，限时打车 6 折", "1"},
            {"点击领取 20 元优惠券", "1"},
            {"邀请好友助力得 30 元", "1"},
            {"您的司机已接单，正在前往接驾点", "0"},
            {"行程已结束，订单金额 18.5 元", "0"},
            {"订单已被取消", "0"},
            {"司机已到达上车点", "0"},
    };

    /** @return 形如 "self_test 6/7 passed [失败输入...]" 的自检结论。 */
    public static String selfTest() {
        int passed = 0;
        StringBuilder failed = new StringBuilder();
        for (String[] sample : SELF_TEST) {
            boolean got = matchText(sample[0]) != null;
            boolean want = "1".equals(sample[1]);
            if (got == want) {
                passed++;
            } else {
                if (failed.length() > 0) failed.append(" | ");
                failed.append(sample[0]).append("=>").append(got ? "拦" : "放");
            }
        }
        return "self_test " + passed + "/" + SELF_TEST.length + " passed"
                + (failed.length() == 0 ? "" : " [" + failed + "]");
    }
}
