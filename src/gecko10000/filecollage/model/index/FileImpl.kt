@file:UseSerializers(UUIDSerializer::class)

package gecko10000.filecollage.model.index

import gecko10000.filecollage.config.serializers.UUIDSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.util.*

@Serializable
data class FileImpl(
    override var id: UUID,
    override var name: String,
    override var permissions: Int,
    override var uid: Long,
    override var gid: Long,
    override var fileChunks: MutableList<FileChunk> = mutableListOf(),
    override var size: Long = 0,
) : File()
