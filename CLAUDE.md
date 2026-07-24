# Mishka

miuix + mihomo 的 Android 代理客户端。**单模块 `:app`**（`com.android.application`，AGP 9 内置 Kotlin，源码全在 `src/main`）。UI 用 AndroidX Compose（compose compiler 插件 + `androidx.compose.*`），miuix 走其 `-android` 发布件。

## 技术栈

Kotlin（AGP 9 内置，不加独立 kotlin 插件）+ KSP。UI：AndroidX Compose（运行时经 miuix `-android` 件传递）+ miuix + navigation3 + material-icons-extended。数据：Room 3（反射 builder）+ Ktor + kotlinx-* + Koin。其他：quickie（扫码）、hiddenapibypass（预测性返回）。核心：mihomo（Mishka fork patch，见「关键约束」）。

**版本与坐标唯一真源 = `gradle/libs.versions.toml`**（含 `[bundles]` 分组），mihomo 版本在 `gradle.properties`，应用坐标/SDK 在 `buildSrc/ProjectConfig.kt`；**文档不复述版本号**（避免漂移）。版本信息走 `BuildConfig.VERSION_NAME`/`VERSION_CODE`。`scripta:editor`（Compose Multiplatform 代码编辑器）经 `includeBuild("scripta")` 引入，其插件由 scripta 自己的 `pluginManagement` 解析，根 `build.gradle.kts` 无需声明。

Compose 稳定性走 [app/compose_compiler_config.conf](app/compose_compiler_config.conf)（把 kotlinx/Ktor/AndroidX 长生命周期容器与 `domain.model.*` 通配声明 stable，数据模型不加 `@Immutable`）。**新增推断 unstable 的三方/平台字段时优先加进该文件**，而非散落 `@Stable` 注解。

## 项目结构

**单模块 `:app`**。分层靠**包名**（`domain.model`/`domain.repository`/`data.*`/`platform`/`ui`/`viewmodel`/`di`/`service`），跨层即普通包引用。约定：`domain.model` 只放 `@Serializable` 数据模型、`domain.repository` 只放仓库接口，二者不引 android/compose/ktor/room（约定非 Gradle 强制）。

```
Mishka/
├── buildSrc/                  ProjectConfig（应用坐标/SDK）+ GoBuildTask（Go 交叉编译）
├── mihomo/                    git submodule（YuKongA/mihomo branch Mishka，5 patch 见关键约束）
├── scripta/                   includeBuild 复合构建代码编辑器（YAML 编辑），app 依赖 `scripta:editor`
└── app/                       build.gradle.kts + compose_compiler_config.conf + schemas/（Room schema）
    └── src/main/
        ├── kotlin/.../mishka/
        │   ├── App.kt / MainActivity.kt / MishkaApplication.kt（startKoin + 全局初始化）
        │   ├── domain/        model（@Serializable 数据模型）+ repository（仓库接口）
        │   ├── data/          api（REST/WS 客户端 + MihomoConnectionManager）+ bridge（MishkaCoreBridge JNI 门面）+ database（Room）+ repository（*Impl + ProfileProcessor + OverrideJsonStore + SubscriptionProxyResolver）
        │   ├── platform/      数据/服务类（见「platform / ui.platform 包」）
        │   ├── service/       服务组件（见「Android 服务层」）
        │   ├── ui/            navigation/ navigation3/ component/ platform/ theme/ screen/
        │   └── viewmodel/ + util/ + di/（4 个 Koin 模块）
        ├── res/               values/ + values-zh-rCN/（全部 UI 字符串）+ drawable + mipmap + xml
        ├── assets/            GeoIP（构建时下载）
        ├── cpp/               process_helper.c + mishka_jni.c + mihomo_wrapper.c + CMakeLists.txt
        ├── jniLibs/arm64-v8a/ libmihomo.so（统一 cgo c-shared）
        └── native/mishka_core/  Go cgo c-shared 源（go.mod replace mihomo → 仓库根 mihomo/）
```

## 架构

### 依赖层级

```
MishkaApplication.startKoin ─ Koin 容器（dataModule + androidPlatformModule + androidAppModule + viewModelModule，均在 :app）
  MainActivity（Koin get 取图）→ App → AppNavigation
    → HorizontalPager（4 Tab）+ NavDisplay（二级页面）
      → Screen Composable
        → ViewModel（构造注入 domain 接口 + data 服务）
          → domain.repository.MihomoRepository / SubscriptionRepository（接口）
            └ 实现 data.repository.MihomoRepositoryImpl / SubscriptionRepositoryImpl
                ├→ MihomoApiClient（Ktor HTTP）+ MihomoWebSocket（Ktor WS）→ mihomo 进程 http://127.0.0.1:9090
                └→ Room Database（ImportedDao / PendingDao / SelectionDao）
```

**Koin 依赖注入**（`严格遵循 koin inject`，4 模块均在 `:app` 的 `di/` 包，按职责拆分而非模块边界）：

- `dataModule`：appScope（CoroutineScope 单例）、DAO（从 AppDatabase）、OverrideJsonStore、SubscriptionProxyResolver、MihomoConnectionManager、`SubscriptionRepositoryImpl` + `single<SubscriptionRepository>{ get<SubscriptionRepositoryImpl>() }` 接口绑定、`factory { ProfileProcessor }`
- `androidPlatformModule`（`androidContext()` 绑定 app Context）：AppDatabase、PlatformStorage、ProxyServiceController、AppListProvider、WifiPolicyController、BootStartManager、BackupManager
- `androidAppModule`：`single<ProfileFileManager>{ AndroidProfileFileManager }`（实现属服务层）
- `viewModelModule`：12 个 ViewModel（单 Activity，用 `single`）；HomeViewModel/ProxyViewModel 的 getActive lambda 从 `get<SubscriptionRepository>()` 派生；**SubscriptionViewModel 注入 `androidContext()`**（`context.getString(R.string.x)` 解析错误文案）
- **组合根注入**：VM/服务图由 Koin 管理，MainActivity 用 `org.koin.android.ext.android.get()` 在组合根取图后透传给 `App(...)`（App 签名保持全参数化默认值，屏幕仍参数化、不用 koinViewModel）；仅需 Activity 上下文的 FilePicker / VPN 授权 launcher 由 MainActivity 直接持有，不入 Koin
- **repo 实现必配接口**：`data.repository.*Impl` 对应 `domain.repository.*` 接口；ViewModel/组合根依赖接口，`ProfileProcessor`（需实体级方法）依赖 `SubscriptionRepositoryImpl` 具体类。OverrideJsonStore/SubscriptionProxyResolver/ProfileProcessor 为 use-case/service（非 Repository），保留具体类但 Koin 注入

### 核心模式

- **通信方案**：runtime（traffic / logs / connections / proxy select / provider 刷新）走 subprocess + Ktor REST + WebSocket，三模式共用；订阅导入（fetch + provider prefetch + Parse）走 JNI in-process，由 [MishkaCoreBridge](app/src/main/kotlin/top/yukonga/mishka/data/bridge/MishkaCoreBridge.kt) 调 libmihomo.so 的 cgo 导出
- **统一 .so 架构**：[libmihomo.so](app/src/main/native/mishka_core/)（cgo c-shared，~56MB）同时承担 JNI 导出 + `mihomoEntry(argc, argv)` runtime 入口；[libmihomo_runner.so](app/src/main/cpp/mihomo_wrapper.c)（C PIE，~6KB）由 MihomoRunner fork+exec 后 dlopen libmihomo.so 调 mihomoEntry。一份 mihomo 代码两条路径共用
- **mihomo 客户端共享**：`MihomoConnectionManager`（Koin `dataModule` 里的 `single`，用 dataModule 的 application 级 `CoroutineScope`；`MishkaApplication.connectionManager` 经 `by inject()` 暴露给服务层）订阅 `ProxyServiceBridge.state`，`Running` 时构造新 `MihomoRepositoryImpl`，其他状态置 null；切换前同步 close 旧实例，杜绝 Ktor `HttpClient` 泄漏。消费方一律经 `connectionManager.repository: StateFlow<MihomoRepository?>` 取用：HomeViewModel 构造注入 connectionManager 自行 collect；MainActivity collect 后 `setRepository` 转发给 Proxy/Log/Provider/Connection/DnsQuery 五个 VM；`DynamicNotificationManager` 直接 collect
- **MishkaCoreBridge**：`init(homeDir, userAgent)` 在 `MishkaApplication.onCreate` 一次性调用，homeDir 指向共享 GeoIP 目录 `files/mihomo/geodata/`；`fetchAndValid` 内部分 token、150ms 轮询进度、取消时调 `nativeCancel` 让 Go ctx 进入 Done
- **导航**：miuix NavDisplay + 自定义 Navigator（push/pop/popUntil + navigateForResult）+ LocalNavigator；back stack 持久化走 Route sealed 多态序列化（`NavBackStackSaver`），新增路由只需 `@Serializable` 即自动获得进程死亡恢复；**`sealed interface Route` 自身必须保留 `@Serializable`**——缺失编译通过但运行时 `SerializationException`
- **深色判定单点**：`ThemeConfig.resolveIsDark(systemDark)` 是 colorMode→isDark 的唯一实现（App.kt 与 MainActivity 系统栏共用），组合树内一律读 `LocalAppDarkMode.current`；**屏幕/组件禁止直接 `isSystemInDarkTheme()`**，否则用户强制深/浅色时该处不跟随（AboutScreen OS3 背景 / YAML 编辑器配色曾因此混色）。主题枚举的用户可见名走 [ThemeLabels.kt](app/src/main/kotlin/top/yukonga/mishka/ui/theme/ThemeLabels.kt) 共享 label()，设置入口摘要与主题页禁止各自 when 映射。`LocalAppMonetEnabled` 标记 Monet 开关：StatusColors 仅 Running 态跟随动态取色（secondaryContainer/primary），Pending/Stopped 警示黄/红固定不随壁纸漂移。底栏毛玻璃无独立开关，跟随全局 `blurEnabled`
- **主页 Tab**：HorizontalPager + MainPagerState + NavigationBar（4 Tab）
- **隧道三模式**：VPN / ROOT TUN / ROOT TPROXY（`TunMode { Vpn, RootTun, RootTproxy }`）
  - **VPN**：VpnService 创建 TUN fd，mihomo 写 `tun.file-descriptor` + `auto-route=false`，工作目录 `imported/{uuid}/`（app UID）
  - **ROOT TUN**：mihomo 以 root 自建 TUN，`auto-route=true` + `auto-detect-interface=true`，工作目录独立 `runtime/{uuid}/` 沙箱（启动前从 imported/ 拷贝，停止时 `su rm -rf`）；imported/ 永远 app UID
  - **ROOT TPROXY**：**`tun.enable=false`**，mihomo 用 `tproxy-port=7895` 入站 + `dns.listen=0.0.0.0:1053`；`RootTproxyApplier` 装 `mangle PREROUTING/OUTPUT` + `nat PREROUTING/OUTPUT` + `ip rule fwmark 0x1000000 lookup 2024` + `ip route add local default dev lo table 2024`，透明劫持本机与热点流量；分应用代理改走 iptables `-m owner --uid-owner`
  - **分应用代理**：VPN 走 VpnService API；ROOT TUN 走 mihomo `include/exclude-package`（sing-tun 翻译为 uidrange）；ROOT TPROXY 走 iptables `uid-owner`（`AppListProvider.resolveUids` 把包名解析为 UID）；**Mishka 自身始终排除**（VPN: disallowed；ROOT TUN: include 过滤 + exclude 叠加；ROOT TPROXY: `-m owner --uid-owner 0 RETURN` 放行 root 含 mihomo + `-m owner --uid-owner $APP_UID RETURN` 兜底；**不**用 `routing-mark`/SO_MARK 自绕——Android Netd 用 fwmark 低 16 位编码 netId，任何自定义 SO_MARK 都会让路由命中 legacy_system 表无默认路由，导致 mihomo 出站 `network unreachable`，box_for_magisk / Surfing / box4magisk 三家同款教训）
  - **ROOT 两子模式共享** MishkaRootService，Intent 通过 `EXTRA_SUBMODE = "tun"/"tproxy"` 区分；attach 路径比对 `ROOT_SUBMODE_ACTIVE` 与请求 submode，不一致 fresh restart
  - ROOT 进程 app 被杀后仍存活，重启 app 通过持久化 PID/secret **attach-only** 重连（绝不因重连失败而全新启动，见关键约束）
  - ROOT 不可用自动回退 VPN（MainActivity 探测失败后回写 `TUN_MODE=vpn`）
- **Wi-Fi 自动切换**：`WifiPolicyMonitorService` 前台监控当前 active Wi-Fi SSID（精确匹配，去掉 Android 外层双引号，忽略 `<unknown ssid>`），权限不足时不触发策略。设置页支持两种动作：
  - **停止服务**：进入匹配 Wi-Fi 且代理运行中时记录 `WIFI_POLICY_PENDING_RESTART=true` 后停止代理；离开匹配 Wi-Fi 时仅在 pending 存在时自动启动一次。监控服务可保留前台运行以保证自动恢复。
  - **Direct 模式**：进入匹配 Wi-Fi 写 `WIFI_POLICY_RUNTIME_MODE=direct`，离开清空 override 回退用户持久 mode，统一通过 `ProxyServiceController.restart()` 热重载当前服务（VPN / ROOT TUN / ROOT TPROXY 行为一致）；runtime mode 由 `RuntimeOverrideBuilder` 优先于用户 `override.user.json` 注入，不污染持久配置。Starting 窗口内的切换排队待 Running 后补一次 restart（Stopping 之后的全新启动自然读到最新值）。
  - 关闭功能时恢复被策略改动的代理状态（pending 补启动 / 清 runtime override 重载）。Wi-Fi 监控通知与切换事件通知使用独立 channel；切换通知可关闭，隐藏监控前台通知为可选项且会降低后台自动恢复可靠性。开机 / 包替换后 `WifiPolicyBootReceiver`（默认 disabled，随功能开关动态启用）恢复监控。
- **状态桥接**：ProxyServiceBridge（全局 StateFlow + TunMode），Service 写入、ViewModel 读取
- **进程模型**：单进程（VpnService 和 UI 同进程），ROOT 模式 mihomo 为独立 root 进程
- **数据持久化**：Room 3（结构化数据）+ PlatformStorage（简单偏好）+ StorageKeys（key 常量）+ OverrideJsonStore（`override.user.json` + ConfigurationOverride `@Serializable`）；store 自带 `state: StateFlow<ConfigurationOverride>` + `update(transform)`，Settings 三个切片 VM 共享同一实例
- **订阅管理**：Pending → Processing → Imported 三阶段沙箱，`ProfileProcessor` 编排 snapshot → fetchAndValid（JNI 一次完成 fetch + provider prefetch + Parse 校验） → commit；processLock 串行，profileLock 守护 DB 一致性
- **深链一键导入**：`clash://install-config?url=...&name=...&update-interval=...`（兼容 `clashmeta://`）由 ExternalImportActivity（透明 + noHistory + excludeFromRecents）承接，校验 url 为 http(s) 后带随机 nonce 转发 MainActivity（`NEW_TASK|SINGLE_TOP|CLEAR_TOP`），经 `App → AppNavigation` 的 `deepLinkImport` 参数驱动导航：popUntil Main → pager 切订阅 Tab（index 2，订阅管理只有 pager Tab 形态、无二级路由）→ push 预填 `Route.SubscriptionAddUrl(initialUrl/initialName/initialIntervalMinutes)`；保存/取消后回到 Main 的订阅 Tab。**不静默导入**——只预填表单，用户确认保存后走常规 Pending → APPLY 管线。两个防御点：① MainActivity 用 **nonce 去重**（已消费 nonce 随 savedInstanceState 持久化）而非 `savedInstanceState` 判空——进程死亡恢复重放旧 intent 需跳过，而「任务存活但进程被杀」时新深链可能带恢复态送达需接受，判空无法区分；② 跳板 exported，`intent.data` 先 `takeIf { isHierarchical }` 再取 query——非层级 URI 上 `getQueryParameter` 直接抛异常
- **订阅 HTTP**：mihomo `component/http.HttpRequest`（in-process，cgo），60s context timeout；UA 默认 `ClashMetaForAndroid/{version}`（订阅服务白名单），用户可在订阅 Add/Edit 页面填自定义 UA 持久化到 `ImportedEntity.userAgent` / `PendingEntity.userAgent`，pipeline 经 PendingSnapshot 透传到 `MishkaCoreBridge.fetchAndValid` → JNI → Go `runFetchAndValid` 内 `effectiveUA = trim(userAgent) ?: currentUserAgent()`；非 2xx / 空 body → `MishkaCoreError`；不做 base64/V2Ray 转换，原始 YAML 直接交 mihomo
- **age 加密订阅**：per-profile `ageSecretKey`（DB v3）随 pipeline 经 `PendingSnapshot` 透传到 `MishkaCoreBridge.fetchAndValid(ageSecretKey)`。**加密原样落盘，运行时解密**（对齐 CMFA）：
  - **导入校验**：Kotlin 侧在 native fetch 前 `nativeSetAgeSecretKey(key)`、fetch 后清空（processLock 串行保证全局密钥不串）；Go `runFetchAndValid` 里 `config.UnmarshalRawConfig`/`ParseRawConfig` 用该全局密钥**在内存中**解密校验，**config.yaml 与 provider 文件保持加密落盘**（fetch.go 不重写明文）。
  - **运行时解密**：`MishkaTunService`/`MishkaRootService` 启动前从 DB 读 active 订阅 `ageSecretKey`，经 `MihomoRunner.start(ageSecretKey)` 加 `--age-secret-key` CLI flag（与 `--secret`/`--ext-ctl` 同渠道）；`runtime.go` 注册该 flag 并在 `hub.Parse` 前 `age.SetGlobalSecretKeys`，mihomo 加载时解密。ROOT attach 路径进程已带密钥，不重传。
  - **副作用**：加密订阅的 config 对 app 不透明，`ConfigGenerator.readSubscriptionSecret`/`readSubscriptionMixedPort` 行扫描扫不到内容 → 退回默认值（仅影响加密订阅的 secret/mixed-port 推导，加密本身固有限制）。per-provider `age-secret-key` 字段的 provider 由 mihomo 运行时按字段解密。
  - **密钥生成**：Meta 设置「生成密钥对」(X25519) / 「生成抗量子密钥对」(mlkem768-x25519) → `mishkaGenAgeKeyPair` / `mishkaGenAgeHybridKeyPair`（mihomo `age.GenX25519KeyPair` / `GenHybridKeyPair`）→ `MishkaCoreBridge.generateAgeKeyPair(hybrid): AgeKeyPair`
- **订阅下载走代理**：`SubscriptionProxyResolver` 按「开关 + 代理运行中 + 可解析 mixed-port」返回 proxy URL 或 null；`ProfileProcessor` resolve 后传 `httpProxy` 给 bridge；native glue 在 fetchAndValid 入口 `os.Setenv("HTTPS_PROXY"/"HTTP_PROXY")` defer Unsetenv，覆盖订阅 fetch + provider prefetch + GeoIP 自动下载（mihomo `downloadToPath` 用 Go stdlib `http.Get` 读 env）。processLock 串行保证 set/unset 并发安全。无代理时直连，由 mihomo 内部 90s timeout 兜底。`resolve(requireUserToggle)` 参数控制是否受该开关约束：订阅下载传 true；图标等通用资源下载传 false（只要代理运行就走本机代理）
- **代理组图标下载走代理**：图标 URL 多为境外 CDN，而 Mishka 自身永远绕过 TUN 直连——这是「图标加载慢」的根因模式，任何 app 内下载外网资源的新功能都要走 mixed-port（对齐 sparkle `getImageDataURL`）。[IconLoader](app/src/main/kotlin/top/yukonga/mishka/ui/platform/IconLoader.kt)：内存 LRU(64) → 磁盘缓存 → 网络（限流 3 并发 + 5s/15s 超时）；失败 URL 负缓存 60s（lazy item 滚回视口不重放失败请求）；磁盘读取/解码在 `Dispatchers.IO`；`MainActivity` 注入 `SubscriptionProxyResolver.resolve(requireUserToggle=false)` 作 proxy 解析器（解析结果缓存 10s，避免一屏图标并发时逐个查 mihomo API），代理 client 随 proxy URL 变化 close 旧建新
- **Pipeline 可取消**：协程 cancel → `nativeCancel(token)` → Go ctx Done → native 立即返回 "context canceled"；`ImportProgressDialog` 可选 `onCancel`；`cancelCurrentUpdate` 先同步 `clearProgress()` 让 UI 立即响应，再 cancel 协程
- **GeoIP 预制**：构建时 DownloadGeoFilesTask 下载 geoip.metadb/geosite.dat/ASN.mmdb 到 assets，启动时提取到 `files/mihomo/geodata/`。JNI 路径用 `mishkaCoreInit(geodataDir)` 把 mihomo 全局 homeDir 指到这里；subprocess runtime 仍按 `-d workDir` + symlink 复用同一份 GeoIP
- **配置校验**：JNI in-process `MishkaCoreBridge.fetchAndValid` 调用 mihomo `config.ParseRawConfig`，含 GEOIP/GEOSITE/IP-ASN 规则时触发数据库 init（缺失则走代理下载到 geodata/）
- **WebDAV 备份恢复**：设置 →「备份与恢复」（`Route.BackupRestore` + `BackupViewModel` + `data/backup/`）。固定文件名覆盖式：[WebDavClient](app/src/main/kotlin/top/yukonga/mishka/data/backup/WebDavClient.kt) 只做 MKCOL/PUT/GET + Basic Auth（Ktor 短生命周期 client 用完即 close，无 PROPFIND 多版本历史），路径 `{root}/Mishka/mishka-backup.zip`。**重定向手动处理**：Ktor 默认只对 GET/HEAD 跟随 3xx，而服务器常把无尾斜杠目录路径 301 到带斜杠版本（Apache mod_dir / NAS 网关），MKCOL/PUT 收到 301 直接失败（表象「baseUrl 填 /dav 服务根就 301」）；集合 URL 一律带尾斜杠 + `davRequest` 手动跟随 301/302/307/308（`followRedirects=false`，保持方法与 body，**仅限同主机**——Basic 凭据跨主机跟随会泄露密码）。[BackupManager](app/src/main/kotlin/top/yukonga/mishka/data/backup/BackupManager.kt) 打包 `backup.json`（三表 **JSON 导出重放**而非拷 db 文件——绕开 WAL 一致性、跨 schema 由字段默认值兜底 + prefs 两类 Map）+ imported/、pending/ 目录树 + override.user.json。**geodata 符号链接与实体拷贝不进备份**（`ProfileFileOps.GEODATA_FILES` 名单 + isSymbolicLink 双重排除，启动/校验路径 ensureGeodataLinks 自动重建；否则 readBytes 追链接把几十 MB GeoIP 实体化进 zip）；prefs 黑名单排除设备/运行时态（HAS_ROOT / ROOT PID / boot-session / Wi-Fi 运行中间态 / 迁移标记）与 WebDAV 凭据自身。备份与恢复都持 `ProfileProcessor.withProcessLock` 进程级锁（防与导入管线并发互踩）；恢复前置校验代理已停止（Bridge 非 Running/Starting/Stopping），解包 canonicalPath 校验防 zip-slip，active uuid 指向不存在订阅时清空；**恢复成功后强制重启进程**（OverrideJsonStore / Repository Flow 等内存热状态不随磁盘恢复刷新），完成对话框仅「立即重启」一个按钮 → `MainActivity.restartApplication()`（CLEAR_TASK relaunch + exit）。**组件状态型设置**：开机自启是 PackageManager 组件位而非 pref，走快照独立字段 `bootStartEnabled` 捕获/恢复；Wi-Fi 策略的组件位与监控服务由重启后 MainActivity 按恢复出的 `WIFI_POLICY_ENABLED` 幂等 reconcile（恢复时机拉起的服务活不过强制重启）。**本地备份**复用同一 zip 与恢复管线：FilePicker 扩展 SAF `CreateDocument`（`saveZipFile`，`"wt"` 截断写防旧文档尾部残留损坏 zip）+ `OpenDocument` 读字节（`pickZipFile`，不按 MIME 过滤——网盘流转后 provider 常报 octet-stream）；导出文件名带时间戳 `mishka-backup-yyyyMMdd-HHmmss.zip`，取消（uri=null）静默不提示；两种恢复来源共用覆盖确认对话框（`pendingRestoreAction` 挂起动作模式）
- **国际化**：英文 + 中文（zh-rCN），Composable 内 `stringResource(R.string.x)`、非 Composable `context.getString(R.string.x)`；日志消息英文，代码注释中文

## 数据库架构（Room 3）

### 三表结构

| 表         | Entity          | 用途                                |
| ---------- | --------------- | ----------------------------------- |
| imported   | ImportedEntity  | 已导入的稳定订阅配置                |
| pending    | PendingEntity   | 编辑中的草稿（提交后移入 imported） |
| selections | SelectionEntity | 代理组选择记录（per 订阅）          |

### 类型安全与时间语义

- **ProfileType enum**（`File`/`Url`/`External`）通过 `ProfileTypeConverter` 透明映射为 TEXT 列
- **订阅 UUID 完整 36 字符**（`Uuid.random().toString()`，UUID v4 不做循环冲突检测）
- **updatedAt 动态计算**：`ImportedEntity` 无此字段，`resolveProfile` 读 pending→imported 目录 mtime，fallback `imported.createdAt`；订阅 commit/update 自然更新文件 mtime，无需主动写 DB

### Schema 版本

- **v1 → v2**（`MIGRATION_1_2`）：为 `imported` / `pending` 增加 `userAgent TEXT NOT NULL DEFAULT ''` 列，支持 per-profile UA 覆写。新增列时 schema 须由 KSP 自动导出到 [app/schemas](app/schemas/)（exportSchema 已在 [app/build.gradle.kts](app/build.gradle.kts) `room3 { schemaDirectory("$projectDir/schemas") }` 配置），跑一次 `:app:assembleDebug` 落盘；MIGRATION 必须在 `AppDatabaseBuilder` 的 `addMigrations(MIGRATION_1_2)` 注册，遗漏会让升级用户首次启动 crash。
- **v2 → v3**（`MIGRATION_2_3`）：为 `imported` / `pending` 增加 `ageSecretKey TEXT NOT NULL DEFAULT ''` 列，支持 per-profile age 解密密钥（age armor 加密订阅）。同样须 KSP 导出 schema 落盘，并在 `AppDatabaseBuilder` 的 `addMigrations(MIGRATION_1_2, MIGRATION_2_3)` 注册。

### 三阶段流程（ProfileProcessor）

```
CREATE → Pending ✓, Imported ∅
  → APPLY（processLock 串行，3 阶段）：
      ① snapshot（profileLock 内）：query Pending + enforceFieldValid + prepareProcessing（清 processing/ + 复制 pending/{uuid}/ → processing/）
      ② fetchAndValid（锁外，可取消，JNI in-process 一次完成 fetch + provider prefetch + Parse）：
         - Url 类型：force=true，bridge 删旧 config.yaml 后由 mihomo `clashHttp.HttpRequest` 重新下载到 processing/config.yaml；并发 prefetch 所有 proxy-provider / rule-provider 到 processing/providers/；Parse 校验
         - File 类型：force=false，processing/config.yaml 已由 prepareProcessing 复制就位，bridge 跳过 fetch，直接 prefetch + Parse
         - httpProxy 由 SubscriptionProxyResolver.resolve() 决定（运行中代理 + 开关），透传到 native glue 的 HTTPS_PROXY env
      ③ commit（profileLock 内，`withContext(NonCancellable)` 原子）：snapshot 一致性检查 → commitProcessingToImported（清 imported/{uuid}/ + 复制 processing/ → imported/{uuid}/ + 删 pending/{uuid}/）→ DB 更新
  → 失败：cleanupProcessing（走 NonCancellable）；pending/ 与 imported/ 都不动，可 retry
  → RELEASE（放弃）：删 Pending DB + 删 pending/{uuid}/

PATCH（编辑已导入）→ Imported ✓, Pending ✓ → APPLY → Imported ✓（更新）, Pending ∅
UPDATE（手动/自动）→ 等价 APPLY，snapshot 取自 Imported，processing 基准为 imported/{uuid}/config.yaml
DELETE → Imported + Pending + Selection 三表清理 + imported/{uuid}/ + pending/{uuid}/ 删除
```

### 目录结构

```
files/mihomo/
├── geodata/                    共享 GeoIP（启动时从 assets 提取 + 符号链接到各订阅目录）
├── imported/{uuid}/            已验证的稳定配置（app UID）
├── pending/{uuid}/             编辑中的草稿（app UID）
├── processing/                 临时校验沙箱（单例）
├── runtime/{uuid}/             ROOT 模式 mihomo 运行时沙箱（从 imported/ 复制 + provider 缓存）
├── override.user.json          用户设置的 override（ConfigurationOverride）
└── override.run.json           启动时合并 TUN fd + AppProxy + rootMode 后的运行时 override
```

## 路由清单

`Route.kt` 中定义的路由，均实现 `NavKey`：

| 路由               | 类型        | 页面                          | 入口                   |
| ------------------ | ----------- | ----------------------------- | ---------------------- |
| Main               | data object | 主页（HorizontalPager 4 Tab） | 根路由                 |
| SubscriptionAdd    | data object | SubscriptionAddScreen         | 订阅 Tab               |
| SubscriptionAddUrl | data class  | SubscriptionAddUrlScreen      | 添加订阅页 / 扫码 / clash:// 深链 |
| SubscriptionEdit   | data class  | SubscriptionEditScreen        | 订阅项编辑按钮         |
| Log                | data object | LogScreen                     | QuickEntries           |
| Provider           | data object | ProviderScreen                | QuickEntries           |
| DnsQuery           | data object | DnsQueryScreen                | QuickEntries           |
| Connection         | data object | ConnectionScreen              | QuickEntries           |
| ThemeSettings      | data object | ThemeSettingsScreen           | 设置页                 |
| VpnSettings        | data object | VpnSettingsScreen             | 设置页（仅 VPN 模式）  |
| RootSettings       | data object | RootSettingsScreen            | 设置页（仅 ROOT 模式） |
| NetworkSettings    | data object | NetworkSettingsScreen         | 设置页                 |
| MetaSettings       | data object | MetaSettingsScreen            | 设置页                 |
| ExternalControl    | data object | ExternalControlScreen         | 设置页                 |
| AppProxy           | data object | AppProxyScreen                | 设置页                 |
| WifiPolicy         | data object | WifiPolicyScreen              | 设置页                 |
| FileManager        | data object | FileManagerScreen             | 设置页                 |
| BackupRestore      | data object | BackupRestoreScreen           | 设置页                 |
| FileManagerEditor  | data class  | FileManagerEditorScreen       | FileManager 点击项     |
| About              | data object | AboutScreen                   | 设置页                 |

## 页面与 ViewModel

| Screen                   | ViewModel             | 说明                                                            |
| ------------------------ | --------------------- | --------------------------------------------------------------- |
| HomeScreen               | HomeViewModel         | 状态/ActionButtons/NetworkInfo/QuickEntries/Latency/BottomCards |
| ProxyScreen              | ProxyViewModel        | 代理组 Tab + 节点选择 + 延迟测试 + 选择记忆                     |
| SubscriptionScreen       | SubscriptionViewModel | 订阅列表 + 增删改 + 全部更新 + 编辑 + 复制（可取消 Pipeline）   |
| SubscriptionEditScreen   | SubscriptionViewModel | 编辑名称/URL/更新间隔                                           |
| SettingsScreen           | —                     | 设置入口（TUN 模式/主题/开机自启）                              |
| LogScreen                | LogViewModel          | 实时日志流 + 级别过滤                                           |
| ConnectionScreen         | ConnectionViewModel   | 活跃连接列表 + 关闭                                             |
| ProviderScreen           | ProviderViewModel     | Provider 列表 + 刷新                                            |
| DnsQueryScreen           | DnsQueryViewModel     | DNS 查询（A/AAAA/CNAME/MX/TXT/NS）                              |
| AppProxyScreen           | AppProxyViewModel     | 应用代理白/黑名单                                               |
| ThemeSettingsScreen      | —                     | 主题设置（模式/Monet/界面缩放/底栏样式），写 ThemeConfig        |
| WifiPolicyScreen         | —                     | Wi-Fi 自动切换（SSID 列表、停止服务 / Direct 模式、通知策略）   |
| VpnSettingsScreen        | —                     | VPN 设置（系统代理/排除路由等），仅 VPN 模式可见                |
| RootSettingsScreen       | —                     | ROOT 设置（TUN 设备名 + 热点客户端处置），仅 ROOT 模式可见      |
| NetworkSettingsScreen    | NetworkSettingsVM     | 端口/局域网/IPv6/DNS                                            |
| MetaSettingsScreen       | MetaSettingsVM        | 统一延迟/Geodata/TCP 并发/嗅探器                                |
| ExternalControlScreen    | ExternalControlVM     | mihomo HTTP API external-controller + API secret                |
| FileManagerScreen        | SubscriptionViewModel | imported 订阅目录浏览                                           |
| BackupRestoreScreen      | BackupViewModel       | 备份与恢复（本地/WebDAV 两入口行，各自 Dialog 内完成操作；恢复后强制重启） |
| FileManagerEditorScreen  | SubscriptionViewModel | 多行 TextField 编辑 YAML，保存前 mihomo -t 校验，失败回滚       |
| AboutScreen              | —                     | hero 图标 + 3 阶段视差 + OS3 动态背景 + 开放源代码许可列表（版本信息在 hero，无独立信息卡；mihomo 版本拼在其许可条目 title） |
| SubscriptionAddScreen    | —                     | 添加方式选择（文件/URL/QR Code）                                |
| SubscriptionAddUrlScreen | SubscriptionViewModel | URL 导入订阅                                                    |

## platform / ui.platform 包

`platform` 包放数据/服务类，`ui.platform` 包放 Compose 相关助手：

| 类/函数                       | 类型        | 实现                                  |
| ----------------------------- | ----------- | ------------------------------------- |
| PlatformContext               | typealias   | = android `Context`                   |
| PlatformStorage               | class       | SharedPreferences                     |
| PlatformSystemInfo            | class       | ConnectivityManager + /proc           |
| ProxyServiceController        | class       | 按 TunMode 组装 Intent 启停 Tun/Root Service |
| AppListProvider               | class       | PackageManager                        |
| BootStartManager              | class       | BroadcastReceiver                     |
| FilePicker                    | class       | SAF                                   |
| AppIcon                       | @Composable | BitmapFactory                         |
| IconDiskCache                 | object      | 磁盘缓存                              |
| WifiPolicyController          | class       | Wi-Fi 权限 / 当前 SSID / 监控服务启停 |
| ProxyServiceBridge            | object      | 全局代理状态 StateFlow（ProxyServiceState.kt） |
| ProfileFileManager            | interface   | 订阅目录抽象（实现在服务层）          |
| scanTetherInterfacesAsRoot    | fun         | NetworkInterface 扫描热点接口候选（TetherInterfaceScanner.kt） |
| showToast / initToastPlatform | fun         | android.widget.Toast（主线程派发）    |

`ui.platform` 含 AppIcon / IconLoader / IconDiskCache 图像三件套 + `Clipboard.setPlainText/getPlainText` 剪贴板读写（Compose suspend `Clipboard` 需构造 `ClipEntry`，实现走 Android `ClipData`）。

## Android 服务层

| 组件                       | 用途                                                                                                                                                                                                 |
| -------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| MishkaTunService           | VpnService + JNI fork+exec 启动 mihomo                                                                                                                                                               |
| MishkaRootService          | ROOT 模式前台服务（ROOT TUN + ROOT TPROXY 两 submode 共享；EXTRA_SUBMODE 分支）                                                                                                                      |
| RootTproxyApplier          | ROOT TPROXY 规则装配（mangle/nat chains + fwmark ip rule + local default dev lo，iptables + uid-owner 分应用）                                                                                       |
| RootHelper                 | root 检测/启动/终止/存活检查/残留清理 + rmRfAsRoot + chownRecursiveAsRoot + runAsRootReturnCode                                                                                                      |
| RootTetherHijacker         | ROOT 模式热点流量处置（BYPASS：ip rule `goto` sing-tun nop 绕过代理 / PROXY：TPROXY 走代理，详见关键约束）                                                                                           |
| DynamicNotificationManager | 动态通知（订阅 `MishkaApplication.connectionManager.repository` 的 traffic 流，不持有自己的 HttpClient），两个 Service 共用                                                                          |
| MishkaTileService          | Quick Settings Tile 一键启停代理（经 ProxyServiceController）                                                                                                                                        |
| BootReceiver               | 开机自启（默认 disabled，动态启用）                                                                                                                                                                  |
| WifiPolicyMonitorService   | Wi-Fi 自动切换前台监控服务（NetworkCallback + 当前 SSID 匹配；停止服务 / Direct runtime mode + 统一重载）                                                                                            |
| WifiPolicyBootReceiver     | 开机 / 包替换后拉起监控服务（默认 disabled，随功能开关动态启用）                                                                                                                                     |
| ConfigGenerator            | mihomo 工作目录/secret 生成工具（getWorkDir/getConfigFile/generateSecret）                                                                                                                           |
| RuntimeOverrideBuilder     | 运行时 override.run.json 装配（按 Submode = Vpn/RootTun/RootTproxy 分支：TUN fd / auto-route+include/exclude-package / tproxy-port+routing-mark+dns.listen；Wi-Fi runtime mode 优先于用户持久 mode） |
| ProfileFileOps             | 订阅目录管理（imported/pending/processing/runtime + GeoIP + ROOT 沙箱）                                                                                                                              |
| AndroidProfileFileManager  | ProfileFileManager 接口的 Android 实现                                                                                                                                                               |
| MihomoRunner               | mihomo runtime 进程管理（VPN: JNI fork+exec / ROOT: su）                                                                                                                                             |
| MishkaCoreBridge           | 订阅导入 JNI 门面：libmishka_jni.so（C 桥）+ libmihomo.so（cgo c-shared 统一库，含 mihomo）；fetch + provider prefetch + Parse 一次完成                                                              |
| ProcessHelper              | JNI 包装（nativeForkExec/nativeKill/nativeWaitpid，仅 runtime 用）                                                                                                                                   |
| NotificationHelper         | 三层通知渠道（VPN/更新进度/更新结果）                                                                                                                                                                |
| ProfileReceiver            | AlarmManager 调度自动更新                                                                                                                                                                            |
| ProfileWorker              | 前台服务执行后台配置更新                                                                                                                                                                             |
| VpnPermissionActivity      | 透明跳板 Activity：Tile / 通知等无 Activity 上下文的 VPN 授权（见关键约束「启动校验单点」）                                                                                                          |
| ExternalImportActivity     | `clash://` / `clashmeta://install-config` 深链透明跳板（根包）：解析 url/name/update-interval → NEW_TASK+SINGLE_TOP+CLEAR_TOP 转发 MainActivity → 预填 SubscriptionAddUrlScreen，用户确认后走常规导入管线 |
| IptablesIntranet           | 私网/组播/保留段 CIDR 常量（V4/V6），ROOT TUN route-exclude 与 ROOT TPROXY RETURN 共用                                                                                                               |

## 数据模型

`domain.model` 包下的 `@Serializable` 数据类：

ConnectionInfo, DelayResult, DnsQuery, LogMessage, MemoryData, MihomoConfig, ProviderInfo, ProxyGroup, ProxyNode, RuleInfo, Subscription, TrafficData

另有 `ProfileType` enum + `ConfigurationOverride` override 数据类。

## 构建命令

mihomo 通过 git submodule 引入 Mishka fork（`mihomo/`，branch `Mishka`）。Gradle 按 ABI 自动驱动 Go 构建任务（当前仅 arm64-v8a），产物落到 `app/src/main/jniLibs/<ABI>/`：

```bash
# 首次 clone 后或拉新 commit 后同步 submodule
git submodule update --init --recursive

# 构建 APK（assemble 自动触发 buildMihomo / CMake；downloadGeoFiles 需手动/CI 跑一次）
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease

# 快速验证 Kotlin（跳过 Go native，秒级）：
./gradlew :app:compileDebugKotlin -x buildMihomo_arm64_v8a
```

单模块 `:app`。改 Kotlin 优先用 `:app:compileDebugKotlin -x buildMihomo_arm64_v8a` 快速验证（跳过 Go cgo）；只有验证 native/打包才跑完整 `:app:assembleDebug`（Go cgo ~分钟级）。`scripta` 复合构建首次 fresh config 时若报插件解析错，见「技术栈」节 scripta 复合构建说明（其插件由 scripta 自己的 `pluginManagement` 解析）。

Go 构建任务（[GoBuildTask](buildSrc/src/main/kotlin/GoBuildTask.kt)）：

- `buildMihomo_arm64_v8a` → `libmihomo.so`（cgo c-shared，单一统一库 ~56MB）。CGO_ENABLED=1，需 NDK clang（从 `androidComponents.sdkComponents.ndkDirectory` 读）。ldflags 注入 `-Wl,-soname,libmihomo.so` 让消费方 DT_NEEDED 不烙构建路径

CMake 任务自动 `dependsOn(buildMihomo)`，CMake 产出两个轻量 native 文件链 libmihomo.so（IMPORTED + IMPORTED_SONAME）：

- `libmihomo_runner.so` (~6KB)：PIE 可执行 wrapper（[mihomo_wrapper.c](app/src/main/cpp/mihomo_wrapper.c)），dlopen 同目录 libmihomo.so + dlsym `mihomoEntry` + 透传 argv；MihomoRunner fork+exec 它启 runtime
- `libmishka_jni.so` (~6KB)：薄 JNI 桥（[mishka_jni.c](app/src/main/cpp/mishka_jni.c)），翻译 Kotlin `MishkaCoreBridge.nativeXxx` 到 libmihomo 的 C ABI

手动构建只在调试 native 出错时需要；版本字符串由 `gradle.properties` 的 `mihomo.version` 注入。

## 关键架构约束

不读代码看不出来的约束。违反会直接踩坑。

**启动校验单点**：所有"启动代理"路径必须经过 [ProxyServiceController.start / restart](app/src/main/kotlin/top/yukonga/mishka/platform/ProxyServiceController.kt)。`resolveStartSubscriptionId()` 统一校验 active 订阅 + `imported/{uuid}/config.yaml` 落盘，失败时一次完成：toast 提示用户、`ProxyServiceBridge.updateState(Error)`、清 `SERVICE_WAS_RUNNING`、在 Running 时发 STOP 让状态自洽。`HomeUiState.errorMessage` 当前未在 UI 展示，因此 Toast 必要。新增入口（Wear OS / shortcut / 自动化）严禁绕过 controller 直接 `startService(MishkaTunService/MishkaRootService)`；Service 内 `ProfileFileOps.hasValidConfig` 是兜底防御层，针对 ADB / 第三方 Intent 直拉 Service。Tile / 通知等无 Activity 上下文要在 VPN 模式启动时弹系统授权对话框，必须经 [VpnPermissionActivity](app/src/main/kotlin/top/yukonga/mishka/service/VpnPermissionActivity.kt) 透明跳板（`VpnService.prepare()` 要求 Activity context，TileService 不是 Activity）；UI 路径仍走 MainActivity 自身处理。错误文案直接 `context.getString(R.string.error_no_active_profile)`（单模块，R 类同包可直接引用）。

**Ktor HttpClient 所有权**：禁止任何模块直接 `MihomoApiClient(...)` / `MihomoWebSocket(...)`；统一从 `MishkaApplication.instance.connectionManager.repository: StateFlow<MihomoRepository?>` 订阅。`MihomoConnectionManager` 是唯一持有 `close()` 责任的方，按 `ProxyServiceBridge.state` 自动 connect/disconnect、原子 close 旧 + new 新——不做 endpoint 比对（attach 重连多一次重建 < 50ms，胜过状态机比对出 race 的代价）。新增消费方仅 collect repository 即可；ViewModel 的 `setRepository(repo)` 仅做信号传递，不承担 close 责任。例外：`SubscriptionProxyResolver` 因 mixed-port 探测场景独立于 mihomo 实时连接，可自建短生命周期 HttpClient，但必须 `client.use{}` 或 try/finally close。订阅 fetch 走 JNI in-process（[MishkaCoreBridge.fetchAndValid](app/src/main/kotlin/top/yukonga/mishka/data/bridge/MishkaCoreBridge.kt)），不经 Ktor。

**ViewModel `setRepository` 必须 cancel 旧拉取协程**：mihomo 重启 / 切换订阅时 `MihomoConnectionManager` 会 close 旧 client 并 emit 新 repo，消费方 ViewModel 的 `setRepository(repo)` 必须先 `loadJob?.cancel()` 再切字段；HTTP 一次性拉取协程（getProxies / getProviders 等）启动时把 `viewModelScope.launch { ... }` 赋给 `loadJob`，进入协程后用 `if (repository !== repo) return@launch` 双保险——Ktor `client.close()` 让 in-flight 请求抛异常**但不会取消协程**，旧响应的 onSuccess 仍可能跑到末尾把 `_uiState` 写成旧订阅的数据，覆盖新 client 已经写入的新订阅数据。流式 Flow（trafficFlow / logsFlow / connectionsFlow）天然随 client.close 终止 collect，但仍需 `Job?.cancel()` 保证下一次新建不重叠。[ProxyViewModel](app/src/main/kotlin/top/yukonga/mishka/viewmodel/ProxyViewModel.kt) / [ProviderViewModel](app/src/main/kotlin/top/yukonga/mishka/viewmodel/ProviderViewModel.kt) / [LogViewModel](app/src/main/kotlin/top/yukonga/mishka/viewmodel/LogViewModel.kt) / [ConnectionViewModel](app/src/main/kotlin/top/yukonga/mishka/viewmodel/ConnectionViewModel.kt) 均按此模式。**为什么必要**：切换订阅后用户在 ProxyScreen 看到旧订阅代理组的回归就是这条 race——`setActive` 同步写已正确（commit 901b3c4），下游 ViewModel 协程没 cancel 才是漏洞。

**Override 注入**：所有 override 走 `--override-json` CLI flag + JSON 文件，Kotlin 侧零 YAML 改写。用户设置 `OverrideJsonStore.update { ... }` → `override.user.json`，启动时 `RuntimeOverrideBuilder` 叠加 TUN fd / AppProxy / rootMode → `override.run.json`。`secret` / `external-controller` 走 `--secret` / `--ext-ctl` CLI flag 不进 JSON。

**RuntimeOverrideBuilder 默认注入**（用户未显式设置时生效）：`tcp-concurrent=true`（代理并发拨号降低首包延迟）、`find-process-mode=off`（分应用已由 sing-tun `include/exclude-package` / VpnService / iptables uid-owner 处理，mihomo 运行期遍历 `/proc` 纯冗余）。ROOT TUN 分支额外默认 `tun.mtu=9000 + gso=true + gso-max-size=65535`（大包聚合减少 sing-tun read syscall），由 `StorageKeys.ROOT_TUN_JUMBO_MTU` 开关（默认 true）控制；关闭时回退 `mtu=1500 + gso=false`，兼容上游链路拒绝大包的极端 ROM。VPN 模式不注入 MTU/GSO（MTU 由 `VpnService.Builder` 系统管）。mixed-port 决策按以下优先级：① 用户 override 显式设置 → 用用户值；② 订阅 yaml 自带 `mixed-port` → 不注入，mihomo 沿用订阅原值；③ `StorageKeys.SUBSCRIPTION_UPDATE_VIA_PROXY` 启用 → 注入 7890 兜底，确保 `SubscriptionProxyResolver` 能稳定解析到代理端口；④ 其余情况不注入。订阅 yaml 的 mixed-port 由 `ConfigGenerator.readSubscriptionMixedPort` 行级扫描得出，避免兜底值意外覆盖订阅自带的非默认端口。用户 `override.user.json` 里的同名字段优先级最高。

**硬编码覆盖订阅（按 submode 分）**：`profile.store-selected=false` / `profile.store-fake-ip=true` 三模式共用。

- **VPN**：`tun.enable=true` + `tun.file-descriptor=fd` + `tun.dns-hijack=[0.0.0.0:53]`，`auto-route=false`；透传 `tun.stack`、`tun.device`
- **ROOT TUN**：`tun.enable=true` + `auto-route=true` + `auto-detect-interface=true` + `iproute2-table-index=2022` + `iproute2-rule-index=9000` + `tun.dns-hijack=[0.0.0.0:53]` + `include/exclude-package`（按 AppProxyMode）+ `route-exclude-address`（私网 + 组播 + 保留段，复用 `IptablesIntranet.V4`，IPv6 开启时叠加 `.V6`）。**必须注入**：sing-tun `auto_route` 在 `RouteExcludeAddress` 为空时铺满整个 `0.0.0.0/0`，会把 LAN 单播 + 224/4 组播一起吸进 TUN，破坏同 LAN 设备发现 / P2P 直连（如妙享桌面投屏）；VPN 由 `bypass_private_route` 路由表分流、ROOT TPROXY 由 iptables intranet RETURN 处理，唯独 ROOT TUN 缺这层放行 → 三模式只有它坏。复用 `IptablesIntranet` 让两个 ROOT 模式 LAN 放行语义一致；用户 `TunOverride.route-exclude-address` 显式设置时优先
- **ROOT TPROXY**：`tun.enable=false`、`tproxy-port=7895`、`dns.listen=0.0.0.0:1053`；**不写** `routing-mark`（Android Netd 冲突，见下）、**不写** `include/exclude-package`（AppProxy 走 iptables uid-owner）；`dns.enhanced-mode` 透传用户设置

**ROOT TPROXY 的 IPv6 注入开关**：`RootTproxyApplier.apply` 接受 `ipv6Enabled` 参数，由 `MishkaRootService.applyTproxyRules` 读 `StorageKeys.VPN_ALLOW_IPV6` 决定（与 VPN/ROOT TUN 的 `inet6-address` 完全同一开关）。默认 false：跳过所有 ip6tables / `ip -6` 规则注入，IPv6 出站走内核原生主路由表（设备有 native IPv6 时直连，无时 ENETUNREACH 让 App 快速放弃）。开启 true 才装 v6 mangle/nat 规则。teardown 永远尝试清 v4+v6（`2>/dev/null` 幂等），保证开关切换路径无残留。**为什么必要**：mihomo 默认 `ipv6: false` 时无法 dial IPv6 目的，TPROXY 无差别拦截 IPv6 → mihomo accept → 拨号 "ip version error" → App 重试，会形成 600 conn/s 紧密循环（实测 95s 产生 56k 失败 + 25MB 日志），mihomo 进程内存随连接跟踪表 + 日志 buffer + GC 压力快速增长。VPN/ROOT TUN 由 `inet6-address` 控制 TUN 是否注册 v6 默认路由，关闭时内核直接 ENETUNREACH，本身就有这层过滤；TPROXY 的 iptables 拦截不区分应用是否能用 v6，必须显式门控。

**secret 优先级**：用户设置 > 订阅 `config.yaml` 顶层 `secret:`（`ConfigGenerator.readSubscriptionSecret` 轻量行扫描）> 随机 UUID 前 16 字节；ROOT attach 分支走 storage 持久化的 `existingSecret`

**`/proxies` 不含 provider 节点**：mihomo 的 `GET /proxies` 与 `findProxyByName` 中间件（`/proxies/{name}`、`/proxies/{name}/delay`）只覆盖 runtime proxies（`proxies:` 段节点 + 代理组），proxy-provider 节点在这套命名空间里**查不到 / 404**。消费方规则：节点详情（type / 延迟 history）需把 `/providers/proxies` 各 provider 的 `proxies` 合并进 `/proxies` 结果（runtime 优先补缺，[ProxyViewModel.loadProxies](app/src/main/kotlin/top/yukonga/mishka/viewmodel/ProxyViewModel.kt) 维护 `nodeProviderMap`）；provider 节点的单节点测速走 `GET /providers/proxies/{provider}/{node}/healthcheck`（`MihomoApiClient.getProviderProxyDelay`），组测速 `/group/{name}/delay` 与组选择 `PUT /proxies/{group}` 不受影响。仅 provider 型（模板）订阅命中此约束，常规订阅节点本就在 runtime 命名空间。

**CMFA embed mode 禁 HTTP 配置 API**：`PATCH/PUT /configs` / `POST /restart` / `POST /configs/geo` / `PUT/PATCH /rules` / `POST /upgrade` 全部 404。**绝不添加** `patchConfig`/`restart` 方法，所有配置修改走 `OverrideJsonStore.update { ... }` + `serviceController.restart()`，UI 用 `RestartRequiredHint` Card 提示。

**订阅导入走 JNI in-process**：fetch + provider prefetch + Parse 三步走 [MishkaCoreBridge.fetchAndValid](app/src/main/kotlin/top/yukonga/mishka/data/bridge/MishkaCoreBridge.kt)，禁止再起 mihomo 子进程做这些事。`MishkaApplication.onCreate` 必须先 `extractGeoFiles()` 再 `MishkaCoreBridge.init(geodataDir, userAgent)`——后者 `constant.SetHomeDir` 必须指向已就位的 GeoIP 目录。runtime 仍是 subprocess 路径，与 JNI 路径互不干扰

**JNI 库加载顺序**：`libmishka_jni.so` 链接依赖 libmihomo.so 的导出符号，`System.loadLibrary("mihomo")` 必须先于 `loadLibrary("mishka_jni")`，否则 jni 库找不到符号

**libmihomo.so 必须显式设 SONAME**：cgo c-shared 默认不写 SONAME，消费方链接器会把构建期 .so 绝对路径烙进 DT_NEEDED，运行时 dlopen 报 `UnsatisfiedLinkError: library "..." not found`。两边对齐：[GoBuildTask](buildSrc/src/main/kotlin/GoBuildTask.kt) 给 ldflags 加 `-extldflags=-Wl,-soname,libmihomo.so`，CMake `IMPORTED_SONAME` 一致

**libmihomo_runner.so 是 PIE wrapper**：[mihomo_wrapper.c](app/src/main/cpp/mihomo_wrapper.c) 产出的 ~6KB 可执行，读 `/proc/self/exe` 推同目录 → dlopen libmihomo.so → dlsym `mihomoEntry` → 透传 argv。新加 CLI flag 必须同步注册到 mishka_core/runtime.go 的 `flag.NewFlagSet`，否则 fork+exec 时被 ExitOnError 拦截。`RootHelper.cleanupOrphanedMihomo` 按 `libmihomo_runner.so` cmdline 匹配孤儿进程

**cgo `*C.char` 必须 Go 侧释放**：libmihomo.so 通过 `//export` 返回的字符串内存属于 Go runtime，C 侧只能调 `mishkaFreeString()`，绝不能 `free()`，否则 cgo 堆损坏

**Mishka 自身包名必须绕过 TUN/VPN**：`ProcessBuilder` 子进程 HTTP 被代理捕获会永久阻塞；ROOT 三种 AppProxyMode 都把 `packageName` 从 include 剔除或塞进 exclude，VPN `AllowSelected` 分支先过滤 self 再 addAllowed，过滤后空列表退化到 `addDisallowedApplication(self)`。

**协程锁规则**：`kotlinx.coroutines.sync.Mutex` **不可重入**。`updateImported`/`commitPending`/`queryImported`/`queryPending` 被 `ProfileProcessor` 在 `withProfileLock { ... }` 内调用，**不能自己加** `profileLock.withLock`；`create`/`patch`/`release`/`clone`/`delete` 直接被 ViewModel 调用，**保留自身** `profileLock.withLock`。

**processing/ 单例目录必须进程级串行**：`processing/` 是进程内单例沙箱（`ProfileFileOps.getProcessingDir` 不带 uuid），`prepareProcessing` 每次 `deleteRecursively` 清空后从 `pending/{uuid}/` 重填、fetch 下载进 `processing/config.yaml`，`commitProcessingToImported(uuid)` 再拷回 `imported/{uuid}/`。因此 `ProfileProcessor.processLock` 必须是 **companion 进程级** `Mutex`（不能是实例级）——前台 `SubscriptionViewModel.processor` 与后台 `ProfileWorker.processor`（每个 `ACTION_UPDATE_PROFILE` 都 `scope.launch` 新建 repo+processor，可并发）是不同实例；锁若实例级，两个并发 update 会交错清空/复用同一 `processing/`，把 B 下载的 config 提交进 `imported/A/`，造成「界面显示订阅 A、点击启动实际运行订阅 B」的偶发 Bug（自动更新 A/B 间隔相近时后台并发触发，纯被动无需用户操作；临时解法「更新订阅→杀进程→重开→启动」之所以有效，是因为重新更新把 `imported/A/config.yaml` 重写回 A 的真实内容）。应用启动清理 `processing/` 残留也必须走 `ProfileProcessor.cleanupResidual(fileManager)`（持同一把进程级锁），不能直接 `ProfileFileOps.cleanupProcessing`，否则会擦掉后台正在进行更新的 `processing/` 内容。

**切换 active 订阅的重启决策走权威状态**：订阅页点选切换 active 后，`SubscriptionScreen.onActiveChanged` → `HomeViewModel.onActiveSubscriptionChanged()` 决定是否重启代理切到新订阅。**必须读 `serviceController.status`（ProxyServiceBridge）权威状态**，不能用 `uiState.isRunning`——后者是滞后 UI 标志，代理 Starting 窗口（启动后约 10s，`MihomoRunner.waitForReady` 轮询期）内仍为 false，切换会漏掉重启，导致「界面显示新订阅、代理仍跑旧订阅」。Starting/Stopping 过渡态先置 `pendingRestartOnRunning`，待 status 切到 Running 再 `restartProxy()`——避免在 Service 内与启动中的协程并发重启的竞态；Stopped/Error 时 `resetHotStates` 清挂起标志。`AppNavigation` 两处 onActiveChanged（二级页 entry + Pager tab）都走此方法。

**Pipeline 协程取消语义**：外层 `runProcess` 可取消，仅 commit 阶段包 `withContext(NonCancellable)` 保证文件 swap + DB 更新原子；catch 块 `cleanupProcessing` 也走 NonCancellable。`cancelCurrentUpdate` 先同步 `clearProgress()` 让 Dialog 立即消失，再 `currentJob?.cancel()` 让协程后台收尾。`MishkaCoreBridge.fetchAndValid` 内部 try/catch 任何 Throwable 时调 `nativeCancel(token)`，让 Go ctx 进入 Done，native 函数立即返回 `error: context canceled`（HTTP read 中的请求要等到下一次 ctx 检查，通常 <100ms）

**ROOT runtime/ 沙箱**：ROOT mihomo 工作目录是独立 `runtime/{uuid}/`（从 imported/ 复制），不碰 imported/。启停钩子：`startProxy` 新鲜启动前 `prepareRootRuntime`；stop/restart/进程监控三条死亡路径都在 `clearPersistedState` 之前 `cleanupRootRuntime`；attach 分支**不重建** runtime/。存量旧 root:root 遗孤由 `MishkaApplication` 后台线程一次性 `su chown -R $APP_UID imported/` 迁移（`StorageKeys.MIGRATION_ROOT_RECLAIM_DONE` 打标）

**订阅导入不自动切换活跃**：`addSubscription`/`addFromFile` 成功后**不**调 `setActive(sub.id)`；仅首次导入（`importedDao.count() == 1`）由 `commitProcessingToImported` 自动激活

**SubscriptionRepository 单例 + 订阅流量数据合并**：`SubscriptionRepositoryImpl` 由 Koin `dataModule` 以 `single` 提供（`single<SubscriptionRepository>{ get<SubscriptionRepositoryImpl>() }` 绑定接口），`SubscriptionViewModel` 与 `HomeViewModel` 经 Koin 注入同一实例（`ProfileWorker` 例外，后台调度独立进程构建临时实例）；禁止 ViewModel 内部 new 自建。订阅页（`SubscriptionScreen` 看 `subscription.total > 0`）与主页流量栏的数据语义必须**强一致**——`SubscriptionRepository.resolveProfile` 在 combine 内合并三层数据：`pending > live provider snapshot > imported DB`。`_liveProvider: MutableStateFlow<LiveProviderSnapshot?>` 携带 `subscriptionId` 做归属校验，避免用户切 active 不 restart 时把旧 active 的 runtime 数据归属到新 active。[HomeViewModel](app/src/main/kotlin/top/yukonga/mishka/viewmodel/HomeViewModel.kt) 是唯一 runtime producer（`refreshProviderTraffic` GET 快照 / `updateAllProviders` 逐 provider PUT 触发重新拉订阅后再 GET——mihomo 的 `subscriptionInfo` 仅在 provider 更新时刷新，纯 GET 读到的永远是旧快照）：连接成功、打开代理集合弹窗和手动刷新都从 mihomo `/providers/proxies` 拉取，`aggregateProviderInfo` 将所有 `subscriptionInfo.Total > 0` 的 provider Upload/Download/Total 求和、Expire 取最近非零，再以 `onLiveProviderInfo(uuid, info)` 推回 Repository。弹窗（`SubscriptionTrafficDialog`）按「代理集合」语义展示全部非 Compatible provider（名称/节点数/相对更新时间；无 userinfo 的仅隐藏流量面板不整卡过滤）。该请求必须先取消前一次，并同时捕获 `MihomoRepository` identity、连接时 active subscription UUID 与递增 request ID；响应返回后、每次写 UI 或推 live snapshot 前都重验三者。disconnect 或 active UUID 改变时必须 cancel + 清空 + 使旧 request ID 失效，严禁旧 client/旧订阅回写新页面；失败仅更新刷新错误状态，不能清空已确认的 live snapshot。`disconnectStreams` 时推 `(null, null)` 清空。订阅页只 collect `repository.subscriptions`、主页只 collect `activeSubscription`，两边自动看到同一份合并后的视图模型——杜绝「订阅页显示『未获取到流量信息』、主页显示聚合流量」的语义冲突。**为什么聚合**：mihomo `/providers/proxies` 的 `subscriptionInfo` 是 per-`proxy-provider` 解析 `subscription-userinfo` header 得来，多源 yaml 下用 `values.firstOrNull()` 会取到 Map 迭代顺序的随机 provider；聚合保证多源场景"总用量 + 最近过期"语义正确，单源退化为单值。**为什么 DB 仍然必要**：模板订阅（顶层 URL 无 userinfo header）DB.total=0 但 yaml 内 proxy-provider 各自有 header → live 覆盖；常规单源订阅（节点直接写 `proxies:`）mihomo providers 为空 → live=null → fallback DB；File 类型订阅两边都为 0 → UI 显示 "--"。三处触发 subscription 字段刷新：`ProxyState.Starting/Running` 状态切换、`connectToMihomo` 入口、`activeSubscription` Flow emit（用户切 active 或 Repository merge 新 live 后即时反映）。

**Active 订阅名缓存同步**：通知栏（`DynamicNotificationManager.startOrFallbackStatic`）启动时一次性读 storage `ACTIVE_PROFILE_NAME` snapshot，不订阅 DB Flow。`SubscriptionRepository` 在 `commitPending`（编辑/首次激活）与 `updateImported`（手动/自动更新拉到新 `profile-title`）末尾必须调 `syncActiveNameIfActive(uuid, name)`，否则编辑/更新的就是 active 订阅时通知栏标题会停在旧名，直到用户切换 active 才间接修复。辅助函数内部短路 active 检查 + 同名短路（避免周期性流量更新打断通知动画），仅当 uuid 是 active 且 name 实际变化时写 storage + `ProxyServiceBridge.requestNotificationRefresh()` 让 service 重读。`updateImported` 调用方还需在 `name != null && name != existing.name` 时才调，防止 `name = null` 的纯流量字段刷新触发冗余 emit。

**JNI fork+exec**：Android `ProcessBuilder` fork 后强制关闭非标准 fd（无论 O_CLOEXEC），VPN 模式必须用 JNI `fork()+exec()`（`process_helper.c`）保留 TUN fd 继承

**TUN init silent failure 兜底**：mihomo `ReCreateTun` 失败仅 log 不退出。检测规则：① `MishkaTunService` 清 O_CLOEXEC 失败必须视为致命（`closeTunFd` + `ProxyState.Error` + `stopSelf`）② `MihomoRunner.waitForReady` API ready 后 delay 500ms 扫日志匹配 `Start TUN listening error` / `configure tun interface` / `create NetworkUpdateMonitor`

**fd 模式 forwarderBindInterface 必须为 true**：upstream `e38aa82a "don't force bind interface when using fd for tun"` 在 Mishka VPN（gvisor stack + VpnService fd）下实测破坏 fd 路径流量——延迟测试通（mihomo 直接 dial 节点不经 fd），实际经 fd 流量不通。静态搜索 sing-tun 0.4.18 仅 `stack_system` 读这个标志、gvisor stack 不读，但实测推翻该结论，说明 mihomo 内部或 sing-tun 间接路径仍依赖此标志。Mishka fork 第 5 patch（`feat: keep forwarder bind interface for android fd tun`）保留 fd 模式下 `forwarderBindInterface = true` 的旧行为。每次 rebase 上游必须验证 [listener/sing_tun/server.go:160](D:/GitHub/mihomo/listener/sing_tun/server.go#L160) 的 `forwarderBindInterface = true` 仍然 active。

**VPN MTU 同步**：`VpnService.Builder.setMtu` 与 mihomo `cfg.Tun.MTU` 必须同值。sing-tun 在 fd 模式给 gvisor `fdbased.New` 用 `cfg.Tun.MTU` 设 endpoint 缓冲，0 时所有 read 失败 → 表象"延迟测试正常但流量不通"。两侧共用 [RuntimeOverrideBuilder.VPN_TUN_MTU](d:/GitHub/Mishka/app/src/main/kotlin/top/yukonga/mishka/service/RuntimeOverrideBuilder.kt) 常量，禁止任一边 hardcode。

**WebSocket 重连**：Ktor `for (frame in incoming)` graceful close 静默退出。`MihomoWebSocket.webSocketFlow` 自实现无限重连 + 指数退避（1s→30s）+ 20s 心跳；`CancellationException` 必须 rethrow；`connectionState: StateFlow<Boolean>` 粗粒度暴露

**startForeground 防御**：Tun/Root/ProfileWorker 的 onCreate 均 `try { startForeground() } catch(Exception)`。真实风险是 API 31+ `ForegroundServiceStartNotAllowedException` 和 API 34+ FGS type 异常（非 POST_NOTIFICATIONS 拒绝）。失败路径：Tun/Root 上报 `ProxyServiceBridge.Error` + `stopSelf()`；ProfileWorker 仅 `stopSelf()`。**不降级为普通 Service**

**ProfileWorker.jobs**：用 `ConcurrentLinkedQueue<Job>` + `while (true) { jobs.poll()?.join() ?: break }`（非 `mutableListOf` + `while(isActive) delay(1s)` 轮询；onStartCommand 主线程 + scope 协程 IO 跨线程访问需线程安全容器）

**孤儿 mihomo 清理**：`RootHelper.cleanupOrphanedMihomo(tunDevice)` 单次 su shell 完成 pkill + `ip link delete <tunDevice>`（防 sing-tun EEXIST）；device name 走 `escapeShellSingleQuoted` POSIX 转义。VPN 启动在 `hadRootPid || HAS_ROOT` 时触发，清 ROOT 持久化 key + 兜底 `cleanupAllRootRuntime`

**ROOT 模式重连校验**：`attachToExisting` 三重验证（`kill -0` 存活 + `/proc/$pid/cmdline` 含 libmihomo.so + stored secret 通过 `/configs` Bearer 鉴权 2xx）；订阅一致性由 `startProxy` 在 attach 前比对 persisted vs 请求 subscriptionId，不一致走 cleanup + 全新启动

**reopen 重连必须 attach-only + boot-session 门控**：app reopen（`MainActivity.onResume` → `ProxyServiceController.verifyAndSyncState`）在 ROOT 模式尝试重连仍存活的 mihomo 进程，走 `reattachRoot()` 发带 `EXTRA_ATTACH_ONLY=true` 的 START intent；`MishkaRootService.startProxy(attachOnly=true)` 在 attach 失败时**绝不回退全新启动**，而是清状态置 `Stopped` + `stopSelf`。**为什么**：`SERVICE_WAS_RUNNING` / `ROOT_MIHOMO_PID` 存在 SharedPreferences 跨设备重启保留，但重启会杀死 root mihomo 进程 → PID 过期；旧逻辑仅凭「PID 字符串非空」判定「进程仍活」→ 调 `start()` → attach 失败 → 全新启动，造成「未开启开机自启（BootReceiver disabled），重启后打开 app 却看到 ROOT 代理自动跑起来」。修复两层：① `verifyAndSyncState` 用 boot-session 标记 `ROOT_START_ELAPSED`（启动时 `SystemClock.elapsedRealtime()` 落盘，重启归零、单调递增）预门控——`now < startElapsed` ⇒ 期间重启过 ⇒ 进程必死 ⇒ 清 `SERVICE_WAS_RUNNING`+PID+ELAPSED、保持停止（省掉服务启动/通知闪烁）；② attach-only 作为权威兜底覆盖 boot-session 预门控漏判的边界（进程启动极早→重启→app 打开更晚）与「同 session 内进程崩溃」场景。**边界正确性**：`elapsedRealtime` 在同 session 内只增不减，预门控**永不误判**同 session 为重启，故不会破坏正常重连。**boot-start 例外**：BootReceiver 开机自启走 `start()`（全新启动，进程本就该在 boot 时启动），不受 attach-only 影响；boot-start 后 app 打开时 `now > startElapsed` ⇒ 非重启 ⇒ attach-only 命中 boot 启动的活进程重连成功。VPN 模式无此路径（reopen 只清标志、从不自动启动）

**ROOT 模式热点处置**：sing-tun `auto_route` 的 catch-all ip rule（priority 9002：`NOT iif lo lookup 2022`）不区分本机 vs 转发流量，热点客户端包 iif=wlan2/ap0 也命中被导进 TUN，但 mihomo 对非本机源 IP 处理不稳（黑洞/高丢包）。`RootTetherHijacker` 在 sing-tun 之前插队两种处置模式：

- **BYPASS（默认）**：`ip rule priority 8000/8002` 去程 + 回程均 action=`goto 9010`（sing-tun 自己的 nop marker，ruleStart+10）。去程越过 catch-all 后命中 Android 原生 iif forward rule（`iif <tether> lookup <upstream>`，priority ~21000）→ 走 wlan0/rmnet。回程 goto 过去命中 local_network/main 里的 `<subnet> dev <tether>` 连接路由。
- **PROXY**：内核态 TPROXY 透明代理——mihomo 启用 `tproxy-port: 7895` 入站监听（IP_TRANSPARENT socket），`iptables -t mangle -A PREROUTING -i <tether> -j mishka_tether` 把热点 TCP+UDP 劫持到 `--on-port 7895 --tproxy-mark 0x01000000/0x01000000`；`ip rule fwmark 0x01000000/0x01000000 lookup 2024 priority 7999` + `ip route add local default dev lo table 2024` 让带 mark 的包在 PREROUTING 里被判定为本机投递、命中 tproxy listener。**完全绕开 sing-tun userspace TCP stack**，延迟/吞吐接近 BYPASS。常量值（0x01000000 bit 24 / table 2024 / priority 7999）对齐 box_for_magisk 一类成熟 Magisk 模块的验证过的取值，避开 Android Netd 低 16 位 mark。`mishka_tether` chain 内部顺序：① `-m conntrack --ctstate INVALID -j DROP` 丢异常包；② [IptablesIntranet](app/src/main/kotlin/top/yukonga/mishka/service/IptablesIntranet.kt) v4/v6 CIDR `-j RETURN`（DNS 除外，让 mihomo 处理 fake-ip）；③ `-p tcp/udp -m socket -j mishka_tether_divert`（DIVERT 子 chain：ESTABLISHED 流仅打 fwmark + ACCEPT，跳过 TPROXY 重拦截，命中 fwmark 路由→`local default dev lo` 投递到已有 mihomo socket）；④ 新连接 `-j TPROXY` 到 mihomo 监听端口。apply 整体用 heredoc 单次 su 调用（~60 条命令），避免 per-cmd 风格 3-6s 累计 fork 开销。
- **PROXY 降级**：`xt_TPROXY` 内核模块不可用时（部分裁剪 ROM），退回到"ip rule 去程+回程对称 `lookup 2022`"——流量双向进 sing-tun，性能次于 TPROXY 但连接可用。`RootTetherHijacker.probeTproxySupport()` 在 `MishkaRootService.startProxy` 里 runtime 探测；结果驱动 `RuntimeOverrideBuilder.buildAndWriteForRun(tproxyForTether = ...)` 决定是否写 `tproxy-port`。
- **attach 路径约束**：mihomo 进程启动时 tproxy-port 是否监听已锁死；app 被杀期间用户若改过 tether mode，attach 上去规则会与 mihomo 实际状态错位。`StorageKeys.ROOT_TETHER_MODE_ACTIVE` 在 start 成功后写入当时的 mode 快照，`startProxy` attach 前比对 `ROOT_TETHER_MODE` 与 `ROOT_TETHER_MODE_ACTIVE`——不一致则拒绝 attach，走 fresh restart。
- **attach 条件 re-apply**：attach 成功后默认先调 `RootTetherHijacker.anyRulesPresent()` / `RootTproxyApplier.anyRulesPresent()` probe 锚点（优先按 xt_comment 前缀 `mishka:tether:` / `mishka:tproxy:` 扫 iptables，次选 chain 名，最后 priority 7999/8000），present 则 skip 重建；absent 才 re-apply。修复系统重启 / 与 box_for_magisk 共存被清残留场景。`ROOT_ATTACH_FORCE_REAPPLY=true` 强制 re-apply（诊断开关）。
- **接口识别（纯手填）**：用户在 RootSettingsScreen 的「热点接口名」多行 TextField 输入 CSV（`ROOT_TETHER_IFACES`，默认 `wlan1,wlan2`），编辑对话框提供「检测当前接口」扫描按钮辅助（[TetherInterfaceScanner](app/src/main/kotlin/top/yukonga/mishka/platform/TetherInterfaceScanner.kt) 走 `NetworkInterface.getNetworkInterfaces()` 列出 UP + 有 site-local IPv4 + 不在蜂窝/隧道黑名单的候选；勾选默认按 `isLikelyTetherInterface` 排除 `wlan0` 主 STA）。不做实时自动发现 / 系统回调订阅 —— 实践表明 `TETHER_STATE_CHANGED` extras 在 Android 11+ 不可靠（@hide），`NetworkInterface` 白名单 regex 又覆盖不全 OEM 命名，成本收益不划算。
- **xt_comment 标记**：所有 iptables 规则（RootTetherHijacker / RootTproxyApplier 内部）打 `-m comment --comment "mishka:tether:..."` 或 `"mishka:tproxy:..."` 前缀，用于 `anyRulesPresent` 精确区分 Mishka 规则与第三方模块残留；顶层 PREROUTING/OUTPUT jump 规则不打 comment（teardown 走 blind `-D` 需与旧版遗留无 comment 规则兼容，避免跨版本升级遗漏清理）。
- **xt_TPROXY UI 告警**：`StorageKeys.ROOT_TPROXY_KERNEL_CAPABLE` 存 probe 结果（`"true"`/`"false"`/`""`），仅 PROXY 或 ROOT TPROXY 路径写入；RootSettingsScreen 读此 key，`== "false"` 且当前模式会用到 TPROXY 时在顶部显示 `errorContainer` 配色的降级告警 Card，说明已退到兼容路径，让用户明确感知而非静默 fallback。
- **不能** 用 `lookup main`——Android main 表无 default route（default 分散在各 upstream 独立表）。
- 回程规则靠 `NetworkInterface.getByName(iface)` 读 InterfaceAddress + prefix length 算 CIDR（BYPASS / PROXY-fallback 都要）；接口未就绪（热点后开）会 WARN skip，用户需 restart 代理触发重新 apply。
- `ip rule add iif <name>` 对不存在接口内核按名注册，接口出现自动生效；`ip rule add to <subnet>` 则需 apply 时接口已有地址。
- 生命周期：startProxy（含 attach）后 apply；stop/restart/死亡三路径 teardown（NonCancellable）。`teardown()` 清 BYPASS+fallback 的 8000/8001/8002/8003 priority 规则 + TPROXY 路径的 mangle chain `mishka_tether` + `mishka_tether_divert` + fwmark rule (7999) + route table 2024，两类都跑保证任意前置状态都能清干净。teardown 末尾 `verifyClean()` 扫锚点，残留重试一轮，仍残留 Log.w 记录但不阻止 stop。TUN table/rule index 固定写入 `override.run.json`（2022/9000）锁定 goto 目标 = 9010。
- **不做本机 TPROXY**：sing-tun TUN 继续负责本机流量分应用代理 / DNS 劫持；全 TPROXY（对齐 box_for_magisk）需要重写 AppProxy（uid-owner）、DNS（nat REDIRECT）、fake-ip 交互、IPv6 DNS 防泄漏等一整条链，属独立重构范围。

**ROOT 模式不做动态通知**：[DynamicNotificationManager.startOrFallbackStatic](app/src/main/kotlin/top/yukonga/mishka/service/DynamicNotificationManager.kt) 内部判定 `tunMode != TunMode.Vpn` 时强制走静态分支，忽略用户的 `DYNAMIC_NOTIFICATION` 偏好；[SettingsScreen](app/src/main/kotlin/top/yukonga/mishka/ui/screen/settings/SettingsScreen.kt) 的开关在 ROOT 模式下 `enabled=false` + 副标题说明。原因：VPN 模式靠 `BIND_VPN_SERVICE` 隐式让进程进入 `BOUND_FOREGROUND_SERVICE` 状态自动保 CPU、不被 battery optimization 限制；ROOT 模式无任何系统 binding，Activity 进后台后整个 device 进 idle（CPU governor 降频 + idle C-state），1Hz `/traffic` WS 帧合并、`NotificationManager.notify()` 批处理，动态通知冻结。`PARTIAL_WAKE_LOCK` 实测被系统 DISABLED 救不了（它只阻 SoC suspend 不阻 CPU idle）；唯一根治是引导用户 `IGNORE_BATTERY_OPTIMIZATIONS`，权衡 UX 后选择「直接不支持」承认平台限制。**ROOT TUN 历史上看似工作**只是因为 mihomo 持续 `read(tun_fd)` 顺手撑住 device 不进 idle，不可靠且不一致——一并退化为静态。

**Flow.catch 是终结型操作**：`.catch` 捕获后流结束、不会重订阅。长生命周期 UI/通知 Flow 的瞬态异常（如 `NotificationManager.notify()` 偶发 `RemoteServiceException`）应包到 `collect` 内部用 `runCatching` 处理；`.catch` 只留给真正需要终结的失败。[DynamicNotificationManager](app/src/main/kotlin/top/yukonga/mishka/service/DynamicNotificationManager.kt) 历史曾因顶层 `.catch` 在 `notify()` 偶发抛错时让整条 trafficJob 永久死亡，回归过一次。

**日志列表按显示帧率发射**：[LogViewModel](app/src/main/kotlin/top/yukonga/mishka/viewmodel/LogViewModel.kt) 的 mihomo 日志 WS 流在日志风暴下可达数百行/秒（见 ROOT TPROXY 600 conn/s 教训）。`appendLog` **只写 buffer + 置 `logsDirty`，不 emit**；独立 `flushJob` 每 `FLUSH_INTERVAL_MS`(120ms) 才 `_logs.value = buffer.toPersistentList()`，把 `LogScreen` 重组 + 500 条 key diff 从「日志行速率」降到「显示帧率」。buffer/logsDirty 仅在 viewModelScope(Main) 访问无需锁。**禁止**改回每行 `_logs.value = buffer.toList()`。autoScroll 的 `LaunchedEffect` key 必须用 `logs.lastOrNull()?.id`（单调递增），**不能用 `logs.size`**——缓冲写满后 size 恒为 `MAX_LOGS`，跟随会永久停摆。

**错误兜底**：用户面向异常走 `Throwable.describe()`（`message ?: simpleName ?: "Unknown error"`），避免 Ktor `ConnectException()` 等无参异常漏到 UI 显示 "null"；`SubscriptionFetcher` 显式检查 `response.status.isSuccess` + 空 body 抛 typed `ImportError`

**后台卡片隐藏**：`StorageKeys.HIDE_TASK_CARD` 控制 Settings General 里的「隐藏最近任务卡片」开关；Android 侧由 `MainActivity` 在 onCreate 读取偏好并通过 `ActivityManager.AppTask.setExcludeFromRecents()` 应用，运行时切换经 `App -> AppNavigation -> SettingsScreen` 透传 nullable `onHideTaskCardChange` callback 即时生效。不要把 MainActivity 固定写成 manifest `android:excludeFromRecents="true"`，否则会失去用户可切换语义；App/屏幕 composable 暴露 callback、不直接调 Android API（保持 UI 可测）。当前实现依赖单 Activity task（`appTasks.firstOrNull()`）；若以后引入 document/multi-task 入口，必须改为按当前 `taskId` 匹配目标 AppTask。

**其他**：`Activity configChanges=uiMode` 防深浅色切换重建；预测性返回手势走 HiddenApiBypass 反射 `setEnableOnBackInvokedCallback`（Android 14+ 可选）；`network_security_config.xml` 全局 `cleartextTrafficPermitted=true`（订阅源常用 HTTP，对齐 CMFA UX；CMFA 因订阅 fetch 在 Go 侧绕过 Java 网络栈而无需此设置，Mishka 的 Ktor 走 OkHttp 必须显式放行）；`jniLibs.useLegacyPackaging = true` 确保 libmihomo.so 解压到 nativeLibraryDir

## UI 规范

- 所有 UI 组件使用 miuix；其组件内部已用 squircle 渲染圆角，直接用即可
- **自定义形状元素用 squircle modifier**：非 miuix 组件的手搓形状不用 `RoundedCornerShape` 的 clip/background，改用 `top.yukonga.miuix.kmp.squircle.*`（随 miuix-ui 传递；低版本/无 runtime shader 自动回退圆角）。按性能选：
  - **非点击纯色背景**（内容不溢出）→ `squircleBackground(color, radius)`：无 offscreen layer，**不要 clip**
  - **图片 / 必须裁剪的内容** → `squircleClip(radius)`：一个 offscreen layer
  - **可点击元素**（涟漪裁进圆角）→ `squircleSurface(color, radius)` + `.clickable{}`；条件可点击时按 `isSelectable` 退化为 `squircleBackground`
  - **3dp 小徽章保持 `clip(RoundedCornerShape(3.dp))`**：该尺寸肉眼无差异，不值多一个 GPU layer
- 返回按钮使用 MiuixIcons.Back
- 底栏图标：Sidebar / Tune / UploadCloud / Settings
- Badge：`clip(RoundedCornerShape(3.dp))` + 9.sp Bold Monospace
- 操作 IconButton：`minHeight/minWidth = 35.dp, backgroundColor = secondaryContainer`
- **页面骨架**：Scaffold + TopAppBar(scrollBehavior) + LazyColumn
  - LazyColumn 必须加 `.scrollEndHaptic().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection)`
  - `contentPadding` 仅设 top（`innerPadding.calculateTopPadding()`），不设 bottom
  - 首个 item 是 Card / 表单时加 `item { Spacer(Modifier.height(12.dp)) }`；SmallTitle / RestartRequiredHint 开头**不加**（SmallTitle 自带上边距）
  - 末尾统一 `item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }`
  - **二级页面签名禁止 `bottomPadding: Dp` 参数**——靠上述末尾 Spacer 自适应
  - **4 个 Pager Tab 例外**（Home/Proxy/Subscription/Settings）：外层 `MainPage` Scaffold 持有 `bottomBar`，必须接 `bottomPadding: Dp` 透传 outer `innerPadding.calculateBottomPadding()` 给 `contentPadding`
- **顶栏 / 底栏毛玻璃**：所有页面 Scaffold 用 `BlurredBar` 包裹 `TopAppBar` / `NavigationBar`；MainPage 与每个二级页各自一份 backdrop（嵌套 layerBackdrop OK，抓取相互独立）。模式：
  - 顶层 `val backdrop = rememberBlurBackdrop()`、`blurActive = backdrop != null`、bar 色 `if (blurActive) Color.Transparent else colorScheme.surface`
  - `topBar/bottomBar = { BlurredBar(backdrop, blurActive) { TopAppBar/NavigationBar(color = barColor) } }`
  - 内容区 LazyColumn 追加 `.then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)` 供 textureBlur 抓取
  - 搜索页（AppProxy/Connection）：BlurredBar 内套 `searchStatus.TopAppBarAnim(backgroundColor = 同 bar 色) { ... }`，搜索切换不挡毛玻璃
- **宽屏适配**：窗口宽 ≥ 600dp（`WideScreenMinWidth`，`rememberIsWideScreen()`，[WindowSize.kt](app/src/main/kotlin/top/yukonga/mishka/ui/util/WindowSize.kt)；用缩放前 `LocalPlatformDensity` 量宽，界面缩放不翻转外壳）时：
  - **导航**：[MainPage](app/src/main/kotlin/top/yukonga/mishka/ui/navigation/AppNavigation.kt) 把底部 `NavigationBar` 换成可展开收起的侧边 `NavigationRail`（`rememberNavigationRailState()`，默认收起）；手机/宽屏共用同一 `pagerContent` lambda。Home Tab 图标用 `MiuixIcons.Home`（不用 `Sidebar`，避免与 rail 展开钮撞脸）。inset：rail 吸收起始侧（`defaultWindowInsetsPadding=true`），内容区 `consumeWindowInsets(Start)` + `windowInsetsPadding(systemBars∪displayCutout .only(End))`，底部经 `bottomPadding` 透传各 Tab
  - **顶栏**：所有 `TopAppBar` 页面走 [AdaptiveTopAppBar](app/src/main/kotlin/top/yukonga/mishka/ui/component/AdaptiveTopAppBar.kt)——宽屏固定不折叠 `SmallTopAppBar`（纵向空间紧张）、手机大标题 `TopAppBar`。**AboutScreen 例外**（hero 视差固定 `SmallTopAppBar`，不套，否则手机端多出重复大标题）。搜索页保留 `searchStatus.TopAppBarAnim { AdaptiveTopAppBar(...) }`；其搜索框动态 top padding 宽屏**恒为 0**（`if (isWideScreen) 0.dp else 12.dp * (1f - collapsedFraction)`）
  - **内容居中**：4 个主 Tab 用 `WideContentBox { sidePadding -> LazyColumn(...) }`；**LazyColumn 保持全宽**（两侧无滚动死区），仅把 `sidePadding` 加进 `contentPadding` 的 start/end 把内容居中到 `MaxContentWidth=800dp`（与 600dp 外壳阈值是两个独立常量）。**是否居中复用 `rememberIsWideScreen()`**（外壳唯一权威）——独立比较阈值会在 densityScale≠1 时出现外壳与居中不一致。二级页宽屏仍全宽；手机路径行为不变。**不要**改回压缩 LazyColumn 节点宽度的 layout modifier（滚动死区）
  - **横屏屏幕缺口**：miuix `Scaffold` 不自动 padding 内容，二级页 `contentPadding` 只吃 top → 横屏侧边刘海/手势条会压到内容。**每个二级页根 LazyColumn** 在 `.fillMaxSize()` 后加 `Modifier.horizontalCutoutPadding()`（只补水平 `displayCutout ∪ navigationBars`，竖屏为 0）；顶栏由 `TopAppBar` 自身 inset 处理，不重叠。**AboutScreen 相反**：内容侧已自行处理缺口，只给其 `SmallTopAppBar(defaultWindowInsetsPadding=false)` 加该 modifier。4 个主 Tab 内容居中在缺口内侧，无需此项
- **Card 间距**：水平 12.dp，每项统一 `padding(horizontal = 12.dp).padding(bottom = 12.dp)`；不使用 `Arrangement.spacedBy`
- **多组件卡片拆为独立 lazy item（滚动性能）**：`LazyColumn` 里禁止 `item { Card { 多行 } }`——整卡一次性组合，行多时（settings 大卡、代理组数百节点）滚动/展开卡顿。改用 [GroupedCardItems](app/src/main/kotlin/top/yukonga/mishka/ui/component/GroupedCardItems.kt)：`groupedCardItems(keyPrefix, items = listOf(CardItem("k") { row() }, ...))` 每行独立 item，`CardSegment` 分角拼回视觉连续的 miuix 卡片，只组合可见段。settings 各屏、DnsQueryScreen 结果、ProxyScreen 节点网格已按此重构。要点：
  - **分角背景**：有圆角的首/末段 `squircleSurface`（fill+clip，**必须 clip**——否则段内 clickable 的方角涟漪溢出圆角）；中间段纯 `background`（无 offscreen layer 最省）。语义对齐 miuix `Card`（surfaceContainer + onSurfaceContainer + 16.dp 圆角；preference 自带内边距故段 `insidePadding=0`）
  - `outerBottomPadding` 按所替换 Card 的 bottom padding 传（6/12/0）；条件行用 `buildList { if (…) add(CardItem…) }`
  - `groupedCardItems` **不加 item 动画**（拆分是不可见的纯性能优化）；需要动画自行在 item 内 `Modifier.animateItem(...)`，**placement spec 不能设 null**——否则下方各组硬跳、无展开感
  - **ProxyScreen 特例**：展开状态从 item 内 `rememberSaveable` 上提到屏幕级 `SnapshotStateList`（节点行是顶层 lazy item，随展开动态增删，存 item 内会随销毁丢失）；组头段 + 每行 ≤2 节点段拼卡，排序/分行在内容 lambda 完成；组头与节点行都用 `Modifier.animateItem()` 替代 `AnimatedVisibility(expandVertically)`（一次性组合整组才是卡顿源）；组头底角随展开 `16.dp↔0.dp` 走 `animateDpAsState(tween(300))` 经 `CardSegment.bottomCornerRadius` 覆写——否则随 `isLast` 翻转瞬间圆↔方突变
  - **不适用**：纯静态文本卡（ExternalControl 提示卡、RootSettings 警告卡）与视差 + `textureBlur` 的 AboutScreen 保持单 `item { Card }`；AboutScreen 内容 Column 用 `heightIn(min = 视口高)` 而非 `fillParentMaxHeight()`——后者钉死恰好一屏，横屏矮视口下超出内容被裁且无法滚动
- **TextField 表单**：不包 Card，直接 `padding(horizontal = 12.dp).padding(bottom = 12.dp)`
- **Edit Dialog 按钮顺序**：`not_modified | cancel | confirm`（三按钮 weight(1f) + `spacedBy(8.dp)`），confirm 用 `ButtonDefaults.textButtonColorsPrimary()`
- **长内容 Dialog 滚动 + 按钮固定底部**：miuix `WindowDialog` 手机上不限 content 高度，过长会把底部按钮顶出屏。包 `Column(Modifier.heightIn(max = 500.dp))` 限高，滚动区 `Modifier.weight(1f, fill = false).verticalScroll(...)`（短内容自然收缩），按钮作为非加权子项固定底部。范例：[MetaSettingsScreen](app/src/main/kotlin/top/yukonga/mishka/ui/screen/settings/MetaSettingsScreen.kt) 的 Age 密钥对 Dialog
- **选项列表 Dialog**（入口行点击弹出、内含若干操作行）：`WindowDialog` + `insideMargin = DpSize(0.dp, 24.dp)`——水平 0 让 `ArrowPreference` 操作行全出血（涟漪铺满对话框整宽），行自带 `insideMargin = PaddingValues(horizontal = 24.dp, vertical = 12.dp)` 内缩；垂直 24 补内置 title 的顶距与底部留白（miuix 的 title/summary/content 全包在 insideMargin 内，内置 title 自身仅 `bottom 12dp`，垂直置 0 会贴顶）。操作行可带 `startAction` 图标（`Modifier.padding(end = 16.dp)`）；底部按钮补 `horizontal = 24.dp` 与行内容对齐。内容超长时与上一条「长内容 Dialog」模式**组合**使用（WebDAV Dialog 即两者叠加）。范例：[BackupRestoreScreen](app/src/main/kotlin/top/yukonga/mishka/ui/screen/settings/BackupRestoreScreen.kt) 的本地备份 / WebDAV Dialog
- **弹 Dialog 的入口行设 holdDownState**：`ArrowPreference(holdDownState = showXxxDialog, onClick = { showXxxDialog = true })`——对应 dialog 打开期间入口行保持按下态（MIUI 交互惯例）。仅适用于「入口行可见期间弹层存在」的场景；操作行先关闭自身再弹下一层（如恢复确认）时无按下态窗口，不设
- **用户反馈**：`platform.showToast(message, long = false)`——轻量操作结果提示
- **i18n**：所有用户字符串走 `stringResource(R.string.xxx)`（Composable）/ `context.getString(R.string.xxx)`（非 Composable），禁止硬编码；新增同时加 `res/values` + `res/values-zh-rCN`。key 命名 `{页面}_{描述}`，通用按钮 `common_` 前缀。日志消息英文，代码注释中文
- **语义色 token**：状态/延迟/按钮/错误色统一走 `ui.theme.StatusColors`（`runState`/`delay`/`actionButton`/`danger`/`healthy`/`warning`/`neutral`/`selectedNodeContainer`）。**禁止屏幕里散落 `Color(0xFF...)`**；合法颜色源仅 `MiuixTheme.colorScheme.*` 与 `StatusColors`
- **Flow 收集**：所有屏幕用 `collectAsStateWithLifecycle()`（`lifecycle-runtime-compose`），不用 compose runtime 的 `collectAsState`——后台时上游不再驱动重组
- **强跳过友好的状态形状**：UiState `data class` 必须 `@Immutable`；大集合字段（节点/连接/组列表）一律 `ImmutableList`/`ImmutableMap`（`.toPersistentList()`/`.toPersistentMap()`），避免 SSM 重组做全表结构性 `equals`
- **可复用组件 API**：`ui/component/*` 可复用 composable 第一可选参必须是 `modifier: Modifier = Modifier` 并应用到 root-most 节点；wrapper 透传到底层 miuix 组件
