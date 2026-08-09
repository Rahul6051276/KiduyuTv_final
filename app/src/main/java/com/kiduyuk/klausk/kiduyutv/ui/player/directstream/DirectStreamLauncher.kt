package com.kiduyuk.klausk.kiduyutv.ui.player.directstream

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.kiduyuk.klausk.kiduyutv.util.AdFallbackDispatcher
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single launch boundary for native direct playback.
 *
 * An interstitial is requested before the player opens. The ad dispatcher
 * always completes its callback when an ad is dismissed, unavailable, or
 * disabled, so playback navigation can never be blocked by ad inventory.
 */
object DirectStreamLauncher {

    fun launch(
        context: Context,
        intent: Intent,
        onLaunched: () -> Unit = {}
    ) {
        val activity = context.findActivity()
        val launched = AtomicBoolean(false)
        val openPlayer = {
            if (launched.compareAndSet(false, true)) {
                if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                    activity.startActivity(intent)
                } else {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.applicationContext.startActivity(intent)
                }
                onLaunched()
            }
        }

        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            openPlayer()
        } else {
            AdFallbackDispatcher.showInterstitial(activity) { openPlayer() }
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
