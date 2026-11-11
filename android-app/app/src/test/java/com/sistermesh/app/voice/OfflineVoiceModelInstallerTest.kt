package com.sistermesh.app.voice

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okio.Buffer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfflineVoiceModelInstallerTest {
    @Test
    fun verifiesAndInstallsModelOnce() = runBlocking {
        val archive = testArchive()
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(archive)))
        server.start()
        val root = Files.createTempDirectory("voice-model-ready").toFile()
        try {
            val installer = installer(server, root, archive)

            installer.ensureInstalled()
            val result = withTimeout(10_000) {
                installer.state.first { it is OfflineModelState.Ready || it is OfflineModelState.Failed }
            }

            assertEquals(OfflineModelState.Ready, result)
            assertTrue(File(installer.modelPath, "am/final.mdl").isFile)
            assertTrue(File(installer.modelPath, "conf/model.conf").isFile)

            installer.ensureInstalled()
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsArchiveWithIncorrectSha256() = runBlocking {
        val archive = testArchive()
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(archive)))
        server.start()
        val root = Files.createTempDirectory("voice-model-bad-hash").toFile()
        try {
            val installer = installer(server, root, archive, expectedHash = "0".repeat(64))

            installer.ensureInstalled()
            val result = withTimeout(10_000) {
                installer.state.first { it is OfflineModelState.Ready || it is OfflineModelState.Failed }
            }

            assertTrue(result is OfflineModelState.Failed)
            assertTrue(!File(installer.modelPath, "am/final.mdl").exists())
        } finally {
            server.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun resumesAnInterruptedArchiveOnRetry() = runBlocking {
        val archive = testArchive()
        val offset = archive.size / 2
        val firstPart = archive.copyOfRange(0, offset)
        val remainder = archive.copyOfRange(offset, archive.size)
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(firstPart)))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $offset-${archive.size - 1}/${archive.size}")
                .setBody(Buffer().write(remainder))
        )
        server.start()
        val root = Files.createTempDirectory("voice-model-resume").toFile()
        try {
            val installer = installer(server, root, archive)

            installer.ensureInstalled()
            val interrupted = withTimeout(10_000) {
                installer.state.first { it is OfflineModelState.Failed || it is OfflineModelState.Ready }
            }
            assertTrue(interrupted is OfflineModelState.Failed)

            installer.ensureInstalled()
            val resumed = withTimeout(10_000) {
                installer.state.first { it is OfflineModelState.Failed || it is OfflineModelState.Ready }
            }

            assertEquals(OfflineModelState.Ready, resumed)
            server.takeRequest()
            assertEquals("bytes=$offset-", server.takeRequest().getHeader("Range"))
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun reportsInsufficientStorageWithoutStartingDownload() {
        val archive = testArchive()
        val server = MockWebServer()
        server.start()
        val root = Files.createTempDirectory("voice-model-low-space").toFile()
        try {
            val installer = installer(server, root, archive, freeBytes = 0L)

            installer.ensureInstalled()

            assertTrue(installer.state.value is OfflineModelState.Failed)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
            root.deleteRecursively()
        }
    }

    private fun installer(
        server: MockWebServer,
        root: File,
        archive: ByteArray,
        expectedHash: String = sha256(archive),
        freeBytes: Long = 256L * 1024 * 1024
    ): OfflineVoiceModelInstaller {
        val spec = VoiceModelSpec(
            version = "test-model",
            url = server.url("/model.zip").toString(),
            archiveBytes = archive.size.toLong(),
            sha256 = expectedHash,
            archiveDirectory = "test-model"
        )
        return OfflineVoiceModelInstaller(
            modelsDirectory = File(root, "offline-voice-models"),
            client = OkHttpClient(),
            spec = spec,
            availableBytes = { freeBytes }
        )
    }

    private fun testArchive(): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("test-model/am/final.mdl"))
            zip.write("model".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("test-model/conf/model.conf"))
            zip.write("config".toByteArray())
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
