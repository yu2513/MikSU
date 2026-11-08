package io.milksu.manager.data.system

import io.milksu.manager.ui.util.module.LatestVersionInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

data class CoreApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val signerSha256: Set<String>,
    val debuggable: Boolean = false,
    val testOnly: Boolean = false,
    val split: Boolean = false,
)

object CoreManagerUpdatePolicy {
    const val MAX_APK_BYTES = 128L * 1024 * 1024
    private val sha256 = Regex("[0-9a-fA-F]{64}")

    fun validateIdentity(
        installed: CoreApkIdentity,
        candidate: CoreApkIdentity,
        release: LatestVersionInfo,
    ) {
        verify(installed.packageName.isNotBlank() && candidate.packageName == installed.packageName,
            "The update APK belongs to another package")
        verify(candidate.versionCode == release.versionCode.toLong() &&
            candidate.versionCode > installed.versionCode,
            "The update APK version does not match the newer release")
        verify(candidate.versionName == release.version,
            "The update APK version name does not match the release")
        verify(!candidate.debuggable && !candidate.testOnly && !candidate.split,
            "The update must be a complete release APK")
        val currentSigners = installed.signerSha256.map { it.lowercase() }.toSet()
        val newSigners = candidate.signerSha256.map { it.lowercase() }.toSet()
        verify(currentSigners.isNotEmpty() && newSigners.isNotEmpty() &&
            currentSigners.all(sha256::matches) && newSigners.all(sha256::matches) &&
            currentSigners == newSigners,
            "The update APK signing certificate does not match the installed Manager")
    }

    fun copyDownload(
        input: InputStream,
        destination: File,
        release: LatestVersionInfo,
        checkActive: () -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ): String {
        validateSize(release)
        try {
            checkActive()
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    bytes += count
                    verify(bytes <= release.assetSize, "The APK exceeds the published size")
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    onProgress(bytes)
                }
                verify(bytes == release.assetSize, "The APK download is incomplete")
                output.flush()
                output.fd.sync()
            }
            val hash = hex(digest.digest())
            verifyHash(hash, release.assetSha256)
            checkActive()
            return hash
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    fun verifyFile(
        file: File,
        release: LatestVersionInfo,
        pinnedSha256: String? = release.assetSha256,
        checkActive: () -> Unit = {},
    ): String {
        validateSize(release)
        verify(file.isFile && file.length() == release.assetSize,
            "The saved APK size does not match the release")
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count == -1) break
                bytes += count
                verify(bytes <= release.assetSize, "The saved APK changed during verification")
                digest.update(buffer, 0, count)
            }
        }
        verify(bytes == release.assetSize, "The saved APK changed during verification")
        return hex(digest.digest()).also { hash ->
            verifyHash(hash, release.assetSha256)
            verifyHash(hash, pinnedSha256)
        }
    }

    private fun validateSize(release: LatestVersionInfo) {
        verify(release.assetSize in 1..MAX_APK_BYTES, "The release APK size is invalid")
        verify(release.assetSha256 == null || sha256.matches(release.assetSha256),
            "The release APK digest is invalid")
    }

    private fun verifyHash(actual: String, expected: String?) {
        if (expected != null) {
            verify(sha256.matches(expected) && actual.equals(expected, ignoreCase = true),
                "The update APK SHA-256 does not match")
        }
    }

    private fun verify(condition: Boolean, message: String) {
        if (!condition) throw IOException(message)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
