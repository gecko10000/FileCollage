package gecko10000.filecollage.model.index

import kotlinx.serialization.Serializable
import java.util.*


/**
 * Represents a file in our filesystem.
 */
@Serializable
sealed class File : Node() {
    abstract var id: UUID
    abstract var fileChunks: MutableList<FileChunk>
    abstract var size: Long
}
