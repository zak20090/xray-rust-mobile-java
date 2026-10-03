# xray-rust-mobile-java

Plain-Java, headless controller SDK for Android on top of
[`xray-rust-mobile`](https://github.com/zak20090/xray-rust-mobile) (the Kotlin/JNI `xraymobile`
library), with an API modelled on `libv2ray_main.go` of
[2dust/AndroidLibV2rayLite](https://github.com/2dust/AndroidLibV2rayLite). It lets Java-only apps
(v2rayNG style) use the Rust core without touching Kotlin. No native code lives in this repository.

## API parity

| AndroidLibV2rayLite (`libv2ray`)    | This library (`org.xrayrust.mobile.compat`) |
|-------------------------------------|---------------------------------------------|
| `CoreController.StartLoop`          | `CoreController.startLoop`                  |
| `CoreController.StopLoop`           | `CoreController.stopLoop` (+ `isRunning`)   |
| `CoreController.QueryStats`         | `CoreController.queryStats` (delta emulation, see Javadoc) |
| `CoreController.MeasureDelay`       | `CoreController.measureDelay` (reads the core's health snapshot) |
| `MeasureOutboundDelay`              | `CoreController.measureOutboundDelay`       |
| `CheckVersionX`                     | `CoreController.checkVersionX`              |
| `CoreCallbackHandler`               | `CoreCallbackHandler` (`onStartup`, `onShutdown`, `onEmitStatus`) |
| `InitCoreEnv`                       | not needed: the Rust/JNI core manages its own environment |

Behavioural differences, all documented in Javadoc: the FFI exposes cumulative byte counters, so
`queryStats` computes deltas instead of resetting a counter; and the FFI has no on-demand probe for
a running core, so `measureDelay` reports the core's own health-check delay.

## Dependency

The wrapper depends on the upstream library published to Maven Central by
`aimalygin/xray-rust-mobile` (see `release/version.env` there):

```
io.github.aimalygin:xray-rust-mobile:0.7.0
```

The coordinates are set by `XRAY_RUST_MOBILE_GROUP`, `XRAY_RUST_MOBILE_ARTIFACT` and
`XRAY_RUST_MOBILE_VERSION` in `gradle.properties` and can be overridden with `-P`. To test against
a local build of upstream, run its `publishToMavenLocal`, add `mavenLocal()` to the repositories
and pass `-PXRAY_RUST_MOBILE_VERSION=<local version>`.

Add this module to your project (`include(":xraymobile-java")`) or publish it to your own Maven
repository. `minSdk` is 24, Java 11+.

## Usage

```java
CoreController controller = new CoreController(new CoreCallbackHandler() {
    @Override public int onStartup() { return 0; }
    @Override public int onShutdown() { return 0; }
    @Override public int onEmitStatus(int code, String message) {
        Log.i("xray", code + ": " + message);
        return 0;
    }
});

controller.startLoop(configJson);              // throws XrayCoreException on failure
long up = controller.queryStats("proxy", "uplink");  // bytes since the previous call
controller.stopLoop();

long delay = CoreController.measureOutboundDelay(configJson, "https://www.gstatic.com/generate_204");
String version = CoreController.checkVersionX();
```

## Scope

The controller is headless: it creates no TUN device and no `VpnService`, just like
`AndroidLibV2rayLite`'s `CoreController`, around which apps build their own `VpnService`. For full
VPN integration use `org.xrayrust.mobile.XrayVpnService` from the upstream library directly.

## Development

`./gradlew build test` (needs the Android SDK). Unit tests run on the JVM against a fake of the
package-private `NativeCore` seam, because `XrayCore` loads native libraries in its static
initializer.

## License

MPL-2.0, see [LICENSE](LICENSE).
