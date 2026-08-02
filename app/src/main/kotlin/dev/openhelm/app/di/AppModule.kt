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
     * process dies.
     *
     * The connection and video loops treat **any** `Exception` as a failed attempt and retry with
     * backoff — the loops themselves are the recovery mechanism, and nothing that a single attempt
     * can throw is allowed to kill one. This handler is therefore the *last* line of defence, not
     * part of normal recovery: anything that still reaches it escaped the loops entirely (a bug in
     * the loop structure itself), and the right response is a loud log and a surviving process.
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
