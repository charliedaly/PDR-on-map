package com.example.irishhike.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import com.example.irishhike.repository.HikeRepository

/**
 * High-priority receiver that intercepts Bluetooth and wired headset buttons
 * before external media apps (like podcast or music players) can react.
 */
class HikeMediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_MEDIA_BUTTON) return

        val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        } ?: return

        // Only handle key press down (ignore key release up to avoid double triggering)
        if (keyEvent.action == KeyEvent.ACTION_DOWN) {
            Log.d("HikeMediaButton", "Received media key code: ${keyEvent.keyCode}")

            var handled = false
            when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    // Single click / Center button: Speak bearing and distance to target waypoint
                    HikeRepository.announceBearingAndDistance()
                    handled = true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    // Next button / Double click: Advance to next leg
                    HikeRepository.advanceToNextLeg()
                    handled = true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    // Previous button / Triple click: Go to previous leg
                    HikeRepository.goToPreviousLeg()
                    handled = true
                }
            }

            if (handled) {
                // Abort broadcast so podcast/music players never receive this event
                if (isOrderedBroadcast) {
                    abortBroadcast()
                }
            }
        }
    }
}