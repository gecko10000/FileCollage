package gecko10000.filecollage.util

object PathUtil {
    private fun throwIfTrailingSlash(path: String) {
        require(path.isBlank() || path.lastIndexOf('/') + 1 != path.length) {
            "Path has trailing slash: $path"
        }
    }

    fun getParentPath(path: String): String {
        throwIfTrailingSlash(path)
        val lastSlash = path.lastIndexOf('/')
        return path.take(lastSlash)
    }

    fun getNodeName(path: String): String {
        throwIfTrailingSlash(path)
        val lastSlash = path.lastIndexOf('/')
        return path.substring(lastSlash + 1)
    }
}
