package com.cameramanager.app.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.cameramanager.app.data.model.Device
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

/**
 * Two-way voice intercom engine.
 *
 * Uses 8kHz 16-bit mono PCM (G.711-compatible payload) transported over a raw
 * TCP/UDP channel to the camera. The camera side protocol varies by vendor;
 * the [transport] parameter selects TCP (default, common for talk-back) or UDP.
 *
 *  - [start] opens the mic (uplink -> camera) and a playback track (downlink).
 *  - [stop] releases all native audio resources.
 */
class VoiceIntercom(
    private val device: Device,
    private val transport: Transport = Transport.TCP,
    private val onLevel: ((Int) -> Unit)? = null
) {

    enum class Transport { TCP, UDP }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var uplinkJob: Job? = null
    private var downlinkJob: Job? = null
    private var socket: Socket? = null
    private var udpSocket: DatagramSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var running = false

    private val sampleRate = 8000
    private val channelInMono = AudioFormat.CHANNEL_IN_MONO
    private val channelOutMono = AudioFormat.CHANNEL_OUT_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelInMono, encoding)
        if (minBuf <= 0) {
            Log.w(TAG, "AudioRecord min buffer invalid")
            return false
        }
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate, channelInMono, encoding, minBuf * 2
            )
            audioTrack = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                sampleRate, channelOutMono, encoding, minBuf * 2,
                AudioTrack.MODE_STREAM
            )
            when (transport) {
                Transport.TCP -> {
                    val s = Socket()
                    s.connect(java.net.InetSocketAddress(device.host, device.port + 2), 3000)
                    s.tcpNoDelay = true
                    socket = s
                }
                Transport.UDP -> {
                    udpSocket = DatagramSocket()
                }
            }
            running = true
            audioTrack?.play()
            uplinkJob = scope.launch { uplinkLoop(this@launch) }
            downlinkJob = scope.launch { downlinkLoop(this@launch) }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "start failed: ${e.message}")
            stop()
            return false
        }
    }

    /** Uplink: capture mic PCM and send to camera. */
    private suspend fun uplinkLoop(cs: CoroutineScope) {
        val bufSize = 640
        val buffer = ByteArray(bufSize)
        val ar = audioRecord ?: return
        ar.startRecording()
        while (running && cs.isActive) {
            val read = ar.read(buffer, 0, bufSize)
            if (read > 0) {
                reportLevel(buffer, read)
                try {
                    when (transport) {
                        Transport.TCP -> socket?.getOutputStream()?.write(buffer, 0, read)
                        Transport.UDP -> {
                            val addr = InetAddress.getByName(device.host)
                            udpSocket?.send(DatagramPacket(buffer, read, addr, device.port + 2))
                        }
                    }
                } catch (_: IOException) { /* peer may temporarily drop */ }
            }
        }
    }

    /** Downlink: receive audio from camera and play through speaker. */
    private suspend fun downlinkLoop(cs: CoroutineScope) {
        val buffer = ByteArray(640)
        val at = audioTrack ?: return
        while (running && cs.isActive) {
            try {
                when (transport) {
                    Transport.TCP -> {
                        val input = socket?.getInputStream() ?: return
                        val n = input.read(buffer)
                        if (n > 0) at.write(buffer, 0, n)
                    }
                    Transport.UDP -> {
                        val packet = DatagramPacket(buffer, buffer.size)
                        udpSocket?.receive(packet)
                        at.write(buffer, 0, packet.length)
                    }
                }
            } catch (_: IOException) { /* keep going */ }
        }
    }

    private fun reportLevel(buffer: ByteArray, len: Int) {
        if (onLevel == null) return
        var sum = 0
        var i = 0
        while (i + 1 < len) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            sum += kotlin.math.abs(sample)
            i += 2
        }
        val avg = sum / (len / 2)
        onLevel?.invoke(kotlin.math.min(100, avg / 320))
    }

    fun stop() {
        running = false
        uplinkJob?.cancel(); downlinkJob?.cancel()
        scope.cancel()
        runCatching { audioRecord?.stop() }
        runCatching { audioTrack?.stop() }
        runCatching { audioRecord?.release() }
        runCatching { audioTrack?.release() }
        runCatching { socket?.close() }
        runCatching { udpSocket?.close() }
        audioRecord = null; audioTrack = null; socket = null; udpSocket = null
    }

    companion object { private const val TAG = "VoiceIntercom" }
}
