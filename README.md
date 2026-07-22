# Androscan

Kleine Android-App zur Barcode-Erfassung mit Artikel-Kacheln.

## Funktionen

1. Kameravorschau oben – Barcode wird per ML Kit gelesen
2. Danach Bestätigung über eine der 6 Kacheln: `1VMP`, `1VOP`, `1PL`, `1PI`, `1KN`, `1EN`
3. Jeder Eintrag erhält eine ID aus Hardware-ID + Zeitstempel (`ANDROID_ID-yyyyMMddHHmmssSSS`)
4. Einträge werden lokal (Room) gespeichert
5. CSV-Export wird direkt per SMTP an `mhosiner@grandits.com` gesendet

## Bauen

SMTP-Zugangsdaten in `smtp.properties` (siehe `smtp.properties.example`).

In Android Studio öffnen oder:

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Ablauf

1. Barcode in die Kamera halten
2. Kachel tippen zur Bestätigung
3. „Per SMTP senden“ schickt die CSV direkt (ohne Share-Dialog)
