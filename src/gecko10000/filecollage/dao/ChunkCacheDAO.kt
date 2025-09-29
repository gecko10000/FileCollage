package gecko10000.filecollage.dao

import gecko10000.filecollage.config.Config
import gecko10000.filecollage.dao.remote.FileId
import gecko10000.filecollage.dao.remote.RemoteDAO
import gecko10000.filecollage.model.cache.CachedChunk
import gecko10000.filecollage.model.index.FileChunk
import gecko10000.filecollage.util.log
import gecko10000.telefuse.config.JsonConfigWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.ConcurrentHashMap

class ChunkCacheDAO : KoinComponent {

    private val remoteDAO: RemoteDAO by inject()
    private val coroutineScope: CoroutineScope by inject()
    private val configFile: JsonConfigWrapper<Config> by inject()
    private val config: Config
        get() = configFile.value

    private val cache: MutableMap<FileChunk, CachedChunk> = ConcurrentHashMap()

    suspend fun flush() {
        val iterator = cache.iterator()
        val tasks = mutableMapOf<FileChunk, Deferred<FileId>>()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.value.dirty) continue
            entry.value.dirty = false
            // Upload all chunks asynchronously
            tasks += entry.key to coroutineScope.async {
                entry.value.uploading = true
                val fileId = remoteDAO.uploadFileChunk(entry.key, entry.value)
                entry.value.uploading = false
                return@async fileId
            }
        }
        for (task in tasks) {
            val fileId = task.value.await()
            task.key.remoteChunkId = fileId
        }
    }

    private fun findOldestChunk(): Map.Entry<FileChunk, CachedChunk> {
        return cache.entries.minBy { it.key.lastUse }
    }

    private fun evictOldestChunk() {
        val oldest = findOldestChunk()
        log.info("Evicting cached chunk {} because cache is full.", oldest.key.id)
        // chunk doesn't need to be saved.
        if (!oldest.value.dirty) {
            // and doesn't need to be removed, as there's already a job for it.
            if (!oldest.value.uploading) {
                cache.remove(oldest.key)
            }
            return
        }
        oldest.value.dirty = false // set it to be non-dirty temporarily so it's not uploaded over and over again
        coroutineScope.launch {
            oldest.value.uploading = true
            val fileId = remoteDAO.uploadFileChunk(oldest.key, oldest.value)
            oldest.value.uploading = false
            oldest.key.remoteChunkId = fileId
            cache.remove(oldest.key)
        }
    }


    /**
     * Chunk has been updated and needs to be "touched" in the cache.
     * Also possibly evicts a chunk.
     */
    fun touchChunk(fileChunk: FileChunk, cachedChunk: CachedChunk) {
        fileChunk.lastUse = System.currentTimeMillis()
        val prev = cache.put(fileChunk, cachedChunk)
        if (prev != null) return // we did not add a new value, no need to clean.
        if (cache.size <= config.cacheSizeChunks) return // cache has room.
        coroutineScope.launch { evictOldestChunk() }
    }

    suspend fun getChunk(fileChunk: FileChunk): CachedChunk {
        val existingChunk = cache[fileChunk]
        log.info("Got cached {} for {}", existingChunk?.id ?: "N/A", fileChunk.id)
        var cachedChunk = existingChunk
        if (cachedChunk == null) {
            if (fileChunk.remoteChunkId == null) {
                cachedChunk = CachedChunk.empty(remoteDAO.getMaxChunkSize())
            } else {
                val bytes = remoteDAO.downloadFileChunk(fileChunk)
                cachedChunk = CachedChunk.of(bytes, remoteDAO.getMaxChunkSize())
            }
        }
        touchChunk(fileChunk, cachedChunk)
        return cachedChunk
    }

    fun getChunkAsync(fileChunk: FileChunk) {
        coroutineScope.launch { getChunk(fileChunk) }
    }

}
