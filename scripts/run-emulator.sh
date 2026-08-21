#!/usr/bin/env bash
#
# run-emulator.sh — Arranca el emulador, compila, instala y abre AgroVisión.
#
# Uso:
#   scripts/run-emulator.sh              # todo el ciclo
#   scripts/run-emulator.sh --no-build   # solo instalar el APK ya construido
#   scripts/run-emulator.sh --fresh      # borra los datos de la app antes
#   scripts/run-emulator.sh --shot       # además guarda una captura de pantalla
#   scripts/run-emulator.sh --log        # deja el logcat de la app corriendo
#   scripts/run-emulator.sh --camara=iphone   # cámara trasera = iPhone (Continuity)
#   scripts/run-emulator.sh --camara=mac      # cámara trasera = FaceTime HD
#   scripts/run-emulator.sh --camara=virtual  # escena 3D del emulador (por defecto)
#
# ⚠️ La escena virtual del emulador es una sala con estantería, sin nada verde.
# La compuerta de calidad la rechaza —correctamente— por falta de contenido
# vegetal. Para probar el escaneo de verdad hay que apuntar a una hoja real, y
# para eso hace falta una cámara real.
#
set -Eeuo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"

# ⚠️ El applicationId lleva el sufijo .debug: la compilación de depuración se
# instala AL LADO de la publicada, no encima. Es lo que permite tener las dos en
# el mismo teléfono para verificar la migración desde Capacitor.
APP_ID="io.ionic.starter.debug"
LANZADOR="$APP_ID/io.ionic.starter.MainActivity"

BUILD=1; FRESH=0; SHOT=0; LOG=0; CAMARA=""
for arg in "$@"; do
  case "$arg" in
    --no-build)        BUILD=0 ;;
    --fresh)           FRESH=1 ;;
    --shot)            SHOT=1 ;;
    --log)             LOG=1 ;;
    --camara=iphone)   CAMARA="webcam1" ;;
    --camara=mac)      CAMARA="webcam0" ;;
    --camara=virtual)  CAMARA="virtualscene" ;;
    *) echo "Opción desconocida: $arg"; exit 1 ;;
  esac
done

paso() { printf '\033[36m▸ %s\033[0m\n' "$*"; }
ok()   { printf '\033[32m  ✓ %s\033[0m\n' "$*"; }
aviso(){ printf '\033[33m  ! %s\033[0m\n' "$*"; }

[[ -x "$ADB" ]] || { echo "No encuentro adb en $SDK"; exit 1; }

# ── 1. Emulador ──────────────────────────────────────────────────────────────
if [[ -z "$("$ADB" devices | sed -n '2p')" ]]; then
  AVD="$("$EMULATOR" -list-avds | head -1)"
  [[ -n "$AVD" ]] || { echo "No hay ningún AVD creado. Créalo en Android Studio → Device Manager."; exit 1; }

  # Un emulador que murió mal deja un bloqueo y el siguiente arranque falla con
  # «Running multiple emulators with the same AVD». Se limpia siempre.
  rm -rf "$HOME/.android/avd/$AVD.avd/"*.lock 2>/dev/null || true

  ARGS=(-avd "$AVD")
  if [[ -n "$CAMARA" ]]; then
    ARGS+=(-camera-back "$CAMARA")
    paso "Arrancando el emulador $AVD (cámara trasera: $CAMARA)"
    if [[ "$CAMARA" == "webcam1" ]]; then
      aviso "Continuity Camera: el iPhone tiene que estar desbloqueado y cerca."
    fi
  else
    paso "Arrancando el emulador $AVD"
  fi

  "$EMULATOR" "${ARGS[@]}" > /tmp/agrovision-emulator.log 2>&1 &

  # `wait-for-device` solo espera al demonio; el sistema tarda bastante más.
  # Sin esperar a `boot_completed`, el install falla con «device offline».
  "$ADB" wait-for-device
  printf '  esperando a que arranque Android'
  for _ in $(seq 1 60); do
    [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && break
    printf '.'; sleep 3
  done
  echo
  ok "emulador listo"
else
  ok "ya hay un dispositivo conectado"
fi

# ── 2. Compilar ──────────────────────────────────────────────────────────────
APK="$RAIZ/app/build/outputs/apk/debug/app-debug.apk"
if (( BUILD )); then
  paso "Compilando"
  ( cd "$RAIZ" && ./gradlew :app:assembleDebug -q )
  ok "APK construido"
fi
[[ -f "$APK" ]] || { echo "No existe $APK — compila primero."; exit 1; }

# ── 3. Instalar ──────────────────────────────────────────────────────────────
if (( FRESH )); then
  paso "Borrando datos de la app"
  # Desinstalar y no `pm clear`: así también se prueba el primer arranque real,
  # con la siembra del catálogo y la importación desde Capacitor.
  "$ADB" uninstall "$APP_ID" > /dev/null 2>&1 || true
  ok "instalación anterior eliminada"
fi

paso "Instalando"
"$ADB" install -r "$APK" | tail -1

# En el emulador se concede la cámara por adb para no pelear con el diálogo.
# En un teléfono real DÉJALO que lo pida: ese flujo también hay que probarlo.
"$ADB" shell pm grant "$APP_ID" android.permission.CAMERA 2>/dev/null || true

# ── 4. Abrir ─────────────────────────────────────────────────────────────────
paso "Abriendo AgroVisión"
"$ADB" shell am force-stop "$APP_ID"
"$ADB" logcat -c
"$ADB" shell am start -n "$LANZADOR" > /dev/null
sleep 6

PID="$("$ADB" shell pidof "$APP_ID" | tr -d '\r')"
if [[ -n "$PID" ]]; then
  ok "corriendo (pid $PID)"
else
  aviso "el proceso murió — revisa el crash:"
  "$ADB" logcat -d -b crash | tail -25
  exit 1
fi

echo
paso "Registro de arranque"
"$ADB" logcat -d --pid="$PID" 2>/dev/null | grep -iE "agrovision" | tail -8 || true

if (( SHOT )); then
  DESTINO="/tmp/agrovision-$(date +%H%M%S).png"
  "$ADB" exec-out screencap -p > "$DESTINO"
  echo; ok "captura en $DESTINO"
fi

if (( LOG )); then
  echo; paso "Logcat en vivo (Ctrl+C para salir)"
  "$ADB" logcat --pid="$PID"
fi
