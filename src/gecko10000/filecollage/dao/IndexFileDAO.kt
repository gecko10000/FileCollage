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
import sun.misc.Signal
import java.nio.file.NoSuchFileException
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.system.exitProcess

class IndexFileDAO : KoinComponent {

    companion object {
        private val ALL_PERMS_DIR = "755".toInt(8)
        private val ALL_PERMS_FILE = "644".toInt(8)
        private val SIGINT_THRESHOLD = 5
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

    private fun handleSigint() {
        var sigInts = 0
        Signal.handle(Signal("INT"), {
            if (sigInts++ >= SIGINT_THRESHOLD) {
                log.error("Quitting forcibly. State may be inconsistent.")
                exitProcess(1)
            }
            // Only flush and exit on first sigint.
            if (sigInts != 1) {
                log.warn(
                    "Not done cleaning up! Send SIGINT {} more times to quit forcibly.",
                    SIGINT_THRESHOLD - sigInts + 1
                )
                return@handle
            }
            log.info("Exiting with SIGINT")
            runBlocking { saveToFile() }
            exitProcess(0)
        })
    }

    init {
        loadFromFile()
        coroutineScope.launch {
            while (true) {
                delay(config.saveIntervalMs)
                saveToFile()
            }
        }
        handleSigint()
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
