# 滴滴去广告 + 界面简化（LSPosed 模块）

<img src="assets/icon-512.png" width="96" alt="模块图标">

针对 **滴滴出行（`com.sdu.didi.psnger`）** 的 LSPosed 模块：**去广告** + **首页界面简化**，并带一个**按页面分类**的设置页 —— 每个开关都写明「对应页面」，一眼知道它改的是哪个界面。

- 框架：LSPosed（libxposed **API 102**，`minApiVersion=101` `targetApiVersion=102`）
- 实测环境：realme RMX8899（Android 16 / arm64 / APatch + LSPosed 2.2.0）· 滴滴 **8.0.2 (1208000204)**
- 设计原则：**只拦广告，不碰正常弹窗**；全部锚点按「类名 + 形状 + 控件 id」匹配，换版本缺失的会自动跳过（fail-open），绝不因为一个锚点没找到就影响其它功能

## 功能（设置页按此分类）

| 分类 | 开关 | 对应页面 | 说明 |
|---|---|---|---|
| 全局 | 广告总闸 | 全局 · 所有广告位 | `AdSdk.d(AdRequest)` 恒返回 false → 广告 SDK 置为“未就绪”，广告展示路径自然退出 |
| 广告拦截 | 广告弹窗 · 通知 | 任意页 · 弹窗浮层 / 通知 | 拦截 `PopRequest` / `NotifyRequest`（**只拦广告弹窗，正常弹窗不拦**） |
| 广告拦截 | 开屏广告 | 启动页 · 冷启动开屏 | 不运行开屏展示流程、不接受开屏资源 |
| 广告拦截 | 推送通知广告 | 通知栏 · 推送下发的广告通知 | 拦 `NotificationManager.notify` 这个**所有通知的唯一出口**：先判通知渠道 id/名，再判标题与正文里的广告词。命中的广告通知**根本不下发**；行程 / 接单 / 送达等正常通知原样保留 |
| 广告拦截 | ⚠ 通用弹窗全拦（**默认关**） | 任意页 · DiDi 自有弹窗 | 拦截 `com.didi.sdk.view.dialog.b.show()`。**开了会连正常提示一起拦**，默认关闭 |
| 首页界面 | 首页营销卡片 / 营销横幅 | 首页 · 顶部智能卡片 | 隐藏 `v8_smart_card_container` 等 |
| 首页界面 | 首页底部营销专区 | 首页 · 最下方推广区 | 隐藏 `banner_parent_container` / `card_layout` 等 |
| 首页界面 | 首页顶部频道栏 | 首页 · 顶部（出行/送货/旅行/车主） | 隐藏 `tabLayout` |
| 首页界面 | 首页右上角工具按钮 | 首页 · 右上角（乘车码 / 扫一扫） | 隐藏 `riding_code` / `scan` |
| 首页界面 | 首页底部导航栏（**默认关**） | 首页 · 底部导航栏 | 整条底部栏隐藏（底栏功能有用的话别开） |
| 首页界面 | 底部导航栏只留「首页 / 我的」（**默认关**） | 首页 · 底部导航栏逐项 | 在构建 tab **之前**过滤数据，宽度按过滤后的数量计算，安全 |
| 首页界面 | 首页场景行 | 首页 · 搜索卡片下方一行 | 隐藏 `ch_scene_layout`（AI叫车/预约/帮人叫车/接送机） |
| 诊断与恢复 | 调用观测（只写日志） | 诊断 · 不改变界面 | 只读探针，命中打 `hit=` 日志 |
| 诊断与恢复 | 版本兼容自检 | — | 每次注入自动扫描 13 个锚点，设置页显示 `锚点 ok/总数`；缺失项自动跳过 |

设置页还有两个按钮：**强制停止滴滴（立即生效）**、**恢复默认**。

## 推送通知广告闸门（push_notify）

挂在 `android.app.NotificationManager` 上，四个入口：`notify(int,Notification)`、`notify(String,int,Notification)`、`createNotificationChannel(NotificationChannel)`、`createNotificationChannels(List)`。

**为什么挂这里**：`notify(...)` 是滴滴进程内所有通知的**唯一出口**——厂商推送通道、自建长连接、轮询、AlarmManager 拉回来的广告最终都要调它；而且它是**平台类、不参与 R8 混淆**，滴滴更新改的是自己的广告 SDK 名字，这里不受影响。挂平台类还意味着换版本不会失效，判据表可跨版本复用。

**判定顺序**（先便宜后昂贵，命中即停）：

1. 渠道 id —— 一次 `indexOf`，广告常自带 `*_ad / *_promo` 这类渠道；
2. 渠道名 / 描述 —— 渠道创建后固定，是强信号；
3. 标题 / 正文 / 长文 / 副标题 / 附加文本 / 滚动文本 / tag —— 广告文案关键词。

**只拦广告**：词表刻意避开行程类正常通知用词（行程 / 司机 / 派单 / 接驾 / 送达 / 取消）。任何异常一律 fail-open 放行，行为与没装模块完全一致。

**性能**：`notify` 是低频事件（按小时计），不是 `onDraw` 那种热路径。拦截体内只做 `String.indexOf` 和取已有对象，零反射、零分配（`String.toString()` 返回 `this`）、零逐条日志；放行路径直接 `proceed()`。

**判据表可改**：在 `app/src/io/github/didiadclean/NotifyGate.java` 的 `CHANNEL_TOKENS` / `CHANNEL_NAME_TOKENS` / `TEXT_TOKENS` 里改，改完强停滴滴生效。词表是通用广告词，遇到拦不准的通知就往里加词。

**可观测**：设置页「适配诊断」里这一项会显示 `已装 4 条钩子；self_test 7/7 passed`。`self_test` 是安装时用固定样本跑一遍判定函数的结论（正样本应当拦、行程类负样本应当放），用来证明**判定逻辑本身**是对的；真正拦到广告后，这一行会变成 `已拦广告通知 N 条 / 共见到 M 条；广告渠道 A/B`。

**`createNotificationChannel` 只观测不拦截**：把渠道拦掉会让后续 `notify` 抛异常，反而更糟。

## 版本兼容机制

### 已验证版本区间：7.2.17 ~ 8.0.14（整模块）／ 6.5.18 ~ 8.0.14（通知闸门 + 首页隐藏）

逐版真机装 APK → 冷启动 → 读模块落盘报告：

| 宿主版本 | versionCode | push_notify | self_test | 9 项状态 | 结论 |
|---|---|---|---|---|---|
| 6.5.18 | 1206051804 | 4/4、7/7 | 7/7 | 5 matched / 4 miss | 通知闸门 + 4 项首页隐藏 |
| 7.0.0 | 1207000001 | 4/4、7/7 | 7/7 | 6 matched / 3 miss | 同上 + 探测探针 |
| 7.2.17 | 1207021704 | 4/4、7/7 | 7/7 | 9 matched / 0 miss | 全通过 |
| 8.0.0 | 1208000004 | 4/4、7/7 | 7/7 | 9 matched / 0 miss | 全通过 |
| 8.0.11 | 1208001102 | 4/4、7/7 | 7/7 | 9 matched / 0 miss | 全通过 |
| 8.0.12 | 1208001204 | 4/4、7/7 | 7/7 | 9 matched / 0 miss | 全通过 |
| 8.0.13 | 1208001301 | 4/4、7/7 | 7/7 | 9 matched / 0 miss | 全通过 |
| 8.0.14 | 1208001404 | 4/4、7/7 | 7/7 | 9 matched / 0 miss | 全通过 |

**两个区间，含义不同：**

- **整模块可用：7.2.17 ~ 8.0.14**。五个实测点全部 9 项 matched、0 miss。区间内每两个相邻点之间没有再插点测试，按二分法的前提整段标记为可用。下界不是随便挑的：再往前一档实测 7.0.0 还有 3 项 miss，所以真实分界在 7.1.x 区间内，本模块不声称覆盖 7.1 及更早。
- **仅推送通知闸门 + 首页隐藏可用：6.5.18 ~ 8.0.14**。这条宽得多，原因分两条：
  - 通知闸门只挂 `android.app.NotificationManager` 这个**平台类**，滴滴改混淆名不影响它，四个版本上都是 4/4 入口、self_test 7/7。
  - 首页那几项靠的是**视图控件 ID** + 按形状发现 Fragment，7.0 及更早也认得出来（见下一节）。

代码里对应 `Config.VERIFIED_MIN/VERIFIED_MAX`（整模块）和 `Config.PUSH_VERIFIED_MIN/PUSH_VERIFIED_MAX`（仅通知），都按区间判断而非逐个版本号列举，中间若出现没单独测过的版本号也自动覆盖。

**只有整模块区间会触发「跳过扫描」**，命中后设置页显示 `已验证版本 X(code)：覆盖 …，跳过锚点扫描`。通知闸门区间只影响 `push_notify` 那一行的标注——7.x 上广告 SDK 那几项本来就找不到锚点，扫不扫都一样，fail-open 逐项跳过才是对的，所以不会因此跳过扫描。

### 7.0 及更早：首页隐藏怎么适配上去的

7.0 之前滴滴把首页整包搬到了 `framework.v6x.home`，而且 R8 把 Fragment 和容器都压成了短名——`v6x/home/a|b|c|d`、`common/app/a|b|c|d|e`，具名列表一个都命中不了，于是 `hide_promo_card` / `hide_home_banner` / `hide_top_tabs` / `hide_top_tools` 全部报 miss（7.0.0 上 2 matched / 7 miss）。

改成**有界短名单发现**：只扫已知的 home 包前缀 × R8 常见的短名（`a~z`、`a0~z0`），命中条件是「声明了 `onCreateView` 那种形状的方法」。三个关键点：

1. **沿继承链往上找**。R8 常把 `onCreateView` 留在 `BaseXxxFragment` 基类上，具体 Fragment 继承但不重写——只看 `getDeclaredMethods()` 的话一个都认不出来。这是让 7.0.0 从 2 matched 变成 6 matched 的关键。
2. **用宿主的 targetLoader 查，不能用 `Class.forName`**。那走的是模块自己的定义 ClassLoader，看不到宿主 APK 里的 androidx 类。
3. **有界，且不枚举整个 dex**。前缀个数 × 52 次 `loadClass`，滴滴 APK 近 100 MB，枚举会把启动卡住（这在 QQ 音乐那个模块上已经实测过一次）。

具名列表命中时直接走具名，扫描只在全落空时才跑。

扫描过程落到报告的 `discovery` 行（每个前缀载到几个 / 其中几个形状匹配），出问题时能直接看出卡在哪一关，不用反复装机试。

### 仍然不适用的：广告 SDK 那三项

`no_ads` / `popup` / `splash` 在 7.0 及更早**依然 miss**，这一条没解决，原因在宿主身上：

| 8.x 上具名 | 7.0.0 上实际 |
|---|---|
| `com.didi.ad.AdSdk` | `com.didi.ad.a` … `com.didi.ad.g`（全被混淆） |
| `com.didi.ad.splash.QuickSplashShow` | 不存在，只有 `QuickSplashLoad` |
| `com.didi.ad.api.AdRequest` | `com.didi.ad.api.a` … `.n` |
| `com.didi.ad.base.net.HttpSender` | `com.didi.ad.base.net.a` … `.f` |

不是改个名字就行：整个广告 SDK 在 7.x 上是混淆的，模块拿不到 `AdRequest` 的类型，也就无从判断哪个静态方法才是「要广告」的那个入口——多个候选方法形状完全一样，而这里挂错钩子的后果是直接在用户点广告时崩溃，**且没法靠离线分析验证**。按本模块「候选不唯一就跳过、绝不猜」的规矩，这三项在 7.x 上就是标成 miss 跳过。

所以老版本上的实际效果是：**推送通知广告拦得住、首页推广位和横幅藏得住，但开屏广告和弹窗广告藏不掉。** 开屏和弹窗仍需在滴滴 App 内自行关闭。

### 老版本上模块不加载的根因（已修）

滴滴 6.x 把**整个界面**挂在 `com.sdu.didi.psnger:privacy` 子进程上（`LauncherActivity` 的 `android:process` 就是它，主进程根本不启动）。模块原先只认主进程，结果 6.x 上一次都不加载，连状态都不落盘。

现在放行主进程 + 全部子进程。通知下发本身就可能发生在任一子进程，闸门必须装到。

APK 取自豌豆荚历史版本页（`/apps/285799/history`），每个包都用 `aapt2 dump badging` 核过包名与 versionCode 才安装。豌豆荚网页版历史页默认折叠，但 `/apps/<id>/history` 确实存在，能列出全部历史版本号。

### 匹配策略

1. **类名锚点 + 形状匹配**：不写死混淆方法名（滴滴各版本 R8 名字不同，例如首页 Fragment 的“创建视图”方法 8.0.13 叫 `Ud`、8.0.2 叫 `Ad`），统一按 `(LayoutInflater, ViewGroup[, Bundle]) → View` 这类形状找。
2. **控件 id 名匹配**：界面简化用 `resources.getIdentifier("v6x_home_bottom_nav","id",pkg)`，比类名稳；隐藏前会先数该 id 在当前页面出现次数。
3. **运行时自检**：注入时扫描 `Config.COMPAT_ANCHORS`，输出 `event=compat_scan ok=N/13 missing=[...]`，并在设置页展示；缺失锚点自动跳过（fail-open）。
4. **配置/状态通道**：设置页写 LSPosed `RemotePreferences`，被 hook 进程只读；状态用显式广播回传（API 34+ 带 `setShareIdentityEnabled(true)` 并校验发送方 uid）。

## 构建

纯命令行，无需 Android Studio（`javac → d8 → aapt2 → zipalign → apksigner`）：

```powershell
powershell -File app\build.ps1          # 产物：app\dist\didi-adclean-v0.6.0.apk
```

需要 JDK 17 与 Android SDK。工具链**自动探测**，不再写死某台机器的路径：SDK 依次找 `DAC_SDK` → `ANDROID_SDK` → `ANDROID_HOME` → `ANDROID_SDK_ROOT` → 仓库同级的 `.android_build_tools\android-sdk` → `C:\Android\Sdk` → `%LOCALAPPDATA%\Android\Sdk`；JDK 找 `DAC_JDK` → `JAVA_HOME` → 同级 `.android_build_tools\jdk17` → AdoptOpenJDK。platform 与 build-tools 取该 SDK 下**实际可用的最高版本**，不再钉死 android-35 / build-tools 35.0.0。

D8 单独解析：优先 `R8_JAR` 环境变量，其次 `<SDK>\d8\r8-*.jar`，最后才用 SDK 自带 `d8.jar`。两个原因：不带自己 `d8.jar` 的 build-tools 安装会让 `d8.bat` 退化成看不懂的 `ClassNotFoundException`；旧 build-tools 自带的 R8 3.3.20 在 dexing 本项目时会抛 `Cannot invoke String.length() because <parameter1> is null`。本仓库在 Android SDK `platforms;android-34` + `build-tools;34.0.0` + R8 9.4.27 上构建通过。

另外：工作区路径含中文时，`Get-ChildItem -Filter '*.java'` 在 PowerShell 5.1 上会**偶发返回 0 个文件**（曾表现为静默跳过所有源码），所以脚本统一用 `Get-FilesByExtension` 按扩展名过滤，不使用 `-Filter`。

`app/debug.keystore` 是本地测试签名，仓库里不含；首次构建会自动生成。

## 安装

```bash
adb install -r app/dist/didi-adclean-v0.6.0.apk
# LSPosed 管理器里启用模块 + 勾选作用域 com.sdu.didi.psnger（本模块 staticScope=true，作用域由 APK 内 scope.list 声明）
adb shell am force-stop com.sdu.didi.psnger     # 或在设置页点“强制停止滴滴”
```

## 日志与状态

**首选：设置页的「适配诊断」**（逐项显示 `matched / partial / miss / off` + 原因 + 命中数），这是跨版本最稳的通道。

也可以直接读模块进程落盘的报告（设置页读的就是它）：

```bash
adb shell su -c "cat /data/data/io.github.didiadclean/shared_prefs/compat_reports.xml"
```

关注 `rows` 里 `push_notify` 那一行，形如
`push_notify<TAB>matched<TAB>0<TAB>已装 4 条钩子；self_test 7/7 passed`。

**注意：在 Vector（`zygisk_vector`）上模块日志不进 logcat。** 实测 `logcat -d | grep DiDiAdClean` 什么都搜不到，
框架和模块日志都被 Vector 写进 `/data/adb/lspd/log/` 自己的文件。查加载情况用：

```bash
adb shell su -c "grep -a didiadclean /data/adb/lspd/log/modules_*.log | tail -20"
```

经典 LSPosed（`LSPosedFramework` tag）上 `logcat` 那条路才有效。

## 已知限制

- 开屏广告：已布防（`QuickSplashShow.f/c`），需要真实开屏库存才能在日志里看到命中。
- 底部营销专区是数据晚到才挂上去的：采用视图创建后 1.5s/4s/8s/15s 四次一次性补隐藏（非轮询）。
- “通用弹窗全拦”默认关闭：它拦的是滴滴自有弹窗组件的统一出口，会连确认/安全类弹窗一起拦。
- 仅针对滴滴出行做适配；换版本后请看设置页的「版本兼容自检」。

## 免责声明

仅供个人学习与自用，请勿用于商业用途或传播修改后的客户端；使用风险自负。
