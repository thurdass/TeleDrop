#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_FILE="${1:-$PROJECT_DIR/config.properties}"

if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "Configuração não encontrada: $CONFIG_FILE" >&2
  echo "Copie config.example.properties para config.properties e preencha as credenciais." >&2
  exit 1
fi

"$PROJECT_DIR/build.sh"
exec java -cp "$PROJECT_DIR/out/classes" com.thurdass.telegramwatcher.Main "$CONFIG_FILE"
