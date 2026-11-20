# TapDrop

Přenos souborů mezi telefonem a notebookem **jedním přiložením telefonu k NFC tagu**. Žádný cloud, žádný internet – vše běží jen v lokální WiFi.

```
telefon ──(NFC tag u notebooku)──► spustí přenos ──(WiFi, HTTP :8765)──► notebook
```

* **Telefon → notebook:** vybereš soubory, stiskneš „Připraveno k odeslání“, přiložíš telefon k tagu. Soubory se nahrají do `~/TapDrop` na PC.
* **Notebook → telefon (bez potvrzení):** přetáhneš soubory do okna TapDrop na PC, přiložíš telefon k tagu a telefon si je **hned sám stáhne** do `Stažené/TapDrop` – žádný dialog „Chceš stáhnout?“. Funguje i když je aplikace zavřená.

## Struktura repozitáře

| Složka | Obsah |
|---|---|
| `android-app/` | Aplikace pro Android (Kotlin, Jetpack Compose, Material 3, minSdk 26, targetSdk 34) |
| `pc-rust/` | Program pro Windows 10/11 (Rust, Tokio + Axum, okno egui) → jediný soubor `TapDrop.exe` |
| `scripts/test-api.sh` | Testovací skript s příklady `curl` |

## Sestavení

### PC program (`TapDrop.exe`)

Potřebuješ [Rust](https://rustup.rs) (toolchain `stable-x86_64-pc-windows-msvc`). Python není potřeba.

```powershell
cd pc-rust
cargo build --release
```

Výsledek: `pc-rust\target\release\TapDrop.exe` – jeden samostatný soubor, stačí ho zkopírovat kamkoli.

### Android aplikace

Otevři složku `android-app` v **Android Studiu** (Koala nebo novější, JDK 17+) a stiskni Run, nebo z příkazové řádky:

```powershell
cd android-app
.\gradlew.bat assembleDebug
```

APK: `android-app\app\build\outputs\apk\debug\app-debug.apk` (instalace: `adb install -r app-debug.apk`).
Soubor `local.properties` s cestou k Android SDK si Android Studio vytvoří samo.

## Použití – krok za krokem

1. **Spusť PC program.** Dvojklik na `TapDrop.exe`. Při prvním spuštění povol ve Windows Firewallu přístup pro *soukromé sítě*. V okně uvidíš stav „● Běží“, IP adresu, `NOTEBOOK_ID`, `TOKEN` a QR kód. (Zavření okna program jen skryje do lišty u hodin; ukončit jde z menu ikony.)
2. **Spáruj telefon přes QR.** V aplikaci TapDrop → *Domů* → **Spárovat s notebookem** → naskenuj QR kód z okna PC. Uloží se IP, port 8765, `NOTEBOOK_ID` a `TOKEN`. Telefon i notebook musí být ve stejné WiFi.
3. **Zapiš tag.** V aplikaci **Zapsat tag** → přilož prázdný NFC tag (NTAG213/215/216). Zapíše se NDEF URI `tapdrop://p?id=NOTEBOOK_ID` (cca 20 bajtů).
4. **Nalep tag** vedle notebooku.
5. **Test telefon → PC.** V záložce *Odeslat* vyber soubor → **Připraveno k odeslání** → přilož telefon k tagu. Uvidíš průběh (%, rychlost, zbývající čas), notifikaci „Odesláno“ a soubor se objeví v seznamu *Přijato z telefonu* i ve složce `~/TapDrop`.
6. **Test PC → telefon bez potvrzení.** Přetáhni soubor do okna TapDrop na PC (zařadí se do „Čeká na stažení“) → přilož telefon k tagu. Telefon bez ptaní stáhne vše do `Stažené/TapDrop`, pošle potvrzení (`/ack`, soubor zmizí z fronty) a ukáže jen notifikaci „Staženo: X souborů“.

Automatické stahování lze vypnout v *Nastavení* (výchozí je zapnuto). Pak se po přiložení jen oznámí, kolik souborů čeká, a stáhnout je jde tlačítkem.

## Jak to funguje

* **Tag** obsahuje jen `tapdrop://p?id=nb01` (max. 12 znaků ID). Aplikace má `intent-filter` pro `NDEF_DISCOVERED`, takže přiložení tagu otevře TapDrop i ze zavřeného stavu; otevřená aplikace používá foreground dispatch.
* **Hledání notebooku:** telefon nejdřív zkusí mDNS (`_tapdrop._tcp.local.`), pak uloženou IP z párování. Ověří se `GET /discover` (správné ID i token).
* **Přenos** zajišťuje foreground služba – pokračuje i když odejdeš z aplikace.
* **Obnovení po výpadku WiFi:** stahování používá HTTP `Range`, odesílání se po výpadku naváže od místa, kam PC došlo (`GET /upload/status`). Velké soubory se streamují, nikdy se nenačítají celé do paměti.

## API PC serveru (port 8765)

Všechny endpointy vyžadují hlavičku `X-TapDrop-Token`, jinak odpoví `401`.

| Metoda | Cesta | Popis |
|---|---|---|
| `POST` | `/upload` | `multipart/form-data` (pole `file`), uloží do `~/TapDrop`. Volitelně `X-TapDrop-Size` (celková velikost) a `X-TapDrop-Offset` (navázání) |
| `GET` | `/upload/status?name=&size=` | `{ "offset": N }` – kolik už PC z přerušeného uploadu má |
| `GET` | `/pending` | `[{ "id", "name", "size" }]` – soubory čekající na telefon |
| `GET` | `/download/{id}` | stažení souboru (podporuje `Range`) |
| `POST` | `/ack/{id}` | odstraní soubor z fronty (originál na disku se nemaže) |
| `GET` | `/discover` | `{ "id", "name", "version" }` |

Příklady `curl` najdeš v `scripts/test-api.sh`:

```bash
./scripts/test-api.sh 192.168.1.20 TVUJ_TOKEN
```

nebo ručně:

```bash
curl -H "X-TapDrop-Token: TOKEN" http://192.168.1.20:8765/pending
curl -H "X-TapDrop-Token: TOKEN" -F "file=@foto.jpg" http://192.168.1.20:8765/upload
```

## Bezpečnost

* Jen lokální síť; žádná komunikace s internetem. Server poslouchá na portu 8765.
* Token má 32 znaků, vygeneruje se jednou při prvním spuštění (jde vygenerovat znovu v *Nastavení* – telefon se pak musí spárovat znovu). Porovnává se v konstantním čase.
* Názvy souborů se čistí (žádné cesty `../`, zakázané znaky, rezervované názvy Windows), duplicity se přejmenují `soubor (1).txt`.
* Provoz je nešifrované HTTP v domácí síti – nepoužívej v nedůvěryhodné WiFi (kdo zná token, může soubory číst i nahrávat).

## Soubory na PC

* Přijaté soubory: `C:\Users\<ty>\TapDrop` (jde změnit v nastavení)
* Konfigurace, fronta a **logy**: `%APPDATA%\TapDrop\` (logy v podsložce `logs`, tlačítko „Otevřít složku s logy“ v nastavení)
* Autostart (volitelný) se zapíná v nastavení; program pak startuje skrytý v liště.

## Řešení potíží

| Hláška v telefonu | Co dělat |
|---|---|
| **Notebook nenalezen** | Na notebooku musí běžet TapDrop, oba musí být ve stejné WiFi, povol program ve Windows Firewallu. Zkus *Nastavení → Test spojení*, případně ručně uprav IP. (Některé routery blokují mDNS mezi zařízeními – pak se použije uložená IP; přiděl notebooku v routeru pevnou IP.) |
| **Špatný token** | Token se změnil. Spáruj telefon znovu. |
| **WiFi vypnuta** | Zapni WiFi a připoj se do stejné sítě jako notebook. |
| **Tag patří jinému notebooku** | Tag byl zapsán pro jiný `NOTEBOOK_ID`. Zapiš ho znovu. |

Změna portu v PC programu (výchozí 8765) vyžaduje restart programu a nové spárování.

## Omezení a poznámky

* Z okna PC lze přetahovat soubory (ne celé složky).
* Zvuk po přenosu je volitelný (*Nastavení*), haptická odezva při přiložení tagu je vždy.
* Widget „Rychle odeslat“ (plocha telefonu) otevře obrazovku *Odeslat*.
