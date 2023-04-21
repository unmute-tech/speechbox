#!/usr/bin/env sh
cat /proc/bus/input/devices | grep -Poz '("MATRIX3x4"[\s\S]+?)\Kevent\d+' | sed 's/\x0//g'