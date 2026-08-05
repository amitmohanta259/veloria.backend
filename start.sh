#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SECRET_FILE="$SCRIPT_DIR/.keycloak-secret"

if [ -f "$SECRET_FILE" ]; then
  export KEYCLOAK_CLIENT_SECRET="$(cat "$SECRET_FILE")"
else
  echo "[start.sh] WARNING: .keycloak-secret not found — using env var or default"
fi

MVN="/Users/amitkumarmohanta/Documents/apache-maven-3.9.9/bin/mvn"
exec "$MVN" -f "$SCRIPT_DIR/pom.xml" spring-boot:run -q
