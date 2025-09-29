package gecko10000.filecollage.dao.remote

import gecko10000.filecollage.model.cache.CachedChunk
import gecko10000.filecollage.model.index.FileChunk

typealias FileId = String

interface RemoteDAO {

    suspend fun downloadFileChunk(fileChunk: FileChunk): ByteArray
    suspend fun uploadFileChunk(fileChunk: FileChunk, cachedChunk: CachedChunk): FileId

}
