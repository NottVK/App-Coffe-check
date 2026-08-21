#!/usr/bin/env bash
#
# verify-manifest.sh — Contrato de identidad Play Store (§2.4)
#
# Envoltorio del mismo contrato que impone la tarea Gradle
# `verifyPublishedIdentity`, pensado para dos casos que Gradle no cubre bien:
#
#   1. Verificar un AAB YA CONSTRUIDO contra PRESERVE.md, sin recompilar.
#   2. Correr en un paso de CI antes de tener el entorno Gradle listo.
#
# Uso:
#   scripts/verify-manifest.sh                       # solo contrato + fuentes
#   scripts/verify-manifest.sh app/build/outputs/bundle/release/app-release.aab
#
set -Eeuo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRESERVE="$RAIZ/PRESERVE.md"
AAB="${1:-}"
FALLOS=0

rojo()  { printf '\033[31m%s\033[0m\n' "$*"; }
verde() { printf '\033[32m%s\033[0m\n' "$*"; }
gris()  { printf '\033[90m%s\033[0m\n' "$*"; }

fallo() { rojo "  ✗ $*"; FALLOS=$((FALLOS + 1)); }
ok()    { verde "  ✓ $*"; }

# Extrae una clave del bloque legible por máquina de PRESERVE.md.
contrato() {
  awk '/<!-- CONTRATO-INICIO -->/{f=1;next} /<!-- CONTRATO-FIN -->/{f=0} f' "$PRESERVE" \
    | grep -E "^[[:space:]]*$1:" \
    | head -1 \
    | sed -E "s/^[[:space:]]*$1:[[:space:]]*//"
}

contrato_todas() {
  awk '/<!-- CONTRATO-INICIO -->/{f=1;next} /<!-- CONTRATO-FIN -->/{f=0} f' "$PRESERVE" \
    | grep -E "^[[:space:]]*$1:" \
    | sed -E "s/^[[:space:]]*$1:[[:space:]]*//"
}

propiedad() {
  grep -E "^$1=" "$RAIZ/gradle.properties" | head -1 | cut -d= -f2- | tr -d '[:space:]'
}

echo
echo "── Contrato de identidad Play Store ──────────────────────────────────────"
echo

[[ -f "$PRESERVE" ]] || { rojo "No existe PRESERVE.md. Es la tarea cero (§2.2)."; exit 1; }

# ── 1. applicationId ─────────────────────────────────────────────────────────
echo "applicationId"
ID_CONTRATO="$(contrato applicationId)"
ID_BUILD="$(propiedad 'agrovision.applicationId')"

if [[ "$ID_CONTRATO" == TODO* ]]; then
  fallo "PRESERVE.md aún no declara el applicationId publicado."
elif [[ "$ID_BUILD" == TODO* ]]; then
  fallo "gradle.properties aún no declara agrovision.applicationId."
elif [[ "$ID_CONTRATO" != "$ID_BUILD" ]]; then
  fallo "No coinciden — PRESERVE.md=$ID_CONTRATO  gradle.properties=$ID_BUILD"
else
  ok "$ID_BUILD"
fi

# ── 2. versionCode ───────────────────────────────────────────────────────────
echo
echo "versionCode"
VC_PUBLICADO="$(contrato versionCode)"
VC_ACTUAL="${AGROVISION_VERSION_CODE:-}"

if [[ "$VC_PUBLICADO" == TODO* ]]; then
  fallo "PRESERVE.md aún no declara el versionCode publicado."
elif [[ -z "$VC_ACTUAL" ]]; then
  gris "  · AGROVISION_VERSION_CODE no fijado; CI lo calcula (§17.2). Publicado: $VC_PUBLICADO"
elif (( VC_ACTUAL <= VC_PUBLICADO )); then
  fallo "$VC_ACTUAL no supera al publicado ($VC_PUBLICADO). Play rechaza la subida."
else
  ok "$VC_ACTUAL > $VC_PUBLICADO"
fi

# ── 3. Componentes exportados y enlaces profundos ────────────────────────────
echo
echo "Componentes exportados y enlaces profundos"
MANIFIESTO="$RAIZ/app/src/main/AndroidManifest.xml"

while IFS= read -r comp; do
  [[ -z "$comp" || "$comp" == TODO* ]] && continue
  CORTO=".${comp##*.}"
  if grep -q "android:name=\"$comp\"" "$MANIFIESTO" || grep -q "android:name=\"$CORTO\"" "$MANIFIESTO"; then
    ok "exportado $comp"
  else
    fallo "falta el componente exportado $comp"
  fi
done < <(contrato_todas exported)

while IFS= read -r enlace; do
  [[ -z "$enlace" || "$enlace" == TODO* ]] && continue
  HOST="${enlace#*://}"; HOST="${HOST%%/*}"
  if grep -q "$HOST" "$MANIFIESTO"; then
    ok "enlace profundo $enlace"
  else
    fallo "falta el enlace profundo $enlace (host $HOST)"
  fi
done < <(contrato_todas deepLink)

# ── 4. Migración destructiva de Room (§23, error nº 2) ───────────────────────
echo
echo "Migraciones Room"
ENCONTRADO="$(grep -rEl --include='*.kt' \
  'fallbackToDestructiveMigration' "$RAIZ" \
  --exclude-dir=build --exclude-dir=build-logic --exclude-dir=.gradle 2>/dev/null || true)"

if [[ -n "$ENCONTRADO" ]]; then
  while IFS= read -r archivo; do
    fallo "migración destructiva en ${archivo#"$RAIZ"/}"
  done <<< "$ENCONTRADO"
else
  ok "sin migraciones destructivas"
fi

# ── 5. Verificación contra un AAB construido ─────────────────────────────────
if [[ -n "$AAB" ]]; then
  echo
  echo "AAB construido — $AAB"
  if ! command -v aapt2 >/dev/null 2>&1; then
    gris "  · aapt2 no está en el PATH; se omite la verificación del bundle."
  elif [[ ! -f "$AAB" ]]; then
    fallo "no existe el archivo $AAB"
  else
    BADGING="$(aapt2 dump badging "$AAB" 2>/dev/null | head -1)"
    ID_AAB="$(sed -E "s/.*name='([^']+)'.*/\1/" <<< "$BADGING")"
    VC_AAB="$(sed -E "s/.*versionCode='([^']+)'.*/\1/" <<< "$BADGING")"

    [[ "$ID_AAB" == "$ID_CONTRATO" ]] \
      && ok "applicationId del bundle: $ID_AAB" \
      || fallo "el bundle declara $ID_AAB, el contrato $ID_CONTRATO"

    if [[ "$VC_PUBLICADO" != TODO* ]] && (( VC_AAB > VC_PUBLICADO )); then
      ok "versionCode del bundle: $VC_AAB > $VC_PUBLICADO"
    else
      fallo "versionCode del bundle ($VC_AAB) no supera al publicado ($VC_PUBLICADO)"
    fi
  fi
fi

# ── Resultado ────────────────────────────────────────────────────────────────
echo
if (( FALLOS > 0 )); then
  rojo "── $FALLOS incumplimiento(s). No se puede publicar. ──────────────────────"
  echo
  gris "Ninguno de estos fallos produce un error al compilar: todos se manifiestan"
  gris "después de subir el AAB. Por eso se comprueban aquí."
  echo
  exit 1
fi

verde "── Identidad publicada preservada. ───────────────────────────────────────"
echo
