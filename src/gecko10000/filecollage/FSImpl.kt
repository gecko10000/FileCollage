package gecko10000.filecollage

import gecko10000.filecollage.dao.DirectoryDAO
import gecko10000.filecollage.dao.FileDAO
import gecko10000.filecollage.model.index.*
import gecko10000.filecollage.util.PathUtil
import gecko10000.filecollage.util.log
import jnr.ffi.Pointer
import jnr.ffi.types.mode_t
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ru.serce.jnrfuse.ErrorCodes
import ru.serce.jnrfuse.FuseFillDir
import ru.serce.jnrfuse.FuseStubFS
import ru.serce.jnrfuse.struct.FileStat
import ru.serce.jnrfuse.struct.FuseFileInfo
import ru.serce.jnrfuse.struct.Timespec
import java.util.*


class FSImpl : FuseStubFS(), KoinComponent {

    private val directoryDAO: DirectoryDAO by inject()
    private val fileDAO: FileDAO by inject()

    override fun create(
        path: String,
        @mode_t permissions: Long,
        fi: FuseFileInfo,
    ): Int {
        try {
            log.debug("Creating file {}", path)
            if (directoryDAO.lookupNode(path) != null) {
                log.debug("File {} already exists, could not create.", path)
                return -ErrorCodes.EEXIST()
            }
            val parentDir: Dir? = directoryDAO.lookupParent(path)
            if (parentDir == null) {
                log.debug("Parent directory for {} does not exist, could not create.", path)
                return -ErrorCodes.ENOENT()
            }
            val fileName = PathUtil.getNodeName(path)
            val newFile = FileImpl(
                id = UUID.randomUUID(),
                name = fileName,
                permissions = permissions.toInt(),
                uid = this.context.uid.longValue(),
                gid = this.context.gid.longValue(),
                accessTime = Time.now(),
                modificationTime = Time.now(),
            )
            directoryDAO.addNode(parentDir, newFile)
            return 0
        } catch (ex: Exception) {
            ex.printStackTrace()
            throw ex
        }
    }

    override fun getattr(path: String, stat: FileStat): Int {
        try {
            log.debug("Getting attributes for {}", path)
            val node = directoryDAO.lookupNode(path)
            if (node == null) {
                log.debug("File {} does not exist, could not getattr.", path)
                return -ErrorCodes.ENOENT()
            }
            stat.st_mode.set(node.permissions)
            if (node is File) {
                stat.st_size.set(node.size)
            }
            stat.st_gid.set(node.gid)
            stat.st_uid.set(node.uid)
            stat.st_atim.tv_sec.set(node.accessTime.seconds)
            stat.st_atim.tv_nsec.set(node.accessTime.nanoseconds)
            stat.st_mtim.tv_sec.set(node.modificationTime.seconds)
            stat.st_mtim.tv_nsec.set(node.modificationTime.nanoseconds)
            return 0
        } catch (ex: Exception) {
            ex.printStackTrace()
            throw ex
        }
    }

    override fun mkdir(path: String, @mode_t permissions: Long): Int {
        try {
            log.debug("Creating dir {}", path)
            if (directoryDAO.lookupNode(path) != null) {
                log.debug("Directory {} already exists.", path)
                return -ErrorCodes.EEXIST()
            }
            val parent: Dir? = directoryDAO.lookupParent(path)
            if (parent == null) {
                log.debug("Parent directory of {} does not exist, could not create it.", path)
                return -ErrorCodes.ENOENT()
            }
            val dirName = PathUtil.getNodeName(path)
            val newDir = DirImpl(
                name = dirName,
                permissions = permissions.toInt() or FileStat.S_IFDIR,
                gid = this.context.gid.longValue(),
                uid = this.context.uid.longValue(),
                accessTime = Time.now(),
                modificationTime = Time.now(),
            )
            directoryDAO.addNode(parent, newDir)
            return 0
        } catch (ex: Exception) {
            ex.printStackTrace()
            throw ex
        }
    }

    override fun read(
        path: String,
        buf: Pointer,
        size: Long,
        offset: Long,
        fi: FuseFileInfo,
    ): Int {
        try {
            log.debug("Reading file {}", path)
            val file = directoryDAO.lookupNode(path)
            if (file == null) {
                log.debug("File {} not found, cannot read.", path)
                return -ErrorCodes.ENOENT()
            }
            if (file !is File) {
                log.debug("Node {} is not a file, cannot read.", path)
                return -ErrorCodes.EISDIR()
            }
            return fileDAO.readBytes(file, buf, size.toInt(), offset)
        } catch (ex: Exception) {
            ex.printStackTrace()
            throw ex
        }
    }

    override fun readdir(
        path: String,
        buf: Pointer,
        filter: FuseFillDir,
        offset: Long,
        fi: FuseFileInfo,
    ): Int {
        try {
            log.debug("Reading dir {}", path)
            val node = directoryDAO.lookupNode(path)
            if (node == null) {
                log.debug("Directory {} does not exist, could not read it.", path)
                return -ErrorCodes.ENOENT()
            }
            if (node !is Dir) {
                log.debug("File {} is not a directory, could not readdir.", path)
                return -ErrorCodes.ENOTDIR()
            }
            filter.apply(buf, ".", null, 0)
            filter.apply(buf, "..", null, 0)
            for (child in node.children.values) {
                filter.apply(buf, child.name, null, 0)
            }
            return 0
        } catch (ex: Exception) {
            ex.printStackTrace()
            throw ex
        }
    }

    override fun rename(oldPath: String, newPath: String): Int {
        try {
            log.debug("Renaming {} to {}", oldPath, newPath)
            val node = directoryDAO.lookupNode(oldPath)
            if (node == null) {
                log.debug("Node {} does not exist, could not rename.", oldPath)
                return -ErrorCodes.ENOENT()
            }

            val oldParent: Dir = directoryDAO.lookupParent(oldPath)!!

            val newParent = directoryDAO.lookupNode(PathUtil.getParentPath(newPath))
            if (newParent == null) {
                log.debug("Parent of {} does not exist, could not rename.", newPath)
                return -ErrorCodes.ENOENT()
            }
            if (newParent !is Dir) {
                log.debug("Parent of {} is not a dir, could not rename.", newPath)
                return -ErrorCodes.ENOTDIR()
            }
            val newName = PathUtil.getNodeName(newPath)

            directoryDAO.removeNode(oldParent, node)
            node.name = newName
            directoryDAO.addNode(newParent, node)
            return 0
        } catch (ex: Exception) {
            ex.printStackTrace()
            throw ex
        }
    }

    override fun rmdir(path: String): Int {
        try {
            log.debug("Removing dir {}", path)
            val node = directoryDAO.lookupNode(path)
            if (node == null) {
                log.debug("Directory {} doesn't exist, couldn't remove.", path)
                return -ErrorCodes.ENOENT()
            }
            if (node !is Dir) {
                log.debug("File {} is not a directory, could not remove.", path)
                return -ErrorCodes.ENOTDIR()
            }
            val parent: Dir = directoryDAO.lookupParent(path)!!
            directoryDAO.removeNode(parent, node)
            return 0
        } catch (ex: Exception) {
            ex.printStackTrace()
            throw ex
        }
    }

    override fun truncate(path: String, size: Long): Int {
        try {
            log.debug("Truncating {}", path)
            val file = directoryDAO.lookupNode(path)
            if (file == null) {
                log.debug("File {} does not exist, could not truncate.", path)
                return -ErrorCodes.ENOENT()
            }
            if (file !is File) {
                log.debug("Node {} is not a file, could not truncate.", path)
                return -ErrorCodes.EISDIR()
            }
            fileDAO.truncateFile(file, size)
            return 0
        } catch (ex: Exception) {
            ex.printStackTrace()
            throw ex
        }
    }

    override fun unlink(path: String): Int {
        // TODO: remove chunks from cache
        try {
            log.debug("Unlinking {}", path)
            val node = directoryDAO.lookupNode(path)
            if (node == null) {
                log.debug("File {} does not exist, could not remove.", path)
                return -ErrorCodes.ENOENT()
            }
            val parent: Dir = directoryDAO.lookupParent(path)!!
            directoryDAO.removeNode(parent, node)
            if (node is File) {
                fileDAO.deleteFileChunks(node)
            }
            return 0
        } catch (ex: Exception) {
            ex.printStackTrace()
            throw ex
        }
    }

    override fun open(path: String, fi: FuseFileInfo): Int {
        try {
            log.debug("Opening {}", path)
            return 0
        } catch (ex: Exception) {
            ex.printStackTrace()
            throw ex
        }
    }

    override fun write(
        path: String,
        buf: Pointer,
        size: Long,
        offset: Long,
        fi: FuseFileInfo,
    ): Int {
        try {
            log.debug("Writing to {}", path)
            val file = directoryDAO.lookupNode(path)
            if (file == null) {
                log.debug("File {} does not exist, could not write.", path)
                return -ErrorCodes.ENOENT()
            }
            if (file !is File) {
                log.debug("Node {} is not a file, could not write.", path)
                return -ErrorCodes.EISDIR()
            }
            return fileDAO.writeBytes(
                file = file,
                buf = buf,
                size = size.toInt(),
                offset = offset,
            )
        } catch (ex: Exception) {
            ex.printStackTrace()
            throw ex
        }
    }

    override fun utimens(path: String, timespec: Array<out Timespec>): Int {
        try {
            log.debug("Updating times for {}", path)
            val file = directoryDAO.lookupNode(path)
            if (file == null) {
                log.debug("File {} does not exist, could not utimens", path)
                return -ErrorCodes.ENOENT()
            }
            val accessTime = Time.fromTimespec(timespec[0])
            val modificationTime = Time.fromTimespec(timespec[1])
            file.accessTime = accessTime
            file.modificationTime = modificationTime
            return 0
        } catch (ex: Exception) {
            ex.printStackTrace()
            throw ex
        }
    }
}
