package io.reitmaier.speechbox.io.ssd1306

@Suppress("unused")
object Constants {
    const val SSD1306_I2C_ADDRESS: Short = 0x3C
    const val SSD1306_SETCONTRAST: Short = 0x81
    const val SSD1306_DISPLAYALLON_RESUME: Short = 0xA4
    const val SSD1306_DISPLAYALLON: Short = 0xA5
    const val SSD1306_NORMALDISPLAY: Short = 0xA6
    const val SSD1306_INVERTDISPLAY: Short = 0xA7
    const val SSD1306_DISPLAYOFF: Short = 0xAE
    const val SSD1306_DISPLAYON: Short = 0xAF
    const val SSD1306_SETDISPLAYOFFSET: Short = 0xD3
    const val SSD1306_SETCOMPINS: Short = 0xDA
    const val SSD1306_SETVCOMDETECT: Short = 0xDB
    const val SSD1306_SETDISPLAYCLOCKDIV: Short = 0xD5
    const val SSD1306_SETPRECHARGE: Short = 0xD9
    const val SSD1306_SETMULTIPLEX: Short = 0xA8
    const val SSD1306_SETLOWCOLUMN: Short = 0x00
    const val SSD1306_SETHIGHCOLUMN: Short = 0x10
    const val SSD1306_SETSTARTLINE: Short = 0x40
    const val SSD1306_MEMORYMODE: Short = 0x20
    const val SSD1306_COLUMNADDR: Short = 0x21
    const val SSD1306_PAGEADDR: Short = 0x22
    const val SSD1306_COMSCANINC: Short = 0xC0
    const val SSD1306_COMSCANDEC: Short = 0xC8
    const val SSD1306_SEGREMAP: Short = 0xA0
    const val SSD1306_CHARGEPUMP: Short = 0x8D
    const val SSD1306_EXTERNALVCC: Short = 0x1
    const val SSD1306_SWITCHCAPVCC: Short = 0x2
    const val SSD1306_ACTIVATE_SCROLL: Short = 0x2F
    const val SSD1306_DEACTIVATE_SCROLL: Short = 0x2E
    const val SSD1306_SET_VERTICAL_SCROLL_AREA: Short = 0xA3
    const val SSD1306_RIGHT_HORIZONTAL_SCROLL: Short = 0x26
    const val SSD1306_LEFT_HORIZONTAL_SCROLL: Short = 0x27
    const val SSD1306_VERTICAL_AND_RIGHT_HORIZONTAL_SCROLL: Short = 0x29
    const val SSD1306_VERTICAL_AND_LEFT_HORIZONTAL_SCROLL: Short = 0x2A
}