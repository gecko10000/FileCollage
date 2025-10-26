package gecko10000.filecollage.util

import gecko10000.filecollage.dao.IndexFileDAO
import gecko10000.filecollage.model.index.Dir
import gecko10000.filecollage.model.index.File
import gecko10000.filecollage.model.index.Node
import gecko10000.filecollage.model.index.RootDir
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.system.exitProcess

class StartupNullChecker : KoinComponent {

    private val indexFileDAO: IndexFileDAO by inject()

    /**
     * Ensures all file chunks are non-null.
     * Returns the paths of broken files.
     */
    private fun verify(prefix: String, node: Node): List<String> {
        val nodePath: String
        if (node is RootDir) nodePath = ""
        else if (node is Dir && prefix.isEmpty()) nodePath = node.name
        else nodePath = prefix + "/" + node.name
        if (node is Dir) {
            return node.children.values.flatMap { verify(nodePath, it) }
        }
        if (node is File) {
            val isBroken = node.fileChunks.any { it.remoteChunkId == null }
            return if (isBroken) listOf(nodePath) else emptyList()
        }
        throw Exception("Unknown node type ${node.javaClass} ($node)")
    }

    fun checkBroken() {
        val brokenPaths = verify("", indexFileDAO.root)
        if (brokenPaths.isEmpty()) {
            log.info("Verified integrity of existing files.")
            return
        }
        for (path in brokenPaths) {
            log.error("File has null chunks: {}", path)
        }
        log.error("Remove or fix these files.")
        exitProcess(1)
    }

}
