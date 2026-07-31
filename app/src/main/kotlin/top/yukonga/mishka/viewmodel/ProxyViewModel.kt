package top.yukonga.mishka.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.database.SelectionDao
import top.yukonga.mishka.data.database.SelectionEntity
import top.yukonga.mishka.domain.repository.MihomoRepository
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys

@Immutable
data class ProxyGroupUi(
    val name: String = "",
    val type: String = "",
    val now: String = "",
    val all: ImmutableList<String> = persistentListOf(),
    val delays: ImmutableMap<String, Int> = persistentMapOf(),
    val nodeTypes: ImmutableMap<String, String> = persistentMapOf(),
    val icon: String = "",
)

@Immutable
data class ProxyUiState(
    val groups: ImmutableList<ProxyGroupUi> = persistentListOf(),
    val testingGroups: ImmutableSet<String> = persistentSetOf(),
    val testingNodes: ImmutableSet<String> = persistentSetOf(),
    val error: String = "",
    // mihomo 出站模式，小写；取不到配置时为空串
    val mode: String = "",
)

class ProxyViewModel(
    private val selectionDao: SelectionDao? = null,
    private val getActiveUuid: () -> String? = { null },
    private val storage: PlatformStorage? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProxyUiState())
    val uiState: StateFlow<ProxyUiState> = _uiState.asStateFlow()

    // 节点排序状态：sortKeyIndex * 2 + (if reverse 1 else 0)
    // 0/1=默认 升/降，2/3=名称 升/降，4/5=延迟 升/降
    private val _sortOption = MutableStateFlow(loadInitialSortOption())
    val sortOption: StateFlow<Int> = _sortOption.asStateFlow()

    // 节点每行 1 个铺满宽度，关闭时每行 2 个
    private val _singleColumn = MutableStateFlow(loadInitialSingleColumn())
    val singleColumn: StateFlow<Boolean> = _singleColumn.asStateFlow()

    // 纯展示偏好，与排序、单列一样在 UI 层应用，不重新拉数据
    private val _showGlobalGroup = MutableStateFlow(loadInitialShowGlobalGroup())
    val showGlobalGroup: StateFlow<Boolean> = _showGlobalGroup.asStateFlow()

    private var repository: MihomoRepository? = null

    // mihomo 重启切 client 时取消旧的 loadProxies 协程，防止其 HTTP 响应已读完但 UI 写回
    // 晚于新 client 的写入，把刚切走的旧订阅代理组覆盖回来
    private var loadJob: Job? = null

    // provider 节点名 → provider 名。/proxies 命名空间只含 runtime 节点，
    // provider 节点的延迟测试必须路由到 /providers/proxies/{provider}/{node}/healthcheck
    private var nodeProviderMap: Map<String, String> = emptyMap()

    fun updateSortOption(option: Int) {
        _sortOption.value = option
        storage?.putString(StorageKeys.PROXY_NODE_SORT_OPTION, option.toString())
    }

    private fun loadInitialSortOption(): Int =
        storage?.getString(StorageKeys.PROXY_NODE_SORT_OPTION, "0")?.toIntOrNull() ?: 0

    fun updateSingleColumn(enabled: Boolean) {
        _singleColumn.value = enabled
        storage?.putString(StorageKeys.PROXY_NODE_SINGLE_COLUMN, if (enabled) "true" else "false")
    }

    private fun loadInitialSingleColumn(): Boolean =
        storage?.getString(StorageKeys.PROXY_NODE_SINGLE_COLUMN, "false") == "true"

    fun updateShowGlobalGroup(enabled: Boolean) {
        _showGlobalGroup.value = enabled
        storage?.putString(StorageKeys.PROXY_SHOW_GLOBAL_GROUP, if (enabled) "true" else "false")
    }

    private fun loadInitialShowGlobalGroup(): Boolean =
        storage?.getString(StorageKeys.PROXY_SHOW_GLOBAL_GROUP, "true") != "false"

    fun setRepository(repo: MihomoRepository?) {
        loadJob?.cancel()
        repository = repo
        if (repo != null) {
            loadProxies()
        } else {
            nodeProviderMap = emptyMap()
            _uiState.value = ProxyUiState()
        }
    }

    fun loadProxies() {
        val repo = repository ?: return
        _uiState.value = _uiState.value.copy(error = "")

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // 四个请求彼此无依赖，串行会把切 Tab 的刷新延迟叠成四个 RTT
            val groupsDeferred = async { repo.getGroups() }
            val proxiesDeferred = async { repo.getProxies() }
            val providersDeferred = async { repo.getProviders() }
            val configDeferred = async { repo.getConfig() }
            val groupsResult = groupsDeferred.await()
            val proxiesResult = proxiesDeferred.await()
            val providersResult = providersDeferred.await()
            val mode = configDeferred.await().getOrNull()?.mode?.lowercase().orEmpty()
            // 协程被 cancel 后 HTTP 响应仍可能已读完，二次校验 repo identity 防止把旧 client
            // 的结果写到当前 repo 已切换后的 UI
            if (repository !== repo) return@launch

            groupsResult.onSuccess { groupsResponse ->
                // /proxies 只含 runtime 节点（proxies: 段 + 代理组），proxy-provider 节点
                // 的类型与延迟 history 要从 /providers/proxies 合并（runtime 优先补缺）
                val runtimeProxies = proxiesResult.getOrNull()?.proxies ?: emptyMap()
                val allProxies = runtimeProxies.toMutableMap()
                val providerOf = mutableMapOf<String, String>()
                providersResult.getOrNull()?.providers?.forEach { (providerName, provider) ->
                    provider.proxies.forEach { node ->
                        if (node.name !in runtimeProxies) {
                            allProxies.putIfAbsent(node.name, node)
                            providerOf.putIfAbsent(node.name, providerName)
                        }
                    }
                }
                nodeProviderMap = providerOf

                val globalGroup = groupsResponse.proxies.firstOrNull { it.name == GLOBAL_GROUP }
                val orderMap = globalGroup?.all
                    ?.mapIndexed { index, name -> name to index }
                    ?.toMap() ?: emptyMap()

                // GLOBAL 常驻列表：全局模式置顶（此时它是唯一生效的出口），其余模式沉底
                val orderedGroups = groupsResponse.proxies
                    .filter { it.name != GLOBAL_GROUP }
                    .sortedBy { orderMap[it.name] ?: Int.MAX_VALUE }
                val arrangedGroups = when {
                    globalGroup == null -> orderedGroups
                    mode == MODE_GLOBAL -> listOf(globalGroup) + orderedGroups
                    else -> orderedGroups + globalGroup
                }

                val groups = arrangedGroups
                    .map { node ->
                        val delays = mutableMapOf<String, Int>()
                        val nodeTypes = mutableMapOf<String, String>()
                        node.all.forEach { proxyName ->
                            val proxy = allProxies[proxyName]
                            val lastDelay = proxy?.history?.lastOrNull()?.delay
                            if (lastDelay != null && lastDelay > 0) {
                                delays[proxyName] = lastDelay
                            } else if (lastDelay == 0) {
                                // delay=0 表示 healthcheck 超时/失败，映射为 -1 让 UI 显示"超时"
                                delays[proxyName] = -1
                            } else if (proxy != null && proxy.now.isNotEmpty()) {
                                val nowProxy = allProxies[proxy.now]
                                val nowDelay = nowProxy?.history?.lastOrNull()?.delay
                                if (nowDelay != null && nowDelay > 0) {
                                    delays[proxyName] = nowDelay
                                } else if (nowDelay == 0) {
                                    delays[proxyName] = -1
                                }
                            }
                            if (proxy != null && proxy.type.isNotEmpty()) {
                                nodeTypes[proxyName] = proxy.type
                            }
                        }
                        ProxyGroupUi(
                            name = node.name,
                            type = node.type,
                            now = node.now,
                            all = node.all.toPersistentList(),
                            delays = delays.toPersistentMap(),
                            nodeTypes = nodeTypes.toPersistentMap(),
                            icon = node.icon,
                        )
                    }
                    .toPersistentList()
                _uiState.value = _uiState.value.copy(groups = groups, mode = mode)

                // 恢复已保存的代理组选择
                restoreSelections(repo, groups)
            }.onFailure {
                _uiState.value = _uiState.value.copy(error = "加载失败: ${it.message}")
            }
        }
    }

    fun selectProxy(group: String, proxy: String) {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.selectProxy(group, proxy).onSuccess {
                if (repository !== repo) return@onSuccess
                _uiState.value = _uiState.value.copy(
                    groups = _uiState.value.groups
                        .map { if (it.name == group) it.copy(now = proxy) else it }
                        .toPersistentList()
                )
                // 保存选择到数据库
                saveSelection(group, proxy)
            }
        }
    }

    fun testGroupDelay(group: String) {
        val repo = repository ?: return
        if (group in _uiState.value.testingGroups) return
        _uiState.value = _uiState.value.copy(
            testingGroups = (_uiState.value.testingGroups + group).toPersistentSet(),
        )

        viewModelScope.launch {
            // mihomo /group/{name}/delay 测全部节点延迟，结果会自然写入 history.delay
            repo.testGroupDelay(group)
            if (repository !== repo) return@launch
            loadProxies()
            _uiState.value = _uiState.value.copy(
                testingGroups = (_uiState.value.testingGroups - group).toPersistentSet(),
            )
        }
    }

    fun testNodeDelay(nodeName: String) {
        val repo = repository ?: return
        if (nodeName in _uiState.value.testingNodes) return
        _uiState.value = _uiState.value.copy(
            testingNodes = (_uiState.value.testingNodes + nodeName).toPersistentSet(),
        )

        viewModelScope.launch {
            // 延迟结果写入节点全局 history.delay，loadProxies 再从 history 读回并分发到
            // 所有引用该节点的组，保证跨组延迟一致；provider 节点走 provider healthcheck 端点
            val provider = nodeProviderMap[nodeName]
            if (provider != null) {
                repo.getProviderProxyDelay(provider, nodeName)
            } else {
                repo.getProxyDelay(nodeName)
            }
            if (repository !== repo) {
                _uiState.value = _uiState.value.copy(
                    testingNodes = (_uiState.value.testingNodes - nodeName).toPersistentSet(),
                )
                return@launch
            }
            loadProxies()
            _uiState.value = _uiState.value.copy(
                testingNodes = (_uiState.value.testingNodes - nodeName).toPersistentSet(),
            )
        }
    }

    private suspend fun saveSelection(group: String, proxy: String) {
        val uuid = getActiveUuid() ?: return
        val dao = selectionDao ?: return
        dao.insert(SelectionEntity(uuid = uuid, proxy = group, selected = proxy))
    }

    private suspend fun restoreSelections(repo: MihomoRepository, groups: ImmutableList<ProxyGroupUi>) {
        val uuid = getActiveUuid() ?: return
        val dao = selectionDao ?: return
        val selections = dao.queryByUUID(uuid)
        if (selections.isEmpty()) return

        val selectionMap = selections.associate { it.proxy to it.selected }
        val updatedGroups = groups.toMutableList()

        for ((index, group) in groups.withIndex()) {
            // 只恢复 Selector 类型的组
            if (group.type != "Selector") continue
            val saved = selectionMap[group.name] ?: continue
            if (saved == group.now) continue
            if (saved !in group.all) continue

            repo.selectProxy(group.name, saved).onSuccess {
                updatedGroups[index] = group.copy(now = saved)
            }
        }

        _uiState.value = _uiState.value.copy(groups = updatedGroups.toPersistentList())
    }

    companion object {
        const val GLOBAL_GROUP = "GLOBAL"
        const val MODE_GLOBAL = "global"
        const val MODE_DIRECT = "direct"
    }
}
