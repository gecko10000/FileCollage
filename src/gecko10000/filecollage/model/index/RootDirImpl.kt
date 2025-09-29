package gecko10000.filecollage.model.index

import kotlinx.serialization.Serializable

@Serializable
data class RootDirImpl(
    override var name: String,
    override var permissions: Int,
    override var uid: Long,
    override var gid: Long,
    override var children: MutableMap<String, Node>,
    override var accessTime: Time,
    override var modificationTime: Time,
) : RootDir()
