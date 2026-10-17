package com.jarvis.assistant.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Tracks connected audio devices without taking over Android's global communication route.
 * AudioPlayer uses USAGE_MEDIA, so Android's normal media routing selects the active output
 * (speaker, wired headset, USB headset, or Bluetooth media device).
 */
class HeadsetAudioRouter(private val context: Context) {
    companion object { private const val TAG = "HeadsetAudioRouter" }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var isRegistered = false
    @Volatile private var cachedIsHeadsetConnected = false

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            Log.d(TAG, "Audio device broadcast: ${intent?.action}")
            refreshConnectedState()
        }
    }

    private val audioDeviceCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshConnectedState()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshConnectedState()
        }
    } else null

    fun start() {
        try {
            if (!isRegistered) {
                val filter = IntentFilter().apply {
                    addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                    addAction(Intent.ACTION_HEADSET_PLUG)
                    addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
                    addAction("android.bluetooth.adapter.action.STATE_CHANGED")
                    addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
                    addAction("android.bluetooth.device.action.ACL_CONNECTED")
                    addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
                }
                context.registerReceiver(headsetReceiver, filter)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
                    audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
                }
                isRegistered = true
            }
            refreshConnectedState()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio device monitor: ${e.message}", e)
        }
    }

    fun isHeadsetConnected(): Boolean = cachedIsHeadsetConnected

    private fun refreshConnectedState() {
        try {
            val connected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS)
                devices.any { isHeadsetType(it.type) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn || audioManager.isWiredHeadsetOn
            }
            cachedIsHeadsetConnected = connected
            Log.d(TAG, "External audio device connected=$connected; leaving media routing to Android")
        } catch (e: Exception) {
            Log.w(TAG, "Could not refresh audio-device state: ${e.message}")
        }
    }

    private fun isHeadsetType(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID -> true
        else -> false
    }

    fun routeToOptimalAudio() {
        // Intentionally do not set MODE_IN_COMMUNICATION, force speakerphone, or select a
        // communication device. Those APIs can reroute media into call-style/earpiece paths.
        refreshConnectedState()
    }

    fun stop() {
        try {
            if (isRegistered) {
                try { context.unregisterReceiver(headsetReceiver) } catch (_: Exception) {}
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
                    try { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) } catch (_: Exception) {}
                }
                isRegistered = false
            }
            cachedIsHeadsetConnected = false
            Log.i(TAG, "Audio device monitor stopped; Android media routing left untouched")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio device monitor: ${e.message}", e)
        }
    }
}
