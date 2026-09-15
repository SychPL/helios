# Lamp probe results — 2026-09-15

Environment: clock docked in charging base (night light dock), OTA 627,
probe app domain `u:r:untrusted_app:s0` via agent `/agent/dex` (:8555).

## Live device facts

- `getprop ro.product.model` = LenovoCD-24502F, device = Helios (Smart Clock 2)
- `pm list packages`: `com.google.assistant.oemapp` installed
- `service list`: `#39 charge_base` registered (system service live)
- `/sys/class/leds`, `/sys/bus/usb`: Permission denied (untrusted_app)
- `/dev/ttyACM*`, `/dev/ttyUSB*`: not visible

## Route 1: OEM binder (WORKS — recommended)

```
$ python dash/tools/run_clock_plugin.py tools/lamp-probe/LampProbe.dex pl.mateusz.plugin.LampProbe --arg state
bindService=true
connected
descriptor=com.google.assistant.IAssistantOemAccessoryService
isLedOn=false
isLedOn(after)=false

$ --arg on
bindService=true
connected
descriptor=com.google.assistant.IAssistantOemAccessoryService
isLedOn=false
turnOnLed ok
isLedOn(after)=true          <- lamp visibly ON

$ --arg bri:3
isLedOn=true
setLedBrightness(3) ok
isLedOn(after)=true          <- dimmer level

$ --arg off
isLedOn=true
turnOffLed ok
isLedOn(after)=false         <- lamp OFF (left in this state)
```

## Route 2: direct framework access (BLOCKED — documented)

```
$ python dash/tools/run_clock_plugin.py tools/lamp-probe/DirectCbProbe.dex pl.mateusz.plugin.DirectCbProbe --arg state
getSystemService(charge_base)=null
ServiceManager.getService(charge_base)=null
```

Conclusion: SELinux does not hand the `charge_base` service binder to
untrusted apps; ChargeBaseManager cannot be constructed directly. The
exported OEM service binder is the only unprivileged path.

## Quirk

TRANSACTION_INTERFACE_TRANSACTION (0x5f4e5446) reply has no exception
header — `reply.readException()` throws "Unknown exception code: 50"
(50 = descriptor string length). Read with `readString()` only.

## Charge detection (phone on wireless pad) — same session, ~13:27

Listener registered via binder tx 7; user placed phone on pad during window:

```
listener registered, listening for 150s...
EVENT onChargeStop  @+4684ms
EVENT onChargeStart @+6735ms
EVENT onChargeStop  @+15394ms
EVENT onChargeStart @+16943ms
isLedOn=false   (lamp stayed off throughout)
```

Conclusion: charge transitions from the wireless pad are broadcast to any
bound client in real time (pad handshake wobble on placement, then steady
charge = no further events for 133 s). Probe: tools/lamp-probe/ChargeProbe.
