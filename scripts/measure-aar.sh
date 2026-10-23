#!/usr/bin/env bash
# Çekirdek AAR'ı derler, boyutunu basar; 150 KB'ı (SPEC §1) aşarsa 1 ile çıkar.
set -euo pipefail
cd "$(dirname "$0")/.."
LIMIT_BYTES=$((150 * 1024))

./gradlew --console=plain -q :bir-android:assembleRelease
AAR=bir-android/build/outputs/aar/bir-android-release.aar
[ -f "$AAR" ] || { echo "AAR bulunamadı: $AAR" >&2; exit 2; }

BYTES=$(wc -c < "$AAR" | tr -d ' ')
echo "AAR: $AAR"
echo "Boyut: $BYTES bayt ($(awk -v b="$BYTES" 'BEGIN{printf "%.2f", b/1024}') KB)"
echo "Sınır: $LIMIT_BYTES bayt (150 KB)"
if [ "$BYTES" -gt "$LIMIT_BYTES" ]; then
  echo "BAŞARISIZ: AAR 150 KB sınırını aşıyor." >&2
  exit 1
fi
echo "TAMAM: sınırın altında."
