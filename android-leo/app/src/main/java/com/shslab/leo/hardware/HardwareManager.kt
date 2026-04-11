package com.shslab.leo.hardware

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.shslab.leo.core.Logger

/**
 * ══════════════════════════════════════════
 *  LEO HARDWARE MANAGER — SHS LAB v4
 *  Absolute Sovereignty Edition
 *
 *  Expanded hardware control:
 *  • Flashlight (CameraManager)
 *  • Vibration
 *  • Battery level
 *  • Volume (AudioManager)
 *  • Brightness (Settings.System or panel)
 *  • WiFi (Settings.Panel API 29+)
 *  • Camera (Intent launch)
 *
 *  Zero shell calls. Zero SecurityException.
 * ══════════════════════════════════════════
 */
class HardwareManager(private val context: Context) {

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

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

    // ── Flashlight ─────────────────────────────────────────────────────

    fun setFlashlight(on: Boolean): String {
        val cameraId = flashCameraId
            ?: return "error:no_flash_hardware"
        return try {
            cameraManager.setTorchMode(cameraId, on)
            val state = if (on) "ON" else "OFF"
            Logger.action("[HW] Flashlight → $state")
            "flashlight:$state"
        } catch (e: Exception) {
            Logger.error("[HW] Flashlight toggle failed: ${e.message}")
            "error:${e.message}"
        }
    }

    // ── Vibrate ────────────────────────────────────────────────────────

    fun vibrate(durationMs: Long = 200L): String {
        return try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
            Logger.action("[HW] Vibrate → ${durationMs}ms")
            "vibrate:${durationMs}ms"
        } catch (e: Exception) {
            Logger.error("[HW] Vibrate failed: ${e.message}")
            "error:${e.message}"
        }
    }

    // ── Battery ────────────────────────────────────────────────────────

    fun getBatteryLevel(): String {
        return try {
            val iFilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val intent  = context.registerReceiver(null, iFilter)
            val level   = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale   = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct     = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            Logger.action("[HW] Battery → $pct%")
            "battery:${pct}%"
        } catch (e: Exception) {
            "error:${e.message}"
        }
    }

    // ── Volume ─────────────────────────────────────────────────────────

    /**
     * Set media volume. Android max is typically 15 for STREAM_MUSIC.
     * @param value "0"-"15" or "up" / "down" / "mute" / "max"
     */
    fun setVolume(value: String): String {
        return try {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val newVol = when (value.lowercase().trim()) {
                "up"   -> (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + 1).coerceAtMost(maxVol)
                "down" -> (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) - 1).coerceAtLeast(0)
                "mute", "0" -> 0
                "max"  -> maxVol
                else   -> value.toIntOrNull()?.coerceIn(0, maxVol) ?: return "error:invalid_volume_value"
            }
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
            Logger.action("[HW] Volume → $newVol/$maxVol")
            "volume:$newVol/$maxVol"
        } catch (e: Exception) {
            Logger.error("[HW] Volume failed: ${e.message}")
            "error:${e.message}"
        }
    }

    // ── Brightness ─────────────────────────────────────────────────────

    /**
     * Set screen brightness (0–255).
     * Requires WRITE_SETTINGS permission — if not granted, opens the brightness panel.
     * @param value "0"-"255" or "low"/"medium"/"high"/"max"
     */
    fun setBrightness(value: String): String {
        val targetVal = when (value.lowercase().trim()) {
            "low"    -> 30
            "medium" -> 128
            "high"   -> 200
            "max"    -> 255
            "auto"   -> -1
            else     -> value.toIntOrNull()?.coerceIn(0, 255) ?: 128
        }

        return if (Settings.System.canWrite(context)) {
            try {
                if (targetVal < 0) {
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    )
                    Logger.action("[HW] Brightness → AUTO")
                    "brightness:auto"
                } else {
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    )
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        targetVal
                    )
                    Logger.action("[HW] Brightness → $targetVal/255")
                    "brightness:$targetVal/255"
                }
            } catch (e: Exception) {
                Logger.error("[HW] Brightness failed: ${e.message}")
                "error:${e.message}"
            }
        } else {
            // No WRITE_SETTINGS — open the settings panel
            Logger.warn("[HW] WRITE_SETTINGS not granted — opening Display Settings")
            try {
                val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                "brightness:panel_opened:grant_WRITE_SETTINGS_for_direct_control"
            } catch (e: Exception) {
                "error:${e.message}"
            }
        }
    }

    // ── WiFi ───────────────────────────────────────────────────────────

    /**
     * Toggle WiFi. On API 29+, direct enable/disable is restricted.
     * Uses Settings.Panel.ACTION_WIFI for user-facing toggle.
     * @param value "on" | "off" | "panel"
     */
    fun setWifi(value: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val panelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(panelIntent)
                Logger.action("[HW] WiFi panel → opened (API29+ restriction)")
                "wifi:panel_opened:tap_to_toggle"
            } else {
                @Suppress("DEPRECATION")
                val wifiManager = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                val enable = value.lowercase() == "on"
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = enable
                val state = if (enable) "ON" else "OFF"
                Logger.action("[HW] WiFi → $state")
                "wifi:$state"
            }
        } catch (e: Exception) {
            Logger.error("[HW] WiFi failed: ${e.message}")
            "error:${e.message}"
        }
    }

    // ── Camera ─────────────────────────────────────────────────────────

    /**
     * Open the system camera app via Intent.
     * No camera capture permission needed — just launches the camera UI.
     */
    fun openCamera(): String {
        return try {
            val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Logger.action("[HW] Camera → opened")
            "camera:opened"
        } catch (e: Exception) {
            // Fallback: open any camera app
            try {
                val fallback = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_GALLERY)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
                "camera:fallback_gallery_opened"
            } catch (e2: Exception) {
                Logger.error("[HW] Camera open failed: ${e2.message}")
                "error:${e2.message}"
            }
        }
    }

    companion object {
        const val TARGET_FLASHLIGHT = "flashlight"
        const val TARGET_VIBRATE    = "vibrate"
        const val TARGET_BATTERY    = "battery"
        const val TARGET_VOLUME     = "volume"
        const val TARGET_BRIGHTNESS = "brightness"
        const val TARGET_WIFI       = "wifi"
        const val TARGET_CAMERA     = "camera"
    }
}
