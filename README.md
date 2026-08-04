# composedTV（TV-APP4IPTV）

基于 **Jetpack Compose + Android TV** 的 IPTV 直播播放器，对接 `worker4iptv` 后端（见同团队后端仓库）。支持多用户切换、频道分组检索、收藏、HLS 代理播放与竞速（hedged）起播。

> 后端 API 客户端默认地址：`https://tv.run4u.dpdns.org`（`ApiClient.baseUrl`，可改）。

## 技术栈

- **Kotlin + Jetpack Compose**（TV：`androidx.tv:tv-foundation` / `tv-material` alpha10）
- **Media3 ExoPlayer**（`exoplayer` / `exoplayer-hls` / `exoplayer-ui`）—— HLS、FLV 播放
- **OkHttp 4.12** —— 后端 REST 调用
- **Coroutines + ViewModel + Navigation Compose**
- **compileSdk/targetSdk 34**，**minSdk 21**，JVM 17

## 目录结构（关键源码）

```
app/src/main/java/com/example/composedtv/
├── ComposedTVApplication.kt        # Application 入口，初始化 ApiClient
├── MainActivity.kt                 # 屏幕导航 / 按键兜底（双击返回退出）
├── data/remote/
│   ├── ApiClient.kt                # 后端 API 客户端（全部接口封装）
│   └── Models.kt                   # 数据模型 + 国家/语言中文映射
├── player/
│   ├── PlayerEngine.kt             # ExoPlayer 封装：竞速起播/看门狗/FLV 重试
│   └── PlaylistItem.kt             # 播放列表项（见 PlayerEngine）
├── viewmodel/
│   └── PlayerViewModel.kt          # UI 状态、侧边栏三列数据、收藏/搜索逻辑
└── ui/
    ├── screens/  (LoginScreen / UserSelectionScreen / PlayerScreen)
    ├── components/SidePanel.kt     # 源/分类/频道/搜索 三列侧边栏
    └── theme/Theme.kt
```

## 核心功能

- **多用户会话**：保存多个已登录用户 token，用户选择界面快速切换；支持游客模式（`enterAsGuest()`）。
- **源与频道浏览**：公开源 + 我的源（登录后）；按源 → 分类（含「收藏」「搜索」虚拟分类）→ 频道三级导航。
- **实时搜索**：侧边栏第三列搜索模式，跨当前源所有分类实时筛选频道。
- **收藏**：登录后可收藏/取消收藏（后端按 `url` 去重），收藏列表作为虚拟分类展示。
- **播放器能力**（`PlayerEngine`）：
  - **竞速/hedged 起播**：同时发起主备请求，先到者胜，降低起播延迟（`RACE_HEDGE_MS=1500`）。
  - **看门狗**：`WATCHDOG_TIMEOUT_MS=15000`，起播/恢复超时自动切换或重试。
  - **FLV 支持**：内置 `FlvExtractor`，并对 FLV 失败做一次重试（`flvRetryDone`）。
  - **直播 vs 点播**：直播流不做进度恢复；点播内容重载前保存 `pendingResumePositionMs` 恢复进度。
  - **首帧区分**：`hasRenderedFirstFrame` 区分「首次缓冲」与「播放中卡顿」，UI 提示更准确。
  - **连续错误计数**：`consecutiveErrors` 触发自动跳台/降级。
  - **代理标识**：`usingProxy` 标记当前是否经 HLS 代理播放。
- **代理播放**：所有播放 url 经 `ApiClient.hlsProxyUrl()` 走后端 `/api/hls`，台标经 `imgProxyUrl()` 走 `/api/img`，规避跨域/防盗链。
- **缓存策略**（`ApiClient`）：
  - 内存缓存（源/频道/收藏）+ 每日磁盘缓存（`filesDir/daily_cache/`，按 `yyyyMMdd` 隔离，启动清理旧缓存）。
  - 可调用 `invalidateCache(...)` 主动失效。
- **国家/语言中文显示**：`CountryLangMapper` 将 ISO 3166-1 / ISO 639-3 代码映射为中文（台标、筛选用）。

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
| GET | `/api/img?u=` | `imgProxyUrl()` | 图片代理 |

### 认证态字段

- `AuthResult(ok, token, user, message)`
- `ApiUser(id, username, role, needsDefaultSource)`
- 多用户存储：`StoredUser(username, token, userId, role)`，SharedPreferences 持久化。

## 构建

```bash
# 需要本地 keystore.properties 才能打签名 release（缺失则仅能未签名构建）
# keystore.properties 示例（请勿提交到仓库）：
#   storeFile=release.keystore
#   storePassword=****
#   keyAlias=composedtv
#   keyPassword=****

./gradlew assembleRelease        # 产出 app/build/outputs/apk/release/
./gradlew installDebug
```

- `build.gradle.kts` 启用 **ABI splits**（armeabi-v7a / arm64-v8a / x86 / x86_64）+ `universalApk`，便于 TV 盒子分发。
- Release 开启 `minify` + `shrinkResources` + proguard。

## ⚠️ 安全与 .gitignore

以下文件**绝不入库**（已在 `.gitignore` 排除）：

- `keystore.properties` —— 含签名密码
- `local.properties` —— 含本机 SDK 路径
- `release.keystore` —— 签名密钥文件
- `app/build/`、`build/`、`.gradle/` —— 构建产物
- `*.flat` / `*.class` / 编译期生成文件

## 屏幕导航

`UserSelection`（用户选择）→ `Login`（登录）→ `Player`（播放器，可游客进入）。

- 播放器界面 `dispatchKeyEvent` 兜底捕获遥控器按键（确定/左右），避免 Compose 焦点丢失。
- 双击返回键退出（2 秒内）。
