package gecko10000.filecollage.dao

import gecko10000.filecollage.FSImpl
import gecko10000.filecollage.config.Config
import gecko10000.filecollage.model.index.RootDir
import gecko10000.filecollage.model.index.RootDirImpl
import gecko10000.filecollage.model.index.Time
import gecko10000.filecollage.util.log
import gecko10000.telefuse.config.JsonConfigWrapper
import jnr.posix.FileStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.nio.file.NoSuchFileException
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

class IndexFileDAO : KoinComponent {

    companion object {
        private val ALL_PERMS_DIR = "755".toInt(8)
        private val ALL_PERMS_FILE = "644".toInt(8)
    }

    private val json: Json by inject()
    private val fsImpl: FSImpl by inject()
    private val chunkCacheDAO: ChunkCacheDAO by inject()
    private val coroutineScope: CoroutineScope by inject()
    private val configFile: JsonConfigWrapper<Config> by inject()
    private val config: Config
        get() = configFile.value

    var root: RootDir = RootDirImpl(
        name = "/",
        permissions = ALL_PERMS_DIR or FileStat.S_IFDIR,
        uid = fsImpl.context.uid.longValue(),
        gid = fsImpl.context.gid.longValue(),
        children = mutableMapOf(),
        accessTime = Time.now(),
        modificationTime = Time.now(),
    )
        private set

    init {
        loadFromFile()
        coroutineScope.launch {
            while (true) {
                delay(config.saveIntervalMs)
                saveToFile()
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread {
            runBlocking { saveToFile() }
        })
    }


    fun loadFromFile() {
        try {
            val jsonString = String(config.indexFile.readBytes())
            root = json.decodeFromString<RootDir>(jsonString)
        } catch (_: NoSuchFileException) {
            log.warn("Index file {} not found, creating default.", config.indexFile)
            coroutineScope.launch { saveToFile() }
        }
    }

    suspend fun saveToFile() {
        log.info("Flushing chunk cache...")
        chunkCacheDAO.flush()
        log.info("Saving file index...")
        val jsonString = json.encodeToString(root)
        config.indexFile.writeBytes(jsonString.encodeToByteArray())
        log.info("State saved.")
    }

}
