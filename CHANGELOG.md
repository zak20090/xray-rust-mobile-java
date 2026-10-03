# Changelog

## Unreleased

## 0.1.0

First release of the plain-Java wrapper around the `xraymobile` Android library.

- Add `org.xrayrust.mobile.compat.CoreCallbackHandler` and `CoreController`, an API modelled on
  `libv2ray.CoreController` from 2dust/AndroidLibV2rayLite: `startLoop`, `stopLoop`, `isRunning`,
  `queryStats` (delta emulation of get-and-reset), `measureDelay`, `measureOutboundDelay`
  and `checkVersionX`.
- Package-private seam over `XrayCore` so the controller is unit-testable on the JVM.
- Depends on `io.github.aimalygin:xray-rust-mobile:0.7.0`.
