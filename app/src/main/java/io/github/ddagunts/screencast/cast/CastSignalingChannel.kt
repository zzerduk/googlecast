package io.github.ddagunts.screencast.cast

// Registration and session ownership operations require the main thread.
interface CastSignalingChannel {
    val isConnected: Boolean
    fun setMessageReceiver(receiver: (String) -> Unit)
    fun removeMessageReceiver()
    suspend fun send(message: String)
    fun endSession()
}

data class CastSdkSessionStatus(
    val isAvailable: Boolean = false,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val deviceName: String = "",
    val volume: Double = 0.0,
    val isMuted: Boolean = false,
    val isVolumeControlAvailable: Boolean = false,
    val volumeControlError: String? = null,
    val error: String? = null,
    val sessionGeneration: Long = 0
)
