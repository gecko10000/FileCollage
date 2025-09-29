package gecko10000.filecollage.dao

import gecko10000.filecollage.dao.remote.RemoteDAO
import gecko10000.filecollage.model.index.File
import gecko10000.filecollage.model.index.FileChunk
import gecko10000.filecollage.util.Constants
import gecko10000.filecollage.util.log
import jnr.ffi.Pointer
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class FileDAO : KoinComponent {

    private val remoteDAO: RemoteDAO by inject()
    private val chunkCacheDAO: ChunkCacheDAO by inject()

    /**
     * Gets the indices required by the operation.
     * Let's imagine a chunk size of 10.
     * If startByte is 0 and size is 10, we want 0 start and 1 endExcl.
     * If startByte is 0 and size is 11, we want 0 and 2.
     * If startByte is 1 and size is 10, we want 0 and 2.
     * If startByte is 9 and size is 1, we want 0 and 1.
     * If startByte is 9 and size is 11, we want 0 and 2.
     * If startByte is 9 and size is 12, we want 0 and 3.
     */
    private fun calculateChunkIndices(startByte: Long, size: Int): IntRange {
        val startIndex = startByte / Constants.CHUNK_MAX_SIZE
        val endIndexExclusive = ceil((startByte + size) / Constants.CHUNK_MAX_SIZE.toDouble()).toLong()
        return IntRange(startIndex.toInt(), (endIndexExclusive - 1).toInt())
    }

    fun writeBytes(file: File, buf: Pointer, size: Int, offset: Long): Int {
        log.debug("Writing {} bytes to {} at {}", size, file.name, offset)
        val affectedChunks = calculateChunkIndices(offset, size)
        val newFileSize = max(file.size, offset + size)
        file.size = newFileSize

        var bytesCovered = 0
        for (chunkIndex in affectedChunks) {
            // Step 1: calculate bounds in chunk

            // First chunk: start at `offset % chunkSize`
            // Other chunks: start at 0
            val chunkStartByte = if (affectedChunks.first == chunkIndex)
                (offset % Constants.CHUNK_MAX_SIZE).toInt()
            else 0
            // Last chunk: end at (offset + size) % chunkSize
            // Other chunks: end at chunkSize
            val chunkEndByte = if (affectedChunks.last == chunkIndex)
                ((offset + size - 1) % Constants.CHUNK_MAX_SIZE + 1).toInt()
            else Constants.CHUNK_MAX_SIZE

            val bytesToWrite = chunkEndByte - chunkStartByte

            log.debug("Start: {}, end: {}", chunkStartByte, chunkEndByte)
            // Step 2: create dummy LocalFileChunk if needed
            val isNewChunk = chunkIndex >= file.fileChunks.size
            if (isNewChunk) {
                file.fileChunks.add(
                    chunkIndex, FileChunk(UUID.randomUUID(), null, 0)
                )
            }

            // Step 3: fetch chunk if we don't have it
            val fileChunk = file.fileChunks[chunkIndex]
            val cachedChunk = runBlocking { chunkCacheDAO.getChunk(fileChunk) }

            // Step 4: expand the chunk if we need room
            val finalChunkSize = max(file.fileChunks[chunkIndex].size, chunkEndByte)
            if (fileChunk.size != finalChunkSize) {
                fileChunk.size = finalChunkSize
                cachedChunk.size = finalChunkSize
            }
            log.debug(
                "Writing {}, {}, {}, {}", bytesCovered, cachedChunk.bytes.size, chunkStartByte, bytesToWrite
            )
            // Step 5: write to our byte array
            buf.get(
                bytesCovered.toLong(),
                cachedChunk.bytes,
                chunkStartByte,
                bytesToWrite
            )
            cachedChunk.dirty = true
            chunkCacheDAO.touchChunk(fileChunk, cachedChunk)
            bytesCovered += bytesToWrite
        }
        return bytesCovered
    }

    fun readBytes(file: File, buf: Pointer, size: Int, offset: Long): Int {
        log.debug("Reading {} bytes from {} at {}", size, file.name, offset)
        val affectedChunks = calculateChunkIndices(offset, size)

        var bytesCovered = 0
        for (chunkIndex in affectedChunks) {
            // Step 1: calculate bounds in chunk
            // Same logic as above
            val chunkStartByte = if (affectedChunks.first == chunkIndex)
                (offset % Constants.CHUNK_MAX_SIZE).toInt()
            else 0
            val actualSize = min(size.toLong(), file.size - offset).toInt()
            val chunkEndByte = if (affectedChunks.last == chunkIndex)
                ((offset + actualSize - 1) % Constants.CHUNK_MAX_SIZE + 1).toInt()
            else Constants.CHUNK_MAX_SIZE
            val bytesToRead = chunkEndByte - chunkStartByte

            // Step 2: retrieve chunk if needed
            val fileChunk = file.fileChunks[chunkIndex]
            val cachedChunk = runBlocking { chunkCacheDAO.getChunk(fileChunk) }

            // Step 3: read from our byte array
            log.debug("Reading {}, {}, {}, {}", bytesCovered, cachedChunk.bytes.size, chunkStartByte, bytesToRead)
            buf.put(bytesCovered.toLong(), cachedChunk.bytes, chunkStartByte, bytesToRead)
            bytesCovered += bytesToRead
        }
        return bytesCovered
    }

}
