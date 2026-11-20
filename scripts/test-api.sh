#!/usr/bin/env bash
# Test API TapDrop pomocí curl.
# Použití: ./test-api.sh <IP_PC> <TOKEN> [PORT]
# (v Git Bash / WSL / Linux / macOS; ve Windows 10/11 lze použít i curl.exe)
set -u
HOST="${1:-127.0.0.1}"
TOKEN="${2:?Zadej token (je vidět v okně TapDrop)}"
PORT="${3:-8765}"
BASE="http://$HOST:$PORT"
H="X-TapDrop-Token: $TOKEN"

echo "== /discover bez tokenu (očekávám 401)"
curl -s -o /dev/null -w "HTTP %{http_code}\n" "$BASE/discover"

echo "== /discover"
curl -s -H "$H" "$BASE/discover"; echo

echo "== /pending (soubory čekající na stažení do telefonu)"
curl -s -H "$H" "$BASE/pending"; echo

echo "== /upload (simulace odeslání z telefonu)"
TMP="./tapdrop-test.$$.tmp"; echo "Ahoj z curl $(date)" > "$TMP"
curl -s -H "$H" -H "X-TapDrop-Size: $(wc -c < "$TMP" | tr -d ' ')" -H "X-TapDrop-Offset: 0" \
     -F "file=@$TMP;filename=test-curl.txt" "$BASE/upload"; echo
rm -f "$TMP"

echo "== Pokus o path traversal (musí skončit uloženo jako 'passwd.txt', ne mimo složku)"
TMP="./tapdrop-test.$$.tmp"; echo "x" > "$TMP"
curl -s -H "$H" -F "file=@$TMP;filename=../../evil/passwd.txt" "$BASE/upload"; echo
rm -f "$TMP"

echo
echo "== Pro test stažení přetáhni soubor do okna TapDrop a pak:"
echo "   curl -H \"$H\" $BASE/pending"
echo "   curl -H \"$H\" -o stazeno.bin $BASE/download/<id>"
echo "   curl -H \"$H\" -X POST $BASE/ack/<id>"
echo "   (resume: curl -H \"$H\" -C - -o stazeno.bin $BASE/download/<id>)"
