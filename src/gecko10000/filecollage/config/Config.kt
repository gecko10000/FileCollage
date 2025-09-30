@file:UseSerializers(PathSerializer::class)

package gecko10000.filecollage.config

import dev.inmo.tgbotapi.types.ChatIdentifier
import dev.inmo.tgbotapi.types.Username
import gecko10000.filecollage.config.serializers.PathSerializer
import gecko10000.filecollage.util.Constants
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.nio.file.Path
import kotlin.io.path.Path

@Serializable
data class Config(
    val token: String = "NO_TOKEN",
    val channelId: ChatIdentifier = Username("@username"),
    val indexFile: Path = Path(Constants.DEFAULT_DATA_FILE_NAME),
    val requestTimeoutMs: Long = 2 * 60 * 1000,
    val saveIntervalMs: Long = 1 * 60 * 1000, // for both cache and index
    val cacheSizeChunks: Int = 100, // 20 MB * 100 = 2 GB
    val preCacheChunkCount: Int = 3, // loads chunks ahead of time, before they've been read
    val simultaneousCacheEvictions: Int = 5,
)
