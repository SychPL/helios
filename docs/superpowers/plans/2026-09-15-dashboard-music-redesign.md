# Dashboard and music redesign implementation plan

**Status: suspended before code changes.** The user requested a written specification first. After review, [SPEC 0.8](../../SPEC-0.8-dashboard-appearance.md) is an index pointing to [0.8a: hotfix and renderer](../../SPEC-0.8a-renderer-music.md) and [0.8b: per-device backgrounds](../../SPEC-0.8b-backgrounds.md). This earlier outline is not executable as-is: prioritize a separate transport hotfix, then renderer work; backgrounds require their own validated options-flow path. No implementation, installation or publication is implied by this plan.

**Goal:** Refresh the native dashboard and repair missing music metadata/artwork and pause visibility, with a readable 800×480 device UI.

**Architecture:** Preserve one APK, the HA-driven 4×3 geometry and the right-half music overlay. Change rendering, not entity actions or physical device states. Repair transport metadata and playback transitions at their source. Test protocol behavior separately from Android layout.

**Tech stack:** Java, Android API 29+, existing Geist fonts and Java-WebSocket, JUnit.

**Spec:** Existing SPEC 0.6 plus user-approved redesign: larger clock/date, opaque music drawer, rounded/icon transport controls, subtle artwork-derived accents. User may perform final interactive acceptance after installation.

## Constraints

- Keep all four conditional 1×1 notification slots on the bottom row.
- Do not resize/reflow the underlying grid when music opens.
- No secrets in logs, commits, fixtures or reports.
- Preserve existing changes from Claude Code. No commits or branches without user instruction.
- Pause preserves the local session and drawer state; stop/idle/disconnect ends it. No automatic playback at startup.
- Main controls remain visible and have sufficient touch area on 800×480.
- Keep existing hardware mappings: terrace endpoint 2 only, no mower supply, no blaszak2 classification.

## Task 1 — Transport correctness

- [ ] Trace actual metadata partial updates and stream/end + group state ordering.
- [ ] Add failing regression tests to SendspinClientTest before fixes.
- [ ] Fix merge/reset and playback-session handling in SendspinClient without changing public listener interfaces unnecessarily.
- [ ] Run focused JVM tests; report any remaining service-level artwork issues.

Files: SendspinClient.java, SendspinClientTest.java; narrowly scoped MusicSession tests only if required.

## Task 2 — Native UI

- [ ] Audit DashboardView and MusicOverlay measurements and Android default control minimums.
- [ ] Add pure geometry tests for 800×480 bounds/touch regions and clock fitting.
- [ ] Redesign dashboard with a richer charcoal surface, restrained warm accent, larger clock and date; no default Android button rectangles.
- [ ] Redesign opaque right-half music drawer: artwork/header, text, transport, volume, explicit close control; all controls fit.
- [ ] Remove clock's redundant Dom label from the live HA layout only when the new app is ready.

Files: DashboardView.java, MusicOverlay.java, geometry/style helper and test if needed, ha/helios-attention.yaml.

## Task 3 — Integration and verification

- [ ] Check HeliosService artwork lifetime, URL validation and late-result rejection. Regression-test extracted pure logic if a fix is needed.
- [ ] Run JVM tests, assemble and lint; inspect emulator screenshots with long titles and paused state.
- [ ] Independently review the changed transport/UI contract before installation.
- [ ] Back up installed APK/configuration as available, increment version consistently, install without resetting provisioning.
- [ ] Verify actual device screenshot for clock/date and playing/paused drawer; do not adjust unrelated devices or automations.
- [ ] Record completed evidence and explicit untested items for the user's click-through.

## Execution decisions

- Transport regression work can run independently of the UI redesign. Main work proceeds on layout while a worker owns only SendspinClient and its tests.
- The user will perform final manual interaction acceptance; automated tests and non-destructive device checks still precede delivery.
