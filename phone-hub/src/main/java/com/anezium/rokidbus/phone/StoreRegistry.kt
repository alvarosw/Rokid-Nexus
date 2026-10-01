package com.anezium.rokidbus.phone

import android.content.Context
import java.util.concurrent.Executor

/**
 * The Store's catalogue: the fork's registry layered over the upstream registry. Each feed keeps
 * its own cache, so a feed that cannot be reached is served from its last good copy.
 */
class StoreRegistry(
    private val fork: RegistryClient,
    private val upstream: RegistryClient,
    private val ioExecutor: Executor = RegistryClient.DEFAULT_IO_EXECUTOR,
    private val callbackExecutor: Executor = Executor(Runnable::run),
) {
    fun refresh(callback: (RegistryLoadResult) -> Unit) {
        ioExecutor.execute {
            val result = load()
            callbackExecutor.execute { callback(result) }
        }
    }

    /** Blocking seam for deterministic unit tests. UI callers use [refresh]. */
    fun load(): RegistryLoadResult {
        val forkResult = fork.load()
        val upstreamResult = upstream.load()
        val merged = RegistryMerge.merge(forkResult.snapshotOrNull(), upstreamResult.snapshotOrNull())
        if (merged != null) return RegistryLoadResult.Success(merged)
        return upstreamResult as? RegistryLoadResult.Failure ?: forkResult
    }

    /** Reads only the on-disk feeds. This never touches the network. */
    fun cachedSnapshot(): RegistrySnapshot? =
        RegistryMerge.merge(fork.cachedSnapshot(), upstream.cachedSnapshot())

    companion object {
        private const val FORK_CACHE_DIRECTORY = "store-registry-fork"

        // The upstream feed keeps the directory it used before the fork registry existed.
        private const val UPSTREAM_CACHE_DIRECTORY = "store-registry"

        fun create(context: Context): StoreRegistry = StoreRegistry(
            fork = RegistryClient.create(context, BuildConfig.FORK_REGISTRY_URL, FORK_CACHE_DIRECTORY),
            upstream = RegistryClient.create(context, BuildConfig.UPSTREAM_REGISTRY_URL, UPSTREAM_CACHE_DIRECTORY),
            callbackExecutor = RegistryClient.MAIN_THREAD_EXECUTOR,
        )
    }
}

internal object RegistryMerge {
    /**
     * The fork's entry wins for every plugin it lists, matched by registry id, descriptor id or
     * package; every other plugin comes from upstream. A fork that has never loaded contributes
     * nothing, so upstream stands alone — fork and upstream entries are never mixed for one plugin.
     */
    fun merge(fork: RegistrySnapshot?, upstream: RegistrySnapshot?): RegistrySnapshot? {
        if (fork == null && upstream == null) return null
        val forkPlugins = fork?.feed?.plugins.orEmpty()
        val claimedIds = forkPlugins.flatMapTo(hashSetOf()) { listOf(it.id, it.nexus.pluginId) }
        val claimedPackages = forkPlugins.mapTo(hashSetOf()) { it.artifact.packageName }
        val upstreamPlugins = upstream?.feed?.plugins.orEmpty().filter { plugin ->
            plugin.id !in claimedIds &&
                plugin.nexus.pluginId !in claimedIds &&
                plugin.artifact.packageName !in claimedPackages
        }
        val sources = listOfNotNull(fork, upstream)
        return RegistrySnapshot(
            feed = RegistryFeed(RegistryClient.SUPPORTED_VERSION, forkPlugins + upstreamPlugins),
            source = if (sources.any { it.source == RegistrySource.NETWORK }) {
                RegistrySource.NETWORK
            } else {
                RegistrySource.CACHE
            },
            etag = null,
            lastFetchEpochMillis = sources.maxOf(RegistrySnapshot::lastFetchEpochMillis),
        )
    }
}

private fun RegistryLoadResult.snapshotOrNull(): RegistrySnapshot? =
    (this as? RegistryLoadResult.Success)?.snapshot
