@file:UseSerializers(UUIDSerializer::class)

package gecko10000.filecollage.model.index

import gecko10000.filecollage.config.serializers.UUIDSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.UseSerializers
import java.util.*

@Serializable
class FileChunk(
    val id: UUID,
    var remoteChunkId: String?,
    var size: Int,

    // Used in the cache
    @Transient var lastUse: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (other !is FileChunk) return false
        return this.id == other.id
    }

    override fun hashCode(): Int {
        return this.id.hashCode()
    }
}
