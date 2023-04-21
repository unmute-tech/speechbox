package io.reitmaier.speechbox.io

import com.github.michaelbull.logging.InlineLogger
import com.github.michaelbull.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.*
import io.reitmaier.speechbox.IOError
import io.reitmaier.speechbox.MobileNumber
import io.reitmaier.speechbox.TimeoutError
import io.reitmaier.speechbox.util.timeout
import xyz.reitmaier.kinputevents.*
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration

enum class Confirmation(val value: Int) {
  YES(1),
  NO(-1),
  REPLAY(2),
  TIMEOUT(0)
}

enum class MobileNetwork(val value: String) {
  TELKOM("Telkom"),
  MTN("MTN"),
  CELLC("Cell-C"),
  VODACOM("Vodacom"),
  CANCEL("CANCEL"),
}
interface Keypad {
  suspend fun confirmation(timeout: Duration) : Confirmation
  suspend fun next(timeout: Duration) : String
  suspend fun mobileNumber(timeout: Duration, timeoutFirstDigit: Duration): Result<MobileNumber,IOError>
  suspend fun mobileNetwork(timeout: Duration): Result<MobileNetwork,IOError>
}

class KeyPad3x4(
  private val display: Display,
  path: String = "/dev/input/event2",
) : Keypad {
  private val log = InlineLogger()
  private val keyboard = KInputEvents(path)
  private val keyboardFlow = keyboard.startFlow().shareIn(GlobalScope, started = SharingStarted.Lazily)
  override suspend fun confirmation(timeout: Duration): Confirmation =
    runCatching {
      keyboardFlow
        .filter { it.type == EV_KEY && it.value == EV_KEY_RELEASE }
        .timeout(timeout)
        .onCompletion { println("Confirmation Completed") }
        .map {
          // map keycode to characters
          when (it.code) {
            KEY_KP0 -> Confirmation.REPLAY
            KEY_KPASTERISK -> Confirmation.NO
            KEY_KPSLASH -> Confirmation.YES
            else -> null
          }
        }
        .filterNotNull()
        .first()
    }.fold(
      success = { it },
      failure = {
//        log.error { "Confirmation Error: $it" }
        Confirmation.TIMEOUT
      }
    )

  override suspend fun mobileNetwork(timeout: Duration): Result<MobileNetwork, TimeoutError> =
    runCatching {
      val input = keyboardFlow
        .filter { it.type == EV_KEY && it.value == EV_KEY_RELEASE }
        .timeout(timeout)
        .onCompletion { println("Mobile Network Prompt Completed") }
        .map {
          // map keycode to characters
          when (it.code) {
            KEY_KP0 -> null
            KEY_KP1 -> MobileNetwork.TELKOM
            KEY_KP2 -> MobileNetwork.MTN
            KEY_KP3 -> MobileNetwork.CELLC
            KEY_KP4 -> MobileNetwork.VODACOM
            KEY_KP5 -> null
            KEY_KP6 -> null
            KEY_KP7 -> null
            KEY_KP8 -> null
            KEY_KP9 -> null
            KEY_KPASTERISK -> MobileNetwork.CANCEL
            KEY_KPSLASH -> null
            else -> null
          }
        }
        .filterNotNull()
        .first()

      // Clear the display after input
      display.clear()
      input
    }.mapError {
      display.clear()
      TimeoutError("No response to mobile network prompt", it)
    }

  override suspend fun next(timeout: Duration): String =
      keyboardFlow
        .filter { it.type == EV_KEY && it.value == EV_KEY_RELEASE }
        .timeout(timeout)
        .onCompletion { println("Confirmation Completed") }
        .map {
          // map keycode to characters
          when (it.code) {
            KEY_KP0 -> "0"
            KEY_KP1 -> "1"
            KEY_KP2 -> "2"
            KEY_KP3 -> "3"
            KEY_KP4 -> "4"
            KEY_KP5 -> "5"
            KEY_KP6 -> "6"
            KEY_KP7 -> "7"
            KEY_KP8 -> "8"
            KEY_KP9 -> "9"
            KEY_KPASTERISK -> "*"
            KEY_KPSLASH -> "#"
            else -> ""
          }
        }
        .filterNotNull()
        .first()

  override suspend fun mobileNumber(timeout: Duration, timeoutFirstDigit: Duration): Result<MobileNumber, TimeoutError> {
    val userInputBuffer = mutableListOf<String>()
    var confirmed = false
    val firstDigit = runCatching {
      keyboardFlow
        // Only track KEY & KEY_RELEASE events
        .filter { it.type == EV_KEY && it.value == EV_KEY_RELEASE }
        // Timeout after first timeout seconds
        .timeout(timeoutFirstDigit)
        .onCompletion { println("Completed") }
        .onStart {
          val text = userInputBuffer.joinToString(separator = "").padEnd(10, '-')
          display.displayText(text, "Mobile:")
        }
        .onEach {
          // map keycode to characters
          val character = when(it.code) {
            KEY_KP0 -> "0"
            KEY_KP1 -> "1"
            KEY_KP2 -> "2"
            KEY_KP3 -> "3"
            KEY_KP4 -> "4"
            KEY_KP5 -> "5"
            KEY_KP6 -> "6"
            KEY_KP7 -> "7"
            KEY_KP8 -> "8"
            KEY_KP9 -> "9"
            else -> ""
          }
          // add input to buffer
          userInputBuffer.add(character)
          // display the userInputBuffer and pad ending to indicate incomplete input
          val text = userInputBuffer.joinToString(separator = "").padEnd(10,'-')
          display.displayText(text,"Mobile:")
        }
        .first()
    }.mapError {
      display.clear()
      TimeoutError("Incomplete mobile number", it)
    }
    return firstDigit.andThen {
      runCatching {
        keyboardFlow
          // Only track KEY & KEY_RELEASE events
          .filter { it.type == EV_KEY && it.value == EV_KEY_RELEASE }
          // Timeout after last user input
          .timeout(timeout)
          .onCompletion { println("Completed") }
          .onStart {
            val text = userInputBuffer.joinToString(separator = "").padEnd(10, '-')
            display.displayText(text, "Mobile:")
          }
          .onEach {
            // map keycode to characters
            val character = when(it.code) {
              KEY_KP0 -> "0"
              KEY_KP1 -> "1"
              KEY_KP2 -> "2"
              KEY_KP3 -> "3"
              KEY_KP4 -> "4"
              KEY_KP5 -> "5"
              KEY_KP6 -> "6"
              KEY_KP7 -> "7"
              KEY_KP8 -> "8"
              KEY_KP9 -> "9"
              KEY_KPASTERISK -> "*" // Delete
              KEY_KPSLASH -> "#" // Confirm
              else -> ""
            }
            // interpret "*" as "backspace"
            if(character == "*") {
              userInputBuffer.removeLastOrNull()
            }
            // "#" confirms input
            else if( character == "#") {
              // but only if buffer is full
              if(userInputBuffer.size == 10) {
                confirmed = true
              }
            }
            else {
              // other add numbers to the buffer if not already full
              if(userInputBuffer.size < 10) {
                userInputBuffer.add(character)
              }
            }
            // display the userInputBuffer and pad ending to indicate incomplete input
            val text = userInputBuffer.joinToString(separator = "").padEnd(10,'-')
            if(userInputBuffer.size == 10) {
              display.displayText(text,"Confirm:")
            }else {
              display.displayText(text,"Mobile:")
            }
          }
          // Collect until user has inputted 10 numbers (or timeout)
          .takeWhile { !confirmed }
          .collect()
        // Clear the display after input
        display.clear()
        return@runCatching MobileNumber(userInputBuffer.joinToString(separator = ""))
      }.mapError {
        display.clear()
        TimeoutError("Incomplete mobile number", it)
      }
    }
  }

}

class KeypadMock : Keypad {
  private val log = InlineLogger()

  override suspend fun confirmation(timeout: Duration): Confirmation =
    suspendCoroutine { cont ->
      log.info { "Confirm Share: (y)es, (n)o, (t)imeout, (r)eplay:" }
      val scanner = Scanner(System.`in`)
      val chr = scanner.next().single()
      println("Char pressed $chr")
      when (chr) {
        'y' -> cont.resume(Confirmation.YES)
        'n' -> cont.resume(Confirmation.NO)
        't' -> cont.resume(Confirmation.TIMEOUT)
        'r' -> cont.resume(Confirmation.REPLAY)
      }
    }

  override suspend fun next(timeout: Duration): String {
    delay(timeout)
    return " "
  }


  override suspend fun mobileNumber(timeout: Duration, timeoutFirstDigit: Duration): Result<MobileNumber,TimeoutError> =
    suspendCoroutine { cont ->
      log.info { "Questionnaire prompt for (m)obile or (t)imeout: " }
      val scanner = Scanner(System.`in`)
      val chr = scanner.next().single()
      println("Char pressed $chr")
      when (chr) {
        'm' -> cont.resume(Ok(MobileNumber.TEST))
//        'm' -> cont.resume(Ok(MobileNumber("9783099058")))
        't' -> cont.resume(Err(TimeoutError("Timeout waiting for keypress", null)))
      }
    }

  override suspend fun mobileNetwork(timeout: Duration): Result<MobileNetwork, IOError> {
    TODO("Not yet implemented")
  }

}


fun KInputEvents.startFlow(): Flow<InputEvent> = callbackFlow {

  val callback = object : KInputEventListener {
    override fun onEvent(event: InputEvent) {
      trySendBlocking(event)
    }
    override fun onException(exception: Exception) {
      cancel(CancellationException("IO Error", exception))
    }
    override fun onExit() {
      channel.close()
    }
  }
  this@startFlow.addListener(callback)
  this@startFlow.start()
  awaitClose { this@startFlow.removeListener(callback) }
}
