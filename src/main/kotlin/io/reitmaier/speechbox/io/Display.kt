package io.reitmaier.speechbox.io

import com.github.michaelbull.logging.InlineLogger
import com.pi4j.io.gpio.GpioController
import com.pi4j.io.gpio.Pin
import com.pi4j.io.gpio.RaspiPin
import com.pi4j.io.spi.SpiChannel
import com.pi4j.io.spi.SpiDevice
import com.pi4j.io.spi.SpiFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import io.reitmaier.speechbox.io.ssd1306.SSD1306Driver
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import kotlin.time.Duration.Companion.milliseconds

interface Display {
  fun clear()
  fun displayText(text: String, heading: String)
//  fun startIndeterminateProgress()
//  fun stopIndeterminateProgress()
  fun displayAudioAmplitudes(data: FloatArray)
  suspend fun displayIndeterminateProgress()
  fun displayMobileNetworks()
}

class DisplayMock : Display {
  private val log = InlineLogger()
  override fun clear() {
    println()
  }

  override fun displayText(text: String, heading: String) {
    log.debug { "Displaying text: $heading $text" }
  }

//  override fun startIndeterminateProgress() {
//    println("Start Waiting")
//  }
//
//  override fun stopIndeterminateProgress() {
//    println("Stop Waiting")
//  }

  override fun displayAudioAmplitudes(data: FloatArray) {
    // Very noisy
//    println(arrayList.joinToString { "$it" })
  }

  override suspend fun displayIndeterminateProgress() {
    coroutineScope {
      while (isActive) {
        print(".")
        delay(100.milliseconds)
      }
    }
  }

  override fun displayMobileNetworks() {
    TODO("Not yet implemented")
  }
}

class OLEDDisplay(
  gpioController: GpioController,
  private val width: Int = 128,
  private val height: Int = 64,
  spiDevice: SpiDevice = SpiFactory.getInstance(SpiChannel.CS0, 8000000),
  rstPin: Pin = RaspiPin.GPIO_08,
  dcPin: Pin = RaspiPin.GPIO_09,
) : Display {
  private val log = InlineLogger()
  private val driver = SSD1306Driver(
    width,
    height,
    gpioController,
    spiDevice,
    rstPin,
    dcPin
  )
  init {
      driver.begin()
  }

  override fun clear() {
    driver.clear()
    driver.display()
  }


  override fun displayMobileNetworks() {
    driver.clear()
    val g2d = driver.image.createGraphics()
    g2d.color = Color.white
    g2d.background = Color.black
    g2d.font = Font("Monospaced", Font.PLAIN, 15)
    val f = g2d.font
    g2d.clearRect(0, 0, width, height)
    val metrics: FontMetrics = g2d.getFontMetrics(f)
    val x: Int = 10
    g2d.drawString("1: Telkom",x,15)
    g2d.drawString("2: MTN",x,31)
    g2d.drawString("3: Cell-C",x,47)
    g2d.drawString("4: Vodacom",x,63)
    // Draw the image buffer.
    driver.displayImage()
  }
  override fun displayText(text: String, heading: String) {
    driver.clear()
    val g2d = driver.image.createGraphics()
    g2d.color = Color.white
    g2d.background = Color.black
    g2d.font = Font("Monospaced", Font.PLAIN, 22)
    val f = g2d.font
    g2d.clearRect(0, 0, width, height)
    val metrics: FontMetrics = g2d.getFontMetrics(f)
    val x: Int = 0 + (width - metrics.stringWidth(text)) / 2
    val y = if(heading.isNotBlank()) {
      38+5
    } else {
      38
    }
    g2d.drawString(text,x,y)
    if(heading.isNotBlank()) {
      val xHead: Int = 0 + (width - metrics.stringWidth(heading)) / 2
      g2d.drawString(heading,xHead,38-22)
    }
    // Draw the image buffer.
    driver.displayImage()
  }

  private fun displayIndeterminateProgress(start: Int, end: Int) {
    driver.clear()
    driver.graphics.stroke = BasicStroke(3f)
    driver.graphics.drawArc(41, 9, 46, 46, start, end)
    driver.displayImage()
  }

  override suspend fun displayIndeterminateProgress() {
    coroutineScope {
      try {
        var progress = 0
        while (isActive) {
          progress = (progress + 2) % 360
          displayIndeterminateProgress(-progress,120)
          delay(2.milliseconds)
        }
      } catch (e: CancellationException){
        log.debug { "IndeterminateProgress Cancelled" }
      } finally {
        log.debug { "Clearing Display" }
        clear()
      }
    }
  }


  override fun displayAudioAmplitudes(data: FloatArray) {
    driver.clear()
    var x = 0
    for (i in data.indices) {
      if (i % 2 != 0 && (i - 1) % 8 == 0) {
        val y = (data[i] * 250).toInt()
          .coerceIn( -31 .. 31)
        driver.graphics.drawLine(x, 32, x, 32 + y)
        x++
      }
    }
    driver.displayImage()
  }
}
