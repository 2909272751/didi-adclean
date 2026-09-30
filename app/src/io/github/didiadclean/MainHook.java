package io.github.didiadclean;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 滴滴去广告模块（LSPosed API 102 语义，但**不使用 PackageReadyParam.getApplication()**：
 * 实测本机 LSPosed 的 PackageReadyParam 没有该方法，会抛 NoSuchMethodError 且异常发生在日志之前 → 表现为"模块没加载"）。
 * Context 改为通过一次性 Application.attach 钩子获取（脚手架同款）。
 *
 * 规则（逐特性，互不牵连）：
 *   no_ads  —— 广告总闸：AdSdk.d(AdRequest) 恒返回 false（"广告 SDK 未就绪"），
 *              所有广告展示路径按"无广告"自然退出；AdSdk.f() 会因此返回 true（叫它继续）。
 *   popup   —— 弹窗/通知广告：AdSdk.g 不执行；AdSdk.h/i/j 返回 null（原实现本就允许返回 null）。
 *   splash  —— 开屏广告：QuickSplashShow.f（展示流程）与 c（接受资源）不执行。
 *   probes  —— 只读探针：只打 hit 日志、原样放行。
 *
 * 性能规范：只挂一次性决策闸门；拦截体内零反射、零分配；每个 id 只打一次日志（CAS）。
 */
public final class MainHook extends XposedModule {
    private final AtomicBoolean installed = new AtomicBoolean(false);
    private final AtomicBoolean contextHooked = new AtomicBoolean(false);
    private final AtomicBoolean configured = new AtomicBoolean(false);
    private volatile String processName;
    private volatile String compatDetail;
    private volatile boolean compatOk;
    /** 装上了但只覆盖部分入口的特性 → 报 partial，不许被 max 成 matched。 */
    private volatile String partialKey;
    private volatile String partialWhy;
    /** 想在设置页展示的附加说明（目前只有通知闸门的判定自检）。 */
    private volatile String detailKey;
    private volatile String detailExtra;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        try { processName = param.getProcessName(); } catch (Throwable ignored) {}
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        // 直写一行，证明回调进来了（不依赖任何静态状态；任何异常都在这一行之后）
        try { log(Log.INFO, Config.TAG, "[schema=" + Config.REPORT_SCHEMA + "] onPackageReady entered pkg=" + safePackage(param)); } catch (Throwable ignored) {}
        String pkg = null;
        try { pkg = param.getPackageName(); } catch (Throwable ignored) {}
        if (!Config.PACKAGE.equals(pkg)) return;
        String process = processName;
        if (!inScopeProcess(process)) return;
        if (!installed.compareAndSet(false, true)) return;
        try {
            ClassLoader loader = param.getClassLoader();
            H.bind(this, null, "unknown", System.currentTimeMillis());
            logFrameworkInfo();
            install(loader);
            H.summary();
            // 锚点扫描推迟到拿到 Context、知道宿主版本号之后再做：
            // 已实测通过的版本直接跳过，不做这次重复劳动（见 Config.VERIFIED_VERSIONS）。
            installContextHook(loader);
        } catch (Throwable t) {
            try { log(Log.ERROR, Config.TAG, "[schema=" + Config.REPORT_SCHEMA + "] setup failed", t); } catch (Throwable ignored) {}
            H.summary();
        }
    }

    /**
     * 哪些进程要装钩子。
     *
     * <p>8.x 把界面放在主进程 {@code com.sdu.didi.psnger}，但 6.x 把整个界面都挂在
     * {@code :privacy} 子进程上（{@code LauncherActivity} 的 {@code android:process}
     * 就是它，主进程根本不启动）。只认主进程的话，6.x 上模块一次都不会加载。
     *
     * <p>所以放行主进程 + 全部子进程。通知下发本身就可能发生在任一子进程，
     * 推送通知闸门必须装到。代价是子进程也会装一套钩子——但拦截体都有开关前置判断，
     * 且老版本才有的子进程在新版本上并不常驻。
     */
    private static boolean inScopeProcess(String process) {
        if (process == null) return true;   // 框架没给进程名时按主进程处理
        return process.equals(Config.PACKAGE) || process.startsWith(Config.PACKAGE + ":");
    }

    private static String safePackage(XposedModuleInterface.PackageReadyParam param) {
        try { return String.valueOf(param.getPackageName()); } catch (Throwable t) { return "?(" + t + ")"; }
    }

    /** 一次性诊断：框架名/版本/API + PackageReadyParam 实际提供的方法（本机差异就靠这个看出来）。 */
    private void logFrameworkInfo() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("framework=").append(getFrameworkName()).append(' ').append(getFrameworkVersion())
              .append(" api=").append(getApiVersion());
            H.info(sb.toString());
        } catch (Throwable t) {
            H.warn("framework info unavailable: " + t);
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (Method m : XposedModuleInterface.PackageReadyParam.class.getDeclaredMethods()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(m.getName());
            }
            H.info("PackageReadyParam methods: " + sb);
        } catch (Throwable t) {
            H.warn("PackageReadyParam introspection failed: " + t);
        }
    }

    /** 用一次性 Application.attach 钩子拿 Context（本机 PackageReadyParam 没有 getApplication）。 */
    private void installContextHook(final ClassLoader loader) {
        if (!contextHooked.compareAndSet(false, true)) return;
        try {
            final Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attach).setId("capture_context").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (configured.compareAndSet(false, true)) {
                        try {
                            Context context = (Context) chain.getArg(0);
                            Application application = (Application) chain.getThisObject();
                            H.attachContext(application != null ? application : context);
                            H.info("context captured: " + (context != null));
                            maybeCompatScan(context, loader);
                            H.detectVersion(loader);
                        } catch (Throwable t) {
                            H.warn("context capture failed: " + t);
                        }
                    }
                    return result;
                }
            });
            H.installed("capture_context");
        } catch (Throwable t) {
            H.miss("capture_context", t.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- install

    private void install(ClassLoader loader) {
        SharedPreferences prefs = null;
        try { prefs = getRemotePreferences(Config.GROUP); } catch (Throwable t) { H.warn("getRemotePreferences failed: " + t); }
        H.info("config source=" + (prefs != null ? "remote_prefs" : "defaults"));
        H.setSource(prefs != null ? "remote_prefs（设置页写入）" : "defaults（未读到设置，用默认值）");

        final Class<?> adSdk = R.load(loader, "com.didi.ad.AdSdk");
        final Class<?> splashShow = R.load(loader, "com.didi.ad.splash.QuickSplashShow");
        final SharedPreferences prefsRef = prefs;
        // 7.0 及更早首页 Fragment/容器改成了短名，这里先各解析一次，后面几项共用。
        final String[] homeFragments = resolveHomeFragments(loader, HOME_FRAGMENTS);
        final String[] homeContainers = resolveHomeContainers(loader, HOME_CONTAINERS);
        if (homeFragments.length != HOME_FRAGMENTS.length)
            H.info("home fragments resolved: " + java.util.Arrays.toString(homeFragments));
        if (homeContainers.length != HOME_CONTAINERS.length)
            H.info("home containers resolved: " + java.util.Arrays.toString(homeContainers));

        feature("no_ads", prefsRef, new Installer() {
            @Override public void install() {
                gateBoolean(adSdk, "d", "no_ads", "AdSdk.d(AdRequest)->false");
            }
        });
        feature("popup", prefsRef, new Installer() {
            @Override public void install() {
                gateByName(adSdk, "g", "popup", "AdSdk.g");
                gateByName(adSdk, "h", "popup", "AdSdk.h");
                gateByName(adSdk, "i", "popup", "AdSdk.i");
                gateByName(adSdk, "j", "popup", "AdSdk.j");
                gateByName(adSdk, "k", "popup", "AdSdk.k");
            }
        });
        feature("splash", prefsRef, new Installer() {
            @Override public void install() {
                gateByName(splashShow, "f", "splash", "QuickSplashShow.f");
                gateByName(splashShow, "c", "splash", "QuickSplashShow.c");
            }
        });
        feature("push_notify", prefsRef, new Installer() {
            @Override public void install() {
                installPushNotify();
            }
        });
        feature("block_dialogs", prefsRef, new Installer() {
            @Override public void install() {
                Class<?> dialogBase = R.load(loader, "com.didi.sdk.view.dialog.b");
                if (dialogBase == null) { H.miss("DidiDialogBase", "ClassNotFound"); return; }
                gateByName(dialogBase, "show", "block_dialogs", "DidiDialog.show");
            }
        });
        feature("probes", prefsRef, new Installer() {
            @Override public void install() {
                probe(loader, "com.didi.ad.splash.QuickSplashLoad", "probes");
                probe(loader, "com.didi.ad.base.net.HttpSender", "probes");
                probe(loader, "com.didi.ad.api.AdRequest", "probes");
            }
        });

        // ── 兜底：首页 Fragment 视图创建后，延迟再隐藏一遍（广告/营销位是数据到达后才挂上去的）──
        feature("hide_promo_card", prefsRef, new Installer() {
            @Override public void install() {
                installHideDeferred(loader, homeFragments, "hide_promo_card",
                        new String[]{"v8_smart_card_container", "home_main_card_activity_image"});
            }
        });
        feature("hide_home_banner", prefsRef, new Installer() {
            @Override public void install() {
                installHideDeferred(loader, homeFragments, "hide_home_banner",
                        new String[]{"home_banner_proxy_view", "ch_banner_casper_container",
                                "banner_parent_container", "banner_main_title",
                                "ch_home_banner_big_image_view", "ch_home_banner_vertical_small_image_view_v1",
                                "card_layout", "titleView", "subView", "recycler_view"});
            }
        });
        feature("hide_bottom_nav", prefsRef, new Installer() {
            @Override public void install() {
                installHideDeferred(loader, homeContainers, "hide_bottom_nav",
                        new String[]{"v6x_home_bottom_nav", "v6x_home_bottom", "v6x_home_bottom_blur", "shadow_view"});
            }
        });
        feature("keep_home_tabs", prefsRef, new Installer() {
            @Override public void install() {
                installKeepHomeTabs(loader);
            }
        });
        feature("hide_top_tabs", prefsRef, new Installer() {
            @Override public void install() {
                installHideDeferred(loader, homeContainers, "hide_top_tabs", new String[]{"tabLayout"});
            }
        });
        feature("hide_top_tools", prefsRef, new Installer() {
            @Override public void install() {
                installHideDeferred(loader, homeContainers, "hide_top_tools",
                        new String[]{"home_v8x_action_bar_riding_container", "riding_code", "scan", "ch_v8_scan_img"});
            }
        });
        feature("hide_scene_row", prefsRef, new Installer() {
            @Override public void install() {
                installHideDeferred(loader, homeFragments, "hide_scene_row", new String[]{"ch_scene_layout"});
            }
        });
    }

    /**
     * 版本兼容自检：逐个探测锚点是否还在（换版本后 R8 会改方法名/类名）。
     * 结果写日志一行 + 上报设置页（"版本兼容自检：ok/总数"）；缺失项自动跳过，不影响其它功能。
     */
    /**
     * 拿到 Context 后才知道宿主版本号，这时才决定要不要扫锚点。
     *
     * <p>落在 {@link Config#VERIFIED_MIN}~{@link Config#VERIFIED_MAX} 已实测区间内就整段跳过扫描：
     * 钩子已经装完了，再扫一遍既重复、又让人误以为这个版本还没适配。
     * 区间外或查不到版本号时照旧扫描，不猜。
     */
    private void maybeCompatScan(Context context, ClassLoader loader) {
        try {
            if (context != null) {
                android.content.pm.PackageInfo info =
                        context.getPackageManager().getPackageInfo(Config.PACKAGE, 0);
                long code = info.getLongVersionCode();
                Config.observedVersionCode = code;
                if (Config.isVerified(code)) {
                    H.setCompat(true, "已验证版本 " + info.versionName + "(" + code + ")："
                            + "覆盖 " + Config.VERIFIED_MIN + "~" + Config.VERIFIED_MAX + "，跳过锚点扫描");
                    H.info("event=compat_scan skipped=1 verified=" + code);
                    return;
                }
                H.info("event=compat_scan unverified=" + code + " -> scanning anchors");
            }
        } catch (Throwable t) {
            H.warn("verified-version lookup failed, scanning anyway: " + t);
        }
        compatScan(loader);
    }

    private void compatScan(ClassLoader loader) {
        int ok = 0;
        StringBuilder missing = new StringBuilder();
        for (String[] anchor : Config.COMPAT_ANCHORS) {
            String className = anchor[0];
            String methodName = anchor.length > 1 ? anchor[1] : "";
            String shape = anchor.length > 2 ? anchor[2] : "";
            boolean found;
            Class<?> owner = R.load(loader, className);
            if (owner == null) {
                found = false;
            } else if (methodName == null || methodName.isEmpty()) {
                found = true; // 只查类
            } else {
                found = find(owner, methodName, shape) != null;
            }
            if (found) {
                ok++;
            } else {
                if (missing.length() > 0) missing.append(',');
                missing.append(shortName(className));
                if (methodName != null && !methodName.isEmpty()) missing.append('#').append(methodName);
            }
        }
        int total = Config.COMPAT_ANCHORS.length;
        compatOk = ok == total;
        compatDetail = "锚点 " + ok + "/" + total
                + (missing.length() == 0 ? "（本版本全部匹配）" : "（缺失: " + missing + "，已自动跳过）");
        H.info("event=compat_scan ok=" + ok + "/" + total + " missing=[" + missing + "]");
        // 此时可能还没拿到 Context（上报会被丢弃）→ 先存起来，等 Context 到手后在 detectVersion 里补报
        H.setCompat(compatOk, compatDetail);
    }

    /** 按形状找方法：shape="" 表示任意、其余见 Config.COMPAT_ANCHORS 注释。 */
    private static Method find(Class<?> owner, String name, String shape) {
        for (Method m : owner.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] p = m.getParameterTypes();
            if ("1".equals(shape) && p.length != 1) continue;
            if ("view".equals(shape)) {
                if (p.length < 2 || p.length > 3) continue;
                if (!android.view.View.class.isAssignableFrom(m.getReturnType())) continue;
                if (!android.view.LayoutInflater.class.isAssignableFrom(p[0])) continue;
                if (!android.view.ViewGroup.class.isAssignableFrom(p[1])) continue;
            }
            if ("list".equals(shape)) {
                if (p.length != 2 || !java.util.List.class.isAssignableFrom(p[1])) continue;
            }
            return m;
        }
        return null;
    }

    private static final String[] HOME_FRAGMENTS = {
            "com.didi.carhailing.framework.v8.home.V8HomeFragment",
            "com.didi.carhailing.framework.v8.home.V8xHomeFragment",
    };
    /**
     * 首页 Fragment 所在包。7.0 及更早是 {@code v6x}（且 Fragment 本体被 R8 改成短名，
     * 见 {@link #resolveHomeFragments}），8.x 才迁到 {@code v8}。
     */
    private static final String[] HOME_FRAGMENT_PREFIXES = {
            "com.didi.carhailing.framework.v8.home.",
            "com.didi.carhailing.framework.v6x.home.",
            "com.didi.carhailing.framework.v7.home.",
            "com.didi.carhailing.framework.v6.home.",
            "com.didi.carhailing.framework.home.",
    };
    /**
     * 首页容器（底栏 / 顶部 tab / 顶部工具）所在包。7.0 及更早 {@code HomeContainer} 也不见了，
     * 被压成 {@code common/app/a|b|c|d|e}，所以同样要走短名单发现。
     */
    private static final String[] HOME_CONTAINER_PREFIXES = {
            "com.didi.carhailing.framework.v8.home.",
            "com.didi.carhailing.framework.v6x.home.",
            "com.didi.carhailing.framework.common.app.",
            "com.didi.carhailing.framework.v7.home.",
            "com.didi.carhailing.framework.v6.home.",
            "com.didi.carhailing.framework.home.",
    };
    private static final String[] HOME_CONTAINERS = {
            "com.didi.carhailing.framework.v8.home.V8xHomeContainerFragment",
            "com.didi.carhailing.framework.common.app.HomeContainer",
    };

    /**
     * 底部导航栏逐项精简：在 BottomNavigationView 构建 tab **之前**过滤数据列表，
     * 只保留 home_page / user_center（宽度按过滤后的数量计算，不会出现"除以零/整条消失"）。
     */
    private void installKeepHomeTabs(ClassLoader loader) {
        Class<?> owner = R.load(loader, "com.didi.carhailing.framework.common.bottombar.bottom.widget.BottomNavigationView");
        if (owner == null) { H.miss("BottomNavigationView", "ClassNotFound"); return; }
        Method target = null;
        for (Method m : owner.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 2) continue;
            if (!java.util.List.class.isAssignableFrom(p[1])) continue;
            target = m;
            break;
        }
        if (target == null) { H.miss("BottomNavigationView.setItems", "NoShapeMatch"); return; }
        final String label = shortName(target.getDeclaringClass().getName()) + "." + target.getName() + "(List)";
        try { target.setAccessible(true); } catch (Throwable ignored) {}
        try {
            hook(target).setId("keep_home_tabs").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        Object arg = chain.getArg(1);
                        if (arg instanceof java.util.List) {
                            java.util.List list = (java.util.List) arg;
                            int removed = 0;
                            for (int i = list.size() - 1; i >= 0; i--) {
                                String id = itemId(list.get(i));
                                if (id != null && !"home_page".equals(id) && !"user_center".equals(id)) {
                                    list.remove(i);
                                    removed++;
                                }
                            }
                            if (removed > 0) H.hit("keep_home_tabs", label + " removed=" + removed);
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                }
            });
            H.installed("keep_home_tabs@" + label);
        } catch (Throwable t) {
            H.miss("keep_home_tabs", t.getClass().getSimpleName());
        }
    }

    /** 反射取 BottomNavItem.getId()（只在底部栏数据更新时调用，非热路径）。 */
    private static String itemId(Object item) {
        if (item == null) return null;
        try {
            Method getId = item.getClass().getMethod("getId");
            Object value = getId.invoke(item);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 界面简化闸门：在这些"创建视图"方法返回 root view 后按资源 id 隐藏，
     * 并在 1.5s / 4s 各补一次（营销位是数据到达后才挂上去的）。一次性延迟，不是轮询。
     */
    private void installHideDeferred(ClassLoader loader, String[] classNames, final String feature, final String[] idNames) {
        for (String className : classNames) {
            installHide(loader, className, null, feature, idNames, true);
        }
    }

    /**
     * 首页 Fragment 的具名列表 {@link #HOME_FRAGMENTS} 只在 8.x 有效。
     *
     * <p>7.0 及更早滴滴把首页整包搬到了 {@code framework.v6x.home}，而且 R8 把 Fragment
     * 本体改成了 {@code v6x/home/a|b|c|d} 这种短名——具名列表一个都命中不了，于是
     * hide_promo_card / hide_home_banner / hide_top_tabs / hide_top_tools 全部报 miss。
     *
     * <p>所以加一层有界发现：只扫已知的 home 包前缀 × R8 常见的短名单（{@code a~z}、
     * {@code a0~z0}），命中条件是「Fragment 子类 + 声明了 {@code onCreateView} 那种形状
     * 的方法」。这是有界的：前缀个数 × 52 次 {@code loadClass}，不枚举整个 dex
     * （滴滴 APK 近 100 MB，枚举会把启动卡住）。
     *
     * <p>只在具名列表一个都没命中时才扫，且按 versionCode 缓存结果——
     * 同一版本只付一次代价，之后启动直接读缓存。
     *
     * @return 首页 Fragment 类名；具名或扫描都没找到时返回空数组（调用方按原样报 miss）
     */
    private static String[] resolveHomeFragments(ClassLoader loader, String[] named) {
        return resolveByShape(loader, named, HOME_FRAGMENT_PREFIXES, true, "fragment");
    }

    private static String[] resolveHomeContainers(ClassLoader loader, String[] named) {
        // 容器不要求是 Fragment：HomeContainer 这类自定义 ViewGroup 不继承 Fragment，
        // 但只要它声明了"用 LayoutInflater 造 View 树"的方法就说明它负责搭首页。
        // 误命中的后果只是在一个没人用的方法上挂钩子，hideIds 找不到控件就什么都不做。
        return resolveByShape(loader, named, HOME_CONTAINER_PREFIXES, false, "container");
    }

    private static String[] resolveByShape(ClassLoader loader, String[] named, String[] prefixes, boolean requireFragment, String tag) {
        ArrayList<String> hits = new ArrayList<>();
        for (String className : named) {
            if (R.load(loader, className) != null) hits.add(className);
        }
        if (!hits.isEmpty()) { H.setDiscovery(tag + ": 具名直接命中 " + hits); return hits.toArray(new String[0]); }
        Class<?> fragment = requireFragment ? fragmentClass(loader) : Object.class;
        StringBuilder trace = new StringBuilder(tag).append(": 具名全未命中；")
                .append(requireFragment ? "Fragment 基类=" : "不要求 Fragment；");
        if (requireFragment) trace.append(fragment == null ? "加载不到" : fragment.getName()).append("；");
        if (fragment == null) { H.setDiscovery(trace.toString()); return new String[0]; }
        for (String prefix : prefixes) {
            int loaded = 0, shaped = 0;
            for (int i = 0; i < 52; i++) {
                String simple = i < 26
                        ? String.valueOf((char) ('a' + i))
                        : (char) ('a' + i - 26) + "0";
                Class<?> candidate;
                try { candidate = loader.loadClass(prefix + simple); } catch (Throwable ignored) { continue; }
                loaded++;
                if (requireFragment && !fragment.isAssignableFrom(candidate)) continue;
                if (findCreateView(candidate, null) == null) continue;
                shaped++;
                hits.add(prefix + simple);
            }
            if (loaded > 0) trace.append(prefix).append(" 载到 ").append(loaded).append('/').append(shaped).append("；");
        }
        H.setDiscovery(trace.toString());
        return hits.toArray(new String[0]);
    }

    /**
     * androidx 与 platform 两套 Fragment 都要认，老滴滴用的是 platform 那套。
     *
     * <p>必须用宿主的 targetLoader 查，不能用 {@code Class.forName}——那走的是模块自己
     * 的定义 ClassLoader，看不到宿主 APK 里的 androidx 类。
     */
    private static Class<?> fragmentClass(ClassLoader loader) {
        for (String name : new String[]{
                "androidx.fragment.app.Fragment",
                "android.support.v4.app.Fragment",
                "android.app.Fragment"}) {
            try { return loader.loadClass(name); } catch (Throwable ignored) { }
        }
        return null;
    }

    /**
     * 找"用 LayoutInflater 造 View 树"的方法（{@code onCreateView} 那种形状）。
     *
     * <p>必须沿继承链往上找：R8 常把 {@code onCreateView} 留在 {@code BaseXxxFragment} 基类上，
     * 具体 Fragment 继承但不重写。只看 {@code getDeclaredMethods()} 的话，7.0 上那些
     * {@code v6x/home/a|b|c|d} 一个都认不出来。
     *
     * @param methodHint 指定方法名；非 null 时只认这个名字，否则取形状匹配的第一个
     * @return 命中的是「声明它的那个类」上的方法——直接 hook 它就能覆盖所有子类
     */
    private static Method findCreateView(Class<?> owner, String methodHint) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            Method first = null;
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 2 || p.length > 3) continue;
                if (!android.view.View.class.isAssignableFrom(m.getReturnType())) continue;
                if (!android.view.LayoutInflater.class.isAssignableFrom(p[0])) continue;
                if (!android.view.ViewGroup.class.isAssignableFrom(p[1])) continue;
                if (methodHint != null && m.getName().equals(methodHint)) return m;
                if (first == null) first = m;
            }
            if (first != null) return first;
        }
        return null;
    }

    /** 通用"界面简化"闸门：按**形状**找"创建视图"方法（名字跨版本会变）。 */
    private void installHide(ClassLoader loader, String className, String methodHint, final String feature,
                             final String[] idNames, final boolean deferred) {
        Class<?> owner = R.load(loader, className);
        if (owner == null) { H.miss(className, "ClassNotFound"); return; }
        Method target = findCreateView(owner, methodHint);
        if (target == null) { H.miss(className + "." + methodHint, "NoShapeMatch"); return; }
        final String label = shortName(className) + "." + target.getName() + "(" + target.getParameterTypes().length + ")";
        try { target.setAccessible(true); } catch (Throwable ignored) {}
        try {
            hook(target).setId("hide_" + feature + "_" + label).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        if (result instanceof android.view.View) {
                            final android.view.View root = (android.view.View) result;
                            final Runnable task = new Runnable() {
                                @Override public void run() {
                                    try {
                                        int hidden = hideIds(root, idNames);
                                        if (hidden > 0) H.hit(feature, label + " hide=" + hidden);
                                    } catch (Throwable ignored) {
                                    }
                                }
                            };
                            task.run();
                            if (deferred) {
                                android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                                handler.postDelayed(task, 1500);
                                handler.postDelayed(task, 4000);
                                handler.postDelayed(task, 8000);
                                handler.postDelayed(task, 15000);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                }
            });
            H.installed("hide_" + feature + "@" + label);
        } catch (Throwable t) {
            H.miss("hide_" + feature + "@" + label, t.getClass().getSimpleName());
        }
    }

    /** 通用"界面简化"闸门：按**形状**找"创建视图"方法（名字跨版本会变），返回 root view 后按资源 id 一次性隐藏。 */
    private void installHide(ClassLoader loader, String className, String methodHint, final String feature, final String[] idNames) {
        Class<?> owner = R.load(loader, className);
        if (owner == null) { H.miss(className, "ClassNotFound"); return; }
        Method target = findCreateView(owner, methodHint);
        if (target == null) { H.miss(className + "." + methodHint, "NoShapeMatch"); return; }
        final String label = shortName(className) + "." + target.getName() + "(" + target.getParameterTypes().length + ")";
        try { target.setAccessible(true); } catch (Throwable ignored) {}
        try {
            hook(target).setId("hide_" + feature + "_" + label).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        if (result instanceof android.view.View) {
                            int hidden = hideIds((android.view.View) result, idNames);
                            H.hit(feature, label + " hide=" + hidden);
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                }
            });
            H.installed("hide_" + feature + "@" + label);
        } catch (Throwable t) {
            H.miss("hide_" + feature + "@" + label, t.getClass().getSimpleName());
        }
    }

    /** 按资源 id 名隐藏；返回成功隐藏的个数。id 名解析结果缓存，不做全树遍历。 */
    private static int hideIds(android.view.View root, String[] idNames) {
        android.content.Context context = root.getContext();
        if (context == null) return 0;
        android.content.res.Resources res = context.getResources();
        String pkg = context.getPackageName();
        int hidden = 0;
        for (String name : idNames) {
            try {
                int id = res.getIdentifier(name, "id", pkg);
                if (id == 0) continue;
                android.view.View view = root.findViewById(id);
                if (view != null && view.getVisibility() != android.view.View.GONE) {
                    view.setVisibility(android.view.View.GONE);
                    hidden++;
                }
            } catch (Throwable ignored) {
            }
        }
        return hidden;
    }

    // ------------------------------------------------------- 推送通知广告闸门

    /**
     * 推送通知广告闸门：挂 {@code NotificationManager}。
     *
     * <p>选这个点的原因：{@code notify(...)} 是<b>滴滴进程内所有通知的唯一出口</b>——厂商推送
     * 通道、自建长连接、轮询、AlarmManager 拉回来的广告，最终都要调它；而且它是平台类，
     * 不参与 R8 混淆，所以滴滴更新改的是自己的广告 SDK 名字，这里不受影响。
     *
     * <p>{@code createNotificationChannel} 用来在渠道注册那一刻就判定广告渠道：渠道 id/名/描述
     * 创建后固定，是比文案更强的信号，而且只跑一次。它只观测不拦截（拦掉渠道创建会让 App
     * 后续 notify 到不存在的渠道而抛异常，反而更糟）。
     */
    private void installPushNotify() {
        int before = H.hooked();
        hookNotify(false);
        hookNotify(true);
        hookChannel();
        int got = H.hooked() - before;
        if (got < PUSH_NOTIFY_ENTRIES) {
            partialKey = "push_notify";
            partialWhy = "只挂上 " + got + "/" + PUSH_NOTIFY_ENTRIES + " 个通知入口（notify 判定仍对已挂上的入口有效）";
        }
        // 没有真机广告通知时，用固定样本证明"判定函数本身"是对的，而不是只说"钩子装上了"。
        String selfTest = NotifyGate.selfTest();
        pushSelfTest = selfTest;
        H.info(selfTest);
        setDetail("push_notify", selfTest);
        // 通知闸门的适配范围比整模块宽得多：它只挂平台类 NotificationManager，
        // 不受 App 改混淆名影响。在宽区间里就明说，让用户知道这一项在老版本上照样有效。
        if (Config.isPushVerified(Config.observedVersionCode)) {
            setDetail("push_notify", "本版本落在已实测区间 "
                    + Config.PUSH_VERIFIED_MIN + "~" + Config.PUSH_VERIFIED_MAX
                    + " 内（整模块区间是 " + Config.VERIFIED_MIN + "~" + Config.VERIFIED_MAX
                    + "）；" + selfTest);
        }
    }

    private static final int PUSH_NOTIFY_ENTRIES = 4;
    /** 判定自检结论；拦到广告后刷新报告时也要带着它。 */
    private volatile String pushSelfTest = "";

    private void hookNotify(final boolean withTag) {
        String id = withTag ? "push_notify_tagged" : "push_notify_plain";
        try {
            Method target = withTag
                    ? NotificationManager.class.getDeclaredMethod("notify", String.class, int.class, Notification.class)
                    : NotificationManager.class.getDeclaredMethod("notify", int.class, Notification.class);
            hook(target).setId(id).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    // 判据全在 NotifyGate 里，任何异常它自己 fail-open 放行。
                    NotifyGate.Decision decision = NotifyGate.evaluate(
                            (Notification) chain.getArg(withTag ? 2 : 1),
                            withTag ? (String) chain.getArg(0) : null);
                    if (decision.suppress) {
                        // 不调用 proceed() = 通知根本不下发；正常通知一条都不受影响。
                        H.hit("push_notify", "suppress:" + decision.reason);
                        H.row("push_notify", "matched",
                                "已拦广告通知 " + NotifyGate.suppressed() + " 条 / 共见到 " + NotifyGate.scanned()
                                        + " 条；广告渠道 " + NotifyGate.channelsAd() + "/" + NotifyGate.channelsSeen()
                                        + "；" + pushSelfTest);
                        return null;
                    }
                    return chain.proceed(); // 放行路径零日志、零分配
                }
            });
            H.installed(id);
        } catch (Throwable t) {
            H.miss(id, t.getClass().getSimpleName());
        }
    }

    private void hookChannel() {
        try {
            Method target = NotificationManager.class.getDeclaredMethod(
                    "createNotificationChannel", NotificationChannel.class);
            hook(target).setId("push_notify_channel").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        NotifyGate.Decision d = NotifyGate.evaluateChannel((NotificationChannel) chain.getArg(0));
                        if (d.suppress) H.hit("push_notify", "ad_channel:" + d.reason);
                    } catch (Throwable ignored) {}
                    return result;
                }
            });
            H.installed("push_notify_channel");
        } catch (Throwable t) {
            H.miss("push_notify_channel", t.getClass().getSimpleName());
        }
        try {
            Method target = NotificationManager.class.getDeclaredMethod(
                    "createNotificationChannels", java.util.List.class);
            hook(target).setId("push_notify_channels").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object arg = chain.getArg(0);
                        if (arg instanceof java.util.List) {
                            for (Object channel : (java.util.List<?>) arg) {
                                NotifyGate.Decision d = NotifyGate.evaluateChannel((NotificationChannel) channel);
                                if (d.suppress) H.hit("push_notify", "ad_channel:" + d.reason);
                            }
                        }
                    } catch (Throwable ignored) {}
                    return result;
                }
            });
            H.installed("push_notify_channels");
        } catch (Throwable t) {
            H.miss("push_notify_channels", t.getClass().getSimpleName());
        }
    }

    private interface Installer { void install(); }

    private void setPartial(String key, String why) {
        partialKey = key;
        partialWhy = why;
    }

    private void setDetail(String key, String extra) {
        detailKey = key;
        detailExtra = extra;
    }

    private void feature(String key, SharedPreferences prefs, Installer installer) {
        boolean enabled = Config.read(prefs, key, Config.defaultOf(key));
        int before = H.hooked();
        partialKey = null;
        partialWhy = null;
        detailKey = null;
        detailExtra = null;
        if (!enabled) {
            H.info("feature=" + key + " result=off");
            H.report("running", key, "off", "开关已关闭");
            H.row(key, "off", "开关已关闭");
            return;
        }
        String failure = null;
        try {
            installer.install();
        } catch (Throwable t) {
            failure = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        int hooked = H.hooked() - before;
        if (failure != null) {
            H.warn("feature=" + key + " result=miss reason=" + failure);
            H.report("running", key, "miss", failure);
            H.row(key, "miss", failure);
        } else if (hooked <= 0) {
            H.warn("feature=" + key + " result=miss reason=no anchor");
            H.report("running", key, "miss", "锚点未找到");
            H.row(key, "miss", "这个版本找不到锚点，已自动跳过");
        } else if (key.equals(partialKey)) {
            // 只覆盖部分入口：必须单独报出原因，不能算成"成功"。
            H.warn("feature=" + key + " result=partial hooks=" + hooked + " reason=" + partialWhy);
            H.report("running", key, "partial", partialWhy);
            H.row(key, "partial", partialWhy);
        } else {
            String detail = "已装 " + hooked + " 条钩子";
            if (key.equals(detailKey) && detailExtra != null) detail += "；" + detailExtra;
            H.info("feature=" + key + " result=matched hooks=" + hooked);
            H.report("running", key, "matched", detail);
            H.row(key, "matched", detail);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Method find(Class<?> owner, String name, int paramCount) {
        if (owner == null) return null;
        Method found = null;
        for (Method m : owner.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            if (m.getParameterTypes().length != paramCount) continue;
            if (found != null) return null; // 歧义即失败
            found = m;
        }
        return found;
    }

    private void gateBoolean(Class<?> owner, String name, final String feature, final String label) {
        Method target = find(owner, name, 1);
        if (target == null) { H.miss(label, "NoSuchMethod"); return; }
        gateReturn(target, Boolean.FALSE, feature, label);
    }

    /** 按名字挂闸门（参数个数不限，跨版本稳）：void→跳过原实现；boolean→false；其它→null。 */
    private void gateByName(Class<?> owner, String name, final String feature, String label) {
        if (owner == null) { H.miss(label, "NoOwner"); return; }
        Method target = null;
        for (Method m : owner.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            if (target != null) { H.miss(label, "ambiguous"); return; }
            target = m;
        }
        if (target == null) { H.miss(label, "NoSuchMethod"); return; }
        Class<?> ret = target.getReturnType();
        Object value = ret == boolean.class || ret == Boolean.class ? Boolean.FALSE : null;
        gateReturn(target, value, feature, label + "/" + target.getParameterTypes().length);
    }

    private void gateReturn(final Method target, final Object value, final String feature, final String label) {
        try { target.setAccessible(true); } catch (Throwable ignored) {}
        try {
            hook(target).setId("gate_" + label).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    H.hit(feature, label);
                    return value;
                }
            });
            H.installed(label);
        } catch (Throwable t) {
            H.miss(label, t.getClass().getSimpleName());
        }
    }

    private void probe(ClassLoader loader, String className, final String feature) {
        Class<?> owner = R.load(loader, className);
        if (owner == null) { H.miss(className, "ClassNotFound"); return; }
        int before = H.hooked();
        for (Method m : owner.getDeclaredMethods()) {
            if (H.hooked() - before >= 8) break;
            if (!R.isInteresting(m)) continue;
            final String label = shortName(className) + "." + m.getName();
            try { m.setAccessible(true); } catch (Throwable ignored) {}
            try {
                hook(m).setId("probe_" + label).intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        H.hit(feature, label);
                        return chain.proceed();
                    }
                });
                H.installed(label);
            } catch (Throwable t) {
                H.miss(label, t.getClass().getSimpleName());
            }
        }
        if (H.hooked() == before) H.miss(className, "noInterestingMethod");
    }

    private static String shortName(String className) {
        int i = className.lastIndexOf('.');
        return i < 0 ? className : className.substring(i + 1);
    }

    @Override
    public boolean onHotReloading() {
        return false;
    }
}
