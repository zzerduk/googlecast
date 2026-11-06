package io.github.ddagunts.screencast.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import io.github.ddagunts.screencast.webrtc.WEBRTC_RECEIVER_APP_ID
import java.lang.reflect.Field

class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val options = CastOptions.Builder()
            .setReceiverApplicationId(WEBRTC_RECEIVER_APP_ID)
            .setResumeSavedSession(false)
            .setEnableReconnectionService(false)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
        clearCastMediaOptions(options)
        return options
    }

    private fun clearCastMediaOptions(options: CastOptions) {
        val mediaFields = CastOptions::class.java.declaredFields
            .filter { it.type == CastMediaOptions::class.java }
        if (mediaFields.isEmpty()) return
        for (field in mediaFields) {
            field.isAccessible = true
            if (runCatching { field.set(options, null) }.isSuccess) continue
            runCatching {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val unsafeInstance = unsafeClass.declaredFields
                    .first { it.type == unsafeClass }
                    .apply { isAccessible = true }
                    .get(null)
                val offset = unsafeClass
                    .getMethod("objectFieldOffset", Field::class.java)
                    .invoke(unsafeInstance, field) as Long
                unsafeClass
                    .getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
                    .invoke(unsafeInstance, options, offset, null)
            }
        }
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
