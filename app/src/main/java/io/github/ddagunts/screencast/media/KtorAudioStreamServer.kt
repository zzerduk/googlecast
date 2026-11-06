package io.github.ddagunts.screencast.media

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Embedded Ktor HTTP Server streaming live ADTS AAC frames over HTTP progressive chunked transfer.
 *
 * Phase 3.3 & Phase 4.2 Implementation:
 * - Hot [MutableSharedFlow] with `replay = 0`, `extraBufferCapacity = 16`, `BufferOverflow.DROP_OLDEST`.
 * - Endpoint `/c/{token}/stream.aac` responds with `200 OK`, `Content-Type: audio/aac`,
 *   `Cache-Control: no-cache, no-store`, `Connection: keep-alive`, and `Transfer-Encoding: chunked`.
 * - Immediately upon TCP connection establishment, writes and flushes `~0.3s` (configurable via
 *   [AudioStreamConfig.preBurstBufferMs]) of pre-encoded silent ADTS AAC frames in a single burst
 *   so the 1st-Gen Google Home's Chromium audio pipeline fills its initial decode buffer in `<10ms`.
 * - Collects live ADTS frames from [adtsSharedFlow], writes each frame to the `ByteWriteChannel`,
 *   and calls `flush()` immediately after every frame.
 */
class KtorAudioStreamServer(
    private val configProvider: () -> AudioStreamConfig,
    private val onServerEvent: (String) -> Unit = {},
    private val onTelemetryUpdate: (connectedClients: Int, totalBytes: Long, lastPreBurstFrames: Int) -> Unit = { _, _, _ -> }
) {
    /**
     * Zero-Buffer Live Edge hot SharedFlow required by Phase 3.3.
     */
    private val _adtsSharedFlow = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val adtsSharedFlow: SharedFlow<ByteArray> = _adtsSharedFlow.asSharedFlow()

    private var engine: CIOApplicationEngine? = null
    private val connectedClientCount = AtomicInteger(0)
    private val totalBytesStreamed = AtomicLong(0L)

    @Volatile
    var activePort: Int = 0
        private set

    @Volatile
    var streamToken: String = UUID.randomUUID().toString().replace("-", "").take(12)
        private set

    fun emitAdtsFrame(adtsPacket: ByteArray) {
        _adtsSharedFlow.tryEmit(adtsPacket)
    }

    suspend fun startServer(preferredPort: Int = 0): Int {
        if (engine != null && activePort > 0) return activePort

        streamToken = UUID.randomUUID().toString().replace("-", "").take(12)
        val server = embeddedServer(CIO, port = preferredPort, host = "0.0.0.0") {
            routing {
                get("/health") {
                    call.respondText("OK - ScreenCast Audio ADTS Server", ContentType.Text.Plain)
                }

                get("/c/{token}/stream.aac") {
                    val requestedToken = call.parameters["token"].orEmpty()
                    if (requestedToken != streamToken) {
                        call.respondText(
                            "Invalid stream token",
                            ContentType.Text.Plain,
                            HttpStatusCode.Forbidden
                        )
                        return@get
                    }

                    val currentConfig = configProvider()
                    val remoteHost = call.request.local.remoteHost

                    call.response.header(HttpHeaders.CacheControl, "no-cache, no-store")
                    call.response.header(HttpHeaders.Pragma, "no-cache")
                    call.response.header(HttpHeaders.Connection, "keep-alive")
                    call.response.header(HttpHeaders.TransferEncoding, "chunked")

                    val activeClients = connectedClientCount.incrementAndGet()
                    val preBurstFrames = currentConfig.preBurstFrameCount
                    onServerEvent(
                        "Speaker connected to /c/$streamToken/stream.aac from $remoteHost " +
                            "(Pre-bursting $preBurstFrames silent ADTS frames = ${currentConfig.preBurstBufferMs}ms)"
                    )
                    onTelemetryUpdate(activeClients, totalBytesStreamed.get(), preBurstFrames)

                    try {
                        call.respondBytesWriter(
                            contentType = ContentType("audio", "aac"),
                            status = HttpStatusCode.OK
                        ) {
                            // Phase 4.2 Latency Reduction Hack (Pre-burst):
                            // Immediately write and flush ~0.3s (or user-configured ms) of pre-encoded
                            // silent ADTS AAC frames in a single burst upon TCP connection establishment.
                            if (preBurstFrames > 0) {
                                val silentFrame = AdtsAacEncoder.createSilentAdtsFrame(
                                    sampleRateIndex = currentConfig.sampleRateIndex,
                                    channelCount = 2
                                )
                                val burstPayload = ByteArray(silentFrame.size * preBurstFrames)
                                for (i in 0 until preBurstFrames) {
                                    System.arraycopy(
                                        silentFrame,
                                        0,
                                        burstPayload,
                                        i * silentFrame.size,
                                        silentFrame.size
                                    )
                                }
                                writeFully(burstPayload)
                                flush()
                                val bytesAfterBurst = totalBytesStreamed.addAndGet(burstPayload.size.toLong())
                                onTelemetryUpdate(connectedClientCount.get(), bytesAfterBurst, preBurstFrames)
                            }

                            // Phase 3.3 Live Edge Collection:
                            // Collect each live ADTS frame and flush immediately so Ktor never buffers 4KB-8KB.
                            adtsSharedFlow.collect { adtsFrame ->
                                writeFully(adtsFrame)
                                flush()
                                val updatedBytes = totalBytesStreamed.addAndGet(adtsFrame.size.toLong())
                                onTelemetryUpdate(connectedClientCount.get(), updatedBytes, preBurstFrames)
                            }
                        }
                    } catch (_: CancellationException) {
                        // Normal disconnect when stream stops
                    } catch (e: Exception) {
                        Log.i(TAG, "Stream client disconnected ($remoteHost): ${e.message}")
                    } finally {
                        val remaining = connectedClientCount.decrementAndGet().coerceAtLeast(0)
                        onTelemetryUpdate(remaining, totalBytesStreamed.get(), preBurstFrames)
                        onServerEvent("Speaker stream closed for $remoteHost (active clients=$remaining)")
                    }
                }
            }
        }

        server.start(wait = false)
        engine = server
        val resolvedPort = server.resolvedConnectors().firstOrNull()?.port ?: preferredPort
        activePort = resolvedPort
        onServerEvent("Ktor CIO HTTP chunked server listening on port $resolvedPort (/c/$streamToken/stream.aac)")
        return resolvedPort
    }

    fun buildStreamUrl(localIpAddress: String): String {
        return "http://$localIpAddress:$activePort/c/$streamToken/stream.aac"
    }

    fun stopServer() {
        val currentEngine = engine ?: return
        engine = null
        activePort = 0
        connectedClientCount.set(0)
        try {
            currentEngine.stop(gracePeriodMillis = 100, timeoutMillis = 300)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "KtorAudioStreamServer"
    }
}
