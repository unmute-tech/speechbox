package io.reitmaier.speechbox.io.ssd1306

import com.pi4j.io.gpio.GpioController
import com.pi4j.io.gpio.Pin
import java.awt.image.BufferedImage
import java.awt.Graphics2D
import com.pi4j.io.gpio.GpioPinDigitalOutput
import com.pi4j.io.i2c.I2CDevice
import com.pi4j.io.spi.SpiDevice
import kotlin.jvm.JvmOverloads
import com.pi4j.io.i2c.I2CBus
import com.pi4j.wiringpi.I2C
import java.io.IOException
import java.lang.InterruptedException
import kotlin.jvm.Synchronized
import java.awt.image.Raster
import kotlin.experimental.and
import kotlin.experimental.or

class SSD1306Driver private constructor(
  /**
   * @return Display width
   */
  private val width: Int,
  /**
   * @return Display height
   */
  val height: Int, i2c: Boolean, gpio: GpioController, rstPin: Pin?
) {
  private var vccState = 0

  /**
   * Returns internal AWT image
   * @return BufferedImage
   */
  var image: BufferedImage
    private set

  /**
   * Returns Graphics object which is associated to current AWT image,
   * if it wasn't set using setImage() with false createGraphics parameter
   * @return Graphics2D object
   */
  var graphics: Graphics2D
    private set

  private val pages: Int = height / 8
  private val usingI2C: Boolean
  private var hasRst = false
  private var rstPin: GpioPinDigitalOutput? = null
  private var dcPin: GpioPinDigitalOutput? = null
  private var i2c: I2CDevice? = null
  private var spi: SpiDevice? = null
  private var fd = 0
  private var buffer: ByteArray

  /**
   * Display object using SPI communication with a reset pin
   *
   * @param width  Display width
   * @param height Display height
   * @param gpio   GPIO object
   * @param spi    SPI device
   * @param rstPin Reset pin
   * @param dcPin  Data/Command pin
   * @see GpioFactory.getInstance
   * @see com.pi4j.io.spi.SpiFactory.getInstance
   */
  constructor(width: Int, height: Int, gpio: GpioController, spi: SpiDevice?, rstPin: Pin?, dcPin: Pin?) : this(
    width,
    height,
    false,
    gpio,
    rstPin
  ) {
    this.dcPin = gpio.provisionDigitalOutputPin(dcPin)
    this.spi = spi
  }
  /**
   * Display object using I2C communication with a reset pin
   * <br></br>
   * As I haven't got an I2C display and I don't understand I2C much, I just tried to copy
   * the Adafruit's library and I am using a hack to use WiringPi function similar to one in the original lib directly.
   *
   * @param width   Display width
   * @param height  Display height
   * @param gpio    GPIO object
   * @param i2c     I2C object
   * @param address Display address
   * @param rstPin  Reset pin
   * @see GpioFactory.getInstance
   * @see com.pi4j.io.i2c.I2CFactory.getInstance
   * @throws ReflectiveOperationException Thrown if I2C handle is not accessible
   * @throws IOException                  Thrown if the bus can't return device for specified address
   */
  /**
   * Display object using I2C communication without a reset pin
   *
   * @param width   Display width
   * @param height  Display height
   * @param gpio    GPIO object
   * @param i2c     I2C object
   * @param address Display address
   * @see SSD1306Driver.SSD1306Driver
   * @see GpioFactory.getInstance
   * @see com.pi4j.io.i2c.I2CFactory.getInstance
   */
  @JvmOverloads
  constructor(width: Int, height: Int, gpio: GpioController, i2c: I2CBus, address: Int, rstPin: Pin? = null) : this(
    width,
    height,
    true,
    gpio,
    rstPin
  ) {
    val i2cDevice = i2c.getDevice(address)
    this.i2c = i2cDevice
    val f = i2cDevice.javaClass.getDeclaredField("fd")
    f.isAccessible = true
    fd = f.getInt(this.i2c)
  }

  /**
   * Display object using SPI communication without a reset pin
   *
   * @param width  Display width
   * @param height Display height
   * @param gpio   GPIO object
   * @param spi    SPI device
   * @param dcPin  Data/Command pin
   * @see SSD1306Driver.SSD1306Driver
   * @see GpioFactory.getInstance
   * @see com.pi4j.io.spi.SpiFactory.getInstance
   */
  constructor(width: Int, height: Int, gpio: GpioController, spi: SpiDevice?, dcPin: Pin?) : this(
    width,
    height,
    gpio,
    spi,
    null,
    dcPin
  ) {
  }

  init {
    buffer = ByteArray(width * pages)
    usingI2C = i2c
    if (rstPin != null) {
      this.rstPin = gpio.provisionDigitalOutputPin(rstPin)
      hasRst = true
    } else {
      hasRst = false
    }
    image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY)
    graphics = image.createGraphics()
  }

  private fun initDisplay() {
    if (width == 128 && height == 64) {
      init(0x3F, 0x12, 0x80)
    } else if (width == 128 && height == 32) {
      init(0x1F, 0x02, 0x80)
    } else if (width == 96 && height == 16) {
      init(0x0F, 0x02, 0x60)
    }
  }

  private fun init(multiplex: Int, compins: Int, ratio: Int) {
    command(Constants.SSD1306_DISPLAYOFF.toInt())
    command(Constants.SSD1306_SETDISPLAYCLOCKDIV.toInt())
    command(ratio.toShort().toInt())
    command(Constants.SSD1306_SETMULTIPLEX.toInt())
    command(multiplex.toShort().toInt())
    command(Constants.SSD1306_SETDISPLAYOFFSET.toInt())
    command(0x0.toShort().toInt())
    command(Constants.SSD1306_SETSTARTLINE.toInt())
    command(Constants.SSD1306_CHARGEPUMP.toInt())
    if (vccState == Constants.SSD1306_EXTERNALVCC.toInt()) command(
      0x10.toShort().toInt()
    ) else command(0x14.toShort().toInt())
    command(Constants.SSD1306_MEMORYMODE.toInt())
    command(0x00.toShort().toInt())
    command((Constants.SSD1306_SEGREMAP or 0x1).toShort().toInt())
    command(Constants.SSD1306_COMSCANDEC.toInt())
    command(Constants.SSD1306_SETCOMPINS.toInt())
    command(compins.toShort().toInt())
    command(Constants.SSD1306_SETCONTRAST.toInt())
    if (vccState == Constants.SSD1306_EXTERNALVCC.toInt()) command(
      0x9F.toShort().toInt()
    ) else command(0xCF.toShort().toInt())
    command(Constants.SSD1306_SETPRECHARGE.toInt())
    if (vccState == Constants.SSD1306_EXTERNALVCC.toInt()) command(
      0x22.toShort().toInt()
    ) else command(0xF1.toShort().toInt())
    command(Constants.SSD1306_SETVCOMDETECT.toInt())
    command(0x40.toShort().toInt())
    command(Constants.SSD1306_DISPLAYALLON_RESUME.toInt())
    command(Constants.SSD1306_NORMALDISPLAY.toInt())
  }

  /**
   * Turns on command mode and sends command
   * @param command Command to send. Should be in short range.
   */
  fun command(command: Int) {
    if (usingI2C) {
      i2cWrite(0, command)
    } else {
      dcPin!!.setState(false)
      try {
        spi!!.write(command.toShort())
      } catch (e: IOException) {
        e.printStackTrace()
      }
    }
  }

  /**
   * Turns on data mode and sends data
   * @param data Data to send. Should be in short range.
   */
  fun data(data: Int) {
    if (usingI2C) {
      i2cWrite(0x40, data)
    } else {
      dcPin!!.setState(true)
      try {
        spi!!.write(data.toShort())
      } catch (e: IOException) {
        e.printStackTrace()
      }
    }
  }

  /**
   * Turns on data mode and sends data array
   * @param data Data array
   */
  fun data(data: ByteArray) {
    if (usingI2C) {
      var i = 0
      while (i < data.size) {
        i2cWrite(0x40, data[i].toInt())
        i += 16
      }
    } else {
      dcPin!!.setState(true)
      try {
        spi!!.write(*data)
      } catch (e: IOException) {
        e.printStackTrace()
      }
    }
  }
  /**
   * Begin with specified VCC mode (can be SWITCHCAPVCC or EXTERNALVCC)
   * @param vccState VCC mode
   * @see Constants.SSD1306_SWITCHCAPVCC
   *
   * @see Constants.SSD1306_EXTERNALVCC
   */
  /**
   * Begin with SWITCHCAPVCC VCC mode
   * @see Constants.SSD1306_SWITCHCAPVCC
   */
  @JvmOverloads
  fun begin(vccState: Int = Constants.SSD1306_SWITCHCAPVCC.toInt()) {
    this.vccState = vccState
    reset()
    initDisplay()
    command(Constants.SSD1306_DISPLAYON.toInt())
    this.clear()
    display()
  }

  /**
   * Pulls reset pin high and low and resets the display
   */
  fun reset() {
    if (hasRst) {
      try {
        rstPin?.setState(true)
        Thread.sleep(1)
        rstPin?.setState(false)
        Thread.sleep(10)
        rstPin?.setState(true)
      } catch (e: InterruptedException) {
        e.printStackTrace()
      }
    }
  }

  /**
   * Sends the buffer to the display
   */
  @Synchronized
  fun display() {
    command(Constants.SSD1306_COLUMNADDR.toInt())
    command(0)
    command(width - 1)
    command(Constants.SSD1306_PAGEADDR.toInt())
    command(0)
    command(pages - 1)
    this.data(buffer)
  }

  /**
   * Clears the buffer by creating a new byte array
   */
  fun clear() {
    buffer = ByteArray(width * pages)
    image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY)
    graphics = image.createGraphics()
  }

  /**
   * Sets the display contract. Apparently not really working.
   * @param contrast Contrast
   */
  fun setContrast(contrast: Byte) {
    command(Constants.SSD1306_SETCONTRAST.toInt())
    command(contrast.toInt())
  }

  /**
   * Sets if the backlight should be dimmed
   * @param dim Dim state
   */
  fun dim(dim: Boolean) {
    if (dim) {
      setContrast(0.toByte())
    } else {
      if (vccState == Constants.SSD1306_EXTERNALVCC.toInt()) {
        setContrast(0x9F.toByte())
      } else {
        setContrast(0xCF.toByte())
      }
    }
  }

  /**
   * Sets if the display should be inverted
   * @param invert Invert state
   */
  fun invertDisplay(invert: Boolean) {
    if (invert) {
      command(Constants.SSD1306_INVERTDISPLAY.toInt())
    } else {
      command(Constants.SSD1306_NORMALDISPLAY.toInt())
    }
  }



  /**
   * Sets one pixel in the current buffer
   * @param x X position
   * @param y Y position
   * @param white White or black pixel
   * @return True if the pixel was successfully set
   */
  fun setPixel(x: Int, y: Int, white: Boolean): Boolean {
    if (x < 0 || x > width || y < 0 || y > height) {
      return false
    }
    if (white) {
      buffer[x + y / 8 * width] = buffer[x + y / 8 * width] or ((1 shl (y and 7)).toByte())
    } else {
      buffer[x + y / 8 * width] = buffer[x + y / 8 * width] and (1 shl (y and 7)).inv().toByte()
    }
    return true
  }

  /**
   * Copies AWT image contents to buffer. Calls display()
   * @see SSD1306Driver.display
   */
  @Synchronized
  fun displayImage() {
    val r: Raster = image.raster
    for (y in 0 until height) {
      for (x in 0 until width) {
        setPixel(x, y, r.getSample(x, y, 0) > 0)
      }
    }
    display()
  }

  /**
   * Sets internal buffer
   * @param buffer New used buffer
   */
  fun setBuffer(buffer: ByteArray) {
    this.buffer = buffer
  }

  /**
   * Sets one byte in the buffer
   * @param position Position to set
   * @param value Value to set
   */
  fun setBufferByte(position: Int, value: Byte) {
    buffer[position] = value
  }

  /**
   * Sets internal AWT image to specified one.
   * @param img BufferedImage to set
   * @param createGraphics If true, createGraphics() will be called on the image and the result will be saved
   * to the internal Graphics field accessible by getGraphics() method
   */
  fun setImage(img: BufferedImage, createGraphics: Boolean) {
    image = img
    if (createGraphics) {
      graphics = img.createGraphics()
    }
  }

  private fun i2cWrite(register: Int, value: Int) {
    var value = value
    value = value and 0xFF
    I2C.wiringPiI2CWriteReg8(fd, register, value)
  }
}