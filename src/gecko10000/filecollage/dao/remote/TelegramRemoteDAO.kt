package gecko10000.filecollage.dao.remote

import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.bot.settings.limiters.CommonLimiter
import dev.inmo.tgbotapi.extensions.api.files.downloadFile
import dev.inmo.tgbotapi.extensions.api.send.media.sendDocument
import dev.inmo.tgbotapi.requests.abstracts.InputFile
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import gecko10000.filecollage.config.Config
import gecko10000.filecollage.model.cache.CachedChunk
import gecko10000.filecollage.model.index.FileChunk
import gecko10000.telefuse.config.JsonConfigWrapper
import io.ktor.client.plugins.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class TelegramRemoteDAO : RemoteDAO, KoinComponent {

    private val configFile: JsonConfigWrapper<Config> by inject()
    private val config: Config
        get() = configFile.value

    private val bot = telegramBot(config.token) {
        requestsLimiter = CommonLimiter()
        client = client.config {
            install(HttpTimeout) {
                requestTimeoutMillis = config.requestTimeoutMs
            }
        }
    }

    override suspend fun downloadFileChunk(fileChunk: FileChunk): ByteArray {
        return bot.downloadFile(InputFile.fromId(fileChunk.remoteChunkId!!))
    }

    override suspend fun uploadFileChunk(fileChunk: FileChunk, cachedChunk: CachedChunk): FileId {
        val bytes = if (cachedChunk.size == getMaxChunkSize()) cachedChunk.bytes
        else cachedChunk.bytes.copyOf(cachedChunk.size)
        val response = bot.sendDocument(
            config.channelId,
            bytes.asMultipartFile(
                fileName = fileChunk.id.toString()
            )
        )
        return response.content.media.fileId.fileId
    }

    override fun getMaxChunkSize(): Int {
        return 20 * 1024 * 1024
    }

}
