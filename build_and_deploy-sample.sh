#!/usr/bin/env sh
./gradlew build
rsync -avz --progress -e 'ssh ' build/libs/SpeechBox-1.0-standalone.jar pi@IP_OR_HOSTNAME_OF_PI
