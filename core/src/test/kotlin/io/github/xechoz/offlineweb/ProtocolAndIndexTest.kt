package io.github.xechoz.offlineweb

import io.github.xechoz.offlineweb.internal.index.IndexSource
import io.github.xechoz.offlineweb.internal.index.OfflineIndex
import io.github.xechoz.offlineweb.internal.index.ResolveResult
import io.github.xechoz.offlineweb.internal.protocol.ArchiveInfo
import io.github.xechoz.offlineweb.internal.protocol.FileInfo
import io.github.xechoz.offlineweb.internal.protocol.ProtocolException
import io.github.xechoz.offlineweb.internal.protocol.Release
import io.github.xechoz.offlineweb.internal.protocol.ReleaseResponse
import io.github.xechoz.offlineweb.internal.protocol.ReleaseVerifier
import io.github.xechoz.offlineweb.internal.protocol.Route
import io.github.xechoz.offlineweb.internal.protocol.protocolJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtocolAndIndexTest {

    private val fixtures: File = findFixtures()

    @Test
    fun `fixture signature verifies`() {
        val response = protocolJson.decodeFromString(
            ReleaseResponse.serializer(),
            File(fixtures, "release-response.json").readText(),
        )
        val verified = ReleaseVerifier.verify(response, config())
        assertEquals("fixture", verified.release.packageId)
        assertEquals(1, verified.release.version)
        assertEquals(4, verified.release.files.size)
    }

    @Test
    fun `tampered signature is rejected`() {
        val response = protocolJson.decodeFromString(
            ReleaseResponse.serializer(),
            File(fixtures, "release-response.json").readText(),
        )
        val tampered = response.copy(signature = flipBase64(response.signature))
        try {
            ReleaseVerifier.verify(tampered, config())
            fail("expected ProtocolException")
        } catch (_: ProtocolException) {
            // expected
        }
    }

    @Test
    fun `unknown key is rejected`() {
        val response = protocolJson.decodeFromString(
            ReleaseResponse.serializer(),
            File(fixtures, "release-response.json").readText(),
        )
        try {
            ReleaseVerifier.verify(response, config().copy(trustedKeys = mapOf("other" to "x")))
            fail("expected ProtocolException")
        } catch (_: ProtocolException) {
            // expected
        }
    }

    @Test
    fun `query and directory urls resolve to manifest files`() {
        val root = createWebRoot()
        try {
            val index = indexFor(root)
            val rootHit = index.resolve(
                "http://fixture.local/app/",
                isMainFrame = true,
                now = 1_000,
                expiryPolicy = ExpiryPolicy.STRICT,
            ) as ResolveResult.Hit
            assertEquals("index.html", rootHit.file.name)
            assertEquals("text/html", rootHit.mime)

            val cssHit = index.resolve(
                "http://fixture.local/app/app.css?v=2",
                isMainFrame = false,
                now = 1_000,
                expiryPolicy = ExpiryPolicy.STRICT,
            ) as ResolveResult.Hit
            assertEquals("app.css", cssHit.file.name)
            assertEquals("text/css", cssHit.mime)

            val jsHit = index.resolve(
                "http://fixture.local/app/assets/app.js",
                isMainFrame = false,
                now = 1_000,
                expiryPolicy = ExpiryPolicy.STRICT,
            ) as ResolveResult.Hit
            assertEquals("app.js", jsHit.file.name)
            assertEquals("text/javascript", jsHit.mime)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `spa fallback only applies to main frame`() {
        val root = createWebRoot()
        try {
            val index = indexFor(root)
            val main = index.resolve(
                "http://fixture.local/app/route/42",
                isMainFrame = true,
                now = 1_000,
                expiryPolicy = ExpiryPolicy.STRICT,
            ) as ResolveResult.Hit
            assertEquals("index.html", main.file.name)

            val subresource = index.resolve(
                "http://fixture.local/app/missing.js",
                isMainFrame = false,
                now = 1_000,
                expiryPolicy = ExpiryPolicy.STRICT,
            )
            assertTrue(subresource is ResolveResult.Missing)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `path traversal is not resolved`() {
        val root = createWebRoot()
        try {
            val index = indexFor(root)
            val result = index.resolve(
                "http://fixture.local/app/assets/../app.css",
                isMainFrame = false,
                now = 1_000,
                expiryPolicy = ExpiryPolicy.STRICT,
            )
            assertTrue(result is ResolveResult.NotMine)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `longest base path wins`() {
        val rootA = createWebRoot()
        val rootB = File(rootA, "deep").apply { mkdirs() }
        File(rootB, "index.html").writeText("deep")
        try {
            val releaseA = release(expiresAt = null)
            val releaseB = release(expiresAt = null).copy(
                packageId = "deep",
                routes = listOf(Route("http://fixture.local", "/app/deep/", true)),
                files = listOf(FileInfo("index.html", 4, shaOf("deep"))),
            )
            val index = OfflineIndex.build(
                listOf(
                    IndexSource(releaseA, rootA),
                    IndexSource(releaseB, rootB),
                ),
            )
            val hit = index.resolve(
                "http://fixture.local/app/deep/x",
                isMainFrame = true,
                now = 1_000,
                expiryPolicy = ExpiryPolicy.STRICT,
            ) as ResolveResult.Hit
            assertEquals("deep", hit.packageId)
        } finally {
            rootA.deleteRecursively()
        }
    }

    @Test
    fun `expired package respects policy`() {
        val root = createWebRoot()
        try {
            val index = OfflineIndex.build(
                listOf(IndexSource(release(expiresAt = 500), root)),
            )
            val strict = index.resolve(
                "http://fixture.local/app/",
                isMainFrame = true,
                now = 1_000,
                expiryPolicy = ExpiryPolicy.STRICT,
            )
            assertTrue(strict is ResolveResult.Expired)

            val graceful = index.resolve(
                "http://fixture.local/app/",
                isMainFrame = true,
                now = 1_000,
                expiryPolicy = ExpiryPolicy.GRACEFUL,
            )
            assertTrue(graceful is ResolveResult.Hit)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun config(): OfflineConfig = OfflineConfig(
        appKey = "fixture",
        endpoint = "http://fixture.local",
        trustedKeys = mapOf(
            "demo-2026-01" to File(fixtures, "public-key.b64").readText().trim(),
        ),
        allowCleartext = true,
    )

    private fun createWebRoot(): File {
        val root = File.createTempFile("offline-index", "").apply {
            delete()
            mkdirs()
        }
        File(root, "index.html").writeText("index")
        File(root, "app.css").writeText("css")
        File(root, "data.json").writeText("{}")
        File(root, "assets").mkdirs()
        File(root, "assets/app.js").writeText("js")
        return root
    }

    private fun indexFor(root: File): OfflineIndex =
        OfflineIndex.build(listOf(IndexSource(release(expiresAt = null), root)))

    private fun release(expiresAt: Long?): Release {
        val files = listOf(
            fileInfo("index.html", "index"),
            fileInfo("app.css", "css"),
            fileInfo("data.json", "{}"),
            fileInfo("assets/app.js", "js"),
        )
        return Release(
            keyId = "demo-2026-01",
            appKey = "fixture",
            packageId = "fixture",
            version = 1,
            versionName = "fixture-1",
            expiresAt = expiresAt,
            entryUrl = "http://fixture.local/app/",
            archive = ArchiveInfo("site.zip", 100, "0".repeat(64)),
            routes = listOf(Route("http://fixture.local", "/app/", true)),
            files = files,
        )
    }

    private fun fileInfo(path: String, content: String): FileInfo =
        FileInfo(path, content.length.toLong(), shaOf(content))

    private fun shaOf(content: String): String =
        io.github.xechoz.offlineweb.internal.protocol.sha256Hex(content.toByteArray())

    private fun flipBase64(value: String): String {
        val bytes = value.toByteArray()
        bytes[bytes.size / 2] = if (bytes[bytes.size / 2] == 'A'.code.toByte()) 'B'.code.toByte() else 'A'.code.toByte()
        return String(bytes)
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
