package com.sistermesh.app.voice

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

sealed interface OfflineModelState {
    data object NotInstalled : OfflineModelState
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val installing: Boolean = false
    ) : OfflineModelState {
        val progress: Float
            get() = if (totalBytes <= 0) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }
    data object Ready : OfflineModelState
    data class Failed(val message: String) : OfflineModelState
}

internal data class VoiceModelSpec(
    val version: String,
    val url: String,
    val archiveBytes: Long,
    val sha256: String,
    val archiveDirectory: String
)

/**
 * Downloads and verifies the small English Vosk model for Android versions where the
 * platform recognizer cannot request an offline model. The partial archive is retained
 * after network interruptions and resumed with HTTP Range on the next attempt.
 */
class OfflineVoiceModelInstaller internal constructor(
    private val modelsDirectory: File,
    private val client: OkHttpClient,
    private val spec: VoiceModelSpec,
    private val availableBytes: () -> Long
) {
    internal constructor(context: Context) : this(
        modelsDirectory = File(context.applicationContext.filesDir, "offline-voice-models"),
        client = OkHttpClient(),
        spec = DEFAULT_MODEL,
        availableBytes = { StatFs(context.applicationContext.filesDir.path).availableBytes }
    )

    private val modelDirectory = File(modelsDirectory, spec.version)
    private val partialArchive = File(modelsDirectory, "${spec.version}.zip.part")
    private val _state = MutableStateFlow<OfflineModelState>(
        if (isInstalled()) OfflineModelState.Ready else OfflineModelState.NotInstalled
    )
    val state: StateFlow<OfflineModelState> = _state

    @Volatile
    private var activeCall: Call? = null

    val modelPath: String get() = modelDirectory.absolutePath

    @Synchronized
    fun ensureInstalled() {
        if (isInstalled()) {
            _state.value = OfflineModelState.Ready
            return
        }
        if (activeCall?.isExecuted() == true && activeCall?.isCanceled() == false) return

        if (availableBytes() < MINIMUM_FREE_BYTES) {
            _state.value = OfflineModelState.Failed(
                "Free at least 130 MB of storage to set up offline voice."
            )
            return
        }

        if (!modelsDirectory.exists() && !modelsDirectory.mkdirs()) {
            _state.value = OfflineModelState.Failed("Could not create secure storage for the voice model.")
            return
        }
        if (partialArchive.length() == spec.archiveBytes) {
            _state.value = OfflineModelState.Downloading(spec.archiveBytes, spec.archiveBytes, installing = true)
            Thread({
                try {
                    verifyAndInstall()
                    _state.value = OfflineModelState.Ready
                } catch (error: Exception) {
                    _state.value = OfflineModelState.Failed(
                        error.message ?: "Could not install the offline voice model. Tap retry."
                    )
                }
            }, "sistermesh-voice-model-install").start()
            return
        }
        if (partialArchive.length() > spec.archiveBytes) partialArchive.delete()
        val offset = partialArchive.length()
        val request = Request.Builder()
            .url(spec.url)
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()
        _state.value = OfflineModelState.Downloading(offset, spec.archiveBytes)
        val call = client.newCall(request)
        activeCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(this@OfflineVoiceModelInstaller) {
                    activeCall = null
                    _state.value = OfflineModelState.Failed(
                        "Download interrupted. Connect to the internet and tap retry."
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val append = when {
                            offset > 0 && response.code == 206 -> {
                                val range = response.header("Content-Range").orEmpty()
                                if (!range.startsWith("bytes $offset-")) {
                                    throw IOException("The server returned an unexpected download range.")
                                }
                                true
                            }
                            response.code == 200 -> false
                            else -> throw IOException("Model download returned HTTP ${response.code}.")
                        }
                        val body = response.body ?: throw IOException("Model download was empty.")
                        val baseBytes = if (append) offset else 0L
                        val responseLength = body.contentLength().takeIf { it >= 0 } ?: (spec.archiveBytes - baseBytes)
                        val totalBytes = (baseBytes + responseLength).coerceAtMost(spec.archiveBytes)
                        body.byteStream().use { input ->
                            FileOutputStream(partialArchive, append).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var downloaded = baseBytes
                                var lastUpdate = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    downloaded += count
                                    if (downloaded - lastUpdate >= PROGRESS_INTERVAL_BYTES) {
                                        lastUpdate = downloaded
                                        _state.value = OfflineModelState.Downloading(downloaded, totalBytes)
                                    }
                                }
                                output.fd.sync()
                            }
                        }
                        if (partialArchive.length() != spec.archiveBytes) {
                            throw IOException("The model download is incomplete; retry to resume it.")
                        }
                        _state.value = OfflineModelState.Downloading(spec.archiveBytes, spec.archiveBytes, installing = true)
                        verifyAndInstall()
                        synchronized(this@OfflineVoiceModelInstaller) {
                            activeCall = null
                            _state.value = OfflineModelState.Ready
                        }
                    } catch (error: Exception) {
                        synchronized(this@OfflineVoiceModelInstaller) {
                            activeCall = null
                            if (error.message?.contains("checksum", ignoreCase = true) == true) {
                                partialArchive.delete()
                            }
                            _state.value = OfflineModelState.Failed(
                                error.message ?: "Could not install the offline voice model. Tap retry."
                            )
                        }
                    }
                }
            }
        })
    }

    @Synchronized
    private fun isInstalled(): Boolean =
        File(modelDirectory, "am/final.mdl").isFile &&
            File(modelDirectory, "conf/model.conf").isFile

    private fun verifyAndInstall() {
        val actualHash = FileInputStream(partialArchive).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (!actualHash.equals(spec.sha256, ignoreCase = true)) {
            throw IOException("Model checksum verification failed; retry the download.")
        }

        modelsDirectory.listFiles()
            ?.filter { it.name.startsWith(".installing-") }
            ?.forEach(File::deleteRecursively)
        val stagingDirectory = File(modelsDirectory, ".installing-${UUID.randomUUID()}")
        if (!stagingDirectory.mkdirs()) throw IOException("Could not prepare model installation.")
        var uncompressedBytes = 0L
        try {
            ZipInputStream(FileInputStream(partialArchive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val normalizedEntry = entry.name.replace('\\', '/')
                    val relativePath = normalizedEntry.trimEnd('/')
                    if (relativePath.isBlank() ||
                        relativePath.startsWith("/") ||
                        relativePath.split('/').any { it == ".." || it.isEmpty() }
                    ) {
                        throw IOException("The model archive contains an unsafe file path.")
                    }
                    val destination = File(stagingDirectory, relativePath).canonicalFile
                    if (!destination.toPath().startsWith(stagingDirectory.canonicalFile.toPath())) {
                        throw IOException("The model archive contains an unsafe file path.")
                    }
                    if (entry.isDirectory) {
                        if (!destination.exists() && !destination.mkdirs()) {
                            throw IOException("Could not unpack the voice model.")
                        }
                    } else {
                        destination.parentFile?.let { parent ->
                            if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not unpack the voice model.")
                        }
                        FileOutputStream(destination).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                uncompressedBytes += count
                                if (uncompressedBytes > MAX_UNCOMPRESSED_BYTES) {
                                    throw IOException("The model archive is larger than expected.")
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }

            val extractedModel = File(stagingDirectory, spec.archiveDirectory)
            if (!File(extractedModel, "am/final.mdl").isFile ||
                !File(extractedModel, "conf/model.conf").isFile
            ) {
                throw IOException("The downloaded archive does not contain a usable speech model.")
            }

            if (modelDirectory.exists() && !modelDirectory.deleteRecursively()) {
                throw IOException("Could not replace the incomplete voice model.")
            }
            if (!extractedModel.renameTo(modelDirectory)) {
                throw IOException("Could not finish installing the voice model.")
            }
        } finally {
            stagingDirectory.deleteRecursively()
        }
        partialArchive.delete()
    }

    companion object {
        private const val MINIMUM_FREE_BYTES = 130L * 1024 * 1024
        private const val MAX_UNCOMPRESSED_BYTES = 110L * 1024 * 1024
        private const val PROGRESS_INTERVAL_BYTES = 512L * 1024

        internal val DEFAULT_MODEL = VoiceModelSpec(
            version = "vosk-model-small-en-us-0.15",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            archiveBytes = 41_205_931L,
            sha256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498",
            archiveDirectory = "vosk-model-small-en-us-0.15"
        )

        @Volatile
        private var sharedInstance: OfflineVoiceModelInstaller? = null

        fun get(context: Context): OfflineVoiceModelInstaller =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: OfflineVoiceModelInstaller(context.applicationContext).also {
                    sharedInstance = it
                }
            }
    }
}
