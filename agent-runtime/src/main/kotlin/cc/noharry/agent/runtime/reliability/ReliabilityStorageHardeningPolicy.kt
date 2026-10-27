package cc.noharry.agent.runtime.reliability
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal data class ReliabilityStorageHardeningPolicy(
    val minSdk: Int = ANDROID_RELIABILITY_STORAGE_MIN_SDK,
    val rootKind: ReliabilityStorageRootKind = ReliabilityStorageRootKind.APP_PRIVATE_NO_BACKUP,
    val fileLockMode: ReliabilityStorageFileLockMode = ReliabilityStorageFileLockMode.PER_OPERATION_ADVISORY,
    val encryptionMode: ReliabilityStorageEncryptionMode =
        ReliabilityStorageEncryptionMode.HOST_APP_PRIVATE_STORAGE_REQUIRED,
) {
    init {
        require(minSdk >= ANDROID_RELIABILITY_STORAGE_MIN_SDK) {
            "minSdk must be at least $ANDROID_RELIABILITY_STORAGE_MIN_SDK."
        }
    }

    fun validateRoot(root: File) {
        if (root.exists() && !root.isDirectory) {
            throw IllegalArgumentException(RELIABILITY_STORAGE_ROOT_FILE_ERROR)
        }
    }

    fun writeMarker(root: File) {
        validateRoot(root)
        root.mkdirs()
        File(root, RELIABILITY_STORAGE_POLICY_MARKER_FILE).writeText(markerText())
    }

    fun markerText(): String = buildString {
        appendLine("minSdk=$minSdk")
        appendLine("rootKind=$rootKind")
        appendLine("fileLockMode=$fileLockMode")
        appendLine("encryptionMode=$encryptionMode")
    }
}

internal enum class ReliabilityStorageRootKind {
    APP_PRIVATE_NO_BACKUP,
}

internal enum class ReliabilityStorageFileLockMode {
    NONE,
    PER_OPERATION_ADVISORY,
}

internal enum class ReliabilityStorageEncryptionMode {
    HOST_APP_PRIVATE_STORAGE_REQUIRED,
}

internal class ReliabilityStorageFileLock(
    private val root: File,
    private val mode: ReliabilityStorageFileLockMode,
) {
    private val processLock = ReentrantReadWriteLock()

    fun <T> withLock(block: () -> T): T = processLock.write {
        if (mode == ReliabilityStorageFileLockMode.NONE) {
            return@write block()
        }
        root.mkdirs()
        val lockFile = File(root, RELIABILITY_STORAGE_LOCK_FILE)
        RandomAccessFile(lockFile, "rw").use { lockOwner ->
            val lock = try {
                lockOwner.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                throw IllegalStateException(RELIABILITY_STORAGE_LOCK_UNAVAILABLE)
            }
            try {
                return block()
            } finally {
                lock.release()
            }
        }
    }

    fun <T> withReadLock(block: () -> T): T = processLock.read(block)
}

internal const val ANDROID_RELIABILITY_STORAGE_MIN_SDK = 23
internal val RELIABILITY_STORAGE_ROOT_FILE_ERROR =
    RuntimeErrorCode.RELIABILITY_STORAGE_ROOT_MUST_BE_DIRECTORY.wireCode
internal val RELIABILITY_STORAGE_LOCK_UNAVAILABLE =
    RuntimeErrorCode.RELIABILITY_STORAGE_LOCK_UNAVAILABLE.wireCode
internal const val RELIABILITY_STORAGE_POLICY_MARKER_FILE = ".agent-runtime-storage-policy"
internal const val RELIABILITY_STORAGE_LOCK_FILE = ".agent-runtime-storage.lock"
