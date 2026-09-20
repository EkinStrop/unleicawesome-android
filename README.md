# Unleicawesome

Android editor for Leica Essential / M9-filter photos from the Xiaomi 17 Ultra Leica Edition. Supports the 17 Ultra only, as Xiaomi's Leica cloud processing is exclusive to that device.

Scans `DCIM/Camera` for supported photos. Adjusts white balance, tint, exposure and color bias, then exports a new JPEG to the same folder. The original file is kept.

Use supported photos that have not been cloud processed. Root is only needed for the optional **Export & Send to Cloud** action.

The preview is approximate. The cloud result may differ.

## Build

Use JDK 21 and Android SDK 36.1.

```powershell
.\gradlew.bat assembleStaging
```

Output: `app/build/outputs/apk/staging/app-staging.apk`.

## License

Licensed under the [GNU General Public License v3.0](LICENSE).
