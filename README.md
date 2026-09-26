# Idea Walk (Android app)

Record a voice note on a walk. The app transcribes it with your phone's own
speech recogniser, then Claude pulls out your best ideas, scores them 1–5 and
files them into a list by theme. Everything is stored only on your phone.

## 1. Build the APK (pick one)

### Option A: GitHub, no installs (about 5 minutes)
1. Create a free account at github.com and make a new **private** repository.
2. Upload the contents of this folder (drag the files and folders onto
   "uploading an existing file"). If the `.github` folder doesn't upload
   (hidden folders sometimes don't), open the repo's **Actions** tab, choose
   "set up a workflow yourself", and paste in `.github/workflows/build-apk.yml`.
3. Open the **Actions** tab and wait for "Build Idea Walk APK" to finish
   (about 4 minutes; start it with "Run workflow" if it didn't start).
4. Open the finished run and download **IdeaWalk-apk** at the bottom. Unzip it
   to get `app-release.apk`.

### Option B: Android Studio
Open this folder in Android Studio, let it sync, then
**Build > Build App Bundle(s) / APK(s) > Build APK(s)**, or run
`./gradlew assembleRelease`. The APK is at
`app/build/outputs/apk/release/app-release.apk`.

## 2. Install it on your phone
1. Send `app-release.apk` to your phone (Google Drive, email, or USB).
2. Tap it. Android will ask you to allow installs from that app
   (Drive, Files, Gmail…). Allow it, then tap **Install**.
3. If Play Protect warns that the app is unknown, tap **More details > Install anyway**.
   It's flagged only because you built it yourself rather than getting it from the Play Store.

## 3. First run
1. Open **Settings** in the app and paste your Claude API key
   (console.anthropic.com > API keys). It's stored only in the app on your phone.
2. Go to **Record**, tap the mic and allow microphone access.
3. Talk. Tap again to stop, then **Pull out my ideas**.

## Good to know
- **Screen stays on while recording.** Android stops speech recognition when
  the screen locks. The app keeps the screen awake while you record, so don't
  press the power button mid-walk.
- Some phones play a short chime each time recognition restarts after a pause.
- Recognition uses your phone's default language. Change it under Android
  Settings > System > Languages, or Google app > Voice.
- **Backups:** Settings > Share backup sends everything as text to Drive or
  email. Uninstalling the app deletes your data.
- **Costs:** each walk is one Claude API call, typically well under ₹1 with
  claude-sonnet-5. You can switch to claude-haiku-4-5-20251001 in Settings.

## Project layout
- `app/src/main/assets/index.html` – the whole UI (HTML/CSS/JS)
- `app/src/main/java/.../MainActivity.java` – WebView host, speech
  recogniser, Claude API call, share sheet
- `app/ideawalk.keystore` – signing key (password `ideawalk-keystore`).
  Keep it: future updates must be signed with the same key to install over
  the old version without losing your data.
