package com.tvtune.music.playback

import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.pow

class TvVolumeNormalizer(
    private val player: ExoPlayer
) : Player.Listener {

    companion object {
        private const val TAG = "TvVolumeNormalizer"
        // Target loudness: -13.0 LUFS (ideal balance for TV speakers and headphones)
        private const val TARGET_LUFS = -13.0
    }

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentSessionId: Int = -1
    private var lastLoudnessDb: Double? = null
    private var lastPerceptualLoudnessDb: Double? = null

    var isEnabled: Boolean = true
        set(value) {
            field = value
            applyCurrentNormalization()
        }

    init {
        player.addListener(this)
        val initialSession = player.audioSessionId
        if (initialSession > 0) {
            setupEnhancer(initialSession)
        }
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        Log.i(TAG, "Audio session ID changed: $audioSessionId (previous: $currentSessionId)")
        if (audioSessionId > 0 && audioSessionId != currentSessionId) {
            setupEnhancer(audioSessionId)
        }
    }

    private fun setupEnhancer(sessionId: Int) {
        releaseEnhancer()
        currentSessionId = sessionId
        try {
            loudnessEnhancer = LoudnessEnhancer(sessionId).apply {
                enabled = isEnabled
            }
            Log.i(TAG, "LoudnessEnhancer attached to session $sessionId")
            applyCurrentNormalization()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize LoudnessEnhancer: ${e.message}")
            loudnessEnhancer = null
        }
    }

    fun onTrackLoaded(loudnessDb: Double?, perceptualLoudnessDb: Double?) {
        lastLoudnessDb = loudnessDb
        lastPerceptualLoudnessDb = perceptualLoudnessDb
        applyCurrentNormalization()
    }

    private fun applyCurrentNormalization() {
        if (!isEnabled) {
            player.volume = 1.0f
            try {
                loudnessEnhancer?.setTargetGain(0)
                loudnessEnhancer?.enabled = false
            } catch (_: Exception) {}
            Log.d(TAG, "Normalization disabled -> volume 1.0, enhancer off")
            return
        }

        // Determine effective measured LUFS:
        // YouTube perceptualLoudnessDb is in LUFS (e.g. -14.0, -19.5, -9.0).
        // loudnessDb is relative to -7.0 LUFS.
        val measuredLufs = lastPerceptualLoudnessDb?.takeIf { it.isFinite() }
            ?: lastLoudnessDb?.takeIf { it.isFinite() }?.let { it - 7.0 }
            ?: -18.5 // Fallback for video streams / unmeasured tracks

        val diffDb = TARGET_LUFS - measuredLufs
        Log.i(TAG, "Applying volume normalization: measured=${"%.1f".format(measuredLufs)} LUFS, target=$TARGET_LUFS, diff=${"%.1f".format(diffDb)} dB")

        if (diffDb <= 0.0) {
            // Track is louder than target (e.g. loud pop music at -9 LUFS):
            // Attenuate player volume so it matches quieter songs
            val attenuationGain = 10.0.pow(diffDb / 20.0).toFloat().coerceIn(0.2f, 1.0f)
            player.volume = attenuationGain
            try {
                loudnessEnhancer?.setTargetGain(0)
                loudnessEnhancer?.enabled = false
            } catch (_: Exception) {}
            Log.i(TAG, "Loud track attenuated: player.volume=${"%.2f".format(attenuationGain)}, booster=0mB")
        } else {
            // Track is quieter than target (e.g. Cafeteria Song / cartoon soundtracks at -19 LUFS):
            // Set player volume to full 1.0f and boost hardware DSP via LoudnessEnhancer
            player.volume = 1.0f
            val boostMb = ((diffDb * 100.0).toInt()).coerceIn(0, 1000) // Up to +10 dB boost
            try {
                if (loudnessEnhancer == null && currentSessionId > 0) {
                    setupEnhancer(currentSessionId)
                }
                loudnessEnhancer?.setTargetGain(boostMb)
                loudnessEnhancer?.enabled = true
                Log.i(TAG, "Quiet track boosted: player.volume=1.0, booster=+${boostMb}mB (+${"%.1f".format(boostMb / 100.0)} dB)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply LoudnessEnhancer boost: ${e.message}")
            }
        }
    }

    private fun releaseEnhancer() {
        try {
            loudnessEnhancer?.release()
        } catch (_: Exception) {}
        loudnessEnhancer = null
    }

    fun release() {
        player.removeListener(this)
        releaseEnhancer()
    }
}
