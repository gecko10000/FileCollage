package gecko10000.filecollage.dao

import gecko10000.filecollage.config.Config
import gecko10000.filecollage.dao.remote.FileId
import gecko10000.filecollage.dao.remote.RemoteDAO
import gecko10000.filecollage.model.cache.CachedChunk
import gecko10000.filecollage.model.index.FileChunk
import gecko10000.filecollage.util.log
import gecko10000.telefuse.config.JsonConfigWrapper
import kotlinx.coroutines.*
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

    suspend fun flush() {
        val iterator = cache.iterator()
        val tasks = mutableMapOf<FileChunk, Deferred<FileId>>()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val cachedChunk = entry.value.await()
            if (!cachedChunk.dirty) continue
            cachedChunk.dirty = false
            // Upload all chunks asynchronously
            tasks += entry.key to coroutineScope.async {
                cachedChunk.uploading = true
                val fileId = remoteDAO.uploadFileChunk(entry.key, cachedChunk)
                cachedChunk.uploading = false
                return@async fileId
            }
        }
        for (task in tasks) {
            val fileId = task.value.await()
            task.key.remoteChunkId = fileId
        }
    }

    //
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
        log.info("Evicting {}", chunkEntry.key.id)
        if (!chunkEntry.value.isCompleted) {
            cache.remove(chunkEntry.key)
            return
        }
        val cachedChunk = chunkEntry.value.getCompleted()
        // chunk doesn't need to be saved.
        if (!cachedChunk.dirty) {
            // and doesn't need to be removed, as there's already a job for it.
            if (!cachedChunk.uploading) {
                log.debug("Removing clean cached chunk {} because cache is full.", cachedChunk.id)
                cache.remove(chunkEntry.key)
            }
            return
        }
        log.debug("Evicting dirty cached chunk {} because cache is full.", cachedChunk.id)
        cachedChunk.dirty = false // set it to be non-dirty temporarily so it's not uploaded over and over again
        cachedChunk.uploading = true
        val fileId = remoteDAO.uploadFileChunk(chunkEntry.key, cachedChunk)
        chunkEntry.key.remoteChunkId = fileId
        cachedChunk.uploading = false
        if (!cachedChunk.dirty) {
            cache.remove(chunkEntry.key)
        }
    }

    private suspend fun evictOldestChunks() {
        if (cache.size <= config.cacheSizeChunks) return // cache has room.
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
    fun touchChunk(fileChunk: FileChunk, cachedChunk: CachedChunk) {
        fileChunk.lastUse = System.currentTimeMillis()
        val prev = cache.put(fileChunk, CompletableDeferred(cachedChunk))
        if (prev != null) return // we did not add a new value, no need to clean.
        coroutineScope.launch { evictOldestChunks() }
    }

    // Either get existing one (or await it),
    // or cache/get it. Blocking.
    suspend fun getChunk(fileChunk: FileChunk): CachedChunk {
        return getChunkAsync(fileChunk).await()
    }

    // Returns the Deferred immediately
    fun getChunkAsync(fileChunk: FileChunk): Deferred<CachedChunk> {
        val completableDeferred = CompletableDeferred<CachedChunk>()
        log.debug("{}", Exception().stackTraceToString())
        fileChunk.lastUse = System.currentTimeMillis()
        val prev = cache.putIfAbsent(fileChunk, completableDeferred)
        if (prev != null) {
            log.debug("Returning existing chunk future for {}", fileChunk.id)
            return prev
        } else {
            coroutineScope.launch { evictOldestChunks() } // we added to cache, might need to evict some chunks.
        }
        log.debug("Cache miss, retrieving data for {}.", fileChunk.id)
        coroutineScope.launch {
            val cachedChunk: CachedChunk
            if (fileChunk.remoteChunkId == null) {
                cachedChunk = CachedChunk.empty(remoteDAO.getMaxChunkSize())
            } else {
                log.info("Caching chunk {}", fileChunk.id)
                val bytes = remoteDAO.downloadFileChunk(fileChunk)
                cachedChunk = CachedChunk.of(bytes, remoteDAO.getMaxChunkSize())
                log.debug("Downloaded chunk for {}", fileChunk.id)
            }
            touchChunk(fileChunk, cachedChunk)
            completableDeferred.complete(cachedChunk)
        }
        return completableDeferred
    }

    fun dropChunk(fileChunk: FileChunk): Boolean {
        return cache.remove(fileChunk)?.cancel() != null
    }

}
