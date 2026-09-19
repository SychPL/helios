# Music Assistant / Sendspin compatibility prototype

Date: 2026-09-15. Bounded local build investigation for `docs/SPEC-0.6-music-assistant.md`.

## Verdict

The actual Sendspin JVM dependency builds and packages in an isolated Android APK with Java 8 consumer source, minSdk 29 and an ARMv7 ABI filter. This is **build compatibility**, not proof of playback or device/runtime compatibility. The pinned library implements a legacy cleartext Sendspin handshake and does **not** implement the current pinned specification's Noise/authentication/pairing flow. Follow-up inspection of official **MA 2.10.3** sources establishes a matching legacy compatibility path, enabled by default in MA: **handshake compatibility is supported by source evidence, conditional on the actual legacy-client setting and client identity admission**. Noise is therefore not an unconditional blocker for this installation. No live Sendspin connection or audio test has been performed.

## Pins and build inputs

- Library: [Sendspin/sendspin-jvm at 288f22e362d9defbe08bf0545decb00c64909aab](https://github.com/Sendspin/sendspin-jvm/tree/288f22e362d9defbe08bf0545decb00c64909aab), Apache-2.0, verified from its LICENSE. Unmodified checkout is ignored under `probes/music-assistant/upstream`; `upstream.lock` and `run.ps1` enforce the revision and reject tracked source modifications.
- Protocol reviewed: [Sendspin/spec at 8fc2f8f8d8aa324cf385bd3332a284fd3a75520c](https://github.com/Sendspin/spec/blob/8fc2f8f8d8aa324cf385bd3332a284fd3a75520c/README.md). Ignored source checkout retained under `probes/music-assistant/protocol-spec`.
- Library build declares JVM source/target 17 and `jvmToolchain(17)`, Kotlin 2.0.0, KSP 2.0.0-1.0.22, Moshi 1.15.1, coroutines 1.8.1, OkHttp 4.12.0, Java-WebSocket 1.5.7, org.json 20240303. Compiled `SendSpinClient` class major version is **61 (Java 17)**.
- Used existing JDK `<user-home>/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2`, SDK `<user-home>/AppData/Local/Android/Sdk` (matching workspace `local.properties`), compileSdk 35, build tools 36.0.0, AGP 8.13.2 and workspace Gradle wrapper **8.14.3**. Upstream ships wrapper **9.4.1**; that wrapper was not exercised. Unchanged upstream built successfully with the existing 8.14.3 wrapper.
- Composite Gradle build consumes the real upstream project including generated Moshi adapters and transitive dependencies; no protocol source copying, replacement classes or JitPack availability assumptions. Upstream README/publication still use `com.github.OnFreund`, so published coordinates are a separate unresolved distribution question.

## Tests and observations

| Check | Result / limit |
|---|---|
| Upstream `:sendspin-protocol:test :sendspin-protocol:jar` | PASS: 164 tests, 14 suites, zero failures/errors/skips |
| Local `:jvm:test` | PASS: 2 tests using actual dependency |
| Loopback WebSocket | Real OkHttp client against MockWebServer bound to 127.0.0.1, ephemeral port; hello contains fixed fixture ID, exactly four requested roles, PCM 48 kHz/stereo/16-bit and album JPEG 320x320 |
| State | Legacy server hello, controller volume 23 / supported stop command, metadata title, and explicit disconnect verified; sink is upstream NoOpAudioPlayer |
| Current core incompatibility | Parser returns UnknownMessage for server/init, server/activate and server/pair-auth; rejects current name-only server/hello |
| Android `:android-probe:assembleDebug` | PASS, including Java compilation, duplicate-class check, D8 conversion and APK packaging |
| Java 8 consumer | AudioTrackBuildProbe compiled class major **52**; directly references SendSpinClient StateFlow and implements AudioPlayer as an abstract build-only adapter |
| Android manifest / ABI | APK inspected with aapt: minSdk 29, targetSdk 29. ARMv7 filter configured; APK has four DEX files and **no native libraries**, so this does not exercise ARMv7 machine code |
| AudioTrack | Android builder API, PCM format and interface linkage compile; builder was never called, no audio sink was instantiated |

The first loopback run failed only during mock-server shutdown; implementing the WebSocket close reply fixed the fixture. Adding metadata exposed that the library changes its connection state to `STREAMING` on **any server/state**, before receiving audio or completing clock synchronization. The final test asserts that observed behavior while the no-op player remains stopped. Do not map this connection-state enum directly to Helios's local `playing` layout trigger.

The prototype APK has no launcher/activity/service and no network permission. It is a packaging artifact, not a runnable player. `AudioTrackBuildProbe` is deliberately abstract: no buffer drain, decoder, scheduling, audio focus or service implementation is claimed.

## Current protocol gap

At the pinned protocol revision, initial messages are client/init, server/init and Noise handshake; application messages then use encrypted binary transport. Server/hello precedes client/hello, active roles arrive in server/activate, and identity is a persisted Curve25519 public key. Pairing establishes a PSK and includes code/token flows. These requirements differ from this library's immediate cleartext client/hello, arbitrary string/UUID identity, legacy server/hello fields and plaintext JSON / raw audio binary frames. The actual parser tests substantiate the gap; this is not merely a JDK concern.

The public [MA provider documentation](https://github.com/music-assistant/server/blob/62bb0289d390507855990d5d0425705ee44a50bf/music_assistant/providers/sendspin/README.md) describes direct LAN `ws://<host>:8927/sendspin` and separately authenticated MA API/WebRTC signaling. That example is **not verification of the user's endpoint or authentication policy**. An MA API token must not be assumed to satisfy Sendspin pairing or be sent as an invented WebSocket header. The version-specific investigation below supersedes the initial uncertainty about MA's legacy protocol support.

Java 8 app source can consume this Java 17 library through the tested Android toolchain; this does not make the JAR runnable on a Java 8 JVM. Android runtime API usage, release shrinking and eventual dependency conflicts with the full app remain unverified. The fixture explicitly supplies Moshi's KotlinJsonAdapterFactory: upstream has support classes marked `generateAdapter=false`, so do not rely on README's blanket zero-reflection claim.

## MA 2.10.3: pinned public-source follow-up

Only public GitHub refs/raw sources were requested in this follow-up. No local probe code/build inputs were changed, no Python server/package was installed or executed, and no live-server calls were made by this investigator.

- Official annotated tag `2.10.3`: tag object `b109823396e1a5c20faeef6183997d23200f129a`, peeled commit **`3e21f8293fbcd8710dc2d3c8afc2c94418187cf9`**, resolved using `git ls-remote https://github.com/music-assistant/server.git 'refs/tags/2.10.3*'`.
- At that immutable commit, the [Sendspin provider manifest](https://github.com/music-assistant/server/blob/3e21f8293fbcd8710dc2d3c8afc2c94418187cf9/music_assistant/providers/sendspin/manifest.json) pins **`aiosendspin[server]==9.1.1`**, plus `av==16.1.0`. This establishes the release's declared dependency; installed package contents were not independently inspected.
- Official aiosendspin tag `9.1.1` resolves to **`5c024b42893bc6e372f6c87bb8504552051daf24`**, using `git ls-remote https://github.com/Sendspin/aiosendspin.git 'refs/tags/*9.1.1*'`. Protocol behavior was traced in that revision, rather than inferred from the moving specification head.
- MA's [provider implementation](https://github.com/music-assistant/server/blob/3e21f8293fbcd8710dc2d3c8afc2c94418187cf9/music_assistant/providers/sendspin/provider.py) defines hidden `allow_legacy_clients` with `default_value=True`, also reads it with fallback `True`, and passes it to **both** `allow_unencrypted` and `allow_noncompliant_clients`. The underlying aiosendspin constructor defaults to unencrypted disabled, but MA explicitly overrides that default. The live persisted setting remains unknown.
- In aiosendspin's [connection implementation](https://github.com/Sendspin/aiosendspin/blob/5c024b42893bc6e372f6c87bb8504552051daf24/aiosendspin/server/connection.py), `_establish_transport` accepts first-frame cleartext `client/hello` when `allow_unencrypted` is true. It uses the raw WebSocket, consumes the hello's `client_id` and `version=1`, and responds with `LegacyServerHelloMessage`: `server_id`, `name`, `version`, `active_roles`, `connection_reason`. This matches the hello exchange exercised by the pinned JVM fixture; the legacy route substitutes for encrypted hello plus activation.
- The [core models](https://github.com/Sendspin/aiosendspin/blob/5c024b42893bc6e372f6c87bb8504552051daf24/aiosendspin/models/core.py) explicitly retain legacy identity/version fields and versioned support objects. The four requested role registrations ([player](https://github.com/Sendspin/aiosendspin/blob/5c024b42893bc6e372f6c87bb8504552051daf24/aiosendspin/server/roles/player/__init__.py), [metadata](https://github.com/Sendspin/aiosendspin/blob/5c024b42893bc6e372f6c87bb8504552051daf24/aiosendspin/server/roles/metadata/__init__.py), [artwork](https://github.com/Sendspin/aiosendspin/blob/5c024b42893bc6e372f6c87bb8504552051daf24/aiosendspin/server/roles/artwork/__init__.py), [controller](https://github.com/Sendspin/aiosendspin/blob/5c024b42893bc6e372f6c87bb8504552051daf24/aiosendspin/server/roles/controller/__init__.py)) use the registry's default `requires_pairing=False`. They are not removed merely because a legacy connection is unpaired.
- Admission is still conditional: `_admit_legacy_client_id` rejects an unexpected identity or one already paired, staged for pairing, or recorded as trusted-unpaired, preventing an identity downgrade. Legacy connections cannot perform pairing. Turning off legacy support requires a Noise-capable client; no settings were changed here.
- [MA constants](https://github.com/music-assistant/server/blob/3e21f8293fbcd8710dc2d3c8afc2c94418187cf9/music_assistant/constants.py) set Sendspin's separate listener to **8927**, bound by the provider using the streams bind address; [aiosendspin server](https://github.com/Sendspin/aiosendspin/blob/5c024b42893bc6e372f6c87bb8504552051daf24/aiosendspin/server/server.py) fixes the path to `/sendspin`. Thus `ws://<home-assistant>:8927/sendspin` is a **source-derived candidate**, not a tested endpoint. The supplied streamserver port **8097** is not the Sendspin listener; add-on exposure/binding remains unverified.

Conclusion: the tested JVM legacy hello is structurally compatible with the explicitly supported MA 2.10.3 transition mode. This is a static-source comparison, **not** an executed integration test against aiosendspin 9.1.1 or the installed add-on. It does not establish every audio frame/state schema, controller operation, sync behavior or reconnect behavior. The earlier latest-spec mismatch tests remain valid for the encrypted path, but do not demonstrate failure of this legacy path.

## Reproduce (PowerShell, workspace root)

```powershell
Set-Location '<workspace>/dash'
& ./probes/music-assistant/run.ps1 -Mode All
```

Modes `Upstream`, `JVM`, `Android` select individual checks. Optional `-Jdk` and `-Sdk` override the existing paths. Runner fetches the public pinned checkout if absent, verifies it, and invokes the workspace wrapper with `-p`; it never includes or builds the main app. Downloads/cache writes and loopback socket tests may require sandbox network approval. No live-server parameters are accepted.

Equivalent build tasks, after runner environment setup:

```powershell
./gradlew.bat -p probes/music-assistant/upstream :sendspin-protocol:test :sendspin-protocol:jar --console=plain --no-daemon
./gradlew.bat -p probes/music-assistant :jvm:test :android-probe:assembleDebug --console=plain --no-daemon
```

Outputs (all local/ignored under the probe):

- `upstream/sendspin-protocol/build/reports/tests/test/index.html`
- `jvm/build/reports/tests/test/index.html`
- `android-probe/build/outputs/apk/debug/android-probe-debug.apk` (8,147,094 bytes)
- APK SHA-256: `30A51EDC53E988D40971D61FC9A7BE953462C9F037A03EE9D4DDC93CFFC85C1C`
- `upstream-build.log`, `probe-build.log`, `runner-build.log`, `final-build.log` preserve investigation/build evidence; earlier logs include the described fixture failures.

## Missing inputs and next blockers

Coordinator-supplied live HA evidence: Home Assistant **2026.8.3**, `config_entries/get` filtered to domain `music_assistant` returned `[]`. This establishes no configured MA integration entry in that HA instance; it does not imply an absent MA server. This probe did not repeat HA checks or discover live servers.

**New supplied installation evidence:** MA add-on at `http://<home-assistant>:8095`, streamserver port `8097`. The coordinator verified `GET /info`: `server_version=2.10.3`, `schema_version=65`, `min_supported_schema_version=28`, `onboard_done=false`, `homeassistant_addon=true`. Supplied `/api-docs` UI observations confirm `/ws`, authentication first, `message_id` correlation and events. These are attributed live observations from the coordinator; this follow-up did not request the private endpoint. Individual search/player command schemas and authenticated calls were not verified by this probe.

The user subsequently confirmed account creation. The coordinator verified `/setup` now reports "Setup has already been completed"; the main UI requires login in the agent's browser session. `/info` still reports `onboard_done=false`, so account creation must not be equated with completing all application onboarding. A plain HTTP request to the source-derived Sendspin endpoint on port 8927 returned HTTP 400, consistent with a WebSocket endpoint requiring Upgrade; this proves port reachability, not a successful Sendspin handshake. No username/password was invented, no credential was read or created, and no account/setup/API mutation was performed by the agents. Revised blockers:

1. User logs into the handed-off browser session; remaining application onboarding must be checked. A privately provisioned MA API credential and exact command schemas are still needed for authenticated library/search/player work; URL, server version and schema version are now known.
2. Confirm the effective `allow_legacy_clients` setting, then test a distinct persistent legacy identity. Port 8927 is reachable, but no WebSocket handshake was attempted. If legacy mode is disabled, the pinned JVM dependency lacks the required Noise/pairing implementation.
3. A device/LAN audio trial before claiming player registration, 60-second PCM playback, underruns, synchronization, reconnect without autoplay, artwork bytes, stop/volume effects, audio focus, foreground lifecycle, Assist/TTS or wake-word coexistence.

## Live metadata handshake — follow-up

After the user logged into the handed-off MA browser session, the provider UI showed Sendspin **Running**. The music-provider list showed Ambient Sounds, Music Assistant (built-in) and Spotify; this does not independently validate Spotify authentication or playback.

The coordinator then executed a bounded Python WebSocket smoke probe against `ws://<home-assistant>:8927/sendspin`, with a distinct test-only legacy identity named `Helios protocol probe (no audio)`. It advertised **only `metadata@v1`**, no player/controller role, and sent no playback or volume commands. The server returned `server/hello`, `version=1`, `active_roles=["metadata@v1"]`; the probe closed immediately. Local evidence: `.local/sendspin_handshake.py` and `.local/sendspin-handshake-result.json`.

This is an actual successful legacy handshake against the installed server, superseding the earlier endpoint/legacy-admission uncertainty for this test identity. It is not an execution of the JVM client on Android, nor an audio/clock-synchronization test. No security setting was changed and no MA API token was used. The administrator profile had no long-lived API tokens; explicit approval to create a private Helios token is pending before authenticated library/player tests.

## Authenticated API — follow-up

The user subsequently supplied an already-created Helios API token. It was stored only in ignored `.local/ma.json`, not in this report, Git or an APK. No second token was created. HTTP RPC `players/all` authenticated successfully and returned nine available entries, including groups and browser players; this is not nine physical speakers. No Lenovo player was identified in that inventory.

The live command documentation confirmed `music/search` accepts `search_query`, `media_types`, `limit` and `providers`; library-only search uses `providers=["library"]` rather than deprecated `library_only`. A bounded query with `limit=1`, type `track` and library-only scope returned the expected response schema with zero matches. It proves API access and request compatibility, not library completeness or Spotify search/playback. No queue or volume command was sent. Evidence is private in `.local/ma-player-preflight.json`.

The user pasted this administrator credential into the conversation, so it should be revoked/replaced before durable deployment. A replacement should be supplied directly through private provisioning, preferably under a dedicated account with only the required permissions. Do not repeat the token in documentation or recover it from browser session storage.

FLAC decoding/performance, hardware audio and all live MA behavior remain unverified. The library exposes an AudioPlayer/buffer boundary; no FLAC decoder is supplied by this probe. No installation, hardware writes, playback, HA/MA changes, app/docs/tools/README edits or commits were performed.
