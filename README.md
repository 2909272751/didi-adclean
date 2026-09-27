# 滴滴去广告 + 界面简化（LSPosed 模块）

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

## 版本兼容机制

1. **类名锚点 + 形状匹配**：不写死混淆方法名（滴滴各版本 R8 名字不同，例如首页 Fragment 的“创建视图”方法 8.0.13 叫 `Ud`、8.0.2 叫 `Ad`），统一按 `(LayoutInflater, ViewGroup[, Bundle]) → View` 这类形状找。
2. **控件 id 名匹配**：界面简化用 `resources.getIdentifier("v6x_home_bottom_nav","id",pkg)`，比类名稳；隐藏前会先数该 id 在当前页面出现次数。
3. **运行时自检**：注入时扫描 `Config.COMPAT_ANCHORS`，输出 `event=compat_scan ok=N/13 missing=[...]`，并在设置页展示；缺失锚点自动跳过（fail-open）。
4. **配置/状态通道**：设置页写 LSPosed `RemotePreferences`，被 hook 进程只读；状态用显式广播回传（API 34+ 带 `setShareIdentityEnabled(true)` 并校验发送方 uid）。

## 构建

纯命令行，无需 Android Studio（`javac → d8 → aapt2 → zipalign → apksigner`）：

```powershell
$env:JAVA_HOME='<jdk17>'
powershell -File app\build.ps1          # 产物：app\dist\didi-adclean-v0.6.0.apk
```

需要 ANDROID SDK（`build-tools;35.0.0` + `platforms;android-35`）与 JDK 17。`app/debug.keystore` 是本地测试签名，仓库里不含；首次构建会自动生成。

## 安装

```bash
adb install -r app/dist/didi-adclean-v0.6.0.apk
# LSPosed 管理器里启用模块 + 勾选作用域 com.sdu.didi.psnger（本模块 staticScope=true，作用域由 APK 内 scope.list 声明）
adb shell am force-stop com.sdu.didi.psnger     # 或在设置页点“强制停止滴滴”
```

## 日志

```bash
adb shell "logcat -d" | grep DiDiAdClean        # 模块日志（tag 实际为 LSPosedFramework，模块名在方括号里）
adb shell su -c "grep -a didiadclean /data/adb/lspd/log/modules_*.log | tail -50"
```
关键行：`event=install_summary hooked=23 miss=0`、`event=compat_scan ok=13/13 missing=[]`、逐条 `hit=<类.方法>`。

## 已知限制

- 开屏广告：已布防（`QuickSplashShow.f/c`），需要真实开屏库存才能在日志里看到命中。
- 底部营销专区是数据晚到才挂上去的：采用视图创建后 1.5s/4s/8s/15s 四次一次性补隐藏（非轮询）。
- “通用弹窗全拦”默认关闭：它拦的是滴滴自有弹窗组件的统一出口，会连确认/安全类弹窗一起拦。
- 仅针对滴滴出行做适配；换版本后请看设置页的「版本兼容自检」。

## 免责声明

仅供个人学习与自用，请勿用于商业用途或传播修改后的客户端；使用风险自负。
