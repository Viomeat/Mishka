# Mishka

miuix + mihomo 的 Android 代理客户端。单模块 `:app`（`com.android.application`，AGP 9 内置 Kotlin，源码全在 `src/main`）+ `:baselineprofile`（`com.android.test`，只产 Baseline Profile 不进 APK）。UI 用 AndroidX Compose，miuix 走其 `-android` 发布件。

本文件是本仓库唯一的 agent 指南。`CLAUDE.md` 只有一行 `@AGENTS.md` 导入本文件——Claude Code 只自动读 `CLAUDE.md`，不读 `AGENTS.md`。新增长期有效的约束时更新本文件。

## 工作规程

- 每次改动至少跑 `git diff --check` + 与变更匹配的 Gradle 任务。
- 改 Kotlin 用 `./gradlew :app:compileDebugKotlin -x buildMihomo_arm64_v8a`（秒级，跳过 Go cgo）；只有验证 native / 打包才 `:app:assembleDebug`（分钟级）；真机 `:app:installDebug`。
- 新增 composable 后临时加 `composeCompiler { reportsDestination.set(layout.buildDirectory.dir("compose_reports")) }` + `--rerun-tasks` 跑报告，确认 restartable 全部 skippable、0 unstable 参数（当前 101 个），验完删掉临时配置。
- `mihomo/` 是 submodule、`scripta/` 是 includeBuild 复合构建，改前先确认确需触及；首次 clone 后跑 `git submodule update --init --recursive`。
- 保留用户已有的未提交改动；不用破坏性 reset/checkout；不修改或输出 `local.properties`。
- 完成后先报告变更与验证结果。**除非用户在当前请求中明确授权，不执行 `git add`/`commit`/`push`**。
- Commit 用 `<scope>: <summary>`，scope 取 `home`/`proxy`/`subscription`/`settings`/`service`/`native`/`build`/`docs`/`fix`/`chore(deps)`；主题行 ≤ 72 字符、sentence case、无句尾句号；**body 简洁**，只讲代码里看不出的根因与取舍，绝不逐文件复述 diff。

## 技术栈

Kotlin（AGP 9 内置，不加独立 kotlin 插件）+ KSP。UI：Compose（经 miuix `-android` 件传递）+ miuix + navigation3 + material-icons-extended。数据：Room 3（反射 builder）+ Ktor + kotlinx-* + Koin。其他：quickie 扫码、hiddenapibypass 预测性返回。核心：mihomo（Mishka fork）。

**版本与坐标唯一真源 = `gradle/libs.versions.toml`**（含 `[bundles]`），mihomo 版本在 `gradle.properties`，坐标/SDK 在 `buildSrc/ProjectConfig.kt`；**文档不复述版本号**。版本信息走 `BuildConfig.VERSION_NAME`/`VERSION_CODE`。`scripta:editor` 经 `includeBuild("scripta")` 引入，插件由 scripta 自己的 `pluginManagement` 解析。

**Compose 稳定性**走 [compose_compiler_config.conf](app/compose_compiler_config.conf)，**只保留实测起作用的条目**（加之前先跑报告确认确有 unstable 参数），新增 unstable 的三方/平台字段优先进该文件而非散落 `@Stable`。三条易踩：① 条目对**子类生效**——`androidx.lifecycle.ViewModel` 一行覆盖全部 ViewModel，其内部字段稳定性因此完全不影响 composable 参数；② FQN 须与实际依赖一致，Room 3 是 `androidx.room3.*`（写 `androidx.room.*` 静默失配）；③ 只认整行 `//` 注释，行尾注释会被当成 matcher 内容。

## 代码地图

分层靠**包名**，跨层即普通包引用。约定（非 Gradle 强制）：`domain.model` 只放 `@Serializable` 模型、`domain.repository` 只放仓库接口，二者不引 android/compose/ktor/room。

```
buildSrc/  ProjectConfig（坐标/SDK）+ GoBuildTask（Go 交叉编译）
mihomo/    submodule（YuKongA/mihomo branch Mishka，5 patch）
scripta/   includeBuild 复合构建（YAML 编辑器），app 依赖 scripta:editor
app/src/main/
├── kotlin/.../mishka/  App / MainActivity / MishkaApplication（startKoin + 全局初始化）
│   ├── domain/{model,repository}
│   ├── data/{api（REST/WS + MihomoConnectionManager）,bridge（MishkaCoreBridge）,database,repository（*Impl + ProfileProcessor + OverrideJsonStore + SubscriptionProxyResolver）,backup}
│   ├── platform/  service/  viewmodel/  util/  di/（4 个 Koin 模块）
│   └── ui/{navigation,navigation3,component,platform,theme,screen}
├── res/values{,-zh-rCN,-zh-rTW}/   assets/（构建时下载 GeoIP）   schemas/（Room）
├── cpp/  process_helper.c + mishka_jni.c + mihomo_wrapper.c + CMakeLists.txt
├── jniLibs/arm64-v8a/libmihomo.so   native/mishka_core/（Go cgo 源）
└── src/release/generated/baselineProfiles/（生成产物，需提交）
```

路由清单（`ui/navigation/Route.kt`，均实现 `NavKey`）、屏幕↔ViewModel 对应、`platform`/`ui.platform`/`service` 三个包内各组件的名字与职责，都能从文件名直接读出，此处不复述；名字不自明的那些，其约束写在下方对应条目里。

## 架构

```
MishkaApplication.startKoin ─ Koin（dataModule + androidPlatformModule + androidAppModule + viewModelModule）
  MainActivity（Koin get 取图）→ App → AppNavigation → HorizontalPager(4 Tab) + NavDisplay(二级页)
    → Screen → ViewModel → domain.repository 接口 → data.repository.*Impl
        ├→ MihomoApiClient(Ktor HTTP) + MihomoWebSocket(WS) → mihomo 进程 127.0.0.1:9090
        └→ Room（ImportedDao / PendingDao / SelectionDao）
```

**Koin**（4 模块按职责拆分，均在 `di/`）：`dataModule` = appScope、DAO、OverrideJsonStore、SubscriptionProxyResolver、RuleLatencyTester、MihomoConnectionManager、`SubscriptionRepositoryImpl` + 接口绑定、`factory { ProfileProcessor }`；`androidPlatformModule`（绑 `androidContext()`）= AppDatabase、PlatformStorage、ProxyServiceController、AppListProvider、WifiPolicyController、BootStartManager、BackupManager；`androidAppModule` = `single<ProfileFileManager>{ AndroidProfileFileManager }`；`viewModelModule` = 12 个 ViewModel（单 Activity 用 `single`），SubscriptionViewModel 注入 `androidContext()` 取错误文案。**组合根注入**：MainActivity 用 `org.koin.android.ext.android.get()` 取图后透传给 `App(...)`，屏幕保持参数化、不用 koinViewModel；仅需 Activity 上下文的 FilePicker / VPN 授权 launcher 不入 Koin。**repo 实现必配接口**，ViewModel 依赖接口；`ProfileProcessor` 需实体级方法故依赖 `SubscriptionRepositoryImpl` 具体类。

- **通信方案**：runtime（traffic/logs/connections/proxy select/provider 刷新）走 subprocess + Ktor REST + WS，三模式共用；订阅导入（fetch + provider prefetch + Parse）走 JNI in-process，由 [MishkaCoreBridge](app/src/main/kotlin/top/yukonga/mishka/data/bridge/MishkaCoreBridge.kt) 调 libmihomo.so 的 cgo 导出。
- **统一 .so**：libmihomo.so（cgo c-shared，~56MB）同时承担 JNI 导出 + `mihomoEntry(argc, argv)` runtime 入口；libmihomo_runner.so（C PIE，~6KB）由 MihomoRunner fork+exec 后 dlopen 它调 mihomoEntry。一份 mihomo 代码两条路径共用。
- **mihomo 客户端共享**：`MihomoConnectionManager`（`dataModule` single）订阅 `ProxyServiceBridge.state`，Running 时造新 `MihomoRepositoryImpl`、其他状态置 null，切换前同步 close 旧实例。消费方一律经 `connectionManager.repository: StateFlow<MihomoRepository?>`：HomeViewModel 自行 collect；MainActivity collect 后 `setRepository` 转发给 Proxy/Log/Provider/Connection/DnsQuery 五个 VM；`DynamicNotificationManager` 直接 collect。
- **MishkaCoreBridge**：`init(homeDir, userAgent)` 在 `MishkaApplication.onCreate` 一次性调用，homeDir 指向共享 GeoIP 目录 `files/mihomo/geodata/`；`fetchAndValid` 内部分 token、150ms 轮询进度、取消时调 `nativeCancel` 让 Go ctx 进入 Done。
- **导航**：miuix NavDisplay + 自定义 Navigator（push/pop/popUntil + navigateForResult）+ LocalNavigator；back stack 经 Route sealed 多态序列化持久化（`NavBackStackSaver`），新增路由只需 `@Serializable` 即获得进程死亡恢复；**`sealed interface Route` 自身必须保留 `@Serializable`**——缺失编译通过但运行时 `SerializationException`。
- **深色判定单点**：`ThemeConfig.resolveIsDark(systemDark)` 是 colorMode→isDark 的唯一实现，组合树内一律读 `LocalAppDarkMode.current`；**屏幕/组件禁止直接 `isSystemInDarkTheme()`**，否则用户强制深/浅色时该处不跟随（AboutScreen OS3 背景 / YAML 编辑器配色曾因此混色）。主题枚举的用户可见名走 [ThemeLabels.kt](app/src/main/kotlin/top/yukonga/mishka/ui/theme/ThemeLabels.kt) 共享 `label()`，禁止各自 when 映射。`LocalAppMonetEnabled` 标记 Monet：StatusColors 仅 Running 态跟随动态取色，Pending/Stopped 警示黄/红固定。底栏毛玻璃无独立开关，跟随全局 `blurEnabled`。
- **隧道三模式**（`TunMode { Vpn, RootTun, RootTproxy }`）：
  - **VPN**：VpnService 创建 TUN fd，mihomo 写 `tun.file-descriptor` + `auto-route=false`，工作目录 `imported/{uuid}/`（app UID）。
  - **ROOT TUN**：mihomo 以 root 自建 TUN，`auto-route=true` + `auto-detect-interface=true`，工作目录独立 `runtime/{uuid}/` 沙箱（启动前从 imported/ 拷贝，停止 `su rm -rf`）；imported/ 永远 app UID。
  - **ROOT TPROXY**：**`tun.enable=false`**，用 `tproxy-port=7895` 入站 + `dns.listen=0.0.0.0:1053`；`RootTproxyApplier` 装 mangle/nat PREROUTING+OUTPUT + `ip rule fwmark 0x1000000 lookup 2024` + `ip route add local default dev lo table 2024`，劫持本机与热点流量。
  - **分应用代理**：VPN 走 VpnService API；ROOT TUN 走 mihomo `include/exclude-package`（sing-tun 翻译为 uidrange）；ROOT TPROXY 走 iptables `-m owner --uid-owner`（`AppListProvider.resolveUids` 解析包名）。**Mishka 自身始终排除**；**不**用 `routing-mark`/SO_MARK 自绕——Android Netd 用 fwmark 低 16 位编码 netId，自定义 SO_MARK 会让路由命中无默认路由的 legacy_system 表，导致出站 `network unreachable`（box_for_magisk / Surfing / box4magisk 三家同款教训）。
  - ROOT 两子模式共享 MishkaRootService，Intent 经 `EXTRA_SUBMODE = "tun"/"tproxy"` 区分；attach 路径比对 `ROOT_SUBMODE_ACTIVE` 与请求 submode，不一致 fresh restart。ROOT 进程 app 被杀后仍存活，重启 app 靠持久化 PID/secret **attach-only** 重连。ROOT 不可用自动回退 VPN。
- **Wi-Fi 自动切换**：`WifiPolicyMonitorService` 前台监控当前 active Wi-Fi SSID（精确匹配、去 Android 外层引号、忽略 `<unknown ssid>`），权限不足不触发。两种动作：**停止服务**（进入匹配 Wi-Fi 且运行中时记 `WIFI_POLICY_PENDING_RESTART` 后停止，离开时仅在 pending 存在时自动启动一次）；**Direct 模式**（进入写 `WIFI_POLICY_RUNTIME_MODE=direct`，离开清 override 回退用户持久 mode，统一经 `ProxyServiceController.restart()` 热重载，三模式行为一致；runtime mode 由 `RuntimeOverrideBuilder` 优先于 `override.user.json` 注入，不污染持久配置）。Starting 窗口内的切换排队待 Running 后补一次 restart。关闭功能时恢复被策略改动的状态。监控通知与切换通知用独立 channel。开机/包替换后 `WifiPolicyBootReceiver`（默认 disabled，随开关动态启用）恢复监控。
- **状态桥接**：ProxyServiceBridge（全局 StateFlow + TunMode），Service 写、ViewModel 读。**进程模型**：单进程（VpnService 与 UI 同进程），ROOT 模式 mihomo 为独立 root 进程。
- **数据持久化**：Room 3（结构化）+ PlatformStorage（简单偏好）+ StorageKeys（key 常量）+ OverrideJsonStore（`override.user.json` + `ConfigurationOverride`）；store 自带 `state: StateFlow` + `update(transform)`，Settings 三个切片 VM 共享同一实例。**OverrideJsonStore 的内存 state 是权威值**：`load()` 返回内存值不读盘，落盘由 appScope 串行异步完成（排队期间被新值取代的快照直接放弃）。因此 **Service / ProfileWorker 必须注入 Koin 单例，不得各自 `new`**——自建实例读的是盘上旧值，用户改完设置立刻启动会用到改前的配置。
- **订阅管理**：Pending → Processing → Imported 三阶段沙箱，`ProfileProcessor` 编排 snapshot → fetchAndValid（JNI 一次完成 fetch + provider prefetch + Parse） → commit；processLock 串行，profileLock 守护 DB 一致性。
- **深链一键导入**：`clash://install-config?url=&name=&update-interval=`（兼容 `clashmeta://`）由 ExternalImportActivity（透明 + noHistory + excludeFromRecents）承接，校验 url 为 http(s) 后带随机 nonce 转发 MainActivity，经 `deepLinkImport` 参数驱动：popUntil Main → pager 切订阅 Tab → push 预填 `Route.SubscriptionAddUrl`。**不静默导入**，用户确认后走常规 Pending → APPLY 管线。两个防御点：① 用 **nonce 去重**而非 `savedInstanceState` 判空——进程死亡恢复重放旧 intent 需跳过，而「任务存活但进程被杀」时新深链可能带恢复态送达需接受，判空无法区分；② 跳板 exported，`intent.data` 先 `takeIf { isHierarchical }` 再取 query——非层级 URI 上 `getQueryParameter` 直接抛异常。
- **订阅 HTTP**：mihomo `component/http.HttpRequest`（in-process cgo），60s timeout；UA 默认 `ClashMetaForAndroid/{version}`，用户可在订阅 Add/Edit 页填自定义 UA 持久化到 `ImportedEntity.userAgent`/`PendingEntity.userAgent`，经 PendingSnapshot 透传到 Go `runFetchAndValid` 内 `effectiveUA = trim(userAgent) ?: currentUserAgent()`；非 2xx / 空 body → `MishkaCoreError`；不做 base64/V2Ray 转换。**名字留空自动命名**：Url 型允许名字留空（`enforceFieldValid` 放行），fetch 响应的 `Content-Disposition` filename（Go 侧 `mime.ParseMediaType` 原生解 RFC 5987 `filename*=UTF-8''`，去 .yaml/.yml 后缀）经 `FetchResult.FileName` 回传，`commitPending(fallbackName)` 仅在 **commit 时刻** `pending.name` 仍为空才采用——用户输入（含深链 `name` 参数）永远优先，兜底链 disposition > URL host；更新订阅（isUpdate）不走此路径、绝不改名。
- **age 加密订阅**：per-profile `ageSecretKey`（DB v3）经 `PendingSnapshot` 透传到 `fetchAndValid`。**加密原样落盘、运行时解密**：导入校验时 Kotlin 侧 fetch 前 `nativeSetAgeSecretKey`、fetch 后清空（processLock 串行保证不串），Go 侧在内存中解密校验，**config.yaml 与 provider 文件保持加密落盘**；运行时由 Service 从 DB 读密钥经 `MihomoRunner.start(ageSecretKey)` 加 `--age-secret-key` CLI flag，`runtime.go` 在 `hub.Parse` 前 `age.SetGlobalSecretKeys`。ROOT attach 路径进程已带密钥不重传。**副作用**：加密订阅对 app 不透明，`ConfigGenerator.readSubscriptionSecret`/`readSubscriptionMixedPort` 行扫描扫不到 → 退回默认值。密钥生成走 Meta 设置 → `mishkaGenAgeKeyPair`/`mishkaGenAgeHybridKeyPair`（X25519 / mlkem768-x25519）。
- **订阅下载走代理**：`SubscriptionProxyResolver` 按「开关 + 代理运行中 + 可解析 mixed-port」返回 proxy URL 或 null；native glue 在 fetchAndValid 入口 `os.Setenv("HTTPS_PROXY"/"HTTP_PROXY")` defer Unsetenv，覆盖订阅 fetch + provider prefetch + GeoIP 自动下载。processLock 串行保证 set/unset 并发安全。`resolve(requireUserToggle)` 控制是否受开关约束：订阅下载传 true，图标等通用资源传 false。
- **代理组图标下载走代理**：图标多为境外 CDN 而 Mishka 自身永远绕过 TUN——这是「图标加载慢」的根因模式，**任何 app 内下载外网资源的新功能都要走 mixed-port**。[IconLoader](app/src/main/kotlin/top/yukonga/mishka/ui/platform/IconLoader.kt)：内存 LRU(64) → 磁盘缓存 → 网络（限流 3 并发 + 5s/15s 超时）；失败 URL 负缓存 60s；磁盘读取/解码在 `Dispatchers.IO`；proxy 解析结果缓存 10s，代理 client 随 proxy URL 变化 close 旧建新。
- **Pipeline 取消**：阻塞 JNI 调用不响应协程取消，`withContext` 要等它返回才抛 `CancellationException`——那时 Go 侧 defer 已清空 `cancelRegistry`，`nativeCancel` 成 no-op，processLock 会被占到 60s 超时。故 `fetchAndValid` 在**阻塞调用之前**挂一条停在 `awaitCancellation()` 的 watcher 协程，被取消的瞬间就调 `nativeCancel`；`nativeDone` 标记避免正常返回后多余调用，`finally` 里必须 cancel 掉它，否则 `coroutineScope` 永不返回。**不能改用 `invokeOnCompletion`**——`coroutineScope` 的 Job 要等子协程收敛才算完成，同样太晚。`cancelCurrentUpdate` 先同步 `clearProgress()` 再 cancel。
- **GeoIP 预制**：构建时 DownloadGeoFilesTask 下载 geoip.metadb/geosite.dat/ASN.mmdb 到 assets，启动时提取到 `files/mihomo/geodata/`。JNI 路径用 `mishkaCoreInit(geodataDir)` 把 mihomo 全局 homeDir 指到这里；subprocess runtime 按 `-d workDir` + symlink 复用同一份。
- **WebDAV / 本地备份恢复**（设置 →「备份与恢复」）：固定文件名覆盖式。zip = `backup.json`（三表 **JSON 导出重放**而非拷 db 文件，绕开 WAL 一致性、跨 schema 由字段默认值兜底）+ imported//pending/ 目录树 + override.user.json。备份与恢复都持 `ProfileProcessor.withProcessLock`，恢复前置校验代理已停止。见 [BackupManager](app/src/main/kotlin/top/yukonga/mishka/data/backup/BackupManager.kt) / [WebDavClient](app/src/main/kotlin/top/yukonga/mishka/data/backup/WebDavClient.kt)。
  - **重定向手动跟随**：Ktor 默认只对 GET/HEAD 跟 3xx，而服务器常把无尾斜杠目录 301 到带斜杠版本，MKCOL/PUT 收到 301 直接失败。集合 URL 一律带尾斜杠 + `davRequest` 手动跟随 301/302/307/308，保持方法与 body，**仅限同主机**——Basic 凭据跨主机跟随会泄露密码。
  - **恢复两阶段落地**：先全写进 `.restore/`（zip-slip 基准也是它），正式目录直到 rename 换入才被触碰，失败从 `.restore-old/` 回滚。**绝不能先删再逐条校验/写盘**——非法归档或 ENOSPC 会让订阅目录永久消失。DB 重放仍无事务，中途异常留半张表。
  - **排除项**：geodata 符号链接与实体拷贝不进备份（名单 + isSymbolicLink 双重排除，否则 readBytes 追链接把几十 MB 实体化进 zip）；prefs 黑名单排除设备/运行时态与 WebDAV 凭据自身。
  - **恢复后强制重启进程**（内存热状态不随磁盘刷新）。开机自启是 PackageManager 组件位而非 pref，走快照独立字段；Wi-Fi 策略组件位与监控服务由重启后 MainActivity 按 `WIFI_POLICY_ENABLED` 幂等 reconcile。
  - **本地备份**复用同一 zip 与恢复管线：SAF `CreateDocument`（`"wt"` 截断写防旧文档尾部残留）+ `OpenDocument`（不按 MIME 过滤——网盘流转后常报 octet-stream）。
- **国际化**：英文 + 简体中文（zh-rCN）+ 繁体中文（zh-rTW，台湾用语：設定/檔案/匯入/連線/連接埠/快取/伺服器/金鑰/還原/套用/群組/逾時，非简转繁），Composable 用 `stringResource`、非 Composable 用 `context.getString`；日志英文，代码注释中文。

## 数据库（Room 3）

三表：`imported`（已导入的稳定订阅）/ `pending`（编辑中草稿，提交后移入 imported）/ `selections`（代理组选择记录，per 订阅）。

- **ProfileType enum**（`File`/`Url`/`External`）经 `ProfileTypeConverter` 映射为 TEXT 列；订阅 UUID 完整 36 字符（UUID v4，不做冲突检测）。
- **updatedAt 动态计算**：`ImportedEntity` 无此字段，`resolveProfile` 读 pending→imported 目录 mtime，fallback `createdAt`；commit/update 自然更新 mtime，无需主动写 DB。
- **Schema 版本**：v1→v2 `MIGRATION_1_2` 加 `userAgent`；v2→v3 `MIGRATION_2_3` 加 `ageSecretKey`（均 `TEXT NOT NULL DEFAULT ''`）。新增列时 schema 须由 KSP 导出到 [app/schemas](app/schemas/)（跑一次 `:app:assembleDebug` 落盘），且 **MIGRATION 必须在 `AppDatabaseBuilder` 的 `addMigrations(...)` 注册，遗漏会让升级用户首次启动 crash**。

**三阶段流程**（ProfileProcessor）：

```
CREATE → Pending ✓, Imported ∅
  → APPLY（processLock 串行）：
      ① snapshot（profileLock 内）：query Pending + enforceFieldValid + prepareProcessing（清 processing/ + 复制 pending/{uuid}/ → processing/）
      ② fetchAndValid（锁外、可取消、JNI in-process）：Url 型 force=true 重新下载 config.yaml；File 型 force=false 跳过 fetch；两者均 prefetch provider（**并发度 5**、每源 60s 超时、无总时限）后 Parse 校验；httpProxy 由 SubscriptionProxyResolver 决定
      ③ commit（profileLock 内，`withContext(NonCancellable)` 原子）：一致性检查 → commitProcessingToImported（拷到 commit.new/ 再 rename 换入）→ DB 更新
  → 失败：cleanupProcessing（NonCancellable）；pending/ 与 imported/ 都不动，可 retry
  → RELEASE：删 Pending DB + pending/{uuid}/
PATCH（编辑已导入）→ APPLY → Imported 更新, Pending ∅
UPDATE（手动/自动）→ 等价 APPLY，snapshot 取自 Imported
DELETE → 三表清理 + imported/{uuid}/ + pending/{uuid}/ 删除
```

**目录**：`files/mihomo/` 下 `geodata/`（共享 GeoIP + 符号链接）、`imported/{uuid}/`、`pending/{uuid}/`、`processing/`（临时校验沙箱，单例）、`runtime/{uuid}/`（ROOT 运行时沙箱）、`override.user.json`（用户设置）、`override.run.json`（启动时合并 TUN fd + AppProxy + rootMode）。换入用的临时目录：`commit.new` / `commit.old.{uuid}`（提交换入，见下）、`.restore` / `.restore-old`（备份恢复）。

**imported/ 一律「先拷后 rename」，不得删了再拷**：拷进 `commit.new/` 再两次 rename 换入，删除全排在拷贝之后——失败时 `imported/{uuid}/` 仍完整（update 路径下它是唯一副本）。rename 只要父目录可写，不受 root:root 残留影响。两次 rename 之间的窗口由 `commit.old.{uuid}` 兜底，`cleanupProcessing` 启动时按它还原。

## 构建

mihomo 经 submodule 引入 Mishka fork（branch `Mishka`）。Gradle 按 ABI 驱动 Go 构建（当前仅 arm64-v8a），产物落 `app/src/main/jniLibs/<ABI>/`；`assemble` 自动触发 buildMihomo / CMake，`downloadGeoFiles` 需手动或 CI 跑一次。刷新 Baseline Profile：`./gradlew :app:generateReleaseBaselineProfile`（需 adb 连 arm64 真机，产物提交进仓库）。

[GoBuildTask](buildSrc/src/main/kotlin/GoBuildTask.kt) 产 `libmihomo.so`（CGO_ENABLED=1，需 NDK clang，从 `androidComponents.sdkComponents.ndkDirectory` 读）。CMake `dependsOn(buildMihomo)`，产两个轻量件链 libmihomo.so（IMPORTED + IMPORTED_SONAME）：`libmihomo_runner.so`（PIE wrapper）与 `libmishka_jni.so`（薄 JNI 桥）。

## 关键架构约束

不读代码看不出来的约束，违反会直接踩坑。

**启动校验单点**：所有「启动代理」路径必须经 [ProxyServiceController.start / restart](app/src/main/kotlin/top/yukonga/mishka/platform/ProxyServiceController.kt)。`resolveStartSubscriptionId()` 统一校验 active 订阅 + `imported/{uuid}/config.yaml` 落盘，失败时一次完成 toast + `ProxyServiceBridge.updateState(Error)` + 清 `SERVICE_WAS_RUNNING` + Running 时发 STOP。新增入口（Wear / shortcut / 自动化）严禁绕过 controller 直拉 Service；Service 内 `ProfileFileOps.hasValidConfig` 是针对 ADB / 三方 Intent 的兜底。Tile / 通知等无 Activity 上下文要在 VPN 模式弹授权，必须经 [VpnPermissionActivity](app/src/main/kotlin/top/yukonga/mishka/service/VpnPermissionActivity.kt)（`VpnService.prepare()` 要求 Activity context）。

**Service 启动必须幂等串行**：`MishkaRootService` / `MishkaTunService` 用 `startJob`（`@Volatile`，跨主线程与 IO 协程访问）守门，`ACTION_START` 撞上进行中的启动直接忽略；`stopProxy` / `restartProxy` / `onRevoke` 必须先 `startJob?.cancelAndJoin()` 再往下走，否则被幂等检查挡掉而静默失效。**唯一例外**：fresh START 抢占进行中的 attach-only（cancel 后在新协程里 `join()` 等其收敛），因为 attach-only 失败只保持停止，而 fresh 请求表达的是「必须跑起来」；漏了这条例外，`AUTO_CONNECT_ON_LAUNCH` 关 + 开机自启开时 `reattachRoot` 会抢先占住 startJob，让开机自启失效。**抢占要靠显式 `isActive` 检查兜底**：attach-only 从入口到 `stopSelf()` 全程没有 suspend 点，`cancel()` 打断不了，必须在那个分支里自查 `isActive` 主动让位，否则它会 stopSelf 掉 Service、连带杀死接替的协程。**为什么必要**：ACTION_START 会在数百毫秒内到达两次——「打开应用自动连接」发一次，系统补发被挂起的 BOOT_COMPLETED 让 [BootReceiver](app/src/main/kotlin/top/yukonga/mishka/service/BootReceiver.kt) 再发一次（HyperOS 等 ROM 拒绝向未运行的 app 投递 BOOT_COMPLETED，改为等进程起来后补发，正好和自动连接撞车；BootReceiver 另 `new` 了一个 controller，绕过 `launchAutoConnectConsumed` 那个进程级去重标记）。两条启动协程并发跑 iptables 会互抢 `/system/etc/xtables.lock`，ROOT TPROXY 下直接打死启动。BootReceiver 只在 `state == Running` 时跳过（Starting 仍要发，好让上面那条抢占生效），去重责任单点落在 Service。**症状指纹**：同一 Service 里出现两条 `Starting proxy` 日志、线程号不同。

**iptables 锁争用 ≠ 内核不支持**：所有 iptables/ip6tables 调用必须带 **`-w <秒>`**——裸 `-w` 是无限等待，撞锁会让启动链永久挂起。秒数已并进工具令牌（`IPT4`/`IPT6`/`IPT_BINS`），新增命令一律用令牌拼，脚本里不再单独写 `-w`；`runWithOutput` 自动注入，只认带数字的 `-w`。抢不到 xtables.lock 时 iptables 退出码是 **4**（resource problem），与「内核没有该 target」在返回码上无法区分。`probeTproxySupport()` 因此必须：`-w` 等锁 → 撞锁退避重试 → 重试耗尽仍是锁问题时**返回 true**，把结论交给真正的 apply 验证。误判「支持」最多让 apply 装不上规则并留日志，误判「不支持」会让 ROOT TPROXY 直接中止启动（该 submode 把 probe 失败当致命错误）。带 `-w` 的命令超时必须放宽到 `IPT_WAIT_SECONDS + 5`，否则等锁的那几秒会被当成 su 错误。

**onDestroy 不得覆盖 Error**：两个 Service 的失败路径都是 `updateState(Error) + stopSelf()`，紧接着就走 onDestroy。onDestroy 若无条件写 Stopped，会把刚写入的 Error 与 errorMessage 立刻抹掉，用户只看到「启动中 → 未运行」，失败原因只能靠 logcat 还原。Error 是终态，onDestroy 只在当前非 Error 时才写 Stopped。配套：`HomeViewModel` 的 Error 分支弹 toast（errorMessage 不在首页渲染，Error 与 Stopped 视觉上无差别），同一条只弹一次、回到 Stopped 时清空标记。发布方自己已经 toast 过的（如上一条的 `resolveStartSubscriptionId`，它要覆盖 Tile / 通知这类没有 HomeViewModel 在场的入口）置 `ProxyServiceStatus.errorNotified = true`，UI 层据此跳过，避免前台时同一条弹两次。

**Ktor HttpClient 所有权**：禁止任何模块直接 `MihomoApiClient(...)` / `MihomoWebSocket(...)`，统一从 `connectionManager.repository` 订阅。`MihomoConnectionManager` 是唯一持 `close()` 责任方，按 bridge state 自动 connect/disconnect、原子 close 旧 + new 新——不做 endpoint 比对（attach 重连多一次重建 < 50ms，胜过状态机比对出 race）。ViewModel 的 `setRepository` 仅传信号、不担 close。例外：`SubscriptionProxyResolver` / `RuleLatencyTester` 因探测场景独立于实时连接，可自建短生命周期 client，但必须 `use{}` 或 try/finally close。订阅 fetch 走 JNI 不经 Ktor。

**ViewModel `setRepository` 必须 cancel 旧拉取协程**：mihomo 重启 / 切订阅时 manager 会 close 旧 client 并 emit 新 repo，消费方必须先 `loadJob?.cancel()` 再切字段，协程内再用 `if (repository !== repo) return@launch` 双保险——Ktor `client.close()` 让 in-flight 请求抛异常**但不取消协程**，旧响应的 onSuccess 仍会跑到末尾把 `_uiState` 写成旧订阅数据。**WS 流不随 close 终止**：无限重连且吞掉非取消异常，`close()` 后只进入退避循环，cancel 是唯一终止手段——漏 cancel 等于留一条僵尸协程。ProxyViewModel / ProviderViewModel / LogViewModel / ConnectionViewModel 均按此模式。**切订阅后 ProxyScreen 显示旧组**就是这条 race。

**Override 注入**：所有 override 走 `--override-json` CLI flag + JSON 文件，Kotlin 侧零 YAML 改写。用户设置 `OverrideJsonStore.update{}` → `override.user.json`，启动时 `RuntimeOverrideBuilder` 叠加 TUN fd / AppProxy / rootMode → `override.run.json`。`secret` / `external-controller` 走 `--secret` / `--ext-ctl` 不进 JSON。

**RuntimeOverrideBuilder 默认注入**（用户未显式设置时）：`tcp-concurrent=true`、`find-process-mode=off`（分应用已由 sing-tun / VpnService / iptables uid-owner 处理，运行期遍历 `/proc` 纯冗余）。ROOT TUN 额外默认 `tun.mtu=9000 + gso=true + gso-max-size=65535`（大包聚合减少 read syscall），由 `ROOT_TUN_JUMBO_MTU`（默认 true）控制，关闭回退 1500/false。VPN 不注入 MTU/GSO（由 `VpnService.Builder` 管）。mixed-port 优先级：① 用户 override 显式设置 → 用用户值；② 订阅 yaml 自带 → 不注入；③ `SUBSCRIPTION_UPDATE_VIA_PROXY` 启用 → 注入 7890 兜底；④ 其余不注入。订阅自带值由 `ConfigGenerator.readSubscriptionMixedPort` 行扫描得出，避免兜底值覆盖订阅原值。

**硬编码覆盖订阅（按 submode）**：`profile.store-selected=false` / `store-fake-ip=true` 三模式共用。VPN：`tun.enable=true` + `file-descriptor` + `dns-hijack=[0.0.0.0:53]`，`auto-route=false`，透传 stack/device。ROOT TUN：`auto-route=true` + `auto-detect-interface=true` + `iproute2-table-index=2022` + `iproute2-rule-index=9000` + dns-hijack + `include/exclude-package` + **`route-exclude-address`（私网 + 组播 + 保留段，复用 `IptablesIntranet.V4`，IPv6 开启时叠加 `.V6`）**——sing-tun `auto_route` 在该项为空时铺满 `0.0.0.0/0`，会把 LAN 单播 + 224/4 组播一起吸进 TUN，破坏同 LAN 设备发现 / P2P 直连（妙享投屏）；VPN 由 `bypass_private_route` 分流、ROOT TPROXY 由 iptables RETURN 处理，唯独 ROOT TUN 缺这层。ROOT TPROXY：`tun.enable=false`、`tproxy-port=7895`、`dns.listen=0.0.0.0:1053`；**不写** `routing-mark`（Netd 冲突）、**不写** `include/exclude-package`（走 uid-owner）。

**ROOT TPROXY 的 IPv6 注入必须门控**：`RootTproxyApplier.apply(ipv6Enabled)` 由 `VPN_ALLOW_IPV6` 决定（与 VPN/ROOT TUN 同一开关）。默认 false 时跳过所有 ip6tables / `ip -6` 注入，IPv6 走内核原生路由。teardown 永远尝试清 v4+v6 保证切换无残留。**为什么必要**：mihomo 默认 `ipv6: false` 时无法 dial IPv6，TPROXY 无差别拦截 → accept → 拨号 "ip version error" → App 重试，形成 600 conn/s 紧密循环（实测 95s 产生 56k 失败 + 25MB 日志）。VPN/ROOT TUN 由 `inet6-address` 控制 TUN 是否注册 v6 默认路由，本身就有这层过滤。

**secret 优先级**：用户设置 > 订阅 `config.yaml` 顶层 `secret:`（`readSubscriptionSecret` 行扫描）> 随机 UUID 前 16 字节；ROOT attach 分支走 storage 持久化的 `existingSecret`。

**`/proxies` 不含 provider 节点**：mihomo 的 `GET /proxies` 与 `findProxyByName` 中间件只覆盖 runtime proxies（`proxies:` 段 + 代理组），proxy-provider 节点在这套命名空间里**查不到 / 404**。故节点详情需把 `/providers/proxies` 各 provider 的 `proxies` 合并进结果（runtime 优先补缺，`ProxyViewModel` 维护 `nodeProviderMap`）；provider 节点单测走 `GET /providers/proxies/{provider}/{node}/healthcheck`；组测速与组选择不受影响。仅 provider 型（模板）订阅命中。

**GLOBAL 组常驻代理页**：`GET /group` 里 GLOBAL 与普通组同级（Selector），`loadProxies` 既拿它的 `all` 当组排序基准，也把它本身排进列表——`mode == "global"` 时置顶（唯一生效出口），其余沉底。mode 从 `GET /configs` 现取。非全局模式下是否留在列表由 `PROXY_SHOW_GLOBAL_GROUP`（默认开）控制，**全局模式无视该开关强制显示**——否则退回「生效出口不可选」的老 bug。**关掉只影响展示**：`orderMap` 仍从 `globalGroup.all` 算，过滤只在 UI 层做。**默认开的理由**：rule 模式下 GLOBAL 虽不参与路由，但其 `all` 是全部节点 + 全部组，`/group/GLOBAL/delay` 因此是**唯一的一键全量测速入口**，且提前选好的出口切到 global 立即生效。

**出站模式提示而非隐藏代理组**：`GET /group` / `GET /proxies` 的返回与 `mode` 无关；direct 模式 mihomo 在 `resolveMetadata` 直接返回 DIRECT、根本不查组，global 模式只有 GLOBAL 决定出口。两种模式仍显示全量组是**正确**的：`PUT /proxies/{group}` 在任何模式下都被接受并记住，切回 rule 立即生效，延迟测试照常有效——隐藏会砍掉「先挑好节点再切回规则模式」的正常用法。缺的只是告知，故 `ProxyUiState.mode` 携带小写 mode，`ProxyScreen` 据此在首个 lazy item 渲染提示卡；mode 常量在 `ProxyViewModel.companion`，禁止屏幕里裸写字符串。

**主页延迟测试按规则走 mixed-port，不用 `/proxies/{name}/delay`**：后者对指定组的**当前选中节点直接拨测、绕过规则引擎**，测出的数字与该域名实际命中的分流规则无关；且该 API 必须收一个 proxy 名，这个实现约束会泄露到 UI 上变成「代理组选择器」（历史实现还靠正则猜默认组）。正确做法是经本机 mixed-port 发真实 HTTP 请求（[RuleLatencyTester](app/src/main/kotlin/top/yukonga/mishka/data/api/RuleLatencyTester.kt)），出口由规则引擎决定——**Mishka 自身流量始终绕过 TUN，mixed-port 是唯一能让自己的请求经过 mihomo 的入口**。三条实现约束：① 每次测量新建 client，复用连接池会让重复刷新逐次偏低；② `followRedirects = false`，跟随 3xx 会把跳转往返计进耗时；③ mixed-port 未必存在，解析不到时退回 `GLOBAL` 组拨测并置 `latencyViaRules = false`，UI 标注「未走规则」——**不静默给误导性数字**。代理页的节点/组测速仍该用 `/proxies/{name}/delay`。

**连接速率必须自行差分**：`/connections`（WS，1Hz 全量列表）每条只给**累计** `upload`/`download`，没有瞬时速率——按累计量排序会让长连接永远压在前面。[SpeedDetailSheet](app/src/main/kotlin/top/yukonga/mishka/ui/screen/home/SpeedDetailSheet.kt) + `updateConnectionRates` 对相邻两次快照按 `id` 差分再除以实际间隔，1Hz 推送正是天然差分周期。三条约束：① **首轮只留基准不出结果**，否则每条长连接的历史总量会被当成瞬时速率；② `topConnectionRates` 用 **null 区分「首轮未完成」与「确实无活跃连接」**，UI 分别显示 loading 与空态；③ **订阅只在详情打开期间存续**（`DisposableEffect` start/stop），几百条连接的全量列表每秒推一次，常驻代价不小；`disconnectStreams` 里也要 stop（旧 client close 后差分基准已失效）。**按应用/进程聚合做不到**：`metadata.process` 依赖 `find-process-mode`，而默认注入 `off`，该字段恒为空。同理，**连接页自己的 `/connections` 订阅也只在本页存续**：`ConnectionViewModel` 是 Koin `single`，`setRepository` 无条件开采集会让代理一跑就常驻解析全量列表，故 start/stop 由 `ConnectionScreen` 的 `DisposableEffect` 驱动，`setRepository` 只在 `observing` 时接上。

**viewModelScope 的轮询循环必须门控 UI 可见性**：`HomeViewModel` 的系统信息采样（`NetworkInterface` 枚举 + `/proc/<pid>/stat`，均阻塞、须在 IO）、`/configs` 轮询、uptime 计数都挂在 `viewModelScope` 上，不感知生命周期。三者收敛到 `pollWhileVisible(interval)`，由 `MainActivity.onStart/onStop` 经 `setUiVisible` 驱动——不门控就是后台每天数万次本地 HTTP 与 `/proc` 读。

**CMFA embed mode 禁 HTTP 配置 API**：`PATCH/PUT /configs`、`POST /restart`、`POST /configs/geo`、`PUT/PATCH /rules`、`POST /upgrade` 全 404。**绝不添加** `patchConfig`/`restart` 方法，配置修改一律走 `OverrideJsonStore.update{}` + `serviceController.restart()`，UI 用 `RestartRequiredHint` 提示。

**订阅导入走 JNI in-process**：fetch + provider prefetch + Parse 三步走 `MishkaCoreBridge.fetchAndValid`，禁止再起 mihomo 子进程做这些事。`MishkaApplication.onCreate` 必须先 `extractGeoFiles()` 再 `MishkaCoreBridge.init(...)`——后者 `constant.SetHomeDir` 必须指向已就位的 GeoIP 目录。

**native 五条**：① **JNI 库加载顺序**——`libmishka_jni.so` 链接依赖 libmihomo.so 导出符号，`System.loadLibrary("mihomo")` 必须先于 `loadLibrary("mishka_jni")`。② **libmihomo.so 必须显式设 SONAME**——cgo c-shared 默认不写，消费方会把构建期绝对路径烙进 DT_NEEDED，运行时 `UnsatisfiedLinkError`；GoBuildTask 的 `-extldflags=-Wl,-soname,libmihomo.so` 与 CMake `IMPORTED_SONAME` 两边必须对齐。③ **libmihomo_runner.so 是 PIE wrapper**——读 `/proc/self/exe` 推同目录 → dlopen → dlsym `mihomoEntry` → 透传 argv；新加 CLI flag 必须同步注册到 `mishka_core/runtime.go` 的 `flag.NewFlagSet`，否则被 ExitOnError 拦截；`cleanupOrphanedMihomo` 按其 cmdline 匹配孤儿进程。④ **cgo `*C.char` 必须 Go 侧释放**——`//export` 返回的字符串内存属 Go runtime，C 侧只能调 `mishkaFreeString()`，`free()` 会导致 cgo 堆损坏。⑤ **`//export` 必须收住 panic**——panic 逸出 cgo 边界会终止宿主进程，JNI 是 in-process，死的是整个 app（subprocess 路径只死 mihomo，有 `startProcessMonitor` 兜底）。返回字符串的导出函数走 `guardString` 降级成 `"error: "`；只覆盖同一 goroutine。

**JNI fork+exec**：Android `ProcessBuilder` fork 后强制关闭非标准 fd（无论 O_CLOEXEC），VPN 模式必须用 JNI `fork()+exec()`（`process_helper.c`）保留 TUN fd 继承。

**Mishka 自身包名必须绕过 TUN/VPN**：`ProcessBuilder` 子进程 HTTP 被代理捕获会永久阻塞。ROOT 三种 AppProxyMode 都把 `packageName` 从 include 剔除或塞进 exclude；VPN `AllowSelected` 分支先过滤 self 再 addAllowed，过滤后空列表退化到 `addDisallowedApplication(self)`。

**协程锁规则**：`kotlinx.coroutines.sync.Mutex` **不可重入**。`updateImported`/`commitPending`/`queryImported`/`queryPending` 被 `ProfileProcessor` 在 `withProfileLock{}` 内调用，**不能自己加锁**；`create`/`patch`/`release`/`clone`/`delete` 直接被 ViewModel 调用，**保留自身锁**。

**processing/ 单例目录必须进程级串行**：`processing/` 是进程内单例沙箱（路径不带 uuid），`prepareProcessing` 每次清空后重填、`commitProcessingToImported(uuid)` 再换入 imported/。因此 `ProfileProcessor.processLock` 必须是 **companion 进程级** Mutex——前台 `SubscriptionViewModel.processor` 与后台 `ProfileWorker.processor`（每个 `ACTION_UPDATE_PROFILE` 都新建）是不同实例；锁若实例级，两个并发 update 会交错清空同一 `processing/`，把 B 下载的 config 提交进 `imported/A/`，造成「界面显示订阅 A、点击启动实际运行 B」的偶发 Bug（自动更新间隔相近时后台并发触发，纯被动）。启动清理残留也必须走 `ProfileProcessor.cleanupResidual`（持同一把锁），不能直接 `ProfileFileOps.cleanupProcessing`。

**切换 active 订阅的重启决策走权威状态**：`onActiveSubscriptionChanged()` **必须读 `serviceController.status`（ProxyServiceBridge）**，不能用 `uiState.isRunning`——后者是滞后 UI 标志，代理 Starting 窗口（约 10s）内仍为 false，切换会漏掉重启，导致「界面显示新订阅、代理仍跑旧订阅」。Starting/Stopping 过渡态先置 `pendingRestartOnRunning`，待 Running 再 `restartProxy()`；Stopped/Error 时清挂起标志。

**Pipeline 协程取消语义**：外层 `runProcess` 可取消，仅 commit 阶段包 `withContext(NonCancellable)` 保证文件 swap + DB 更新原子；catch 块 `cleanupProcessing` 同样 NonCancellable。`cancelCurrentUpdate` 先同步 `clearProgress()` 让 Dialog 立即消失再 cancel。`fetchAndValid` 内部捕获任何 Throwable 时调 `nativeCancel(token)` 让 Go ctx 进入 Done。

**ROOT runtime/ 沙箱**：ROOT mihomo 工作目录是独立 `runtime/{uuid}/`（从 imported/ 复制），不碰 imported/。启停钩子：`startProxy` 新鲜启动前 `prepareRootRuntime`；stop/restart/进程监控三条死亡路径都在 `clearPersistedState` 之前 `cleanupRootRuntime`；attach 分支**不重建**。存量旧 root:root 遗孤由 `MishkaApplication` 后台线程一次性 `su chown -R $APP_UID imported/` 迁移。

**订阅导入不自动切换活跃**：`addSubscription`/`addFromFile` 成功后**不**调 `setActive`；仅首次导入（`count() == 1`）由 `commitProcessingToImported` 自动激活。

**SubscriptionRepository 单例 + 订阅流量数据合并**：`SubscriptionRepositoryImpl` 由 Koin `single` 提供，SubscriptionViewModel 与 HomeViewModel 共用同一实例（`ProfileWorker` 例外，后台独立构建）；禁止 ViewModel 内 new。订阅页与主页流量栏的数据语义必须**强一致**——`resolveProfile` 在 combine 内合并三层：`pending > live provider snapshot > imported DB`。`_liveProvider` 携带 `subscriptionId` 做归属校验。[HomeViewModel](app/src/main/kotlin/top/yukonga/mishka/viewmodel/HomeViewModel.kt) 是唯一 runtime producer（`refreshProviderTraffic` GET 快照 / `updateAllProviders` 逐 provider PUT 后再 GET——mihomo 的 `subscriptionInfo` 仅在 provider 更新时刷新，纯 GET 读到的永远是旧快照）：`aggregateProviderInfo` 将所有 `Total > 0` 的 provider 求和、Expire 取最近非零，再经 `onLiveProviderInfo` 推回 Repository。该请求必须先取消前一次，并同时捕获 repository identity、active UUID 与递增 request ID，响应后每次写 UI 前重验三者；disconnect 或 UUID 改变时 cancel + 清空 + 使旧 ID 失效；失败仅更新错误态，不清空已确认的 live snapshot。**为什么聚合**：`subscriptionInfo` 是 per-provider 解析 header 得来，多源 yaml 下 `values.firstOrNull()` 会取到 Map 迭代顺序的随机 provider。**为什么 DB 仍必要**：模板订阅 DB.total=0 但 provider 各自有 header → live 覆盖；常规单源订阅 providers 为空 → fallback DB；File 型两边都为 0 → UI 显示 "--"。

**Active 订阅名缓存同步**：通知栏启动时一次性读 storage `ACTIVE_PROFILE_NAME` snapshot，不订阅 DB Flow。`commitPending` 与 `updateImported` 末尾必须调 `syncActiveNameIfActive(uuid, name)`，否则编辑/更新 active 订阅时通知栏标题会停在旧名。辅助函数内部短路 active 检查 + 同名短路（避免周期性流量更新打断通知动画）；`updateImported` 调用方还需在 `name != null && name != existing.name` 时才调。

**TUN init silent failure 兜底**：mihomo `ReCreateTun` 失败仅 log 不退出。① `MishkaTunService` 清 O_CLOEXEC 失败必须视为致命（`closeTunFd` + Error + `stopSelf`）；② `MihomoRunner.waitForReady` 在 API ready 后 delay 500ms 扫日志匹配 `Start TUN listening error` / `configure tun interface` / `create NetworkUpdateMonitor`。

**fd 模式 forwarderBindInterface 必须为 true**：upstream `e38aa82a` 在 Mishka VPN（gvisor stack + VpnService fd）下实测破坏 fd 路径流量——延迟测试通（mihomo 直接 dial 不经 fd），实际经 fd 流量不通。静态搜索 sing-tun 0.4.18 仅 `stack_system` 读该标志、gvisor 不读，但实测推翻该结论。fork 第 5 patch 保留 fd 模式下的旧行为；每次 rebase 上游必须验证 `listener/sing_tun/server.go` 的 `forwarderBindInterface = true` 仍 active。

**VPN MTU 同步**：`VpnService.Builder.setMtu` 与 mihomo `cfg.Tun.MTU` 必须同值。sing-tun 在 fd 模式给 gvisor `fdbased.New` 用 `cfg.Tun.MTU` 设 endpoint 缓冲，0 时所有 read 失败 → 表象「延迟正常但流量不通」。两侧共用 `RuntimeOverrideBuilder.VPN_TUN_MTU` 常量，禁止任一边 hardcode。

**WebSocket 重连**：Ktor `for (frame in incoming)` graceful close 静默退出。`MihomoWebSocket.webSocketFlow` 自实现无限重连 + 指数退避（1s→30s）+ 20s 心跳；`CancellationException` 必须 rethrow。末尾 **`flowOn(Dispatchers.Default)` 不能删**——消费点全在 Main，不切走则每帧反序列化都占主线程；它引入的缓冲要求消费方除 cancel 外再做 `repository !== repo` 校验。`connectionState` 由四条流共享，**任一流退出即置 false**（关速度详情会让日志页显示「未连接」），不能据此判断单条流。

**startForeground 防御**：Tun/Root/ProfileWorker 的 onCreate 均 `try { startForeground() } catch(Exception)`。真实风险是 API 31+ `ForegroundServiceStartNotAllowedException` 和 API 34+ FGS type 异常（非 POST_NOTIFICATIONS 拒绝）。失败路径：Tun/Root 上报 Error + `stopSelf()`；ProfileWorker 仅 `stopSelf()`。**不降级为普通 Service**。

**ProfileWorker.jobs**：用 `ConcurrentLinkedQueue<Job>` + `while (true) { jobs.poll()?.join() ?: break }`（非 `mutableListOf` + 轮询；onStartCommand 主线程 + scope 协程 IO 跨线程访问需线程安全容器）。

**任何进入 `su -c` 的外部值必须 `escapeShellSingleQuoted`**：双引号挡不住 `$(...)`。`--secret` 来自远端 config.yaml 行扫描、`--age-secret-key` 用户手填、device name 只 trim，上游均无字符校验，转义是唯一防线。启动日志只打 flag 名，密钥不进 logcat。

**孤儿 mihomo 清理**：`RootHelper.cleanupOrphanedMihomo(tunDevice)` 单次 su shell 完成 pkill + `ip link delete <tunDevice>`（防 sing-tun EEXIST）。VPN 启动在 `hadRootPid || HAS_ROOT` 时触发，清 ROOT 持久化 key + 兜底 `cleanupAllRootRuntime`。

**ROOT 模式重连校验**：`attachToExisting` 三重验证（`kill -0` 存活 + `/proc/$pid/cmdline` 含 libmihomo.so + stored secret 通过 `/configs` Bearer 鉴权 2xx）；订阅一致性由 `startProxy` 在 attach 前比对 persisted vs 请求 subscriptionId，不一致走 cleanup + 全新启动。

**reopen 重连必须 attach-only + boot-session 门控**：app reopen（`onResume` → `verifyAndSyncState`）在 ROOT 模式走 `reattachRoot()` 发带 `EXTRA_ATTACH_ONLY=true` 的 START intent；`startProxy(attachOnly=true)` 在 attach 失败时**绝不回退全新启动**，而是清状态置 Stopped + `stopSelf`。**为什么**：`SERVICE_WAS_RUNNING` / `ROOT_MIHOMO_PID` 存在 SharedPreferences 跨设备重启保留，但重启会杀死 root mihomo → PID 过期；旧逻辑仅凭「PID 非空」判定「进程仍活」→ attach 失败 → 全新启动，造成「未开开机自启，重启后打开 app 却看到 ROOT 代理自动跑起来」。修复两层：① `verifyAndSyncState` 经 [BootSession](app/src/main/kotlin/top/yukonga/mishka/platform/BootSession.kt) 预门控——重启过 ⇒ 进程必死 ⇒ 清标志保持停止；② attach-only 作为权威兜底覆盖预门控漏判的边界。**判据是 `Settings.Global.BOOT_COUNT`**（公开 API、免权限、每次开机递增）：精确、无时间窗口、不受时钟调整影响。**不要改回比较 `elapsedRealtime`**——那只有「重启后经过时间 < 上次启动代理时的 uptime」才识别得出重启，代理若在开机 30s 时启动，之后重启只要过了 30s 打开 app 就漏判；也不要用 `currentTimeMillis - elapsedRealtime` 推算开机时刻，NTP 校时会让它跳变。**宁可漏判不可误判**：漏判只多走一次 attach 尝试、由三重校验挡下过期 PID；误判会清掉仍有效的 PID，让活着的 mihomo 变成孤儿进程而 UI 显示未运行。故取不到 BOOT_COUNT 时一律按「没重启」处理。`ROOT_BOOT_COUNT` 是运行时态，必须在 `BackupManager.EXCLUDED_PREF_KEYS` 里排除——跨设备恢复会带进别人的计数。**boot-start 例外**：BootReceiver 走 `start()`（全新启动），不受影响——若它与 `reattachRoot()` 撞在一起，由「Service 启动必须幂等串行」那条的抢占规则裁决（fresh 赢）。注意「绝不回退全新启动」约束的是 attach-only 请求**自身**，不妨碍另一个 fresh 请求把它顶掉。

**打开应用时自动连接**：`AUTO_CONNECT_ON_LAUNCH`（设置 General，默认关）与开机自启是**两个独立开关**——BootReceiver 只在 `SERVICE_WAS_RUNNING=true` 时恢复，自动连接不看上次状态。实现挂在 `verifyAndSyncState` 内部而非另起入口：两者都在回答「app 打开时代理该不该跑」，拆开会与 ROOT attach 路径抢跑（`start()` 异步，attach intent 发出后 bridge 仍是 Stopped，第二个入口读到会重复发 START）。这只挡得住 app 内的入口，进程外的 BootReceiver 仍会重复投递，兜底见「Service 启动必须幂等串行」。三条约束：① **每进程只消费一次**（controller 是 Koin single），冷启动触发、回前台不触发；② 静默校验走 `startableSubscriptionId()`（无副作用版），无可用订阅时什么都不做，不能用错误 toast 打断只是打开 app 的用户；③ VPN 缺授权时 `requestVpnPermission()`，授权回调接续启动。ROOT 分支此时改走 `start()`——`startProxy(attachOnly=false)` 同样先三重校验 attach 复用，区别只在 attach 不成时允许全新启动，而这正是开关表达的意图。

**ROOT 模式热点处置**：sing-tun `auto_route` 的 catch-all ip rule（priority 9002）不区分本机 vs 转发流量，热点客户端包 iif=wlan2/ap0 也命中被导进 TUN，但 mihomo 对非本机源 IP 处理不稳。`RootTetherHijacker` 在 sing-tun 之前插队两种处置：

- **BYPASS（默认）**：`ip rule priority 8000/8002` 去程 + 回程均 action=`goto 9010`（sing-tun 自己的 nop marker）。去程越过 catch-all 后命中 Android 原生 iif forward rule → 走 wlan0/rmnet；回程 goto 过去命中 local_network/main 里的连接路由。
- **PROXY**：内核态 TPROXY——mihomo 启 `tproxy-port: 7895`（IP_TRANSPARENT socket），`mangle PREROUTING -i <tether> -j mishka_tether` 把 TCP+UDP 劫持到 `--on-port 7895 --tproxy-mark 0x01000000/0x01000000`；`ip rule fwmark ... lookup 2024 priority 7999` + `ip route add local default dev lo table 2024` 让带 mark 的包在 PREROUTING 里被判定为本机投递。**完全绕开 sing-tun userspace TCP stack**，延迟/吞吐接近 BYPASS。常量（bit 24 / table 2024 / priority 7999）对齐 box_for_magisk 一类验证过的取值，避开 Netd 低 16 位 mark。chain 内部顺序：① `--ctstate INVALID -j DROP`；② `IptablesIntranet` v4/v6 CIDR `-j RETURN`（DNS 除外，让 mihomo 处理 fake-ip）；③ `-m socket -j mishka_tether_divert`（ESTABLISHED 流仅打 fwmark + ACCEPT，跳过 TPROXY 重拦截）；④ 新连接 `-j TPROXY`。apply 用 heredoc 单次 su 调用（~60 条命令），避免 per-cmd 3-6s 累计 fork 开销。
- **PROXY 降级**：`xt_TPROXY` 不可用时退回「去程+回程对称 `lookup 2022`」（双向进 sing-tun，性能次于 TPROXY）。`probeTproxySupport()` runtime 探测，结果驱动 `buildAndWriteForRun(tproxyForTether = ...)` 决定是否写 `tproxy-port`。`ROOT_TPROXY_KERNEL_CAPABLE` 存探测结果，RootSettingsScreen 据此显示降级告警 Card，让用户明确感知而非静默 fallback。
- **attach 路径约束**：mihomo 启动时 tproxy-port 是否监听已锁死，app 被杀期间用户若改过 tether mode，attach 上去规则会与实际状态错位。`ROOT_TETHER_MODE_ACTIVE` 存 start 成功时的快照，attach 前比对不一致则拒绝 attach、走 fresh restart。**attach 条件 re-apply**：attach 成功后先 `anyRulesPresent()` probe 锚点（优先按 xt_comment 前缀 `mishka:tether:` / `mishka:tproxy:` 扫，次选 chain 名，最后 priority），present 则 skip，absent 才 re-apply——修复系统重启 / 与 box_for_magisk 共存被清残留的场景。`ROOT_ATTACH_FORCE_REAPPLY=true` 强制重建（诊断开关）。
- **接口识别（纯手填）**：用户在 RootSettingsScreen 填 CSV（`ROOT_TETHER_IFACES`，默认 `wlan1,wlan2`），编辑对话框提供「检测当前接口」扫描按钮（`NetworkInterface` 列 UP + 有 site-local IPv4 + 不在蜂窝/隧道黑名单的候选，默认排除 `wlan0`）。不做实时自动发现——`TETHER_STATE_CHANGED` extras 在 Android 11+ 不可靠（@hide），regex 白名单又覆盖不全 OEM 命名。
- **不能**用 `lookup main`——Android main 表无 default route。回程规则靠 `NetworkInterface.getByName` 读 InterfaceAddress + prefix 算 CIDR；接口未就绪（热点后开）会 WARN skip，需 restart 代理重新 apply。`ip rule add iif <name>` 对不存在接口按名注册、接口出现自动生效，`ip rule add to <subnet>` 则需 apply 时已有地址。生命周期：startProxy（含 attach）后 apply；stop/restart/死亡三路径 teardown（NonCancellable），两类规则都跑保证任意前置状态都能清干净，末尾 `verifyClean()` 扫锚点、残留重试一轮。**不做本机 TPROXY**：全 TPROXY 需重写 AppProxy、DNS、fake-ip 交互、IPv6 DNS 防泄漏一整条链，属独立重构。
- **一切 root 批量操作走单次 su**：Magisk 下每次 `su` 约 30~100ms，全压在启停关键路径上。`teardown`（ip rule 删到失败为止 + `-S` 转 `-D`）、`verifyClean`、`dumpState` 都折成一次调用——**数据依赖的两步操作也要留在 shell 里做**（`iptables -S PREROUTING | grep | sed 's/^-A/-D/' | while read`），不要把中间结果拉回 Kotlin 再发第二轮。多条查询用 `echo '--- <key>'` 分段后在 Kotlin 侧切回。同理 `MihomoRunner.waitForReady` 的 ROOT 判活降到 4 轮一次（2s）：`/proc` 是 hidepid，判活必须 fork su，而 API 探测是本地 HTTP、不花钱。

**ROOT 模式不做动态通知**：`DynamicNotificationManager.startOrFallbackStatic` 在 `tunMode != Vpn` 时强制走静态分支，忽略用户 `DYNAMIC_NOTIFICATION` 偏好；设置开关在 ROOT 模式下 `enabled=false` + 副标题说明。原因：VPN 靠 `BIND_VPN_SERVICE` 隐式让进程进入 `BOUND_FOREGROUND_SERVICE` 自动保 CPU；ROOT 无任何系统 binding，Activity 进后台后整个 device 进 idle，1Hz `/traffic` WS 帧合并、`notify()` 批处理，动态通知冻结。`PARTIAL_WAKE_LOCK` 实测被系统 DISABLED 救不了（只阻 SoC suspend 不阻 CPU idle）；唯一根治是引导用户 `IGNORE_BATTERY_OPTIMIZATIONS`，权衡后选择承认平台限制。**ROOT TUN 历史上看似工作**只是因为 mihomo 持续 `read(tun_fd)` 顺手撑住 device 不进 idle，不可靠且不一致。

**Flow.catch 是终结型操作**：`.catch` 捕获后流结束、不会重订阅。长生命周期 UI/通知 Flow 的瞬态异常（如 `notify()` 偶发 `RemoteServiceException`）应包到 `collect` 内部用 `runCatching` 处理；`.catch` 只留给真正需要终结的失败。DynamicNotificationManager 曾因顶层 `.catch` 让整条 trafficJob 永久死亡。

**日志列表按显示帧率发射**：日志风暴下可达数百行/秒。`appendLog` **只写 buffer + 置 `logsDirty`，不 emit**；独立 `flushJob` 每 120ms 才 `_logs.value = buffer.toPersistentList()`，把重组 + 500 条 key diff 从「日志行速率」降到「显示帧率」。**禁止**改回每行 emit。autoScroll 的 `LaunchedEffect` key 必须用 `logs.lastOrNull()?.id`（单调递增），**不能用 `logs.size`**——缓冲写满后 size 恒为 `MAX_LOGS`，跟随会永久停摆。

**错误兜底**：用户面向异常走 `Throwable.describe()`（`message ?: simpleName ?: "Unknown error"`），避免 Ktor `ConnectException()` 等无参异常漏到 UI 显示 "null"；`SubscriptionFetcher` 显式检查 `response.status.isSuccess` + 空 body 抛 typed `ImportError`。

**后台卡片隐藏**：`HIDE_TASK_CARD` 由 `MainActivity` 读取并经 `ActivityManager.AppTask.setExcludeFromRecents()` 应用，运行时切换经 callback 透传即时生效。不要写成 manifest `android:excludeFromRecents="true"`，否则失去用户可切换语义；App/屏幕暴露 callback、不直接调 Android API。当前实现依赖单 Activity task（`appTasks.firstOrNull()`）；若引入 document/multi-task 入口，必须改为按当前 `taskId` 匹配。

**Baseline Profile 只能本地真机生成**：`:baselineprofile` 采集冷启动 + 4 Tab 路径，`:app:generateReleaseBaselineProfile` 回写 `app/src/release/generated/baselineProfiles/`，**产物必须提交**——CI 只消费不生成。四条硬约束：① **CI 生成不可行**（APK 仅 arm64-v8a 且 libmihomo.so 无 x86 产物，x86_64 模拟器装不上；GitHub ARM runner 不提供 KVM），故 `automaticGenerationDuringBuild = false`，绝不能挂到 `assembleRelease`；② **`androidx.profileinstaller` 是必需依赖**——侧载分发拿不到 Play 云端 profile，没有它打进 APK 的 `baseline.prof` 不会被 ART 安装；③ **必须在 `finalizeDsl` 里关掉 `nonMinifiedRelease` 的 `optimization.enable`**——插件只会关旧 DSL 的 `isMinifyEnabled`，管不到 AGP 9 的新开关，不关则 generator 采集到混淆后的类名、release 再按自己的 mapping 重写一遍就全部错位，构建全绿但 profile 静默失效（判据：`mapping/nonMinifiedRelease/mapping.txt` 不该存在，APK 内 `top/yukonga/mishka` 类名应有数千个）；④ **benchmark 不能降到 stable**（`1.4.1` 在 AGP 9 下 apply 即失败，需 `1.5.0-alpha07+`）。generator 里三段 workaround 都是实测根因，删任何一段都退回「Generated Profile is empty」：**必须 `cmd package compile -f -m verify` 强制降级**（HyperOS 自带 BaselineProfile 服务装包时就按 APK 内 prof AOT 成 speed-profile，benchmark 自己的 `compile --reset` 只回到这个已 AOT 状态，运行期不再 JIT）；**必须先 `pm grant POST_NOTIFICATIONS`**（首帧前的授权框会挡住 MainActivity，而 ROM 弹窗按钮没有 AOSP 的 resource-id、按文案点会随 locale 碎掉）；**`startup` 末尾必须等够 ART profile saver 延迟**（`-Xps-save-resolved-classes-delay-ms` 默认 5s，`startActivityAndWait()` 一返回就结束会什么都记不到）。切 Tab 走 `HorizontalPager` 横滑而非按文案定位控件，`swipe` 后要 `SystemClock.sleep` 等动画收敛（Compose 动画不向 accessibility 报告 busy，`waitForIdle()` 会在切换途中返回）。收益要打折：`System.loadLibrary("mihomo")` 加载 56MB 库属 native 固定开销，Baseline Profile 只优化 Compose 首帧、Koin 图构建这类字节码路径。

**其他**：`Activity configChanges=uiMode` 防深浅色切换重建；预测性返回走 HiddenApiBypass 反射 `setEnableOnBackInvokedCallback`；`network_security_config.xml` 全局 `cleartextTrafficPermitted=true`（订阅源常用 HTTP；CMFA 因 fetch 在 Go 侧绕过 Java 网络栈而无需此设置，Mishka 的 Ktor 走 OkHttp 必须显式放行）；`jniLibs.useLegacyPackaging = true` 确保 libmihomo.so 解压到 nativeLibraryDir，且让它在 APK 内保持压缩存储（实测 59 MB → 19.3 MB），是净收益而非体积代价。

## UI 规范

- 所有组件用 miuix（其内部已 squircle 渲染圆角）。返回按钮 `MiuixIcons.Back`；底栏图标 Sidebar / Tune / UploadCloud / Settings；Badge `clip(RoundedCornerShape(3.dp))` + 9.sp Bold Monospace；操作 IconButton `minHeight/minWidth = 35.dp` + `secondaryContainer`。
- **自定义形状用 squircle modifier**（`top.yukonga.miuix.kmp.squircle.*`，非 miuix 组件的手搓形状不用 `RoundedCornerShape` clip/background）。按性能选：非点击纯色背景 → `squircleBackground`（无 offscreen layer，**不要 clip**）；图片/必须裁剪 → `squircleClip`（一个 offscreen layer）；可点击 → `squircleSurface` + `.clickable{}`（涟漪裁进圆角），条件可点击时退化为 `squircleBackground`。**3dp 小徽章保持 `clip(RoundedCornerShape(3.dp))`**——该尺寸下肉眼无差异，省一次 squircle 路径构建。注意省的不是 layer：`clip` 才分配 RenderNode，`squircleBackground` 反而无 offscreen layer。
- **页面骨架**：Scaffold + TopAppBar(scrollBehavior) + LazyColumn。LazyColumn 必须加 `.scrollEndHaptic().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection)`；`contentPadding` 仅设 top，不设 bottom；首个 item 是 Card/表单时加 `item { Spacer(12.dp) }`，SmallTitle / RestartRequiredHint 开头**不加**（SmallTitle 自带 8dp 上下边距）；末尾统一 `item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }`。**二级页面签名禁止 `bottomPadding: Dp` 参数**（靠末尾 Spacer 自适应）；**4 个 Pager Tab 例外**——外层 `MainPage` Scaffold 持有 bottomBar，必须接 `bottomPadding` 透传给 `contentPadding`。
- **顶栏/底栏毛玻璃**：所有页面 Scaffold 用 `BlurredBar` 包裹 TopAppBar / NavigationBar，MainPage 与每个二级页各自一份 backdrop（嵌套 layerBackdrop OK）。模式：顶层 `val backdrop = rememberBlurBackdrop()`、`blurActive = backdrop != null`、bar 色 `if (blurActive) Color.Transparent else surface`；内容区 LazyColumn 追加 `.then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)`。搜索页在 BlurredBar 内套 `searchStatus.TopAppBarAnim(backgroundColor = 同 bar 色)`。
- **宽屏适配**（窗口宽 ≥ 600dp `WideScreenMinWidth`，用缩放前 `LocalPlatformDensity` 量宽，界面缩放不翻转外壳）：底栏换可展开收起的侧边 `NavigationRail`（默认收起，Home Tab 用 `MiuixIcons.Home` 避免与展开钮撞脸），inset 上 rail 吸收起始侧、内容区 `consumeWindowInsets(Start)` + `windowInsetsPadding(systemBars∪displayCutout .only(End))`；所有 TopAppBar 走 [AdaptiveTopAppBar](app/src/main/kotlin/top/yukonga/mishka/ui/component/AdaptiveTopAppBar.kt)（宽屏固定不折叠，纵向空间紧张），**AboutScreen 例外**（hero 视差固定 SmallTopAppBar，套了会在手机端多出重复大标题）；搜索框动态 top padding 宽屏**恒为 0**。**内容居中**用 `WideContentBox { sidePadding -> ... }`：**LazyColumn 保持全宽**（两侧无滚动死区），仅把 `sidePadding` 加进 `contentPadding` 把内容限到 `MaxContentWidth=800dp`（与 600dp 外壳阈值是两个独立常量）；**是否居中复用 `rememberIsWideScreen()`**（外壳唯一权威，独立比较阈值会在 densityScale≠1 时不一致）。**不要**改回压缩 LazyColumn 节点宽度的 layout modifier。
- **横屏屏幕缺口**：miuix `Scaffold` 不自动 padding 内容，二级页 `contentPadding` 只吃 top → 横屏侧边刘海/手势条会压到内容。**每个二级页根 LazyColumn** 在 `.fillMaxSize()` 后加 `Modifier.horizontalCutoutPadding()`（只补水平 `displayCutout ∪ navigationBars`，竖屏为 0）；顶栏由自身 inset 处理。**AboutScreen 相反**：内容侧已自行处理，只给其 `SmallTopAppBar(defaultWindowInsetsPadding=false)` 加该 modifier。4 个主 Tab 内容居中在缺口内侧，无需此项。
- **Card 间距**：水平 12.dp，每项统一 `padding(horizontal = 12.dp).padding(bottom = 12.dp)`；不用 `Arrangement.spacedBy`。**TextField 表单**不包 Card，直接同样的 padding。
- **多组件卡片拆为独立 lazy item（滚动性能）**：`LazyColumn` 里禁止 `item { Card { 多行 } }`——整卡一次性组合，行多时滚动/展开卡顿。改用 [GroupedCardItems](app/src/main/kotlin/top/yukonga/mishka/ui/component/GroupedCardItems.kt)：`groupedCardItems(keyPrefix, items = listOf(CardItem("k") { row() }, ...))` 每行独立 item，`CardSegment` 分角拼回视觉连续的卡片。要点：**分角背景**——有圆角的首/末段用 `squircleSurface`（fill+clip，**必须 clip**，否则段内 clickable 的方角涟漪溢出圆角），中间段纯 `background`（无 offscreen 最省）；语义对齐 miuix Card（surfaceContainer + 16.dp 圆角，preference 自带内边距故段 `insidePadding=0`）；`outerBottomPadding` 按所替换 Card 的 bottom padding 传；条件行用 `buildList`。`groupedCardItems` **不加 item 动画**（拆分是不可见的纯性能优化），需要动画自行在 item 内 `Modifier.animateItem(...)`，**placement spec 不能设 null**——否则下方各组硬跳、无展开感。
- **ProxyScreen 特例**：展开状态从 item 内 `rememberSaveable` 上提到屏幕级 `SnapshotStateList`（节点行是顶层 lazy item、随展开动态增删，存 item 内会随销毁丢失）。**分行恒 `chunked(2)`**，「单列显示」的切换由行内自定义 `Layout` 按 `animateFloatAsState(tween(300))` 进度插值完成（节点宽半宽↔整宽、第二项位置右侧↔下方、行高单行↔两行；`NodeRowVerticalGap = 12.dp` 对齐段间 bottom padding，竖排 y 基准取首项实际高度而非行 max 高度）。**不要改回按开关切 `chunked(1/2)`**——行数一变 lazy key 语义整体错位，前半段是同 key 内容瞬变（无动画）、后半段是新 key fadeIn，`animateItem()` 对不上 key 帮不上忙，观感即「硬切」；morph 收进单个行 item 内部才能让两个节点同处一个 layout scope、位置与宽度连续插值。进度 `State<Float>` 传进 measure 阶段读取，**禁止在 composition 里 `by` 解包**。**排序 + 分块结果必须在 LazyColumn 之外 `remember(groups, sortOption)` 缓存**——content lambda 随 `expandedGroups`/`testingNodes` 等任一变化整体重跑，而 `/proxies` 延迟是逐条回填 groups 的，一次「测试全部」会把几百节点的排序重放几十遍；key 取 `groups` 而非 `uiState`，否则测速中的标记变化也会让缓存失效。节点名两种排布都不截断，走 `basicMarquee(iterations = Int.MAX_VALUE)` + `weight(1f, fill = false)`（marquee 仅在超容器时才动，短名零开销）。组头与节点行都用 `Modifier.animateItem()` 替代 `AnimatedVisibility`（一次性组合整组才是卡顿源）；组头底角随展开 `16.dp↔0.dp` 走 `animateDpAsState(tween(300))` 经 `CardSegment.bottomCornerRadius` 覆写，target 必须判 `isExpanded`（目标态）而非 `rows.isEmpty()`，否则圆角要等收起动画跑完才开始。
- **ProxyScreen 收起是连续高度收缩，不是 disappearing item 淡出**：点收起时**先跑动画、结束才从 `expandedGroups` 摘除**，期间 `retainedGroups`（非 saveable）保住行 item 继续产出，行高按 `(rowCount * 进度 - rowIndex).coerceIn(0f,1f)` 收缩（末行先卷完、逐行往上），内容仍按完整高度测量顶部对齐、由段上 `clipToBounds()` 裁掉——**卷起而非压扁变形**。段内底距 12dp 由行内 Layout 承担而非 `CardSegment.insidePadding`，否则收完残留一条底色。**为什么不能直接移除 item**：移除后行变 disappearing item，脱离布局在原位淡出，而下方内容目标位置已贴到组头、靠 placement spring 从远处追赶，两者交叠穿插 → 实测反馈「收起时卡片不连贯」。**动画期间必须关掉 placement 动画**（`animateItem(placementSpec = null)`）：高度收缩本身已连续，再叠 spring 追赶会让行与行拖影不同步；开关用布尔标记而非直接读进度 State（读进度会让整屏每帧重组）。单列 morph 期间同理要关。per 组进度存屏幕级 `mutableMapOf<String, MutableFloatState>`，进程恢复时 map 为空 → measure 取 `?: 1f` 直接全高无动画；打断重入靠 `expandJobs[name]?.cancel()` + 被 cancel 的旧 job **不**清理状态（交给接手的新动画）。
- **不适用 groupedCardItems**：纯静态文本卡（ExternalControl 提示、RootSettings 警告）与视差 + textureBlur 的 AboutScreen 保持单 `item { Card }`；AboutScreen 内容 Column 用 `heightIn(min = 视口高)` 而非 `fillParentMaxHeight()`——后者钉死恰好一屏，横屏矮视口下超出内容被裁且无法滚动。
- **Edit Dialog 按钮顺序** `not_modified | cancel | confirm`（三按钮 weight(1f) + `spacedBy(8.dp)`，confirm 用 `textButtonColorsPrimary()`）。**长内容 Dialog**：miuix `WindowDialog` 手机上不限 content 高度，过长会把底部按钮顶出屏——包 `Column(Modifier.heightIn(max = 500.dp))`，滚动区 `weight(1f, fill = false).verticalScroll(...)`，按钮作为非加权子项固定底部。**选项列表 Dialog**（入口行点击弹出、内含若干操作行）：`insideMargin = DpSize(0.dp, 24.dp)`——水平 0 让 `ArrowPreference` 全出血（涟漪铺满整宽），行自带 `horizontal = 24.dp` 内缩；垂直 24 补内置 title 顶距（miuix 的 title 自身仅 `bottom 12dp`，垂直置 0 会贴顶）。**弹 Dialog 的入口行设 `holdDownState`**（dialog 打开期间保持按下态，MIUI 惯例）；操作行先关自身再弹下一层时无按下态窗口，不设。
- **BottomSheet 内容要自己补 inset 与高度过渡**：`WindowBottomSheet` 的 `defaultWindowInsetsPadding = true` **只装了 `imePadding()`**（名字有误导性）；水平 24dp 来自 `insideMargin` 默认值；`displayCutout` 与 **`captionBar`（小窗/桌面窗口模式的系统标题栏）**仅取 top 分量参与算 `safeTopInset`、用作 `heightIn(max)`，**不产生任何 padding**。内容根节点因此要加 [`sheetContentSafePadding()`](app/src/main/kotlin/top/yukonga/mishka/ui/util/WindowSize.kt)——取 `systemBars`（已含 statusBars/navigationBars/**captionBar**）∪ `displayCutout` 的**底部**，**不要逐项列举**（漏 captionBar 就是小窗下贴边的原因）。两处刻意排除：① **不含 ime**（sheet 已装 `imePadding()`，并进来叠成双倍底距）；② **不含水平方向**（sheet 是 `BottomCenter` 对齐且 `widthIn(max = 640.dp)`，窗口更宽时两侧本就留白，而水平 inset 只在有缺口的单侧生效，加上反而把居中内容推偏——与二级页 `horizontalCutoutPadding()` 场景不同，那里内容是全宽的）。**高度过渡**：内容 Column 是 `wrapContentHeight()`，sheet 高度完全跟随内容且无动画——loading 切列表、条数增减都会硬跳，加 `animateContentSize(folmeSpring(damping = 0.9f, response = 0.38f, visibilityThreshold = IntSize(1, 1)))`，spec 与 sheet 入场动画同参（`folmeSpring` 是 miuix public API，别自己凑 spring 参数）。
- **卡片内背景水印绘制**（速度卡折线图 [TrafficSparkline](app/src/main/kotlin/top/yukonga/mishka/ui/screen/home/TrafficSparkline.kt) 与订阅卡用量条 [SubscriptionUsageBar](app/src/main/kotlin/top/yukonga/mishka/ui/screen/home/SubscriptionUsageBar.kt)；后者形状即前者退化成的方波，两者共用 `HomeShared.kt` 的 `Watermark*` 常量，**新增水印一律复用**否则同行两卡的绘图区高度/浓度会错开）：miuix `Card` 走 `squircleSurface`（fill + clip），**子内容会被自动裁到圆角**，卡内可直接铺满绘制、无需自套 `squircleClip`。三条要点：① `insideMargin = PaddingValues(0.dp)` + 内层 `Box(fillMaxSize)`，文字挪进 `Column(padding(16.dp))`，绘制层才能铺满（`BasicCard` 用 `propagateMinConstraints = true`，`fillMaxHeight` 的 Card 会把 min 约束传到 content）；② 绘制层用 `Modifier.matchParentSize()` **不参与测量**，卡片高度仍由文字决定——否则同一 `IntrinsicSize.Min` Row 里的邻卡会被一起拉高；③ 绘制层自身要 `clipToBounds()`，滑动动画会把曲线画到左右边界外。
- **1Hz 数据的连续动画**：`TrafficHistory.seq` 单调递增，既是动画 key（列表内容相等时无法区分「新采样点与上一点等值」）**也是动画目标值本身**——`Animatable` 追踪 `seq` 这个绝对量、`animateTo(seq)` 且**绝不 snapTo 归零**，进度由 `1 - (seq - scroll.value)` 反推。**这是防跳变的关键**：新点随重组立即进入绘制，而 `LaunchedEffect` 启动动画晚至少一帧，若用「snapTo(0) + animateTo(1)」的相对进度，那一帧会拿新点配旧进度画出整体左移、下一帧再被拽回，形成双向抖动；数据早于动画时长到达时 snapTo 还会反向回退。绝对量方案下动画未启动时进度天然为 0。单格时长取 **1100ms 略长于 1Hz 推送周期**，让动画总在跑完前被下一帧接上（稳态恒落后约 0.1 格）以吸收抖动，取整 1000ms 每周期末尾会静止一小段。**滚动窗口的 x 步长取 `capacity - 2`**：窗口填满后最老的点滑入起点恰落在 `x=0`、终点滑出到 `x=-step`，左边缘始终有内容；按直觉的 `capacity - 1` 会让左端在 `0..step` 之间来回，每周期闪出一道空白。滑入进度与纵轴上限都在 `onDrawBehind` 里读（draw 阶段读 State 只重绘不重组）。
- **语义色 token**：状态/延迟/按钮/错误色统一走 `ui.theme.StatusColors`（`runState`/`delay`/`actionButton`/`danger`/`healthy`/`warning`/`neutral`/`selectedNodeContainer`/`trafficUpload`/`trafficDownload`/`usage`）。**禁止屏幕里散落 `Color(0xFF...)`**；合法颜色源仅 `MiuixTheme.colorScheme.*` 与 `StatusColors`。
- **Flow 收集**：所有屏幕用 `collectAsStateWithLifecycle()`，不用 compose runtime 的 `collectAsState`——后台时上游不再驱动重组。
- **强跳过友好的状态形状**：UiState `data class` 必须 `@Immutable`；大集合字段一律 `ImmutableList`/`ImmutableMap`/`PersistentSet`，且**从生产端（Repository / Provider）就是这个类型**，不要在 UI 层末端才转——中途任何一处裸 `List` 作为 composable 参数都会让该 composable 不可 skip。
- **帧率级 State 不能在组合期读**：滚动折叠比例、动画进度这类每帧都变的值，用 `derivedStateOf` 包成 `Dp`/`Float` 再 `by` 解包等于把失效上浮到整个 restart scope（值每帧都不同，结构相等去重不生效）。一律以 `State<T>` 或 `() -> T` 透传，由消费方在 **layout/draw 阶段**读：布局用自定义 `layout{}` modifier（`SearchBar.topInset` 是现成范式，**不要** `Modifier.padding(value)`），绘制用 `graphicsLayer{}` / `onDrawBehind{}`（`Modifier.rotate/scale/alpha` 这些值形式包装同样是组合期读，合并进已有的 `graphicsLayer{}` 即可）。只有夹紧成布尔或离散档位后才可在组合期读。
- **持续动画在不可见时必须停**：[BgEffectModifier](app/src/main/kotlin/top/yukonga/mishka/ui/component/effect/BgEffectModifier.kt) 的帧循环与 [BgEffectBackground](app/src/main/kotlin/top/yukonga/mishka/ui/component/effect/BgEffectBackground.kt) 的色阶推进都由 `alpha()` 门控——About 页滚到底后 alpha 归零，两者不停就是纯空转（色阶的 spring 还会持续让 draw 失效）。**判定只能在 draw 里做**：alpha 是延迟读的 lambda，组合期拿不到；draw 阶段的快照读会在 alpha 变回非零时自动重新触发 draw，故不需要额外唤醒路径。协程侧要门控则用 `snapshotFlow { alpha() > 0f }.first { it }` 挂起，**不能** `by` 解包（那正是上一条禁止的组合期读）。
- **可复用组件 API**：`ui/component/*` 第一可选参必须是 `modifier: Modifier = Modifier` 并应用到 root-most 节点；wrapper 透传到底层 miuix 组件。
- **用户反馈**走 `platform.showToast(message, long = false)`。**i18n**：所有用户字符串走 `stringResource` / `context.getString`，禁止硬编码；新增同时加 `res/values` + `res/values-zh-rCN` + `res/values-zh-rTW`；key 命名 `{页面}_{描述}`，通用按钮 `common_` 前缀。
