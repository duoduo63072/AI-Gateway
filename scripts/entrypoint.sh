#!/bin/sh
# Prefer runtime environment variables. If a deployment mounts /app/.env, load it
# as a convenience, but never bake real secrets into the image.
if [ -f /app/.env ]; then
  set -a
  . /app/.env
  set +a
fi
exec java -XX:MaxRAMPercentage=75.0 -jar /app/app.jar
