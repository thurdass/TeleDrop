#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLASS_DIR="$PROJECT_DIR/out/classes"
JAR_FILE="$PROJECT_DIR/out/teledrop.jar"

mkdir -p "$CLASS_DIR"
mapfile -t JAVA_SOURCES < <(rg --files "$PROJECT_DIR/src/main/java" -g '*.java' | sort)

if ((${#JAVA_SOURCES[@]} == 0)); then
  echo "Nenhum arquivo Java encontrado" >&2
  exit 1
fi

javac --release 21 -encoding UTF-8 -d "$CLASS_DIR" "${JAVA_SOURCES[@]}"
jar --create --file "$JAR_FILE" --main-class com.thurdass.telegramwatcher.Main -C "$CLASS_DIR" .
echo "Build concluído: $JAR_FILE"
