package gecko10000.filecollage.dao.remote

import dev.inmo.tgbotapi.bot.exceptions.CommonRequestException
import dev.inmo.tgbotapi.bot.exceptions.TooMuchRequestsException
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.bot.settings.limiters.CommonLimiter
import dev.inmo.tgbotapi.extensions.api.files.downloadFile
import dev.inmo.tgbotapi.extensions.api.send.media.sendDocument
import dev.inmo.tgbotapi.requests.abstracts.InputFile
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.content.DocumentContent
import gecko10000.filecollage.config.Config
import gecko10000.filecollage.model.cache.CachedChunk
import gecko10000.filecollage.model.cache.Priority
import gecko10000.filecollage.model.index.FileChunk
import gecko10000.filecollage.util.log
import gecko10000.telefuse.config.JsonConfigWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.ConcurrentLinkedQueue

class TelegramRemoteDAO : RemoteDAO, KoinComponent {

    companion object {
        private const val DOWNLOAD_ERROR = "wrong file_id or the file is temporarily unavailable"
        private const val UPLOAD_ERROR = "too Many Requests: retry after "
    }

    private val coroutineScope: CoroutineScope by inject()
    private val configFile: JsonConfigWrapper<Config> by inject()
    private val config: Config
        get() = configFile.value

    private val bot = telegramBot(config.telegramToken) {
        requestsLimiter = CommonLimiter()
    }

    private val downloadChannel = Channel<Unit>()
    private val downloadQueues: Map<Priority, ConcurrentLinkedQueue<DownloadRequest>> = run {
        val map = LinkedHashMap<Priority, ConcurrentLinkedQueue<DownloadRequest>>()
        for (priority in Priority.entries) {
            map[priority] = ConcurrentLinkedQueue()
        }
        return@run map
    }

    private val uploadChannel = Channel<UploadRequest>()

    private suspend fun performDownload(workerId: Int) {
        val request = downloadQueues.values.first { it.isNotEmpty() }.poll()
        log.info("Downloading {} ({})", request.fileChunk.id, workerId)
        var bytes: ByteArray? = null
        var attempts = 0
        val file = InputFile.fromId(request.fileChunk.remoteChunkId!!)
        val configuredAttempts = config.downloadRetries
        while (bytes == null && (configuredAttempts == -1 || attempts < configuredAttempts)) {
            bytes = runCatching { bot.downloadFile(file) }
                .onFailure { ex ->
                    val isRateLimit = ex is TooMuchRequestsException
                            || (ex as? CommonRequestException)?.response?.description?.contains(DOWNLOAD_ERROR) == true
                    if (!isRateLimit) throw ex
                    log.error("({}) Failed to download file {}, retrying.", ++attempts, request.fileChunk.id)
                }
                .getOrNull()
        }
        if (bytes == null) throw Exception("Couldn't download chunk after $configuredAttempts attempts.")
        request.response.complete(bytes)
    }

    private suspend fun performUpload(fileChunk: FileChunk, cachedChunk: CachedChunk): FileId {
        val bytes = if (cachedChunk.size == getMaxChunkSize()) cachedChunk.bytes
        else cachedChunk.bytes.copyOf(cachedChunk.size)
        log.info("Uploading {}", fileChunk.id)
        var response: ContentMessage<DocumentContent>? = null
        var attempts = 0
        val configuredAttempts = config.uploadRetries
        while (response == null && (configuredAttempts == -1 || attempts < configuredAttempts)) {
            response = runCatching {
                bot.sendDocument(
                    config.telegramChatId,
                    bytes.asMultipartFile(
                        fileName = fileChunk.id.toString()
                    )
                )
            }
                .onFailure { ex ->
                    val isRateLimit = ex is TooMuchRequestsException
                            || (ex as? CommonRequestException)?.response?.description?.contains(UPLOAD_ERROR) == true
                    if (!isRateLimit) throw ex
                    log.error("({}) Failed to upload file {}, retrying.", ++attempts, fileChunk.id)
                }
                .getOrNull()
        }
        if (response == null) throw Exception("Couldn't upload chunk after $configuredAttempts attempts.")
        return response.content.media.fileId.fileId
    }

    init {
        for (i in 1..config.telegramDownloadWorkers) {
            coroutineScope.launch {
                while (true) {
                    downloadChannel.receive()
                    performDownload(i)
                }
            }
        }
        for (i in 1..config.telegramUploadWorkers) {
            coroutineScope.launch {
                while (true) {
                    val uploadRequest = uploadChannel.receive()
                    val fileId = performUpload(uploadRequest.fileChunk, uploadRequest.cachedChunk)
                    uploadRequest.response.complete(fileId)
                }
            }
        }
    }


    override suspend fun downloadFileChunk(fileChunk: FileChunk, priority: Priority): ByteArray {
        val downloadRequest = DownloadRequest(fileChunk, CompletableDeferred())
        downloadQueues.getValue(priority).add(downloadRequest)
        downloadChannel.send(Unit)
        return downloadRequest.response.await()
    }

    override suspend fun uploadFileChunk(fileChunk: FileChunk, cachedChunk: CachedChunk): FileId {
        val uploadRequest = UploadRequest(fileChunk, cachedChunk, CompletableDeferred())
        uploadChannel.send(uploadRequest)
        return uploadRequest.response.await()
    }

    override fun getMaxChunkSize(): Int {
        return 20 * 1024 * 1024
    }

    private data class DownloadRequest(
        val fileChunk: FileChunk,
        val response: CompletableDeferred<ByteArray>,
    )

    private data class UploadRequest(
        val fileChunk: FileChunk,
        val cachedChunk: CachedChunk,
        val response: CompletableDeferred<FileId>,
    )

}
