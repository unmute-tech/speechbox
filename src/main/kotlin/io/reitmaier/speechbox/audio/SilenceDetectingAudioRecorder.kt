package io.reitmaier.speechbox.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.TargetDataLine
import javax.sound.sampled.AudioInputStream
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.*
import be.tarsos.dsp.Oscilloscope.OscilloscopeEventHandler
import be.tarsos.dsp.writer.WriterProcessor
import java.io.RandomAccessFile
import be.tarsos.dsp.io.jvm.JVMAudioInputStream
import com.github.michaelbull.logging.InlineLogger
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import javax.sound.sampled.LineUnavailableException
import java.io.FileNotFoundException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import io.reitmaier.speechbox.RawAudioFile
import io.reitmaier.speechbox.RecordingError
import java.io.File
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class SilenceDetectingAudioRecorder internal constructor(
  mic: Microphone,
  private val audioFormat: AudioFormat = mic.audioFormat,
  private val microphone: TargetDataLine = mic.targetDataLine,
  val maxDuration: Duration,
  private val silenceThreshold: Double,
) {
  private var numTotalSamplesRecorded = 0
  private var mRawOutputFile: File? = null
  private var mDispatcher: AudioDispatcher? = null
  private val log = InlineLogger()

  private val bufferToMsFactor = RECORDING_SAMPLE_RATE * RECORDING_CHANNELS / DSP_BUFFER_SIZE / 1000
  val durationMinimumBufferCount = (DSP_DURATION_MINIMUM_MS * bufferToMsFactor).roundToInt()
  val durationMaximumBufferCount = (maxDuration.inWholeMilliseconds * bufferToMsFactor).roundToInt()


  private fun canRecord(): Boolean {
    return mDispatcher == null
  }

  fun record(file: File,
             silenceThresholdDb: Double = silenceThreshold
  ): Result<Flow<FloatArray>, RecordingError> {
    if (!canRecord()) {
      return Err(
        RecordingError(
        message = "Can't record. Already Recording.",
        throwable = null,
      ))
    }
    mRawOutputFile = file
    file.delete()
    numTotalSamplesRecorded = 0
    val silenceDetector = SilenceDetector(silenceThresholdDb, false)
    var numSilentSamplesDetected = 0
    val silenceTimeoutBufferCount = (DSP_SILENCE_TIMEOUT_MS * bufferToMsFactor).roundToInt()
    try {
      if(!microphone.isOpen)
        microphone.open(audioFormat)
      microphone.start()
      log.info { "Recording audio to: ${file.name}" }
      val stream = AudioInputStream(microphone)
      val recordingFormat = TarsosDSPAudioFormat(RECORDING_SAMPLE_RATE,
        RECORDING_SAMPLE_SIZE, RECORDING_CHANNELS, RECORDING_SIGNED, RECORDING_BIG_ENDIAN)
      val recordingProcessor: AudioProcessor = WriterProcessor(recordingFormat,
        RandomAccessFile(mRawOutputFile, "rw"))

      //			mSilenceDetector = new SilenceDetector(mSilenceThreshold, false);
      val audioStream = JVMAudioInputStream(stream)
      val dispatcher = AudioDispatcher(audioStream, DSP_BUFFER_SIZE, DSP_OVERLAP)
      mDispatcher = dispatcher
      dispatcher.addAudioProcessor(recordingProcessor) // Saves recording to file
      dispatcher.addAudioProcessor(silenceDetector) // Detects silence
      Thread(dispatcher, "SilenceDetectingAudioRecorder").start()
      return Ok(callbackFlow {
        val oscilloscopeEventHandler = OscilloscopeEventHandler { data, _ ->
          if(!isClosedForSend)
            trySend(data)
        }
        dispatcher.addAudioProcessor(Oscilloscope(oscilloscopeEventHandler))
        val audioProcessor =
          object: AudioProcessor {
            override fun process(audioEvent: AudioEvent) : Boolean {
              val currentSPL = silenceDetector.currentSPL()
              if(currentSPL > silenceThresholdDb) {
                numSilentSamplesDetected = 0 // reset silent sample count
              } else {
                numSilentSamplesDetected++ // increase silent sample count
                log.debug {
                  "Silence: $numSilentSamplesDetected sound detected at: ${System.currentTimeMillis()} with $currentSPL dB SPL"
                }
                if(numSilentSamplesDetected >= silenceTimeoutBufferCount && // sequentially collected samples exceed total count
                  numTotalSamplesRecorded >= durationMinimumBufferCount) {
                  log.info {
                    "Stopping recording after ${DSP_SILENCE_TIMEOUT_MS}ms of silence ($silenceTimeoutBufferCount)"
                  }
                  close()
                  return false // Signals the recording to stop
                }
              }

              numTotalSamplesRecorded++
              if (numTotalSamplesRecorded >= durationMaximumBufferCount) {
                log.info {
                  "Stopping recording after $maxDuration of recording ($durationMaximumBufferCount)"
                }
                close()
                return false // Also signals the recording to stop
              }
              return true // Continue recording
            }
            override fun processingFinished() {
              log.info { "Audio Flow processing Finished" }
              close()
            }
          }
        // Processes silence & recording duration
        dispatcher.addAudioProcessor(audioProcessor)
        awaitClose {
          microphone.stop()
          log.info { "Closed Audio Processor" }
        }
      })
    } catch (e: LineUnavailableException) {
      log.error { "Unable to initialise audio recording" }
      e.printStackTrace()
      return Err(RecordingError("Unable to initialise audio recording", e))
    } catch (e: FileNotFoundException) {
      log.error { "Unable to initialise audio recording: File not found" }
      e.printStackTrace()
      return Err(RecordingError("Unable to initialise audio recording: File not found", e))
    }
  }

  fun stopRecording() : Result<Pair<RawAudioFile, Duration>, RecordingError> {
    mDispatcher?.let {
      if(!it.isStopped)
        it.stop()
    }
    mDispatcher = null
    microphone.stop()
    log.info { "Stopped recording" }

    // Return Raw Audio file
    mRawOutputFile?.let {
      return Ok(Pair(RawAudioFile(it), (numTotalSamplesRecorded.toDouble()/bufferToMsFactor).milliseconds))
        .also { mRawOutputFile = null } //
    }
    // or error, if not found
    return Err(RecordingError("No output file found", null))
  }

  companion object {
    const val MP3_AUDIO_FILE_EXTENSION = ".mp3"
    const val RECORDING_SAMPLE_RATE = 16000f // to match LAME options below
    const val RECORDING_SAMPLE_SIZE = 16
    const val RECORDING_CHANNELS = 1
    const val RECORDING_SIGNED = true
    const val RECORDING_BIG_ENDIAN = false
    const val DSP_BUFFER_SIZE = 512
    const val DSP_OVERLAP = 0
    const val DSP_SILENCE_TIMEOUT_MS = 3 * 1000 // ms of silence before stopping recording after minimum duration
    const val DSP_DURATION_MINIMUM_MS = (1 + 5) * 1000 // ms minimum duration of recording ("ask now" + time)
//    const val DSP_DURATION_MAXIMUM_MS = 20 * 1000 // ms maximum duration of recording
  }
}

