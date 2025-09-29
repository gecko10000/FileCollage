package gecko10000.filecollage

import gecko10000.filecollage.util.log
import kotlin.io.path.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        log.error("Usage: filecollage <mountpoint>")
        exitProcess(1)
    }
    val mountPointString = args[0]
    val mountPoint = Path(mountPointString)
    FileCollage(mountPoint)
}
