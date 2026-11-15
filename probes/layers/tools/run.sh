#!/bin/sh
# Runs one scenario of the layers probe on the device named by $SERIAL and prints its report:
#
#   SERIAL=<serial> run.sh export -e lanes 3 -e clip F1080-h264 ...
set -eu
scenario=${1:?scenario}
shift
exec "${ADB:-adb}" -s "${SERIAL:?serial}" shell am instrument -w -e scenario "$scenario" "$@" app.takes.probe.layers/.LayersProbe
