package gecko10000.filecollage.model.index

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock

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

    @Transient
    private val lock: ReadWriteLock = ReentrantReadWriteLock()
}
