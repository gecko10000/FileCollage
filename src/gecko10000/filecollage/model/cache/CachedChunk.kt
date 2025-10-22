package gecko10000.filecollage.model.cache

import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class CachedChunk(
    val id: UUID, // for testing
    var bytes: ByteArray,
    var size: Int,
    var dirty: Boolean,
    var uploading: AtomicBoolean,
) {
    companion object {
        fun empty(chunkSize: Int): CachedChunk {
            return CachedChunk(
                id = UUID.randomUUID(),
                ByteArray(chunkSize),
                size = 0,
                dirty = false,
                uploading = AtomicBoolean(false),
            )
        }

        // Resizes the byte array if necessary.
        fun of(bytes: ByteArray, chunkSize: Int): CachedChunk {
            val originalSize = bytes.size
            val finalArray = if (originalSize != chunkSize) {
                bytes.copyOf(chunkSize)
            } else bytes
            return CachedChunk(
                id = UUID.randomUUID(),
                bytes = finalArray,
                size = originalSize,
                dirty = false,
                uploading = AtomicBoolean(false),
            )
        }
    }
}
