# Backup

Backup protects older adults from phone scams by alerting a trusted family member at the risky moment. Scammers keep victims on long calls and push them to open a payment app. Backup notices that pattern on the older person's phone and makes the family member's phone ring within seconds.

This repository is a working hackathon demo with three parts:

| Part | What it is | Where |
|---|---|---|
| **Backup** (Member app) | Android app on the older person's phone. Large text, English and Hindi, almost no interaction. Detects the pattern. | `android/member` (`app.backupcircle.member`) |
| **Backup Circle** (Guardian app) | Android app on the adult child's phone. Dashboard, events, full-screen alert. | `android/guardian` (`app.backupcircle.guardian`) |
| **Demo server** | Python (FastAPI) on a laptop on the same Wi-Fi. Pairing, event relay, presence, APK downloads, mDNS discovery. | `server/` |

Everything runs on a local network. No internet, cloud, accounts, Firebase or Google Play Services. Backup never records calls, reads messages, or sends contact names or numbers of saved contacts.

## How it works

1. The Member phone answers a call from a number that is **not saved in its contacts**: the Guardian dashboard shows "Papa is on a call with an unknown number" with a live timer.
2. The call passes the **long-call threshold** (default 5 minutes): the Guardian gets a "long call" warning.
3. The Member opens a **UPI app** (PhonePe, Google Pay, Paytm, BHIM, CRED, MobiKwik, YONO SBI) during the call or within 15 minutes after it: the Guardian phone **rings like an alarm** with a full-screen alert, "Papa may be talking to a scammer", with **Call Papa** and **It's safe, I checked**.

Detection happens on the Member phone. The server only stores and relays events. A payment app opened before the threshold gives an amber "medium" alert; after the threshold or after the call it gives a red "high" alert (at most one of each per call).

## Repository layout

```
server/         FastAPI app (backup_server/), tests/, requirements.txt, data/ (SQLite, created on start)
android/        One Gradle project: :core (models, API + WebSocket client, discovery, theme), :member, :guardian
scripts/        build_apks.py, fake_guardian.py, fake_member.py
dist/           Built APKs, served by the server (created by the build script)
.env            Local settings for this laptop (not committed; see Configuration)
```

---

## Demo-day runbook

### 1. Laptop setup (once)

You need **Python 3.11+**, **JDK 17** and the **Android SDK** (Android Studio installs it; platform 35 and build-tools 35 are downloaded automatically on the first build).

Create the server's Python environment and install the pinned requirements (Windows PowerShell shown; on macOS/Linux use `server/.venv/bin/python`):

```bash
python -m venv server/.venv
```

```bash
server\.venv\Scripts\python -m pip install -r server/requirements.txt
```

Check `.env` (see [Configuration](#configuration-env)). Then build both APKs into `dist/`. The first build downloads Gradle and libraries and takes a few minutes:

```bash
server\.venv\Scripts\python scripts/build_apks.py
```

Start the server with one command. It prints the download address in large digits and whether mDNS advertising worked:

```bash
server\.venv\Scripts\python server/run.py
```

For a fresh demo with no old pairings or events, start it with `--reset` (this wipes the database):

```bash
server\.venv\Scripts\python server/run.py --reset
```

**Windows firewall:** the first time the server starts, Windows asks whether Python may use the network. Allow it on **both Private and Public** networks, because phone hotspots are often treated as Public. If the phones cannot open the download page, this is the usual cause (Windows Security → Firewall → Allow an app through firewall → Python → tick both boxes).

### 2. Network

Use a **phone hotspot or a travel router**. College and office Wi-Fi usually blocks phone-to-phone and phone-to-laptop traffic, and the demo will not work there. Connect the laptop and both demo phones to it before starting the server. The hotspot does not need internet. If the laptop is connected to more than one network, the server picks the default route; force another one with `--ip` or `BACKUP_IP`.

### 3. Install the apps

On each phone, open the address the server prints (for example `http://192.168.43.17:8000`) in the browser.

- Older person's phone: **Download Backup**
- Family member's phone: **Download Backup Circle**

Open the downloaded file. If Android blocks it, tap **Settings**, turn on **Allow from this source**, go back and tap **Install**. If Play Protect warns, tap **Install anyway** (these are demo apps built on the laptop). Both apps can also be installed on one phone for development.

### 4. Prepare the Backup Circle (guardian) phone

1. Open **Backup Circle** → **Get started** → enter your name (for example "Rohan").
2. Allow **notifications**, **full-screen alerts** and **run in the background**.
3. Turn the **alarm volume** up and switch off **Do Not Disturb**.

### 5. Prepare the Backup (member) phone

1. Install at least one **UPI app** (PhonePe, Google Pay, Paytm or BHIM) and open it once.
2. Open **Backup** → choose **English** or **हिंदी** → **Start**.
3. Allow every card on the permissions screen (the family member helps):
   - **Notifications**
   - **Phone, call log and contacts** (three system dialogs)
   - **Caller ID & spam app**: choose **Backup**. If Truecaller or another app holds this role, switch it to Backup.
   - **Usage access**: turn on **Backup** in the list, then come back.
   - **Run in the background** (recommended).
   - On Xiaomi, Redmi, POCO, Oppo, Vivo and Realme: **Allow autostart** (recommended; these phones stop background apps otherwise).
4. Tap **Continue**. The phone shows a large 6-digit code.
5. On the guardian phone: **Add a person to protect** → **Connect their phone** → type the code → choose what you call them (Papa, Mummy, …) and optionally their number (used by the Call button).
6. The member phone shows "You're connected to Rohan" and then its home screen. The guardian dashboard shows **Protected · Online**.
7. Open the **demo panel** on the member phone: **long-press the shield for 3 seconds**. Check that every permission shows ✓, the server address is filled in and the connection says connected. Set the **long-call threshold to 1 minute** for judging sessions.

### 6. The calling phone

Use a third phone whose number is **not saved in the member phone's contacts**. Calls from saved contacts produce nothing, by design.

### 7. Rehearsal checklist

- [ ] Server banner shows **mDNS: OK**, and both APKs are **ready**.
- [ ] Guardian dashboard shows **Protected · Online** (green).
- [ ] Member demo panel: all permissions ✓, connection connected, threshold **1 min**.
- [ ] Guardian phone: alarm volume up, Do Not Disturb off, Backup Circle allowed to run in the background.
- [ ] Test call: answer on the member phone → dashboard shows the call within 3 seconds with a live timer.
- [ ] After 1 minute → guardian gets the long-call warning.
- [ ] Open a UPI app on the member phone → guardian phone rings with the full-screen alert within 3 seconds (also with its screen locked).
- [ ] Tap **It's safe, I checked** → the alert shows "Marked safe by Rohan" on the dashboard and in Events.

### 8. Fallback plan

- **No mobile signal in the hall:** use the simulation buttons in the member demo panel: *Simulate unknown call answered*, wait for the threshold, *Simulate payment app opened*, then *Simulate call ended*. They feed the same detector as a real call, so the guardian side behaves exactly the same.
- **Guardian phone fails:** on the member phone tap **Add another Backup** (toolbar) to show a new code, then run the fake guardian on the laptop screen. It prints every alert readably:

  ```bash
  server\.venv\Scripts\python scripts/fake_guardian.py 482913
  ```

- **A phone cannot find the server:** check that it is on the hotspot and the firewall allows Python, then open the demo panel (member: long-press the shield; guardian: long-press the "Backup Circle" title) and type the address the server prints, or tap **Rediscover server**. Before pairing, the same panel opens by long-pressing the shield on the welcome screen or the headline of the name/code screens.

### 9. Reset between judging rounds

1. Server: press Ctrl+C, then start it again with `--reset`.
2. Both phones: open the demo panel → **Reset this phone**. Each app starts again from its first screen.

If you forget step 2, the apps notice that the server no longer knows them and set themselves up again: the member phone shows a new code, and the guardian registers again under the same name.

### 10. Known limits

- Android 10 or newer only. No iPhone apps.
- No call audio and no message reading, by design.
- Works only on the local network with the laptop server running.
- When the guardian phone is unlocked and in use, Android shows the alert as a ringing heads-up notification instead of taking over the screen. When it is locked, the alert opens full screen.
- Aggressive battery managers (Xiaomi-family phones especially) may stop background apps unless autostart and background running are allowed.

---

## Configuration (.env)

`server/run.py` and the scripts read `.env` in the repository root. Values already set in the real environment, and command-line flags, take priority. A blank value means the built-in default. `.env` is not committed because it holds this laptop's paths. Create it with any of these keys:

| Key | Default | Used by | Meaning |
|---|---|---|---|
| `BACKUP_PORT` | `8000` | server, fake scripts | Port the server listens on. |
| `BACKUP_IP` | detected | server | LAN address to print and advertise. Same as `--ip`. |
| `BACKUP_MDNS` | `true` | server | Advertise `_backupcircle._tcp` over mDNS. `false` = same as `--no-mdns`. |
| `BACKUP_CODE_TTL_SECONDS` | `600` | server | How long a pairing code stays valid. |
| `BACKUP_DB_PATH` | `server/data/backup.db` | server | SQLite file. Relative paths are from the repo root. |
| `ANDROID_HOME` | detected | build script | Android SDK folder. Written to `android/local.properties` on each build. |
| `GRADLE_USER_HOME` | `~/.gradle` | build script | Gradle's download cache. |

On this laptop, `.env` points `ANDROID_HOME` and `GRADLE_USER_HOME` at `.toolchain/`, so the SDK, emulator image and Gradle cache stay on D: (C: is nearly full).

## Developer tools

Server tests (pairing, codes, idempotency, real-time relay, missed events, resolve broadcast, presence):

```bash
server\.venv\Scripts\python -m pytest server/tests -q
```

Detector state-machine tests (contacts, missed and outgoing calls, threshold, medium and high alerts, after-call window, switches, simulation):

```bash
cd android && gradlew.bat :member:testDebugUnitTest
```

**Fake member** registers a member, prints a pairing code, waits for a guardian, then plays a scam scenario (call started → long call → payment app → call ended) to test the Guardian app without a real call:

```bash
server\.venv\Scripts\python scripts/fake_member.py --threshold 30 --alert-at 40 --call-length 60
```

Add `--repeat 3 --gap 30` to replay the scenario. Setting `--alert-at` below `--threshold` gives a medium alert.

**Fake guardian** pairs with a code shown on a member phone and prints every message (`--resolve safe` answers alerts automatically):

```bash
server\.venv\Scripts\python scripts/fake_guardian.py 482913 --name Rohan --call-them Papa
```

### Emulator test path

An emulator can exercise the real call path. Create an Android 35 `google_apis` x86_64 device in Android Studio, install both APKs, and set up the member app as above.

1. The emulator usually finds the laptop server by itself. If not, open the member demo panel and set the server address to `10.0.2.2:8000` (the emulator's name for the laptop).
2. In the demo panel set the threshold to 30 s, then **Add app → Chrome** as a stand-in payment app.
3. Place a call from a number that is not in the emulator's contacts, and answer it:

   ```bash
   adb emu gsm call 5551234567
   ```

   ```bash
   adb emu gsm accept 5551234567
   ```

4. Wait for the threshold, then open Chrome on the emulator:

   ```bash
   adb shell monkey -p com.android.chrome -c android.intent.category.LAUNCHER 1
   ```

5. End the call:

   ```bash
   adb emu gsm cancel 5551234567
   ```

To check that saved contacts produce nothing, save a contact for a number in the emulator's Contacts app and call from that number.

## Architecture notes

- **Discovery:** the server registers `_backupcircle._tcp` over mDNS (python-zeroconf). Both apps find it with Android NsdManager, save the address, and search again when it stops answering. A typed address in the demo panel works as a fallback.
- **Events:** the member app posts events over HTTP with a client event ID, from a queue saved on the phone and retried until acknowledged. The server stores each event once and pushes it to linked guardians over WebSocket. A guardian that was offline fetches what it missed by the last event ID it saw.
- **Presence:** both apps keep a WebSocket open and ping every 15 seconds. After 20 seconds of silence the server asks the phone to ping (which wakes a sleeping phone); after 35 seconds it marks it offline.
- **Clocks:** the live call timer starts at the guardian's own receipt time minus the elapsed seconds in the event, so phone clocks never have to match. The server shifts member timestamps into its own clock.
- **Privacy:** the server only receives the last 4 digits of an unknown caller's number, and app usage only for watched payment apps during a risk window. The member can switch off either kind of sharing in "What can my Backup see?".
- **Pairing codes:** 6 random digits, valid for 10 minutes, single use. A new code replaces the old one. After 5 wrong attempts in a minute a guardian is locked out for 60 seconds.
