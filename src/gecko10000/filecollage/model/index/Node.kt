package gecko10000.filecollage.model.index

import kotlinx.serialization.Serializable

/**
 * Represents a node in our filesystem.
 * Data is serialized and stored locally.
 */
@Serializable
sealed class Node {
    abstract var name: String
    abstract var permissions: Int
    abstract var uid: Long
    abstract var gid: Long
    abstract var accessTime: Time
    abstract var modificationTime: Time
}
