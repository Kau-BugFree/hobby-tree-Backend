#!/bin/sh
set -eu
if [ "$#" -ne 0 ]; then
  echo 'EC2 command-line configuration overrides are disabled.' >&2
  exit 1
fi
python3 /app/validate-ec2-config.py --runtime
exec java -jar /app/hobbytree.jar
