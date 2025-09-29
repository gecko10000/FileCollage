package gecko10000.filecollage.model.index

import kotlinx.serialization.Serializable


/**
 * Represents the root directory that holds all other nodes.
 * Also holds filesystem settings.
 */
@Serializable
sealed class RootDir : Dir()
