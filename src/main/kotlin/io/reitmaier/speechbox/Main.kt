@file:Suppress("RemoveExplicitTypeArguments")

package io.reitmaier.speechbox

import com.github.michaelbull.logging.InlineLogger
import com.github.michaelbull.result.*
import com.github.michaelbull.retry.policy.limitAttempts
import com.github.michaelbull.retry.retryResult
import com.github.pgreze.process.Redirect
import com.github.pgreze.process.process
import com.github.pgreze.process.unwrap
import com.pi4j.io.gpio.GpioFactory
import com.pi4j.io.gpio.PinPullResistance
import com.pi4j.io.gpio.RaspiPin
import com.pi4j.wiringpi.Gpio
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.sample
import splitties.coroutines.raceOf
import splitties.experimental.ExperimentalSplittiesApi
import io.reitmaier.speechbox.audio.AudioPrompt
import io.reitmaier.speechbox.audio.Microphone
import io.reitmaier.speechbox.audio.SilenceDetectingAudioRecorder
import io.reitmaier.speechbox.audio.SilenceDetectingAudioRecorder.Companion.RECORDING_SAMPLE_RATE
import io.reitmaier.speechbox.audio.Speaker
import io.reitmaier.speechbox.io.*
import java.io.File
import java.nio.file.Paths
import kotlin.coroutines.coroutineContext
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds


@ExperimentalSplittiesApi
@FlowPreview
class Main(
  boxId: BoxId,
  apiUrl: String,
  apiPassword: String? = null,
  private val mock: Boolean = false,
  private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
)  {

  // state
  private val appState = MutableStateFlow<State>(State.Init())

  // util
  private val log = InlineLogger()
  private val dataDir: File = File(Paths.get("").toAbsolutePath().toString()).resolve("data")

  // io
  private lateinit var display: Display
  private lateinit var mainButton: MainButton
  private lateinit var keypad: Keypad

  // Make gpioController lazy to support mock impl
  // and ensure singleton access
  private val gpioController by lazy {
    GpioFactory.getInstance()
  }

  // audio
  private lateinit var speaker: Speaker
  private lateinit var recorder: SilenceDetectingAudioRecorder

  private val apiService: ApiService = ApiService(
    apiUrl = apiUrl,
    apiPassword = apiPassword,
    boxId = boxId
  )

  suspend fun start() {
    val stateMachineJob = scope.launch { // launch a new coroutine in background and continue
      appState.collect {
        updateAppState(it)
      }
    }
    val pingJob = scope.launch {
      while(stateMachineJob.isActive) {
        delay(30.seconds)
        apiService.ping()
      }
    }
    // Wait for state machine to crash (e.g. AudioErrors)
    stateMachineJob.join()
    // Then cancel the ping job
    pingJob.cancel()
  }

  // SpeechBox is implemented as State Machine
  private suspend fun updateAppState(newState: State) {
    log.info { "Entering State: $newState" }
    // Post new state (fire and forget) to Api server
    scope.launch {
      runCatching {
        apiService.postSessionState(newState)
      }.onFailure { log.error { it } }
    }

    // Each state calls a (suspend) function which returns the next state
    appState.value =
      when(newState) {
        is State.Init -> enterInitState(newState)
        is State.Idle -> enterIdleState(newState)
        is State.WelcomePrompt -> enterWelcomePromptState(newState)
        is State.Recording -> enterRecordingState(newState)
        is State.ConfirmationPrompt -> enterConfirmationPromptState(newState)
        is State.ReplayAudio -> enterReplayAudioState(newState)
        is State.Upload -> enterUploadState(newState)
        is State.MobileEntryPrompt -> enterMobileEntryPrompt(newState)
        is State.ThankYouPrompt -> enterThankYouPrompt(newState)
        is State.AudioError -> enterAudioErrorState(newState)
        is State.NetworkError -> enterNetworkErrorState(newState)
        is State.HardwareError -> enterHardwareErrorState(newState)
      }
  }

  private suspend fun enterThankYouPrompt(state: State.ThankYouPrompt): State {
    speaker.play(AudioPrompt.THANKS)
      .onFailure { return State.AudioError(state.sessionId,it.message,it.throwable) }
    return State.Idle()
  }

  private suspend fun enterMobileEntryPrompt(state: State.MobileEntryPrompt): State {
    // Decision: We assume that audio upload succeeds

    // Play voucher prompt in foreground
    val mobileNumberPromptJob = scope.launch {  speaker.play(AudioPrompt.VOUCHER) }


    val mobileNumber: MobileNumber =
      keypad.mobileNumber(5.seconds, timeoutFirstDigit = 15.seconds)
        .fold(
          success = {it},
          failure = {
            log.debug { "Timeout waiting for mobile number input" }
            return State.ThankYouPrompt(state.sessionId)
          }
        )
    speaker.stop()

    // Play mobile network prompt in background
    val mobileNetworkPromptJob = scope.launch {  speaker.play(AudioPrompt.NETWORK) }

    // Meanwhile ... Wait to avoid button presses from previous state
    delay(1.seconds)

    // Show mobile network options on screen
    display.displayMobileNetworks()

    val mobileNetwork: MobileNetwork = raceOf<MobileNetwork?>(
      {
        // after playback completes
        mobileNetworkPromptJob.join()
        log.debug { "Network prompt completed. Waiting a further 5 seconds..." }
        // wait another X seconds
        delay(5.seconds)
        log.debug { "TIMEOUT" }
        // then timeout
        null
      },
      {
        // also start listening immediately with long timeout
        val network = keypad.mobileNetwork(60.seconds)

        // stop playing audio after receiving input
        speaker.stop()

        // and return result
        network.get()
      }) ?: return State.ThankYouPrompt(state.sessionId).also { log.debug { "Timeout waiting for mobile network input" } }

    val mobileInfo = MobileInfo(mobileNumber,mobileNetwork.value)

    val progressJob = scope.launch { display.displayIndeterminateProgress() }
    apiService.submitMobile(state.sessionId, mobileInfo).also { progressJob.cancel()}
      .andThen { token ->
        speaker.play(AudioPrompt.QUESTIONNAIRE)
          .map { token }
      }
      .fold(
        success = {
          log.info { "Received Participation Token: $it" }
          return State.ThankYouPrompt(state.sessionId)
        },
        failure = {
          when(it) {
            is IOError -> return State.Idle().also { log.info { "Timeout" } }
            is NetworkError -> return State.NetworkError(state.sessionId, it.message, it.throwable)
          }
        }
      )

  }


  private suspend fun enterUploadState(state: State.Upload): State {
    val progressJob = scope.launch { display.displayIndeterminateProgress() }
    val fileToUpload: File = state.mp3Task.await()
      .fold(
        success = {
          log.info { "Uploading mp3 file: $it"  }

          // So we can delete the raw Audio File
          state.rawAudio.value.delete()

          it.value
        },
        failure = {
          log.error { "Error encoding mp3 file: $it" }
          // delete remnants of mp3 file
          state.rawAudio.mp3File().delete()

          // and upload wav file instead
          log.info { "Uploading raw audio file: ${state.rawAudio}"  }
          state.rawAudio.value
        }
      )

    val storyId = apiService.requestStoryId(state.sessionId)
      .fold(
        success = {
          progressJob.cancel()
          it
        },
        failure = {
          progressJob.cancel()
          return State.NetworkError(state.sessionId, "Could not obtain Story Id", it.throwable)
        }
      )

    // Upload audio file
    val uploadTask = scope.async {
      apiService.uploadStoryAudio(storyId, fileToUpload)
        .onFailure {
          log.error { it }
          fileToUpload.delete()
        }
        .onSuccess {
          fileToUpload.delete()
        }
//        .mapError { ApiError("A network error occurred", it) }
    }
    return State.MobileEntryPrompt(
      sessionId = state.sessionId,
      fileUploaded = fileToUpload,
      storyId = storyId,
      uploadTask = uploadTask,
    )
  }

  private suspend fun enterReplayAudioState(state: State.ReplayAudio): State {
    speaker.play(state.rawAudio)
      .onFailure { return State.AudioError(state.sessionId,it.message,it.throwable) }
    return State.ConfirmationPrompt(
      sessionId = state.sessionId,
      mp3Task = state.mp3Task,
      rawAudio = state.rawAudio,
      replayCount = state.replayCount
    )
  }

  private fun readIntFromFile(path: String) : Result<Int,Throwable> {
   return readFileContents(path).andThen { runCatching {  it.trim().toInt() } }
  }
  private fun readDoubleFromFile(path: String) : Result<Double,Throwable> {
    return readFileContents(path).andThen { runCatching {  it.trim().toDouble() } }
  }

  private suspend fun enterInitState(state: State.Init) : State {
    // Create data directories
    dataDir.mkdirs()

    val volume = readIntFromFile("/boot/speechbox_volume").get() ?: 100
    if(!mock) {
      val res = process("amixer", "sset", "Master,0", "${volume}%") // ${volume}%")
      if(res.resultCode == 0) {
        log.info { "Set volume to: $volume" }
      } else {
        log.error { "Error setting volume: $volume" }
      }
    }

    val silenceThreshold = readDoubleFromFile("/boot/speechbox_silencethreshold").fold(
      success = {
        log.info { "Read silence threshold from /boot/speechbox_silencethreshold: $it" }
        it
      },
      failure = {
        val fallback = -70.0
        log.warn { "Failed to read /boot/speechbox_silencethreshold; using fallback value: $fallback" }
        fallback
      },
    )

    val maxRecordingDuration = readIntFromFile("/boot/speechbox_maxlength").fold(
      success = {
        val duration = it.seconds
        log.info { "Read recording maximum length from /boot/speechbox_maxlength: $duration" }
        duration
      },
      failure = {
        val duration = 120.seconds
        log.warn { "Failed to read recording maximum length from /boot/speechbox_maxlength; using fallback value: $duration" }
        duration
      },
    )

    // Setup IO
    mainButton = if(mock) {
      MainButtonMock()
    } else {
      Gpio.wiringPiSetup() // needed for PWM

      val ledPin = gpioController.provisionSoftPwmOutputPin(RaspiPin.GPIO_06) // note: uses Wiring Pi numbering
      ledPin.setPwmRange(100)

      // uses wiringPI numbering
      val btnInputPin = gpioController.provisionDigitalInputPin(RaspiPin.GPIO_04, PinPullResistance.PULL_DOWN)
      LEDButton(btnInputPin,ledPin)
    }

    display = if(mock) DisplayMock() else {
      OLEDDisplay(gpioController = gpioController)
    }

    val progressJob = scope.launch {
      display.displayIndeterminateProgress()
    }
    keypad = if(mock) KeypadMock() else {
      findKeypadInputPath().fold(
        success = {
          KeyPad3x4(
            display = display,
            path = "/dev/input/$it"
          )
        },
        failure = {
          return State.HardwareError(state.sessionId,"Can't find 3x4 matrix keypad", it)
        }
      )
    }



    //
    Microphone.printMixers()

    // Device [plughw:1,0]
    val micName = readFileContents("/boot/speechbox_mic").fold(
      success = {
        log.info { "Read microphone from /boot/speechbox_mic: $it" }
        it
      },
      failure = {
        val fallback = if(mock) "MacBook Air Microphone" else "Device [plughw:1,0]"
        log.warn { "Falling back to default microphone: $fallback" }
        fallback
      }
    )

    val microphone = Microphone.fromName(micName)
      .fold(
        success = { mic -> mic },
        failure = { error ->
          progressJob.cancel()
          return State.AudioError(state.sessionId, error.message, error.throwable)
        }
      )

    val speakerName = readFileContents("/boot/speechbox_speaker").fold(
      success = {
        log.info { "Read speaker from /boot/speechbox_speaker: $it" }
        it },
      failure = {
        val fallback = if(mock) "MacBook Air Speakers" else "sndrpigooglevoi [plughw:0,0]"
        log.warn { "Falling back to default speaker: $fallback" }
        fallback
      }
    )
    val language = readFileContents("/boot/speechbox_language").fold(
      success = {
        log.info { "Read language from /boot/speechbox_language: $it" }
        it
      },
      failure = {
        val fallbackLanguage = "xh"
        log.info { "Failed to read language from /boot/speechbox_language, using fallback: $fallbackLanguage" }
        fallbackLanguage
      }
    )
//    val speakerName = if(mock) "MacBook Air Speakers" else "LX6000 [plughw:1,0]"
    speaker = Speaker.fromName(speakerName,language)
      .fold(
        success = { it },
        failure = { error ->
          progressJob.cancel()
          return State.AudioError(state.sessionId, error.message, error.throwable)
        }
      )

    recorder = SilenceDetectingAudioRecorder(
      microphone,
      maxDuration = maxRecordingDuration,
      silenceThreshold = silenceThreshold,
    )

    val online =
      retryResult(limitAttempts(100)) {
        runCatching {
          delay(1.seconds)
          log.info { "Waiting for Internet Connection" }
          val host = if(mock) "127.0.0.1" else "8.8.8.8"
          val pingResult = process("ping",
            "-c 1",
            host,
            stdout = Redirect.SILENT)
          if(pingResult.resultCode == 0 ) {
            log.info { "Internet Reachable" }
            true
          } else {
            log.warn { "Internet Unreachable" }
            throw Throwable("Internet Unreachable")
          }
        }.mapError { ConnectionError("Internet Unreachable", it) }
      }

    progressJob.cancel()
    if(online.get() == null)
      return State.NetworkError(state.sessionId, "Can't connect to Internet", null)
    return State.Idle()
  }

  private suspend fun enterIdleStateDEBUG(state: State.Idle) : State {
    // Decision: We assume that audio upload succeeds

    // Play voucher prompt in foreground
    val mobileNumberPromptJob = scope.launch {  speaker.play(AudioPrompt.VOUCHER) }


    val mobileNumber: MobileNumber =
      keypad.mobileNumber(5.seconds, timeoutFirstDigit = 12.seconds)
        .fold(
          success = {it},
          failure = {
            log.debug { "Timeout waiting for mobile number input" }
            return State.ThankYouPrompt(state.sessionId)
          }
        )
    speaker.stop()
//    display.clear()

    // Play mobile network prompt in background
    val mobileNetworkPromptJob = scope.launch {  speaker.play(AudioPrompt.NETWORK) }

    // Meanwhile ... Wait to avoid button presses from previous state
    delay(1.seconds)

    // Show mobile network options on screen
    display.displayMobileNetworks()

    val mobileNetwork: MobileNetwork = raceOf<MobileNetwork?>(
      {
        // after playback completes
        mobileNetworkPromptJob.join()
        log.debug { "Network prompt completed. Waiting a further 5 seconds..." }
        // wait another X seconds
        delay(5.seconds)
        log.debug { "TIMEOUT" }
        // then timeout
        null
      },
      {
        // also start listening immediately with long timeout
        val network = keypad.mobileNetwork(60.seconds)

        // stop playing audio after receiving input
        speaker.stop()

        // and return result
        network.get()
      }) ?: return State.ThankYouPrompt(state.sessionId).also { log.debug { "Timeout waiting for mobile network input" } }

    val mobileInfo = MobileInfo(mobileNumber,mobileNetwork.value)

    val progressJob = scope.launch { display.displayIndeterminateProgress() }
    apiService.submitMobile(state.sessionId, mobileInfo).also { progressJob.cancel()}
      .andThen { token ->
        speaker.play(AudioPrompt.QUESTIONNAIRE)
          .map { token }
      }
      .fold(
        success = {
          log.info { "Received Participation Token: $it" }
          return State.ThankYouPrompt(state.sessionId)
        },
        failure = {
          when(it) {
            is IOError -> return State.Idle().also { log.info { "Timeout" } }
            is NetworkError -> return State.NetworkError(state.sessionId, it.message, it.throwable)
          }
        }
      )

  }

  private suspend fun enterIdleState(state: State.Idle) : State {
    scope.launch {
      // Wait a few seconds before clearing the display
      delay(3.seconds)
      display.clear()
    }
    val blinkJob = scope.launch {
      mainButton.blink()
    }
    mainButton.next(MainButton.Event.RELEASE)
    mainButton.off()
    display.clear()
    log.debug { "Button Pressed" }
    blinkJob.cancel()
    return State.WelcomePrompt(state.sessionId)
  }

  private suspend fun enterWelcomePromptState(state: State.WelcomePrompt) : State {

    // Play welcome prompt in background
    val welcomePromptJob = scope.launch {  speaker.play(AudioPrompt.WELCOME) }

    // Meanwhile ... Wait to avoid button presses from previous state
    delay(2.seconds)

    val pulseJob = scope.launch {
      mainButton.pulseOn()
    }

    val buttonPressed = raceOf<Boolean>(
      {
        // after playback completes
        welcomePromptJob.join()
        log.debug { "Welcome prompt completed. Waiting a further 5 seconds..." }
        // wait another X seconds
        delay(5.seconds)
        log.debug { "TIMEOUT" }
        // then timeout
        false
      },
      {
        // also start listening immediately with long timeout
        mainButton.next(60.seconds, MainButton.Event.PRESS)

        // stop playing audio after confirmation
        speaker.stop()

        // and return result
        true
      })
    pulseJob.cancel()
    return when(buttonPressed) {
      true -> State.Recording(state.sessionId)
      false -> State.ThankYouPrompt(state.sessionId)
    }
  }


private suspend fun enterRecordingState(state: State.Recording): State {
    // and play begin recording prompt
    val supervisor = SupervisorJob()
    return withContext(coroutineContext + supervisor) {

      speaker.play(AudioPrompt.BEGIN_RECORDING)
        .onFailure { return@withContext State.AudioError(state.sessionId, it.message, it.throwable) }

      // and then play the beep & pulse the button
      val recordingNotificationJob = launch {
        speaker.play(AudioPrompt.BEEP)
        mainButton.pulseOn()
      }

      // and start recording
      recorder.record(dataDir.resolve("${state.sessionId.value}.wav"))
        .andThen { dataFlow ->
          val sampleRate = if (mock) 500.milliseconds else 10.milliseconds
          val audioDataPipe = launch {
            dataFlow.sample(sampleRate).onCompletion { display.clear() }.collect { amplitudeData ->
              display.displayAudioAmplitudes(amplitudeData)
            }
          }
          delay(5.seconds)
          // button timeouts should be slightly longer than MAX recording duration
          val buttonTimeout = recorder.maxDuration + 1.seconds
          // Then stop after first condition met
          val reasonForStopping = raceOf(
            // Audio stops recording on:
            // - silence
            // - max duration reached (20 seconds)
            {
              audioDataPipe.join()
              RecordingStopReason("SILENCE/MAX LENGTH")
            },
            {
              // The keypad is pressed with timeout of 21 seconds (a bit longer than max duration)
              val btn = keypad.next(buttonTimeout)
              RecordingStopReason("KEYPAD BUTTON: $btn")
            },
            {
              // or The main button is pressed
              if (mock) {
                delay(2.seconds)
              } else {
                mainButton.next(buttonTimeout, MainButton.Event.RELEASE)
              }
              RecordingStopReason("MAIN BUTTON")
            }
          )
          log.info { "Stopped Recording because: $reasonForStopping" }
          recordingNotificationJob.cancel()
          audioDataPipe.cancel()
          launch { apiService.postStopRecordReason(state.sessionId, reasonForStopping) }
          Ok(reasonForStopping)
        }.andThen {
          recorder.stopRecording()
        }
        .fold(
          success = { (rawAudioFile, duration) ->
            log.info { "Finished recording after ${duration.inWholeSeconds}s to: $rawAudioFile" }
            val mp3Task = async { encodeMP3(rawAudioFile) }
            launch { apiService.postRecordingDuration(state.sessionId, duration) }
            return@withContext State.ConfirmationPrompt(
              sessionId = state.sessionId,
              rawAudio = rawAudioFile,
              mp3Task = mp3Task,
              replayCount = 0,
            )
          },
          failure = {
            return@withContext State.AudioError(state.sessionId, "Error recording", it.throwable)
          }
        )
    }
  }

  private suspend fun enterConfirmationPromptState(state: State.ConfirmationPrompt): State {
    // Play confirmation prompt
    val confirmationPromptJob = scope.launch {  speaker.play(AudioPrompt.CONFIRMATION) }

    val confirmation = raceOf<Confirmation>(
      {
        // after playback completes
        confirmationPromptJob.join()
        log.debug { "Confirmation prompt completed. Waiting a further 5 seconds..." }
        // wait another X seconds
        delay(5.seconds)
        log.debug { "TIMEOUT" }
        // then timeout
        Confirmation.TIMEOUT
      },
      {
      // also start listening immediately with long timeout
      val confirmation = keypad.confirmation(30.seconds)

      // stop playing audio after confirmation
      speaker.stop()

      // and return result
      confirmation
    })

    log.info { "Received confirmation: $confirmation" }
    // Post confirmation (fire and forget) to Api Server
    scope.launch {
      apiService.postConfirmationAnswer(state.sessionId, confirmation)
    }

    return when(confirmation) {
      Confirmation.REPLAY -> State.ReplayAudio(
        sessionId = state.sessionId,
        mp3Task = state.mp3Task,
        rawAudio = state.rawAudio,
        replayCount = state.replayCount + 1
      )
      Confirmation.YES -> {
        State.Upload(
          sessionId = state.sessionId,
          mp3Task = state.mp3Task,
          rawAudio = state.rawAudio,
        )
      }
      Confirmation.NO -> {
        // Cancel encoding task
        state.mp3Task.cancel()
        // Delete MP3
        state.rawAudio.mp3File().delete()
        // Delete raw audio file
        state.rawAudio.value.delete()
//        State.QuestionnairePromptNoShare(state.sessionId)
        State.Recording(state.sessionId)
      }
      Confirmation.TIMEOUT -> {
        // Cancel encoding task
        state.mp3Task.cancel()
        // Delete MP3
        state.rawAudio.mp3File().delete()
        // Delete raw audio file
        state.rawAudio.value.delete()
        State.ThankYouPrompt(state.sessionId)
      }
    }
  }


  // ERROR States
  private suspend fun enterAudioErrorState(newState: State.AudioError): State {
    delay(5.seconds)
    display.displayText("Audio", "Error:")
    log.error { newState.throwable?.localizedMessage }

    // Throw and see if restart/reboot fixes issue
    throw Throwable("Audio Error")
  }

  private suspend fun enterNetworkErrorState(state: State.NetworkError): State {
    display.displayText("Network", "Error:")
    speaker.play(AudioPrompt.NETWORK_ERROR)
    log.error { state.throwable?.localizedMessage }
    return State.Idle()
  }
  private suspend fun enterHardwareErrorState(state: State.HardwareError): State {
    runCatching { display.displayText("Keypad", "Error:") }
    speaker.play(AudioPrompt.HARDWARE_ERROR)
    throw state.throwable
  }

  private suspend fun encodeMP3(rawAudioFile: RawAudioFile) : Result<Mp3AudioFile, EncodingError> {
    val mp3File = Mp3AudioFile(File("${rawAudioFile.value.parent}/${rawAudioFile.value.nameWithoutExtension}${SilenceDetectingAudioRecorder.MP3_AUDIO_FILE_EXTENSION}"))
    log.info { "Encoding ${rawAudioFile.value} to ${mp3File.value}" }
    val res = process("lame",
      "--preset",
      "voice",
      "-s",
      RECORDING_SAMPLE_RATE.toString(),
      rawAudioFile.value.absolutePath,
      mp3File.value.absolutePath,
      stdout = Redirect.SILENT)

    return if(res.resultCode == 0 ) {
      log.info { "Created mp3 file $mp3File successfully" }
      Ok(mp3File)
    }
    else {
      Err(EncodingError("Error encoding ${rawAudioFile.value} to ${mp3File.value}", Throwable(res.output.joinToString())))
    }
  }
  private suspend fun findKeypadInputPath(): Result<String, Throwable> {
    return runCatching {
      javaClass.classLoader.getResource("find3x4matrix.sh")
      this.javaClass.classLoader.getResource("find3x4matrix.sh").let {
        if(it == null) {
          throw Throwable("Can't find script")
        }
        val extractedFile = dataDir.resolve("find3x4matrix.sh")
        extractedFile.createNewFile()
        it.openStream().copyTo(extractedFile.outputStream())
//        File(it.toURI()).copyTo(extractedFile, overwrite = true)
        val res = process("bash", extractedFile.absolutePath, stdout = Redirect.CAPTURE).unwrap()
        val deviceName = res[0].trim()
        log.info { "Found 3x4 Matrix keypad: $deviceName." }
        deviceName
      }
    }
  }

  private fun readFileContents(path: String) : Result<String, Throwable> {
    return runCatching {
      File(path).readText().trim()
    }
  }


  companion object {

    private fun getBoxId() : Result<BoxId, Throwable> =
      readIntFromFile("/boot/speechbox_id").map { BoxId(it) }

    private fun readIntFromFile(path: String) : Result<Int, Throwable> =
      runCatching {
        File(path).readText().trim().toInt()
      }

    @DelicateCoroutinesApi
    @JvmStatic
    fun main(args: Array<String>) {
      val apiUrl = System.getenv("API_URL") ?: throw Throwable("Please supply an API_URL environment variable")
      val apiPassword = System.getenv("API_PASSWORD") // Optional

      // Setting the MOCK environment variable enables local development
      val mock = System.getenv("MOCK") != null

      val boxId = getBoxId().get() ?: BoxId.DEV

      val covidStories = Main(boxId, apiUrl, apiPassword, mock)
      val stateMachineJob = GlobalScope.launch {
        covidStories.start()
      }
      while (stateMachineJob.isActive) {
        Thread.sleep(500)
      }
    }
  }
}