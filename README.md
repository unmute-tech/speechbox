# SpeechBox
*SpeechBox* is an information appliance to engage communities, and collect spoken-language responses, on topics of interest. 

The system's hardware design and software are all released under the Apache 2.0 license. The hardware enclosure is adapted from the [StreetWise Toolkit](https://github.com/reshaping-the-future/streetwise).

The *SpeechBox* Appliance is implemented as a [State Machine](States.kt) and can easily be extended as well as customised with bespoke [Audio Prompts](resources). The Appliance interacts with the [SpeechBox Server](TODO).

## Component List
* [Raspberry Pi 3B+](https://thepihut.com/products/raspberry-pi-3-model-b-plus)
* [Power supply](https://thepihut.com/collections/raspberry-pi-power-supplies/products/official-raspberry-pi-universal-power-supply)
* [SD card](https://www.amazon.co.uk/dp/B06XFSZGCC/)
* [AIY Voice Kit V1](https://aiyprojects.withgoogle.com/voice-v1/)
* [Mini OLED display](https://thepihut.com/products/adafruit-monochrome-1-3-128x64-oled-graphic-display)
* [12-key keypad](https://uk.rs-online.com/web/p/keypads/0146014/)

## Wiring
Follow [the instructions on AIY Voice Kit](https://aiyprojects.withgoogle.com/voice-v1/) on how to wire up the button (with integrated LED), microphone, and speaker.

### Keypad

Next connect the keypad.

| Keypad Pin | BCM PIN | Physical PIN | VoiceHAT |
|-----------:|:--------|:-------------|:---------|
|      Row 1 | 17      | 11           | Driver 1 |
|      Row 2 | 27      | 13           | Driver 2 |
|      Row 3 | 22      | 15           | Driver 3 |
|      Row 4 | 26      | 37           | Servo 0  |
|   Column 1 | 13      | 33           | Servo 2  |
|   Column 2 | 12      | 32           | Servo 4  |
|   Column 3 | 24      | 18           | Servo 5  |

Then configure the Raspberry Pi to recognize the [keypad as a keyboard](https://reitmaier.xyz/blog/matrix_keyboard/). Use the [device tree overlay](dts) in the repository. If you change the wiring, make sure you adapt the device tree overlay accordingly.

### OLED Display
And finally wire up the OLED display.

|  Display Pin | BCM PIN | Physical PIN | VoiceHAT   |
|-------------:|:--------|:-------------|:-----------|
|       Ground |         | 6            | GND (SPI)  |
|   Voltage In |         | 1            | 3.3V (SPI) |
|          3v3 | Nothing | Nothing      | Nothing    |
|  Chip Select | 8       | 24           | CE0        |
|        Reset | 2       | 3            | SDA (I2C)  |
| Data/Command | 3       | 5            | SCL (I2C)  |
|        Clock | 11      | 23           | CLK (SPI)  |
|         Data | 10      | 19           | MOSI (SPI) |

## Enclosure

The hardware is encased in a [laser-cut box](box.svg) made of [3mm acrylic](https://uk.rs-online.com/web/p/solid-plastic-sheets/0824654/).  
The colours of the [laser-cut box template](box.svg) correspond to different modes and orders of cuts.

| Order | Colour | Mode    | Speed | Power |
|------:|:-------|:--------|:------|:------|
|    1. | Red    | Engrave | 200   | 100   |
|    2. | Blue   | Cut     | 14    | 80    |
|    3. | Black  | Cut     | 14    | 80    |


## OS Setup

### Create The SD Card
1. Download & Flash [AIY Voicekit Image](https://github.com/google/aiyprojects-raspbian/releases) or [Raspbian Stretch](https://www.raspberrypi.org/downloads/raspbian/)
2. Enable ssh & wifi & keypad
  - Create an empty file, named `ssh`, in the boot partition of the microSD.
  - If you're connecting to the Pi using wifi, create another file name `wpa_supplicant.conf` in the boot partition:
    ```
    country=US
    ctrl_interface=DIR=/var/run/wpa_supplicant GROUP=netdev
    update_config=1
    
    network={
        ssid="your_real_wifi_ssid"
        scan_ssid=1
        psk="your_real_password"
        key_mgmt=WPA-PSK
    }
    ```

3. Configure keypad device tree overlay driver

Copy the [3x4matrix.dtbo](dts/3x4matrix.dtbo) file to the =/boot/overlay= directory of boot partition[^1]. 

4. Edit `config.txt` file in boot partition to include:
```
# Enable 3x4 matrix keypad
dtoverlay=3x4matrix

# Enable SPI for display
dtparam=spi=on
```

### Boot up and connect to the pi using ssh

Follow [the instructions on AIY Voice Kit](https://aiyprojects.withgoogle.com/voice-v1/#users-guide--ssh-to-your-kit) if you're unsure how to do this.

1. make sure you change the password `passwd`

2. Install all dependencies

sudo apt install oracle-java8-jdk
sudo apt install input-utils
sudo apt install git
sudo apt install wiringpi

3. Test keypad

lsinput
```
/dev/input/event0
   bustype : BUS_HOST
   vendor  : 0x0
   product : 0x0
   version : 0
   name    : "MATRIX3x4"
   bits ev : EV_SYN EV_KEY EV_MSC EV_REP
```

Next check if the button presses set off key pressed and released events.
input-events 0
```
/dev/input/event0
   bustype : BUS_HOST
   vendor  : 0x0
   product : 0x0
   version : 0
   name    : "MATRIX3x4"
   bits ev : EV_SYN EV_KEY EV_MSC EV_REP

waiting for events
14:21:51.857979: EV_MSC MSC_SCAN 0
14:21:51.857979: EV_KEY KEY_N (0x31) pressed
14:21:51.857979: EV_SYN code=0 value=0
14:21:51.947982: EV_MSC MSC_SCAN 0
14:21:51.947982: EV_KEY KEY_N (0x31) released
14:21:51.947982: EV_SYN code=0 value=0
```

### Compile / Deploy software

The [build.gradle.kts](build.gradle.kts) tiggers a 'fatJar' to be created as part of the gradle build process: ~build/libs/SpeechBox-1.0-standalone.jar~

You'll need to copy this file to the home directory of the default user (pi) on the Raspberry Pi. The 
[build_and_deploy-sample.sh](build_and_deploy-sample.sh) illustrates how to automate this step.

The [speechbox.service](speechbox.service) systemd file, which should be copied to the
~/etc/systemd/system/speechbox.service~ path, is responsible for launching the SpeechBox software. 


After installing the [speechbox.service](speechbox.service) systemd file, you can interact with the service using the following commands. 

``` sh
# To start the speechbox service
sudo systemctl start speechbox

# To check the status of the speechbox service
sudo systemctl status speechbox

# To enable the speechbox service (run at boot)
sudo systemctl enable speechbox
```

The file also contains environment variables that you'll need to customise:

- `API_PASSWORD=password`
- `API_URL=https://speechbox.example.com`

If you are developing the software locally, you should customise both environment variables as appropriate and consider setting the `MOCK` environment variable to a non-null value so that you don't need to deploy to the Raspberry Pi.

## License
Apache 2.0

[^1]: Or compile the [3x4matrix.dts](dts/3x4matrix.dts) file if you change the wiring from what is specified in the Keypad subsection above.
