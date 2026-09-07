/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.fcitx.fcitx5.android.R
import timber.log.Timber
import java.util.concurrent.Executors

class ModelKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("ModelKeepAliveService onStartCommand: action=%s flags=%d startId=%d", intent?.action, flags, startId)
        if (intent?.action == ACTION_RELEASE) {
            release()
            return START_NOT_STICKY
        }
        ensureChannel()
        val releaseIntent = Intent(this, ModelKeepAliveService::class.java).apply {
            action = ACTION_RELEASE
        }
        val releasePending = PendingIntent.getService(
            this, 0, releaseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_keyboard_voice_24)
            .setContentTitle(getString(R.string.voice_input_keep_model_ready))
            .setContentText(getString(R.string.voice_input_model_keeping_ready))
            .setOngoing(true)
            .addAction(0, getString(R.string.voice_input_release_model), releasePending)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        Timber.d("ModelKeepAliveService foreground started")
        executor.execute {
            runCatching(LocalMnnEngine::prewarm)
                .onSuccess { Timber.d("ModelKeepAliveService prewarm succeeded") }
                .onFailure { Timber.e(it, "ModelKeepAliveService prewarm failed") }
        }
        return START_STICKY
    }

    private fun release() {
        Timber.d("ModelKeepAliveService release requested")
        VoiceInputPreferences.setKeepModelReady(false)
        executor.execute {
            runCatching(LocalMnnEngine::unload)
                .onFailure { Timber.e(it, "ModelKeepAliveService unload failed") }
            Timber.d("ModelKeepAliveService release complete")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.voice_input_model_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "voice_model_keep_alive"
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_RELEASE = "org.fcitx.fcitx5.android.action.RELEASE_VOICE_MODEL"
        private val executor = Executors.newSingleThreadExecutor()

        fun start(context: Context) {
            if (!VoiceInputPreferences.preferLocal() || !LocalMnnEngine.isReady()) return
            Timber.d("ModelKeepAliveService start: requesting foreground service")
            val intent = Intent(context, ModelKeepAliveService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            Timber.d("ModelKeepAliveService stop: requesting service stop")
            val intent = Intent(context, ModelKeepAliveService::class.java).apply {
                action = ACTION_RELEASE
            }
            context.startService(intent)
        }
    }
}
