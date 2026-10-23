package io.github.xechoz.offlineweb.internal.store

import android.content.Context
import io.github.xechoz.offlineweb.InstalledPackage
import io.github.xechoz.offlineweb.StartupVerification
import io.github.xechoz.offlineweb.internal.protocol.Release
import io.github.xechoz.offlineweb.internal.protocol.protocolJson
import java.io.File
import java.util.UUID

internal class OfflineStore(context: Context, private val appKey: String) {

    val root: File = File(context.filesDir, "offline-web/$appKey").apply { mkdirs() }

    init {
        cleanupTmp()
    }

    fun cleanupTmp() {
        File(root, "tmp").listFiles()?.forEach { it.deleteRecursively() }
    }

    fun activeVersion(packageId: String): Int? = readPointer("active", packageId)

    fun previousVersion(packageId: String): Int? = readPointer("previous", packageId)

    fun releaseDir(packageId: String, version: Int): File =
        File(root, "releases/$packageId/$version")

    fun webRoot(packageId: String, version: Int): File = File(releaseDir(packageId, version), "www")

    fun readReleaseBytes(packageId: String, version: Int): ByteArray? =
        File(releaseDir(packageId, version), "release.json").takeIf { it.isFile }?.readBytes()

    fun readSignature(packageId: String, version: Int): String? =
        File(releaseDir(packageId, version), "release.sig").takeIf { it.isFile }?.readText()

    fun readRelease(packageId: String, version: Int): Release? {
        val bytes = readReleaseBytes(packageId, version) ?: return null
        return runCatching {
            protocolJson.decodeFromString(Release.serializer(), bytes.decodeToString())
        }.getOrNull()
    }

    fun newStagingDir(): File {
        val dir = File(root, "tmp/staging-${UUID.randomUUID()}")
        check(dir.mkdirs()) { "cannot create staging dir" }
        return dir
    }

    fun activate(
        packageId: String,
        version: Int,
        releaseBytes: ByteArray,
        signature: String,
        staging: File,
    ) {
        val previous = activeVersion(packageId)
        val target = releaseDir(packageId, version)
        if (target.exists()) target.deleteRecursively()
        check(target.mkdirs()) { "cannot create release dir" }

        File(target, "release.json").writeBytes(releaseBytes)
        File(target, "release.sig").writeText(signature)

        val www = File(target, "www")
        require(staging.renameTo(www)) { "cannot move staging into release dir" }

        if (previous != null && previous != version) {
            writePointer("previous", packageId, previous)
        }
        writePointer("active", packageId, version)
        cleanupOrphans(packageId)
    }

    fun remove(packageId: String) {
        File(root, "active/$packageId").delete()
        File(root, "previous/$packageId").delete()
        File(root, "releases/$packageId").deleteRecursively()
    }

    fun clear() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun packageIds(): List<String> =
        File(root, "releases").listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()

    fun installedPackages(): List<InstalledPackage> {
        val releasesRoot = File(root, "releases")
        val packageIds = releasesRoot.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()
        return packageIds.mapNotNull { pkg ->
            val version = activeVersion(pkg) ?: return@mapNotNull null
            val release = readRelease(pkg, version) ?: return@mapNotNull null
            InstalledPackage(
                packageId = pkg,
                version = release.version,
                versionName = release.versionName,
                entryUrl = release.entryUrl,
                expiresAt = release.expiresAt,
                sizeBytes = directorySize(webRoot(pkg, version)),
            )
        }
    }

    fun usageBytes(): Long = directorySize(root)

    /**
     * Returns true when the installed release still matches the signed metadata.
     * [StartupVerification.FULL] also re-hashes every file.
     */
    fun verifyInstalled(
        packageId: String,
        version: Int,
        release: Release,
        mode: StartupVerification,
    ): Boolean {
        if (mode == StartupVerification.NONE) return true
        val www = webRoot(packageId, version)
        if (!www.isDirectory) return false
        if (mode == StartupVerification.SIZE_ONLY) {
            return release.files.all { file ->
                File(www, file.path).let { it.isFile && it.length() == file.size }
            }
        }
        return release.files.all { file ->
            val local = File(www, file.path)
            local.isFile &&
                local.length() == file.size &&
                io.github.xechoz.offlineweb.internal.protocol.sha256Hex(local) == file.sha256
        }
    }

    fun cleanupOrphans(packageId: String) {
        val keep = setOfNotNull(activeVersion(packageId), previousVersion(packageId))
        File(root, "releases/$packageId").listFiles()?.forEach { dir ->
            val version = dir.name.toIntOrNull()
            if (version == null || version !in keep) dir.deleteRecursively()
        }
    }

    fun writeActivePointer(packageId: String, version: Int) =
        writePointer("active", packageId, version)

    private fun readPointer(kind: String, packageId: String): Int? =
        File(root, "$kind/$packageId").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()

    private fun writePointer(kind: String, packageId: String, version: Int) {
        val target = File(root, "$kind/$packageId")
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$packageId.tmp")
        tmp.writeText(version.toString())
        if (target.exists()) target.delete()
        check(tmp.renameTo(target)) { "cannot write $kind pointer for $packageId" }
    }

    private fun directorySize(file: File): Long {
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { directorySize(it) } ?: 0L
    }
}
