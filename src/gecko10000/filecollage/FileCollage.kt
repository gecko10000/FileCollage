package gecko10000.filecollage

import gecko10000.filecollage.config.Config
import gecko10000.filecollage.di.modules.FileCollageModule
import gecko10000.filecollage.util.log
import gecko10000.telefuse.config.JsonConfigWrapper
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import java.nio.file.Path

class FileCollage(mountPoint: Path) : KoinComponent {

    private val fuseFS: FSImpl by inject()
    private val configFile: JsonConfigWrapper<Config> by inject()
    private val config: Config
        get() = configFile.value

    init {
        startKoin {
            modules(
                FileCollageModule(
                    mountPoint = mountPoint,
                )
            )
        }
        log.info("Mounting filesystem to {}", mountPoint)
        try {
            fuseFS.mount(mountPoint, true, false, config.mountOptions.toTypedArray())
        } finally {
            fuseFS.umount()
        }
    }

}
