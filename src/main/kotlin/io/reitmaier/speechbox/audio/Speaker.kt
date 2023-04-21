package io.reitmaier.speechbox.audio

import com.github.michaelbull.logging.InlineLogger
import com.github.michaelbull.result.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import io.reitmaier.speechbox.RawAudioFile
import io.reitmaier.speechbox.SpeakerError
import java.net.URL
import javax.sound.sampled.*
import kotlin.coroutines.resume

enum class AudioPrompt(val fileName: String){
  WELCOME("welcome.wav"),

  BEGIN_RECORDING("begin_recording.wav"),

  BEEP("beep.wav"),

  CONFIRMATION("confirmation.wav"),

  VOUCHER("voucher.wav"),

  NETWORK("network.wav"),

  QUESTIONNAIRE("questionnaire.wav"),

  NETWORK_ERROR("network_error.wav"),

  HARDWARE_ERROR("hardware_error.wav"),

  THANKS("thanks.wav"),
}


class Speaker(
  mixer: Mixer,
  private val language: String
) {
  private val clip: Clip = AudioSystem.getClip(mixer.mixerInfo)
  private var listener: LineListener? = null
  private val log = InlineLogger()
  fun stop() {
    clip.stop()
  }

  private fun getPromptAudioUrl(prompt: AudioPrompt): Result<URL, SpeakerError> {
    val soundUrl = javaClass.classLoader.getResource("$language/${prompt.fileName}")
      ?: return Err(SpeakerError("Could not find audio file for: $prompt", null))
    log.debug { "Resolved audio prompt: $prompt -> $soundUrl" }
    return Ok(soundUrl)
  }

  private suspend fun play(audioInputStream: AudioInputStream, name: String): Result<Unit, SpeakerError> =
    suspendCancellableCoroutine { cont ->

      stop()
      runCatching {
        clip.open(audioInputStream)

        // Remove old listeners
        listener?.let {
          clip.removeLineListener(it)
        }

        // Add new listener
        listener = LineListener {
          if (it.type === LineEvent.Type.STOP) { // must release so future playback can take place
            clip.close()
            log.debug { "Finished Playing Audio Clip: $name" }
            cont.resume(Ok(Unit))
          }
        }
        clip.addLineListener(listener)
        clip.start() // asynchronous
        cont.invokeOnCancellation {
          stop()
        }
      }.onFailure {
        cont.resume(Err(SpeakerError("Error playing audio", it)))
      }
    }

  suspend fun play(rawAudioFile: RawAudioFile): Result<Unit, SpeakerError> =
    withContext(Dispatchers.IO) {
      log.info { "Playing Audio File: ${rawAudioFile.value.name}" }
      runCatching {
        AudioSystem.getAudioInputStream(rawAudioFile.value)
      }.mapError { SpeakerError("Error opening audio input stream of ${rawAudioFile.value}", it) }
        .andThen { play(it, rawAudioFile.toString()) }
        .onSuccess { "Finished Playing Audio File: ${rawAudioFile.value.name}" }
        .mapError { it.copy(message = "Error playing audio: ${rawAudioFile.value.name}") }
    }

  suspend fun play(prompt: AudioPrompt): Result<Unit, SpeakerError> =
    withContext(Dispatchers.IO) {
      log.info { "Playing Audio Prompt: $prompt" }
      getPromptAudioUrl(prompt)
        .andThen {
          runCatching {
            AudioSystem.getAudioInputStream(it)
          }.mapError { SpeakerError("Could not obtain audio input stream for: $prompt", it ) }
        }.andThen {
          play(it, prompt.toString())
            .mapError { error -> error.copy(message = "Error playing audio: $prompt") }
        }
    }


  companion object {
    fun fromName(name: String, language: String): Result<Speaker, SpeakerError> =
      runCatching {
        AudioSystem.getMixerInfo().map(AudioSystem::getMixer)
          .find { mixer -> mixer.mixerInfo.name.startsWith(name) }.let {
            if (it != null) {
              Speaker(mixer = it, language = language)
            } else {
              throw Throwable("Could not find mixer with name: $name")
            }
          }
      }.mapError { SpeakerError("Could not initialise speaker $name", it) }
  }
}
