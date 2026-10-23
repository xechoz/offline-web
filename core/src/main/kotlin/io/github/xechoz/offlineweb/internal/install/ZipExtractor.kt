package io.github.xechoz.offlineweb.internal.install

import io.github.xechoz.offlineweb.internal.protocol.FileInfo
import io.github.xechoz.offlineweb.internal.protocol.MAX_FILES
import io.github.xechoz.offlineweb.internal.protocol.ProtocolException
import io.github.xechoz.offlineweb.internal.protocol.isSafePath
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal class ZipRejectedException(message: String) : Exception(message)

internal object ZipExtractor {

    fun extract(zip: File, expectedFiles: List<FileInfo>, destination: File) {
        val expected = expectedFiles.associateBy { it.path }
        if (expected.size != expectedFiles.size) {
            throw ProtocolException("duplicate file in release manifest")
        }
        if (!destination.exists() && !destination.mkdirs()) {
            throw ProtocolException("cannot create extraction directory")
        }
        val destinationRoot = destination.canonicalFile
        val seen = HashSet<String>(expected.size * 2)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var entryCount = 0

        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_FILES * 2 + 1024) {
                    throw ProtocolException("too many zip entries")
                }

                val name = entry.name
                if (entry.isDirectory || name.endsWith("/")) {
                    if (name.contains('\\') || name.startsWith("/") || name.split('/').any { it == ".." }) {
                        throw ProtocolException("unsafe zip directory: $name")
                    }
                    continue
                }
                if (!isSafePath(name)) throw ProtocolException("unsafe zip path: $name")
                if (!seen.add(name)) throw ProtocolException("duplicate zip entry: $name")
                val info = expected[name]
                    ?: throw ProtocolException("zip contains undeclared file: $name")

                val target = File(destinationRoot, name)
                val canonical = target.canonicalFile
                if (!canonical.path.startsWith(destinationRoot.path + File.separator)) {
                    throw ProtocolException("zip path escapes destination: $name")
                }
                canonical.parentFile?.mkdirs()

                digest.reset()
                var written = 0L
                canonical.outputStream().buffered().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        written += read
                        if (written > info.size) {
                            throw ProtocolException("file larger than declared: $name")
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }

                if (written != info.size) {
                    throw ProtocolException("size mismatch for $name")
                }
                if (digest.digest().toHex() != info.sha256.lowercase()) {
                    throw ProtocolException("sha256 mismatch for $name")
                }
            }
        }

        if (seen.size != expected.size) {
            val missing = expected.keys - seen
            throw ProtocolException("zip is missing files: ${missing.take(5).joinToString()}")
        }
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val out = CharArray(size * 2)
        for (i in indices) {
            val value = this[i].toInt() and 0xff
            out[i * 2] = digits[value ushr 4]
            out[i * 2 + 1] = digits[value and 0x0f]
        }
        return String(out)
    }
}
