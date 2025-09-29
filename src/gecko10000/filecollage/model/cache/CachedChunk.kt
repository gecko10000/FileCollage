package gecko10000.filecollage.model.cache

import gecko10000.filecollage.util.Constants
import java.util.*

class CachedChunk(
    val id: UUID, // for testing
    var bytes: ByteArray,
    var size: Int,
    var dirty: Boolean,
    var uploading: Boolean,
) {
    companion object {
        fun empty(): CachedChunk {
            return CachedChunk(
                id = UUID.randomUUID(),
                ByteArray(Constants.CHUNK_MAX_SIZE),
                size = 0,
                dirty = false,
                uploading = false,
            )
        }

        // Resizes the byte array if necessary.
        fun of(bytes: ByteArray): CachedChunk {
            val originalSize = bytes.size
            val finalArray = if (originalSize != Constants.CHUNK_MAX_SIZE) {
                bytes.copyOf(Constants.CHUNK_MAX_SIZE)
            } else bytes
            return CachedChunk(
                id = UUID.randomUUID(),
                bytes = finalArray,
                size = originalSize,
                dirty = false,
                uploading = false,
            )
        }
    }
}
