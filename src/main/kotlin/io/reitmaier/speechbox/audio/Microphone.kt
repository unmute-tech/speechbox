package io.reitmaier.speechbox.audio

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.mapError
import com.github.michaelbull.result.runCatching
import io.reitmaier.speechbox.MicError
import javax.sound.sampled.*

class Microphone internal constructor(
  mixer: Mixer,
  val audioFormat: AudioFormat = AudioFormat(
    16000f,
    16,
    1,
    true,
    false
  ),
  dataLineInfo: DataLine.Info = DataLine.Info(TargetDataLine::class.java, audioFormat),
) {
  val targetDataLine = (mixer.getLine(dataLineInfo) as TargetDataLine)

  companion object {
    fun fromName(name: String = "MICROPHONE"): Result<Microphone, MicError> =
      runCatching {
        AudioSystem.getMixerInfo().map(AudioSystem::getMixer)
          .find { mixer -> mixer.mixerInfo.name.startsWith(name) }.let {
            if(it != null) {
              Microphone(it)
            } else {
              throw Throwable("Could not find mixer with name: $name")
            }
          }
      }.mapError { MicError("Could not initialise microphone $name", it) }

    @Suppress("unused")
    fun printMixers() {

      val mixerInfo = AudioSystem.getMixerInfo()
      println("####")
      for(info in mixerInfo) {
        val m = AudioSystem.getMixer(info)
        println("####")
        println(m.details)

        println("####")
      }
      AudioSystem.getMixerInfo().map(AudioSystem::getMixer)
        .forEach { mixer -> println("mixer info: ${mixer.mixerInfo}\nsourceLineInfo: ${mixer.sourceLineInfo.contentDeepToString()}\ntargetLineInfo: ${mixer.targetLineInfo.contentDeepToString()}\n\n") }
    }

    private inline val Mixer.details: String
      get() = mixerInfo.let {
        """Mixer:
        |type: ${this.javaClass.simpleName}
        |name: ${it.name}
        |version: ${it.version}
        |vendor: ${it.vendor}
        |description: ${it.description}
        |source lines: ${sourceLineInfo.contentDeepToString()}
        |target lines: ${targetLineInfo.contentDeepToString()}
        """.trimMargin()
      }
  }

}