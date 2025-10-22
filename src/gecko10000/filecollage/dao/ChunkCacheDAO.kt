package gecko10000.filecollage.dao

import gecko10000.filecollage.config.Config
import gecko10000.filecollage.dao.remote.FileId
import gecko10000.filecollage.dao.remote.RemoteDAO
import gecko10000.filecollage.model.cache.CachedChunk
import gecko10000.filecollage.model.cache.Priority
import gecko10000.filecollage.model.index.FileChunk
import gecko10000.filecollage.util.log
import gecko10000.telefuse.config.JsonConfigWrapper
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class ChunkCacheDAO : KoinComponent {

    private val remoteDAO: RemoteDAO by inject()
    private val coroutineScope: CoroutineScope by inject()
    private val configFile: JsonConfigWrapper<Config> by inject()
    private val config: Config
        get() = configFile.value

    private val cache: MutableMap<FileChunk, Deferred<CachedChunk>> = ConcurrentHashMap()

    // Possible TODO: figure out why this needs -1
    private val cacheSema = Semaphore(config.cacheHardLimitChunks - 1) // One per chunk in the cache

    private fun removeFromCache(fileChunk: FileChunk): Deferred<CachedChunk>? {
        //log.info("Removing {} cache size: {}, sema: {}", fileChunk.id, cache.size, cacheSema.availablePermits)
        val prev = cache.remove(fileChunk)
        if (prev != null) {
            cacheSema.release()
            coroutineScope.launch { evictOldestChunks() }
        }
        return prev
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun flush() {
        val iterator = cache.iterator()
        val tasks = mutableMapOf<FileChunk, Deferred<FileId>>()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            // Skip still-downloading ones.
            if (!entry.value.isCompleted) continue
            val cachedChunk = entry.value.getCompleted()
            if (!cachedChunk.dirty) continue
            cachedChunk.dirty = false
            // Upload all chunks asynchronously
            tasks += entry.key to coroutineScope.async {
                cachedChunk.uploading.set(true)
                val fileId = remoteDAO.uploadFileChunk(entry.key, cachedChunk)
                cachedChunk.uploading.set(false)
                return@async fileId
            }
        }
        for (task in tasks) {
            val fileId = task.value.await()
            task.key.remoteChunkId = fileId
            coroutineScope.launch {
                evictOldestChunks()
            }
        }
    }

    private fun findOldestChunks(): Map<FileChunk, Deferred<CachedChunk>> {
        val oldestEntries = PriorityQueue<Pair<FileChunk, Deferred<CachedChunk>>>(Comparator { e1, e2 ->
            (e2.first.lastUse - e1.first.lastUse).toInt()
        })
        for (entry in cache.entries) {
            oldestEntries.add(entry.key to entry.value)
            if (oldestEntries.size > config.simultaneousCacheEvictions) {
                oldestEntries.remove()
            }
        }
        val oldestChunks = mutableMapOf<FileChunk, Deferred<CachedChunk>>()
        for (entry in oldestEntries) {
            oldestChunks[entry.first] = entry.second
        }
        return oldestChunks
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun evictOldestChunk(chunkEntry: Map.Entry<FileChunk, Deferred<CachedChunk>>) {
        if (!chunkEntry.value.isCompleted) {
            removeFromCache(chunkEntry.key)
            return
        }
        val cachedChunk = chunkEntry.value.getCompleted()
        // chunk doesn't need to be saved.
        if (!cachedChunk.dirty) {
            // and doesn't need to be removed, as there's already a job for it.
            if (!cachedChunk.uploading.get()) {
                log.debug("Removing clean cached chunk {} because cache is full.", cachedChunk.id)
                removeFromCache(chunkEntry.key)
            }
            return
        }
        log.debug("Evicting dirty cached chunk {} because cache is full.", cachedChunk.id)
        cachedChunk.dirty = false // set it to be non-dirty temporarily so it's not uploaded over and over again
        val prev = cachedChunk.uploading.getAndSet(true)
        if (prev) return // already uploading
        val fileId = remoteDAO.uploadFileChunk(chunkEntry.key, cachedChunk)
        chunkEntry.key.remoteChunkId = fileId
        cachedChunk.uploading.set(false)
        if (!cachedChunk.dirty) {
            removeFromCache(chunkEntry.key)
        }
    }

    private suspend fun evictOldestChunks() {
        if (cache.size <= config.cacheSoftLimitChunks) return // cache has room.
        val oldest = findOldestChunks()
        val jobs = mutableListOf<Job>()
        for (entry in oldest.entries) {
            jobs += coroutineScope.launch { evictOldestChunk(entry) }
        }
        jobs.joinAll()
    }

    /**
     * Chunk has been updated and needs to be "touched" in the cache.
     * Also, possibly evicts a chunk.
     */
    suspend fun touchChunk(fileChunk: FileChunk, cachedChunk: CachedChunk) {
        fileChunk.lastUse = System.currentTimeMillis()
        val prev = cache.put(fileChunk, CompletableDeferred(cachedChunk))
        if (prev != null) return // we did not add a new value, no need to clean.
        coroutineScope.launch { evictOldestChunks() }
        cacheSema.acquire()
        coroutineScope.launch { evictOldestChunks() }
    }

    // Either get existing one (or await it),
    // or cache/get it. Blocking.
    suspend fun getChunk(fileChunk: FileChunk): CachedChunk {
        return getChunkAsync(fileChunk, Priority.HIGH).await()
    }

    // Returns the Deferred immediately
    fun getChunkAsync(fileChunk: FileChunk, priority: Priority): Deferred<CachedChunk> {
        val completableDeferred = CompletableDeferred<CachedChunk>()
        fileChunk.lastUse = System.currentTimeMillis()
        val prev = cache.putIfAbsent(fileChunk, completableDeferred)
        if (prev != null) {
            log.debug("Returning existing chunk future for {}", fileChunk.id)
            return prev
        }
        log.debug("Cache miss, retrieving data for {}.", fileChunk.id)
        coroutineScope.launch {
            launch {
                evictOldestChunks() // we added to cache, might need to evict some chunks.
            }
            cacheSema.acquire()
            launch { evictOldestChunks() }
            val cachedChunk: CachedChunk
            if (fileChunk.remoteChunkId == null) {
                cachedChunk = CachedChunk.empty(remoteDAO.getMaxChunkSize())
            } else {
                val bytes = remoteDAO.downloadFileChunk(fileChunk, priority)
                cachedChunk = CachedChunk.of(bytes, remoteDAO.getMaxChunkSize())
                log.debug("Downloaded chunk for {}", fileChunk.id)
            }
            touchChunk(fileChunk, cachedChunk)
            completableDeferred.complete(cachedChunk)
        }
        return completableDeferred
    }

    fun dropChunk(fileChunk: FileChunk) {
        val cachedChunk = cache.remove(fileChunk)
        if (cachedChunk != null) {
            cachedChunk.cancel()
            cacheSema.release()
        }
    }

}
