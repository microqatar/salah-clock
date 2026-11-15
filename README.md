# Salah Clock — Android prayer-time display (Phone · Tablet · Android TV)

A full-screen clock and prayer display. It shows:

- **Digital and analog clock.** The analog face is drawn in code, so it stays sharp at any size.
- **Gregorian and Hijri date** (Umm al-Qura calendar, with a ±2 day adjustment).
- **Weather for your location**: current conditions, humidity, wind and a 3-day forecast. It uses the free Open-Meteo service, so you don't need an API key.
- **Prayer times** for Fajr, Sunrise, Dhuhr (shown as Jumu'ah on Fridays), Asr, Maghrib and Isha, in English and Arabic. The next prayer is highlighted with a live countdown.
- **All four madhabs**:
  - **Hanafi**: Asr starts when an object's shadow is 2× its length.
  - **Maliki, Shafi'i, Hanbali**: Asr starts when the shadow is 1× its length.
- **13 calculation methods** for Fajr and Isha: Qatar, UAE (Dubai), Umm al-Qura, Kuwait, MWL, Egypt, Karachi, ISNA, Singapore, Turkey, Morocco, France and Russia. **Automatic** picks one from the device time zone.
- **Azan playback** at each prayer, switchable on or off per prayer, with a separate Fajr azan if you want one. It keeps working when the app is in the background or the screen is off, and comes back after a reboot. Tap anywhere to stop it.
- **Screen layouts**: portrait on phones, and a two-column landscape layout on tablets and TVs. The app has an Android TV launcher banner and works with a remote.

## Opening the Admin Panel
| Device | How |
|---|---|
| Phone / tablet | **Long-press anywhere on the screen for 1.5 s** |
| Android TV | **Hold OK/centre on the remote**, or press **Menu** |

The default PIN is **1234**. You can change it in the panel, or leave it empty so the panel opens without asking.

The Admin Panel has these settings:
- **Location**: device GPS/network location, or a manual city search. You can also enter latitude and longitude by hand and rename the location.
- **Prayer**: madhab, calculation method, Hijri offset, and a ± minute adjustment for each prayer.
- **Azan**: on/off for each prayer, volume, your own audio file (one normal azan and one for Fajr), and test buttons.
- **Display**: analog, digital or both; 12 or 24 hour time; seconds; weather on/off; °C or °F; background and accent colours; keep screen on.
- **System**: PIN, start after reboot (for mosque or wall displays), exact-alarm permission, exit.

## Building the APK
**Option A: Android Studio** (Ladybug or newer)
1. Unzip the project, then choose **File → Open** and select the `SalahClock` folder.
2. Wait for Gradle sync to finish. It downloads Gradle and the libraries the first time.
3. Choose **Build → Build App Bundle(s)/APK(s) → Build APK(s)**. The APK is saved at `app/build/outputs/apk/debug/app-debug.apk`.

**Option B: GitHub, without installing anything**
Push the folder to a GitHub repository. The included workflow (`.github/workflows/build.yml`) builds the APK automatically. Download it from the **Actions** tab, under **Artifacts**.

**Installing on Android TV:** copy the APK with a USB stick and open it with a file manager. You can also use the "Send Files to TV" app, or `adb install app-debug.apk`.

## Adding a built-in azan sound (recommended)
The project doesn't include an azan recording. Put your own MP3s here, using these exact lower-case names:
```
app/src/main/res/raw/azan.mp3        ← normal azan
app/src/main/res/raw/azan_fajr.mp3   ← optional, Fajr azan
```
Then rebuild. If you don't add them, the app uses the file picked in the Admin Panel. If nothing is picked there either, it plays the device's alarm tone.

## Notes
- Prayer times are calculated on the device, with no internet needed. Use the per-prayer minute adjustments to match your local mosque or Awqaf timetable exactly. Official tables often add 1–3 minutes of caution.
- Weather needs internet and refreshes every 30 minutes.
- Most TV boxes have no GPS. On a TV, set the location with **Search city** in the Admin Panel.
- Android 12 and newer may ask for **exact alarm** permission so the azan plays on the minute. The Admin Panel shows a button for this when it's needed.
