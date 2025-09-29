package gecko10000.filecollage.model.index

import kotlinx.serialization.Serializable

/**
 * Represents a directory in our filesystem.
 */
@Serializable
sealed class Dir : Node() {
    abstract var children: MutableMap<String, Node>
}
