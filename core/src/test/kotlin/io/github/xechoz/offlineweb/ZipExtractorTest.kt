package io.github.xechoz.offlineweb

import io.github.xechoz.offlineweb.internal.install.ZipExtractor
import io.github.xechoz.offlineweb.internal.protocol.FileInfo
import io.github.xechoz.offlineweb.internal.protocol.ProtocolException
import io.github.xechoz.offlineweb.internal.protocol.Release
import io.github.xechoz.offlineweb.internal.protocol.protocolJson
import io.github.xechoz.offlineweb.internal.protocol.sha256Hex
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipExtractorTest {

    private val fixtures: File = findFixtures()

    @Test
    fun `fixture archive extracts and verifies`() {
        val release = readRelease()
        val target = tempDir()
        try {
            ZipExtractor.extract(File(fixtures, "site.zip"), release.files, target)
            assertTrue(File(target, "index.html").readText().contains("fixture"))
            assertTrue(File(target, "data.json").readText().contains("offline fixture loaded"))
            assertEquals(4, target.walkTopDown().count { it.isFile })
        } finally {
            target.deleteRecursively()
        }
    }

    @Test
    fun `zip slip path is rejected`() {
        val zip = tempFileWithZip(
            "../evil.txt" to "evil".toByteArray(),
            "index.html" to "ok".toByteArray(),
        )
        try {
            val expected = listOf(fileInfo("index.html", "ok"))
            assertThrows(ProtocolException::class.java) {
                ZipExtractor.extract(zip, expected, tempDir())
            }
        } finally {
            zip.delete()
        }
    }

    @Test
    fun `undeclared file is rejected`() {
        val zip = tempFileWithZip(
            "index.html" to "ok".toByteArray(),
            "extra.txt" to "extra".toByteArray(),
        )
        try {
            val expected = listOf(fileInfo("index.html", "ok"))
            assertThrows(ProtocolException::class.java) {
                ZipExtractor.extract(zip, expected, tempDir())
            }
        } finally {
            zip.delete()
        }
    }

    @Test
    fun `missing and corrupted files are rejected`() {
        val zip = tempFileWithZip("index.html" to "ok".toByteArray())
        try {
            val missing = listOf(
                fileInfo("index.html", "ok"),
                fileInfo("app.js", "js"),
            )
            assertThrows(ProtocolException::class.java) {
                ZipExtractor.extract(zip, missing, tempDir())
            }
            val wrongHash = listOf(FileInfo("index.html", 2, "0".repeat(64)))
            assertThrows(ProtocolException::class.java) {
                ZipExtractor.extract(zip, wrongHash, tempDir())
            }
        } finally {
            zip.delete()
        }
    }

    private fun readRelease(): Release {
        val name = "release.json"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "fixtures/$name")
            if (candidate.isFile) {
                return protocolJson.decodeFromString(Release.serializer(), candidate.readText())
            }
            dir = dir.parentFile
        }
        error("fixture release not found")
    }

    private fun fileInfo(path: String, content: String): FileInfo =
        FileInfo(path, content.toByteArray().size.toLong(), sha256Hex(content.toByteArray()))

    private fun tempDir(): File = File.createTempFile("offline-zip", "").apply {
        delete()
        mkdirs()
    }

    private fun tempFileWithZip(vararg entries: Pair<String, ByteArray>): File {
        val file = File.createTempFile("offline-test", ".zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun findFixtures(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "fixtures")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("fixtures directory not found")
    }
}
