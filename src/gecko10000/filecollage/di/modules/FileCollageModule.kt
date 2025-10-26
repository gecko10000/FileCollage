package gecko10000.filecollage.di.modules

import gecko10000.filecollage.FSImpl
import gecko10000.filecollage.client.RemoteClient
import gecko10000.filecollage.client.TelegramRemoteClient
import gecko10000.filecollage.config.Config
import gecko10000.filecollage.dao.ChunkCacheDAO
import gecko10000.filecollage.dao.DirectoryDAO
import gecko10000.filecollage.dao.FileDAO
import gecko10000.filecollage.dao.IndexFileDAO
import gecko10000.filecollage.util.StartupNullChecker
import gecko10000.telefuse.config.JsonConfigWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.koin.core.qualifier.qualifier
import org.koin.dsl.module
import java.io.File
import java.nio.file.Path

object FileCollageModule {

    const val MOUNT_POINT_QUAL = "mountPoint"

    operator fun invoke(mountPoint: Path) = module {
        single(qualifier = qualifier(MOUNT_POINT_QUAL)) { mountPoint }
        single<Json> {
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }
        }
        single { FSImpl() }
        single(createdAtStart = true) { IndexFileDAO() }
        single { DirectoryDAO() }
        single { FileDAO() }

        single { ChunkCacheDAO() }
        single<RemoteClient> { TelegramRemoteClient() }
        single<JsonConfigWrapper<Config>>(createdAtStart = true) {
            JsonConfigWrapper(
                configDirectory = File("."),
                configName = "config.json",
                initialValue = Config(),
                serializer = Config.serializer(),
            )
        }
        single<CoroutineScope> { CoroutineScope(Dispatchers.IO) }
        single { StartupNullChecker() }
    }
}
