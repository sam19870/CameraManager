package com.cameramanager.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import android.widget.Toast

/**
 * Handles alarm push button actions (e.g. dismiss / stop deterrence).
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.cameramanager.app.action.DISMISS_ALARM" -> {
                val id = intent.getIntExtra("notif_id", -1)
                if (id >= 0) AlarmNotifier.cancel(context, id)
            }
            "com.cameramanager.app.action.ALARM" -> {
                // Local vibration + sound deterrent on the phone side
                vibrate(context)
                Toast.makeText(context, "收到设备告警", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun vibrate(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1)
            )
        } else {
            @Suppress("DEPRECATION")
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            @Suppress("DEPRECATION")
            v.vibrate(longArrayOf(0, 300, 200, 300), -1)
        }
    }
}
