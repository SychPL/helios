# Installing Helios on a Lenovo Smart Clock 2

The Smart Clock 2 ships with no launcher, no app store, no file manager and no
ADB. So the first question is not "how do I install Helios" but "how do I get
any APK onto this device at all". There are two answers, and the rest of this
page assumes you have picked one.

| you are here | go to |
|---|---|
| stock clock, no ADB | [A. TalkBack, no cable, no root](#a-talkback-no-cable-no-root) |
| ADB over Wi-Fi already on | [B. adb install](#b-adb-install) |
| APK on the clock | [Pair with Home Assistant](#pair-with-home-assistant) |

**What you need either way:** the clock set up through the Google Home app up to
the clock face, on the same Wi-Fi as your Home Assistant, and a Home Assistant
instance reachable over plain HTTP or HTTPS on your LAN.

**Where to get the APK:** build it yourself (see the README) or take it from the
[releases page](https://github.com/SychPL/helios/releases/latest). The build is
a debug build on purpose, which is also what lets `adb shell run-as` reach the
app's private files later.

---

## A. TalkBack, no cable, no root

This is the install path for a stock clock. It uses the clock's screen reader to
copy a URL into the clipboard and the browser that hides behind TalkBack's own
settings screen to download the APK. Full credit for the trick goes to
[ThomasPrior/LenovoSmartClock2](https://github.com/ThomasPrior/LenovoSmartClock2);
[smartclock2tool](https://github.com/SychPL/smartclock2tool/blob/main/INSTALL.md)
documents it step by step and is the version these instructions follow.

You need a phone with the Google Home app and the Google account whose calendar
the clock displays.

1. **Put the APK URL on the clock's screen.** In Google Calendar, create an
   event a few minutes in the future whose **title is only the URL** - no other
   words. The clock renders upcoming events as text and TalkBack reads titles in
   full.
2. **Turn the screen reader on.** Google Home app -> your clock -> gear icon ->
   Accessibility -> Screen reader.
3. **Make it read the URL.** On the clock, ask for your upcoming events and
   swipe sideways until TalkBack reads the event title.
4. **Copy it.** Draw an **L** on the screen to open the TalkBack menu, swipe to
   *Copy last utterance to clipboard*, double-tap.
5. **Open the hidden browser.** Draw an **L** again, choose *Open TalkBack
   settings*, scroll to the bottom and tap *Privacy policy*. That opens the
   clock's built-in browser. Accept the storage permission prompts. You can turn
   the screen reader off now.
6. **Download and install.** Tap the address bar, clear it, long-press ->
   Paste, long-press the URL -> Open. When the download finishes, open it from
   the Downloads screen and allow the browser to install unknown apps.
7. **Install a launcher too** (recommended). Repeat the same steps with a small
   launcher such as `ultra-small-launcher`, then set it as the default home
   screen. From then on installing an APK is download and tap, and Helios is
   just an icon.

If the download refuses to start, the usual causes are a calendar title that
contains anything besides the URL, or the clock's old browser giving up on
GitHub's CDN redirect. In the second case host the APK on your own machine and
use a plain `http://<your-pc>:8000/helios.apk` URL instead.

Helios registers itself as a home-screen app, so you can also make Helios itself
the default launcher once it is installed.

---

## B. adb install

If the clock already has ADB over Wi-Fi - which on this device means you ran
[smartclock2tool](https://github.com/SychPL/smartclock2tool) and pressed
`ROOT + ADB` - then installing is the boring one-liner:

```bash
adb connect <clock-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n pl.mateusz.helios/.MainActivity
```

Two things worth knowing about that tool: everything it does is runtime-only, so
a power cycle returns the clock to stock and you press the button again; and
while ADB over Wi-Fi is on, any host on your LAN can connect to the clock as
`shell`, so turn it off when you are done.

ADB is also the comfortable way to grant the microphone permission without
touching the screen:

```bash
adb shell pm grant pl.mateusz.helios android.permission.RECORD_AUDIO
```

---

## Pair with Home Assistant

Helios pairs with a six-digit code. No long-lived admin token is ever typed into
the clock: the integration creates a dedicated Home Assistant user for the
device, non-admin and local-network-only, and gives the clock that user's token.

1. **Install the integration.** HACS -> Integrations -> three dots -> Custom
   repositories -> `https://github.com/SychPL/ha-helios`, category Integration
   -> install **Helios** 0.8.0 or newer. Restart Home Assistant. (Without HACS:
   copy `custom_components/helios` into `config/custom_components/helios`.)
2. **On the clock**, the first start shows *Wybierz Home Assistant* (Choose Home
   Assistant). Pick your server from the mDNS list, or choose *Wpisz adres*
   (Enter address) and type `http://<host>:8123`. Leave the clock on that
   screen.
3. **In Home Assistant**, go to Settings -> Devices and services -> Add
   integration -> **Helios**. It shows a six-digit code, valid for five minutes.
   Leave that dialog open: it is what completes the pairing.
4. **On the clock**, tap *Dalej* (Next) and type the code. The clock posts it to
   `/api/helios/pair`, and the integration answers with the device's own token,
   the Assist pipeline to use and, if Music Assistant is installed, its address
   and token.
5. **Assign the device to an area** (bedroom, kitchen, ...). From the next
   conversation on, voice commands carry that room as context.

Re-pairing later (clock menu -> *Paruj z HA*) replaces the user and token, and
only removes the old one once the new pairing succeeded. Deleting the
integration deletes the clock's user, its Music Assistant token and its stored
images; the clock notices, stops reconnecting and asks to be paired again.

### Music Assistant note

If Music Assistant runs as a Home Assistant add-on, it sees Home Assistant as a
system user and refuses to mint a token the clock could use. In that case paste
a token once: Music Assistant -> Settings -> Tokens, then Home Assistant ->
Settings -> Devices and services -> Helios -> Configure -> *Token Music
Assistant dla zegara*. The integration stores it as-is, never verifies it and
never revokes it.

---

## After the install

- **Microphone.** The first start asks for the microphone permission. The wake
  word is "Okay Nabu" and detection runs entirely on the device; nothing is
  streamed before it fires. The clock's physical microphone switch is the mute
  control - there is no on-screen toggle.
- **Dashboard.** Until you publish a `helios` section in a Lovelace dashboard,
  the clock shows a fallback layout with just the time. See
  [ha-dashboard.md](ha-dashboard.md) for the tile schema, and `ha/` for example
  manifests - they describe one specific home and are meant to be edited.
- **Updates.** The clock's hidden menu (long-press the HELIOS label) can check
  GitHub releases and install a newer APK through the Android package installer.
  Sideloaded updates also work: `adb install -r` keeps the pairing and settings.
- **Nothing is persisted outside the app.** Helios does not change your default
  launcher, does not touch Google settings and does not start itself on boot
  unless you make it the home screen app.
