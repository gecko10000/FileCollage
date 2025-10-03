package gecko10000.filecollage.dao.remote

import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.bot.settings.limiters.CommonLimiter
import dev.inmo.tgbotapi.extensions.api.files.downloadFile
import dev.inmo.tgbotapi.extensions.api.send.media.sendDocument
import dev.inmo.tgbotapi.requests.abstracts.InputFile
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
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
        log.debug("Worker {} downloading chunk {}", workerId, request.fileChunk.id)
        val bytes = bot.downloadFile(InputFile.fromId(request.fileChunk.remoteChunkId!!))
        request.response.complete(bytes)
    }

    private suspend fun performUpload(fileChunk: FileChunk, cachedChunk: CachedChunk): FileId {
        val bytes = if (cachedChunk.size == getMaxChunkSize()) cachedChunk.bytes
        else cachedChunk.bytes.copyOf(cachedChunk.size)
        val response = bot.sendDocument(
            config.telegramChatId,
            bytes.asMultipartFile(
                fileName = fileChunk.id.toString()
            )
        )
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
        log.info("Downloading {} at {} priority", downloadRequest.fileChunk.id, priority)
        downloadQueues.getValue(priority).add(downloadRequest)
        downloadChannel.send(Unit)
        return downloadRequest.response.await()
    }

    override suspend fun uploadFileChunk(fileChunk: FileChunk, cachedChunk: CachedChunk): FileId {
        val uploadRequest = UploadRequest(fileChunk, cachedChunk, CompletableDeferred())
        log.info("Uploading {}", fileChunk.id)
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
