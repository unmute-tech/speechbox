@file:Suppress("unused")

package io.reitmaier.speechbox.io

import com.github.michaelbull.logging.InlineLogger
import com.github.michaelbull.result.*
import com.pi4j.io.gpio.GpioPinDigitalInput
import com.pi4j.io.gpio.GpioPinPwmOutput
import com.pi4j.io.gpio.PinState
import com.pi4j.io.gpio.event.GpioPinListenerDigital
import kotlinx.coroutines.*
import io.reitmaier.speechbox.util.TimeoutException
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration

interface MainButton {
  fun off()
  suspend fun pulseOn()
  suspend fun next(timeout: Duration, buttonEvent: MainButton.Event) : Result<Unit,TimeoutException>
  suspend fun next(buttonEvent: MainButton.Event) : Boolean
  suspend fun blink()
  enum class Event {
    PRESS,
    RELEASE
  }
}

class LEDButton(
  private val inputPin: GpioPinDigitalInput,
  private val ledPin: GpioPinPwmOutput,
) : MainButton {
  private val log = InlineLogger()

  // TODO consider stateFlow
  override suspend fun next(buttonEvent: MainButton.Event): Boolean =
      suspendCancellableCoroutine { cont ->
        val listener = GpioPinListenerDigital { event ->
          log.debug {  " --> GPIO PIN STATE CHANGE: " + event.pin + " = " + event.state }
          val desiredPinState = when(buttonEvent) {
            MainButton.Event.PRESS -> PinState.LOW
            MainButton.Event.RELEASE -> PinState.HIGH
          }
          if (event.state == desiredPinState) {
            // cont can only be resumed once
            // check if active before resuming
            if (cont.isActive)
              cont.resume(true).also { inputPin.removeAllListeners() }
          }
        }
        log.debug { "Adding Listener" }
        inputPin.addListener(listener)
        // remove listener when the job is cancelled
        cont.invokeOnCancellation {
          inputPin.removeListener(listener)
          log.debug { "removed Listener" }
        }
      }

  override suspend fun next(timeout: Duration, buttonEvent: MainButton.Event): Result<Unit, TimeoutException> {
    return runCatching {
      withTimeout(timeout) {

        suspendCancellableCoroutine<Unit> { cont ->
          val listener = GpioPinListenerDigital { event ->
            log.debug { " --> GPIO PIN STATE CHANGE: " + event.pin + " = " + event.state }
            val desiredPinState = when(buttonEvent) {
              MainButton.Event.PRESS -> PinState.LOW
              MainButton.Event.RELEASE -> PinState.HIGH
            }
            if (event.state == desiredPinState) {
              // cont can only be resumed once
              // check if active before resuming
              if (cont.isActive)
                cont.resume(Unit).also { inputPin.removeAllListeners() }
            }
          }
          log.debug { "Adding Listener" }
          inputPin.addListener(listener)
          // remove listener when the job is cancelled
          cont.invokeOnCancellation {
            inputPin.removeListener(listener)
            log.debug { "removed Listener" }
          }
        }
      }
    }.mapError { TimeoutException() }
  }

  override fun off() {
    ledPin.pwm = 0
  }

  override suspend fun pulseOn() {
    withContext(Dispatchers.IO) {
      coroutineScope {
        log.debug { "Start fading LED" }
        try {
          while (isActive) {
            for (i in 0..LED_FADE_INTENSITY) {
              ledPin.pwm = i
              delay(FADE_DELAY_MS)
            }
            delay((FADE_DELAY_MS * (100 - LED_FADE_INTENSITY) / 2))
            for (i in LED_FADE_INTENSITY downTo 0) {
              ledPin.pwm = i
              delay(FADE_DELAY_MS)
            }
            delay((FADE_DELAY_MS * (100 - LED_FADE_INTENSITY) / 2))
          }
        } catch (e: CancellationException){
          // Ignore
        } finally {
          ledPin.pwm = 0
          log.debug { "Stop fading LED" }
        }
      }
    }
  }

  override suspend fun blink() {
    withContext(Dispatchers.IO) {
      coroutineScope {
        try {
          log.debug { "Blinking LED Twice" }
          for (i in 0..LED_BLINK_INTENSITY) {
            ledPin.pwm = i
            delay(BLINK_DELAY_MS)
          }
          delay((BLINK_DELAY_MS * (100 - LED_BLINK_INTENSITY) / 2))
          for (i in LED_FADE_INTENSITY downTo 0) {
            ledPin.pwm = i
            delay(BLINK_DELAY_MS)
          }
          delay((BLINK_DELAY_MS * (100 - LED_BLINK_INTENSITY) / 2))
          for (i in 0..LED_BLINK_INTENSITY) {
            ledPin.pwm = i
            delay(BLINK_DELAY_MS)
          }
        } catch (e: CancellationException) {
          log.debug { "Cancelled Blink" }
        } finally {
          log.debug { "Finished Blink" }
        }
      }
    }
  }

  companion object {
    private const val FADE_DELAY_MS = 25L // ms
    private const val BLINK_DELAY_MS = 10L // ms

    private const val LED_FADE_INTENSITY = 70 // when no interaction, fade led from 0 to this value (max: 100)
    private const val LED_BLINK_INTENSITY = 100 // when no interaction, fade led from 0 to this value (max: 100)
    private const val LED_BLINK_INTERVAL_ACTIVE = 200 // ms blink interval when something is happening
  }
}

class MainButtonMock : MainButton {



  override suspend fun next(buttonEvent: MainButton.Event): Boolean =
    suspendCoroutine { cont ->
      print("Listening for main button (b) > ")
      val scanner = Scanner(System.`in`)
      val chr = scanner.next().toCharArray().first()
      println("Char pressed $chr")
      if(chr == 'b') {
        cont.resume(true)
      } else {
        println("try again")
      }
    }
  override suspend fun next(timeout: Duration, buttonEvent: MainButton.Event): Result<Unit, TimeoutException> {
    next(buttonEvent)
    return Ok(Unit)
  }


  override fun off() {
    println("MainButton(off)")
  }

  override suspend fun pulseOn() {
    println("MainButton(glow)")
  }

  override suspend fun blink() {
    println("MainButton(blink)")
  }

}
