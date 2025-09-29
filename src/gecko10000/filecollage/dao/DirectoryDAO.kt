package gecko10000.filecollage.dao

import gecko10000.filecollage.model.index.Dir
import gecko10000.filecollage.model.index.File
import gecko10000.filecollage.model.index.Node
import gecko10000.filecollage.util.log
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Paths are assumed to be /dir or /dir/file
 */
class DirectoryDAO : KoinComponent {

    private val indexFileDAO: IndexFileDAO by inject()

    fun lookupParent(path: String): Dir? {
        require(path != "/") { "Cannot look up parent of the root" }
        val parentPath = path.substringBeforeLast('/')
        if (parentPath.isBlank()) {
            return indexFileDAO.root
        }
        val parentNode = lookupNode(parentPath)
        return parentNode as? Dir
    }

    fun lookupNode(path: String): Node? {
        return lookupNode(indexFileDAO.root, path)
    }

    private fun lookupNode(parent: Dir, path: String): Node? {
        log.debug("Looking up {} (parent: {})", path, parent.name)
        val pathWithoutSlash = path.substringAfter('/')
        if (pathWithoutSlash.isBlank()) return parent

        val nodeName = pathWithoutSlash.substringBefore('/')
        val foundNode = parent.children[nodeName] ?: return null
        val remainingPath = "/${pathWithoutSlash.substringAfter('/', missingDelimiterValue = "")}"
        if (foundNode is File) {
            log.debug("Returning {} (remainingPath: {})", foundNode.name, remainingPath)
            return if (remainingPath == "/") foundNode else null
        }
        return lookupNode(foundNode as Dir, remainingPath)
    }

    fun addNode(parent: Dir, node: Node) {
        log.debug("Adding node {} to {}", node.name, parent.name)
        parent.children[node.name] = node
    }

    fun removeNode(parent: Dir, node: Node) {
        log.debug("Removing node {} from {}", node.name, parent.name)
        parent.children.remove(node.name)
    }

}
