package gecko10000.filecollage.client

import gecko10000.filecollage.model.cache.CachedChunk
import gecko10000.filecollage.model.cache.Priority
import gecko10000.filecollage.model.index.FileChunk

typealias FileId = String

interface RemoteClient {

    suspend fun downloadFileChunk(fileChunk: FileChunk, priority: Priority): ByteArray
    suspend fun uploadFileChunk(fileChunk: FileChunk, cachedChunk: CachedChunk): FileId
    fun getMaxChunkSize(): Int
}
