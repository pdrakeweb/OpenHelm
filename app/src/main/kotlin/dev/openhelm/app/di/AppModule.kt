package dev.openhelm.app.di

import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-lifetime scope for the connection and discovery machinery, which must outlive any
 * one screen: rotating the phone must not drop the control channel.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * The scope everything long-lived runs in.
     *
     * [SupervisorJob] stops one failed child from cancelling its siblings, but on its own it does
     * not stop a failure from reaching the thread's default handler, which on Android means the
     * process dies. The socket loops catch `IOException` because that is the expected failure; a
     * `NullPointerException` from a malformed RTSP response, or an `IllegalStateException` out of
     * `MediaCodec`, is not caught anywhere and used to take the whole app down — including the
     * control channel, which is the one part that must survive video going wrong.
     *
     * The handler makes those failures loud but survivable: the affected coroutine still dies and
     * its own reconnect logic still applies, while the rest of the app keeps running.
     */
    @Provides
    @Singleton
    @AppScope
    fun appScope(): CoroutineScope {
        val handler = CoroutineExceptionHandler { context, throwable ->
            Log.e(TAG, "Unhandled failure in $context", throwable)
        }
        return CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    }
}

private const val TAG = "OpenHelm"
