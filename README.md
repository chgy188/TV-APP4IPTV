# composedTV（TV-APP4IPTV）

基于 **Jetpack Compose + Android TV** 的 IPTV 播放客户端，对接 `worker4iptv` 后端。支持多用户切换、频道分组检索、收藏、HLS 直连/代理串行兜底起播、点播当天续播、外部 `ACTION_VIEW` 视频播放与老设备音频软解兜底。

> 后端 API 默认地址：`https://tv.run4u.dpdns.org`（`ApiClient.baseUrl`，可改）。

## 技术栈

| 项 | 版本 / 说明 |
|----|-------------|
| 语言与 UI | Kotlin 2.0.21、Jetpack Compose（BOM 2024.02.00）、`androidx.tv` alpha10 |
| 播放器 | AndroidX Media3 / ExoPlayer **1.6.1**（`exoplayer` / `exoplayer-hls` / `ui` / `extractor` / `datasource-okhttp`），版本被 `resolutionStrategy.force` 锁定 |
| 软解扩展 | `org.jellyfin.media3:media3-ffmpeg-decoder:1.6.1+2`（FFmpeg 音频/视频解码，带 `libffmpegJNI.so`，GPL-3.0） |
| 网络 | OkHttp 4.12.0（`ApiClient` 与播放数据源各自独立客户端） |
| 异步与架构 | Coroutines + ViewModel + StateFlow + Navigation Compose |
| SDK | `compileSdk 35` / `targetSdk 34` / `minSdk 21`（兼容 Android 5 电视盒子） |
| JVM | Kotlin/Java 编译目标 **JVM 11**；Gradle 与 CI 使用 JDK 17 运行 |
| 其它 | `coreLibraryDesugaring`（Java 8 API 脱糖）、Lint `NewApi` 提升为 error（拦截老设备 `NoSuchMethodError`） |

## 目录结构（关键源码）

```
app/src/main/java/com/example/composedtv/
├── ComposedTVApplication.kt          # Application 入口，初始化 ApiClient
├── MainActivity.kt                   # 导航 / 遥控器按键兜底（双击返回退出）
├── data/
│   ├── remote/ApiClient.kt           # 后端 REST 封装、缓存、HLS/图片代理 URL
│   ├── remote/Models.kt              # 数据模型 + 国家/语言中文映射
│   ├── local/LocalFavoritesStore.kt  # 本地收藏
│   └── VodProgressStore.kt           # 点播「当天有效」续播进度（按用户隔离）
├── player/
│   ├── PlayerEngine.kt               # 播放核心：串行起播、音轨兜底、续播、状态机
│   │                                 # （PlaylistItem / PlayerState 也定义在此文件）
│   └── MkvCuesPatchDataSource.kt     # 直连 MKV 的 Cues 索引等长修补，恢复可 seek
├── viewmodel/PlayerViewModel.kt      # UI 状态、侧边栏数据、收藏/搜索、播放设置
├── debug/
│   ├── DebugDiagnostics.kt           # 诊断采集：音轨/解码器/AudioTrack/错误/HUD 快照
│   └── DebugHud.kt                   # 诊断悬浮层
└── ui/
    ├── screens/                      # LoginScreen / UserSelectionScreen / PlayerScreen
    ├── components/                   # SidePanel / SettingsDrawer / WaterGlassLoader / AmbientGradientBackdrop
    └── theme/Theme.kt
```

## 核心功能

### 账号与频道

- **多用户会话**：保存多个已登录用户 token，用户选择界面快速切换；支持游客模式（`enterAsGuest()`）。
- **源与频道浏览**：公开源 + 我的源（登录后）；按「源 → 分类（含收藏/搜索虚拟分类）→ 频道」三级导航。
- **实时搜索**：侧边栏第三列跨当前源所有分类实时筛选频道。
- **收藏**：登录后可收藏/取消收藏（后端按 `url` 去重），收藏列表作为虚拟分类展示。
- **外部播放**：可被文件管理器/浏览器以 `ACTION_VIEW` 唤起播放本地或网络视频，并支持「保存到收藏」面板。

### 播放引擎（`PlayerEngine`）

- **直连/代理串行兜底起播**：`planPlay()` 决定尝试顺序（国内仅直连；海外 HLS 为 `direct → proxy`），同一时刻仅 1 个 `ExoPlayer`，避免老设备双路解码 OOM。仅代理候选使用 `ApiClient.hlsProxyUrl()` 包裹地址，直连走原 URL 并携带频道 UA/Referer。
- **起播超时看门狗**：每个尝试按 `directTimeoutMs` / `proxyTimeoutMs`（设置抽屉可调）计时；超时未 READY、连接停滞或出错即切换下一个，全部失败才走 `handlePlayFailure()`（直播自动跳下一台，点播直接提示）。
- **容器嗅探**：由 `DefaultMediaSourceFactory` 按流首字节识别 HLS / FLV / MP4 / TS / MKV，不依赖 URL 关键字。
- **直播补偿**：`BehindLiveWindowException` 自动滑窗补偿重载；直播不写续播进度。
- **音轨兜底**：`onTracksChanged` 中若无「已选中且受支持」的音轨，显式改选受支持的轨（`ensureSupportedAudioTrack`），缓解多音轨点播在老设备上「有画面无声音」。
- **老设备解码保护**：`EXTENSION_RENDERER_MODE_ON`（系统 MediaCodec 优先、FFmpeg 扩展兜底）+ `setEnableDecoderFallback(true)`；API ≤ 23 设备上用自定义 `MediaCodecSelector` 屏蔽会触发系统 `ACodec` native abort 的 MP3 家族解码器，改由 FFmpeg 软解（详见「故障排查」）。
- **缓冲/加载提示**：起播与播放中缓冲显示加载态，并展示当前尝试模式与剩余超时。
- **连续错误计数 / 代理标识**：`consecutiveErrors` 达频道总数则提示「所有频道均无法播放」；`usingProxy` 标记当前是否经代理。
- **设置抽屉**：首播频道、直连/代理起播超时、渲染方式（SurfaceView / TextureView）、流畅优先、音画同步、诊断 HUD。

### 点播续播

- `VodProgressStore` 按内容 URL / 外部 Uri 记录播放位置，**仅当天有效**（`yyyyMMdd`），按用户隔离，启动时 `pruneStale()` 清理隔天记录；小于 5s 不保存，播完的片尾位置不写回。
- `prepare` 前通过 `setMediaItem(item, startPositionMs)` 注入续播位置；`scheduleResumeFallback()` 在 SeekMap 未就绪时持续补 `seekTo`，直到位置真正达标才提示「已为您续播」并允许覆盖进度记录（续播守护避免把 27s 记录改写成 3s）。
- **MKV Cues 补丁**：部分 MKV 只在文件尾 `SeekHead` 写 Cues 位置，Media3 会判为不可 seek。`MkvCuesPatchDataSource` 在数据源层对头部 `SeekHead` 做等长就地修补（仅直连 `.mkv` 启用，探测失败自动透传）。
- 进度在切台、重载、`onPause`、`stopPlayback()`、`release()` 时统一由 `saveVodProgressNow()` 持久化；`onResume()` 只恢复由后台主动暂停的播放，不覆盖用户手动暂停。

### 缓存与代理

- 内存缓存（源/频道/收藏）+ 每日磁盘缓存（`filesDir/daily_cache/`，按 `yyyyMMdd` 隔离，启动清理旧缓存），可 `invalidateCache(...)` 主动失效。
- 频道/收藏的台标地址由后端 JSON 的 `logo` 字段直接提供；国家/语言由 `CountryLangMapper` 映射为中文。

### 诊断

`DebugDiagnostics` 通过 `AnalyticsListener` 采集解码器名、音视频格式、音轨支持/选中状态、AudioTrack 输出配置、播放错误与起播竞速过程，输出到 logcat（tag `Diag`）并可在设置中打开 `DebugHud` 查看。排查「有画面无声音」时看 `audio-tracks` / `audio-decoder-init` / `audio-track-init` 三类日志即可。

## 后端 API（由 `ApiClient` 调用）

基础：`{baseUrl}` = `https://tv.run4u.dpdns.org`

| 方法 | 路径 | 对应 ApiClient 方法 | 说明 |
|------|------|--------------------|------|
| GET | `/api/public-sources` | `getPublicSources()` | 公开源列表 |
| GET | `/api/mysources` | `getMySources()` | 我的源（登录） |
| GET | `/api/all-sources` | `getAllSources()` | 我的源 + 公开源去重 |
| GET | `/api/channels?sourceId=` | `getChannels()` | 某源频道列表 |
| GET | `/api/guest-start` | `getGuestStart()` | 游客起始频道 |
| GET | `/api/favorites` | `getFavorites()` | 收藏列表 |
| POST | `/api/favorites` | `addFavorite(url,name,logo,country)` | 新增收藏（body 嵌套 `channel`） |
| DELETE | `/api/favorites?url=` | `removeFavorite(url)` | 取消收藏（按 url） |
| POST | `/api/auth` | `auth(action,...)` | login/register/logout/refresh |
| GET | `/api/hls?url=` | `hlsProxyUrl()` | HLS 流代理 |

认证态字段：`AuthResult(ok, token, user, message)`、`ApiUser(id, username, role, needsDefaultSource)`；多用户存储 `StoredUser(username, token, userId, role)`，SharedPreferences 持久化并按用户作用域隔离。

## 构建

```bash
# Linux / macOS
./gradlew assembleDebug           # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease         # 需要根目录 keystore.properties
./gradlew installDebug

# Windows PowerShell
.\gradlew.bat :app:compileDebugKotlin --offline   # 只编译 Kotlin，快速验证
.\gradlew.bat :app:assembleDebug --offline
```

`keystore.properties` 示例（**请勿提交到仓库**）：

```properties
storeFile=release.keystore
storePassword=****
keyAlias=composedtv
keyPassword=****
```

- **单一 APK**：`splits.abi` 关闭。现已引入 FFmpeg 扩展，APK 内含 4 个 ABI 的 `libffmpegJNI.so`；不分包可避免 Release 出现多个包导致用户装错，代价是单包体积增大。
- Release 开启 `minify` + `shrinkResources` + `proguard-rules.pro`；本地打 tag 时 `versionName` 可由环境变量 `APP_VERSION_NAME` 注入。

### 实机调试（ADB）

```powershell
adb connect <电视IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.composedtv/.MainActivity

# 抓 native 崩溃（Java 层 catch 不到的 SIGABRT 只在 crash 缓冲区）
adb logcat -d -b crash -v threadtime -t 400
# 按时间过滤 APP/播放器日志
adb logcat -d -v threadtime -T '11-07 20:00:00.000' |
  Select-String 'PlayerEngine|Diag|ExoPlayer|AndroidRuntime|FATAL'
```

## GitHub Actions 自动构建

`.github/workflows/build.yml` 的触发条件：

- **push `v*` tag**：签名 release 构建、上传 APK 产物，并自动创建名为 `Release <版本号>` 的 GitHub Release；版本号取 tag 名（`v1.2.3` → `1.2.3`）。
- **向 `main` 开 PR**：仅构建 debug 用于校验；非 tag 构建版本号为 `0.0.<YYYYMMDDHHMM>`。
- 普通 push 到 `main` 不触发完整 release 构建（避免与 tag 推送重复）。

### 1. 配置仓库 Secrets（一次性）

`Settings → Secrets and variables → Actions` 添加：

| Secret 名称 | 内容 |
|-------------|------|
| `KEYSTORE_BASE64` | `composedtv-release.jks` 的 base64 全文 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | `composedtv` |
| `KEY_PASSWORD` | key 密码 |

生成 `KEYSTORE_BASE64`（PowerShell）：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("app\composedtv-release.jks")) | Out-File keystore.b64 -NoNewline
# 把 keystore.b64 的全部内容粘进 KEYSTORE_BASE64
```

### 2. 产物位置

Actions 页面 → 对应 run → **Artifacts** 下载 `apks-<版本号>`（含签名 release 单包，或 PR 场景的 debug）；打 tag 时也可在 **Releases** 页面直接下载签名 APK。

CI 在构建完成后、上传前会执行 `Rename APK` 步骤，把 Gradle 默认产物改名：

| 场景 | APK 文件名 |
|------|-----------|
| release（tag 构建） | `影直播-<版本号>.apk` |
| debug（PR 校验） | `影直播-<版本号>-debug.apk` |

本地 `assembleRelease` / `assembleDebug` 仍输出 Gradle 默认的 `app-release.apk` / `app-debug.apk`；若希望本地与 CI 完全一致，可改为在 `app/build.gradle.kts` 里直接设置 `outputFileName`（与 CI 重命名步骤二选一，不要同时启用）。

CI 使用 JDK 17，`setup-android` 安装 Android 34 平台与 build-tools 34.0.0；Gradle 依赖仓库顺序按 `CI=true` 自动切换为官方源优先、阿里云镜像兜底（见 `settings.gradle.kts`）。

## 安全与忽略项

以下文件**绝不入库**（已在 `.gitignore` 排除）：`keystore.properties`、`local.properties`、`*.jks` / `*.keystore`、`app/build/`、`build/`、`.gradle/`、`*.apk`、各类构建输出日志。

另外需注意：

- `.kotlin/sessions/*.salive` 是 Kotlin 编译器的临时会话文件，构建过程会自动创建/删除，建议加入 `.gitignore` 或在提交时排除，避免产生无意义的删除记录。
- 根目录 `crash_log.txt` 等日志属于排查产物，提交前请确认没有把设备日志、token 或内部地址写进仓库。

## 屏幕导航

`UserSelection`（用户选择）→ `Login`（登录）→ `Player`（播放器，可游客进入）。

- 播放器界面 `dispatchKeyEvent` 兜底捕获遥控器按键（确定/左右），避免 Compose 焦点丢失。
- 双击返回键退出（2 秒内）；`PlayerScreen` 通过 `LifecycleEventObserver` 把前后台事件转发给 `PlayerEngine`。

## 故障排查与修复记录

### 2026-11-07：切到直播频道播放失败后 native 闪退（已修复并实机验证）

**现象**：Android 5.1.1（MStar / 极米 H1S，API 22）切换频道后播放不成功，随后整个 App 闪退，Java 层无异常堆栈。

**日志**：

```
Fatal signal 6 (SIGABRT) in tid (MediaCodec_loop) >>> com.example.composedtv <<<
Abort message: ACodec.cpp:3850 CHECK_EQ(mOMX->getParameter(..., OMX_IndexParamAudioMp3, ...), OK)
               failed: -1010 vs. 0
```

**根因**：该频道音轨为 `audio/mpeg-L2`（MP2/MP3 家族），厂商 OMX MP3 解码器不支持 `OMX_IndexParamAudioMp3` 参数查询，系统在 `ACodec::configureCodec` 中直接 `__android_log_assert` 终止进程；这类 native abort 无法被 Kotlin `try/catch` 捕获。原配置下系统 MediaCodec 优先，必然命中坏解码器。

**修复**（`PlayerEngine.createCandidatePlayer`）：老设备（`isLegacyDevice()`，SDK ≤ 23）通过自定义 `MediaCodecSelector` 对 `audio/mpeg`（含 `-L1` / `-L2`）返回空列表，使 `MediaCodecAudioRenderer` 不创建该解码器；`TrackSelector` 自动改选已打包的 `FfmpegAudioRenderer`。其它格式仍走硬解，避免全局软解增加 CPU 负担；即使 FFmpeg 扩展加载失败也只是跳过音频轨，不会再拖垮进程。诊断日志新增 `mp3Guard` 标记。

**验证**：`assembleDebug` 通过；`adb install -r` 到实机后自动回到出事频道 `1008_1.m3u8`，日志显示 `Loaded FfmpegAudioRenderer`、`audio-decoder-init name=ffmpegLavc60.3.100-mp3`、`起播成功: direct`；经历一次 `BehindLiveWindow` 自动恢复后进程仍存活，crash 缓冲区无新增 `Fatal signal`。

### 2026-11-07：MKV 点播无法续播

部分 MKV 把 Cues 索引只写在文件尾 `SeekHead`，Media3 的 `MatroskaExtractor` 只读头部，导致 `SeekMap.Unseekable`、`startPositionMs` 与 `seekTo()` 被 clamp 回 0。新增 `MkvCuesPatchDataSource` 做等长就地修补，并增加 `scheduleResumeFallback()` 持续补 seek + 续播守护，避免续播失败时把旧进度覆盖成 0。

### 老设备「有画面无声音」定位步骤

1. 设置抽屉打开诊断 HUD，看 `音频` / `音轨` / `音频解码` 三行状态。
2. logcat 过滤 `Diag`：`audio-decoders` 列设备全部音频 MediaCodec；`audio-tracks` 列每条轨的 `sup`/`sel`；`audio-decoder-init` 是实际启用的解码器；`audio-track-init` 确认 AudioTrack 是否真的输出 PCM。
3. 若 `audioState=UNSUPPORTED`，说明该格式没有可用解码器（系统缺失或被 MP3 保护屏蔽且 FFmpeg 不支持），此时视频正常播放但无声，需换带 AAC-LC 2.0 音轨的片源。

### 外部输入播放「保存到收藏」面板（ExternalAddPanel）焦点/输入/导航修复

**问题现象**：外部 App 唤起播放弹出的「保存到收藏」面板无焦点框、焦点仅停留在取消按钮、D-pad 无法移动焦点、无法修改频道名称。

**修复要点**（`PlayerScreen.kt` 的 `ExternalAddPanel`）：

1. `.focusRequester(focusRequester)` + `.focusable()` 必须直接加在 `BasicTextField` 自身，而非父容器；`requestFocus()` 返回 `Success` 不代表焦点真正到达输入框内部节点。
2. 面板根 `Box` 用 `onKeyEvent`（上浮阶段）处理返回键，输入框用 `onPreviewKeyEvent`：输入法可见时先收起输入法，已收起才关闭面板。
3. 输入框 `onPreviewKeyEvent` 中显式 `focusManager.moveFocus(FocusDirection.Up/Down)`，因为 `BasicTextField` 内部会消费方向键用于光标移动。
4. `LaunchedEffect` 中加重试循环（最多 15 次），通过 `onFocusChanged` 确认焦点真正到达输入框。
5. 根布局 `pointerInput` 在面板打开时早退，`onPreviewKeyEvent` 放行所有按键；输入框聚焦显示蓝色边框，按钮聚焦放大 1.06 倍。

## 变更记录

### 2026-11-07（已提交 `f376fee`，基于 `fa91713`）

| 模块 | 内容 |
|------|------|
| 依赖 / 构建 | Media3 由 1.2.1 升到 **1.6.1** 并 `force` 锁定 7 个模块；`compileSdk` 34 → 35；新增 Jellyfin FFmpeg 解码扩展 `1.6.1+2` |
| 点播续播 | `VodProgressStore` 改为「当天有效 + 按用户隔离 + 过期清理 + 格式化提示」；`PlayerEngine` 增加 prepare 前注入、续播兜底重试、续播守护、统一保存时机；`PlayerScreen` 接入生命周期观察器 |
| MKV | 新增 `MkvCuesPatchDataSource`，直连 `.mkv` 修补 Cues 索引恢复可 seek |
| 音频诊断 | `DebugDiagnostics` 增加音轨/解码器/AudioTrack/错误采集，`DebugHud` 增加音频状态展示；播放器增加受支持音轨改选兜底 |
| 解码策略 | 扩展模式由 `PREFER` 改回 `ON`（硬解优先、软解兜底）；老设备屏蔽系统 MP3 解码器，根治 native `SIGABRT` 闪退 |
| 文档 | 本 README 同步技术栈、目录结构、CI 触发条件与排障记录 |

验证：`:app:compileDebugKotlin`、`:app:assembleDebug` 均 `BUILD SUCCESSFUL`；debug 包已通过 adb 覆盖安装到极米 H1S 实机，出事频道起播成功且无新增 native 崩溃。
