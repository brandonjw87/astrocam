package com.example.astrocam.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Loud spoken + beeped countdown so you can hear it while walking away from the phone.
 *
 * Everything plays on the ALARM stream (which ignores media volume and is loud on Pixel's
 * speaker), and the alarm volume is temporarily pushed to max, then restored.
 */
class CountdownAnnouncer(context: Context) : TextToSpeech.OnInitListener {
  private val audio = context.getSystemService(AudioManager::class.java)
  private val tts = TextToSpeech(context.applicationContext, this)
  @Volatile private var ttsReady = false
  private var tone: ToneGenerator? =
    runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
  private var savedAlarmVolume: Int? = null

  override fun onInit(status: Int) {
    if (status != TextToSpeech.SUCCESS) return
    tts.language = Locale.getDefault().takeIf { tts.isLanguageAvailable(it) >= 0 } ?: Locale.US
    tts.setAudioAttributes(
      AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    )
    tts.setSpeechRate(1.15f)
    ttsReady = true
  }

  /** Push alarm volume to maximum for the countdown. */
  fun beginLoud() {
    try {
      if (savedAlarmVolume == null) savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
      audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
    } catch (e: SecurityException) {
      Log.w(TAG, "Can't change alarm volume (Do Not Disturb?)", e)
    }
  }

  /** Restore the user's alarm volume. */
  fun endLoud() {
    val v = savedAlarmVolume ?: return
    savedAlarmVolume = null
    runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) }
  }

  fun tick(secondsLeft: Int) {
    tone?.startTone(if (secondsLeft <= 3) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 180)
    speak(secondsLeft.toString())
  }

  /** Shutter about to open. */
  fun go() {
    tone?.startTone(ToneGenerator.TONE_DTMF_D, 700)
  }

  fun done() {
    tone?.startTone(ToneGenerator.TONE_PROP_ACK, 600)
    speak("Exposure complete. Your photo is being saved.", delayed = true)
  }

  fun failed() {
    tone?.startTone(ToneGenerator.TONE_PROP_NACK, 600)
    speak("Exposure failed.", delayed = true)
  }

  fun cancelled() {
    tts.stop()
    tone?.stopTone()
  }

  private fun speak(text: String, delayed: Boolean = false) {
    if (!ttsReady) return
    val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
    if (delayed) tts.playSilentUtterance(500, TextToSpeech.QUEUE_FLUSH, "pad")
    tts.speak(text, if (delayed) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, params, text)
  }

  fun shutdown() {
    endLoud()
    tts.stop()
    tts.shutdown()
    tone?.release()
    tone = null
  }

  companion object {
    private const val TAG = "AstroCam"
  }
}
