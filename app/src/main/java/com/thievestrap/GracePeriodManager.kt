package com.thievestrap

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

object GracePeriodManager {

    private val TAG = "TT-Grace"
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRunnable: Runnable? = null
    private var pendingPhotoFile: File? = null
    private var pendingCount = 0
    private var isWaiting = false

    // Called immediately when wrong PIN is entered
    // photoFile: the selfie already taken
    // onSendAlert: called after grace period if not cancelled
    fun startGrace(
        context: Context,
        wrongCount: Int,
        photoFile: File?,
        onSendAlert: () -> Unit
    ) {
        val prefs = context.getSharedPreferences("tt_prefs", Context.MODE_PRIVATE)
        val graceSecs = prefs.getInt("grace_period", 30)

        // Cancel any previous pending alert
        cancelGrace()

        pendingPhotoFile = photoFile
        pendingCount = wrongCount
        isWaiting = true

        Log.i(TAG, "Grace period started: ${graceSecs}s, photo=${photoFile?.name}")

        pendingRunnable = Runnable {
            if (isWaiting) {
                isWaiting = false
                Log.i(TAG, "Grace expired — sending alert")
                onSendAlert()
            }
        }
        handler.postDelayed(pendingRunnable!!, graceSecs * 1000L)
    }

    // Called when phone is successfully unlocked — cancel everything
    fun cancelGrace() {
        if (isWaiting) {
            Log.i(TAG, "Grace cancelled — phone unlocked by owner")
            isWaiting = false
            pendingRunnable?.let { handler.removeCallbacks(it) }
            pendingRunnable = null

            // Delete the selfie photo (false alarm)
            pendingPhotoFile?.let { file ->
                if (file.exists()) {
                    file.delete()
                    Log.i(TAG, "Selfie deleted (owner unlocked)")
                }
            }
            pendingPhotoFile = null
        }
    }

    fun isActive() = isWaiting
}
