package com.shslab.leo.hardware

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.shslab.leo.core.Logger

/**
 * ══════════════════════════════════════════
 *  LEO HARDWARE MANAGER — SHS LAB
 *
 *  Native Android hardware control via framework APIs.
 *  ZERO shell calls — no SecurityException.
 *  Uses CameraManager for torch (API 23+, always available API 29+).
 * ══════════════════════════════════════════
 */
class HardwareManager(private val context: Context) {

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** The first camera with a flash unit */
    private val flashCameraId: String? by lazy {
        try {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            Logger.error("[HW] Failed to enumerate cameras: ${e.message}")
            null
        }
    }

    /**
     * Toggle the device flashlight.
     * @param on true = flashlight ON, false = OFF
     * @return "ok" on success, error description on failure
     */
    fun setFlashlight(on: Boolean): String {
        val cameraId = flashCameraId
        if (cameraId == null) {
            Logger.warn("[HW] No flash hardware found on this device.")
            return "error: no flash hardware"
        }
        return try {
            cameraManager.setTorchMode(cameraId, on)
            val state = if (on) "ON" else "OFF"
            Logger.action("[HW] Flashlight → $state")
            "flashlight:$state"
        } catch (e: Exception) {
            Logger.error("[HW] Flashlight toggle failed: ${e.message}")
            "error: ${e.message}"
        }
    }

    /**
     * Vibrate the device using the Vibrator service.
     * Pattern: [delay_ms, vibrate_ms, sleep_ms, ...]
     */
    fun vibrate(durationMs: Long = 200L): String {
        return try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
            Logger.action("[HW] Vibrate → ${durationMs}ms")
            "vibrate:${durationMs}ms"
        } catch (e: Exception) {
            Logger.error("[HW] Vibrate failed: ${e.message}")
            "error: ${e.message}"
        }
    }

    /**
     * Get current battery level (0–100).
     */
    fun getBatteryLevel(): String {
        return try {
            val iFilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val intent = context.registerReceiver(null, iFilter)
            val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            Logger.action("[HW] Battery → $pct%")
            "battery:${pct}%"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    companion object {
        const val TARGET_FLASHLIGHT = "flashlight"
        const val TARGET_VIBRATE    = "vibrate"
        const val TARGET_BATTERY    = "battery"
    }
}
