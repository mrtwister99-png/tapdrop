# TapDrop

Přenos souborů mezi telefonem a notebookem v **lokální WiFi** – spárování jedním naskenováním QR kódu, pak už to jede samo. Žádný cloud, žádný internet, žádné NFC.

* **Telefon → notebook:** v aplikaci vybereš soubory a stiskneš „Odeslat do notebooku“. Uloží se do `~/TapDrop` na PC.
* **Notebook → telefon (bez potvrzení):** přetáhneš soubory do okna TapDrop na PC a telefon si je **hned sám stáhne** do `Stažené/TapDrop` – žádný dialog. Telefon na pozadí hlídá notebook (zapnuto ve výchozím stavu).
* **Obousměrný text „Odhoz“:** na telefonu i PC můžeš psát text, odeslat ho na druhou stranu a zkopírovat celý obsah nebo jeho označenou část.

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

Hotový **instalátor** (`TapDrop-Setup.exe`) se staví přes [Inno Setup](https://jrsoftware.org/isinfo.php):

```powershell
ISCC.exe installer\tapdrop.iss
```

Výsledek: `dist\TapDrop-Setup.exe`. Skript bere EXE přímo z `pc-rust\target\release\TapDrop.exe`.

### Android aplikace

Otevři složku `android-app` v **Android Studiu** (Koala nebo novější, JDK 17+) a stiskni Run, nebo z příkazové řádky:

```powershell
cd android-app
.\gradlew.bat assembleDebug
```

APK: `android-app\app\build\outputs\apk\debug\app-debug.apk` (instalace: `adb install -r app-debug.apk`).
Soubor `local.properties` s cestou k Android SDK si Android Studio vytvoří samo.

## Použití – krok za krokem

1. **Spusť PC program** (`TapDrop.exe`). Při prvním spuštění povol Windows Firewall pro *soukromé sítě*. V okně uvidíš „● Běží“, IP, `NOTEBOOK_ID`, `TOKEN` a QR kód. Okno má záložky Přehled, Odhoz, Historie a Nastavení; zavření okna program skryje do lišty u hodin.
2. **Spáruj telefon přes QR.** V aplikaci *Domů* → **Spárovat s notebookem** → naskenuj QR z okna PC. Hotovo, telefon i notebook musí být ve stejné WiFi.
3. **Test telefon → PC.** Záložka *Odeslat* → vyber soubor → **Odeslat do notebooku**. Uvidíš % / rychlost / zbývající čas, notifikaci „Odesláno“ a soubor je v `~/TapDrop` i v okně PC.
4. **Test PC → telefon bez potvrzení.** Přetáhni soubor do okna TapDrop na PC → během pár sekund ho telefon sám stáhne (notifikace „Staženo: X souborů“), soubor zmizí z fronty „Čeká na stažení“.
5. **Test textu Odhoz.** V telefonu otevři záložku *Odhoz*, napiš text a stiskni „Odeslat text“; na PC otevři záložku *Odhoz* a text se objeví. Můžeš také napsat text na PC a načíst ho v telefonu. Maximální velikost textu je 1 MiB.

Automatické stahování a běh na pozadí jdou vypnout v *Nastavení* aplikace (výchozí je zapnuto). Telefon přenos vždy spouští i ručně tlačítkem „Stáhnout teď“.

## Jak to funguje

* **Párování:** QR kód nese `tapdrop://pair?ip=…&port=8765&id=…&token=…`.
* **Hlídání:** po spárování běží na telefonu foreground služba, která každé 3 s volá `GET /pending`. Jakmile jsou na PC soubory, rychle je stáhne a pošle `/ack`.
* **Hledání notebooku:** telefon používá uloženou IP; když přestane odpovídat, zkusí mDNS (`_tapdrop._tcp.local.`) a adresu aktualizuje.
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
| `GET` | `/odhoz` | `{ "text" }` – vrátí aktuální sdílený text |
| `PUT` | `/odhoz` | `{"text":"..."}` – uloží aktuální sdílený text (max 1 MiB) |

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

Změna portu v PC programu (výchozí 8765) vyžaduje restart programu a nové spárování.

## Omezení a poznámky

* Z okna PC lze přetahovat soubory (ne celé složky).
* Zvuk po přenosu je volitelný (*Nastavení*).
* Widget „Rychle odeslat“ (plocha telefonu) otevře obrazovku *Odeslat*.
* Aby telefon na pozadí fungoval, nevypínej aplikaci „TapDrop“ v úsporném režimu baterie (výrobci jako Xiaomi/Samsung to někdy zabíjejí).
