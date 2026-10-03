package org.xrayrust.mobile.compat;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.xrayrust.mobile.XrayCoreException;
import org.xrayrust.mobile.XrayFfiCapability;
import org.xrayrust.mobile.XrayFfiInfo;
import org.xrayrust.mobile.XrayOutboundAccounting;
import org.xrayrust.mobile.XrayOutboundHealthState;
import org.xrayrust.mobile.XrayOutboundHealthStatus;
import org.xrayrust.mobile.XrayStartupProbeOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Plain-Java, headless controller for the xray-rust core, modelled on {@code CoreController} from
 * {@code 2dust/AndroidLibV2rayLite} ({@code libv2ray_main.go}).
 *
 * <p>It is a thin facade over {@code org.xrayrust.mobile.XrayCore}. It does <em>not</em> create a
 * TUN device or a {@code VpnService}; callers that need a VPN should use
 * {@code org.xrayrust.mobile.XrayVpnService} directly.
 *
 * <p><b>Environment setup:</b> {@code AndroidLibV2rayLite} exposes {@code InitCoreEnv(envPath, key)}
 * to configure asset/certificate paths and an Android asset reader for the Go core. The Rust/JNI
 * core manages its own environment, so there is no equivalent here and none is needed.
 *
 * <p><b>Thread safety:</b> all instance methods are synchronized on the controller. Callbacks are
 * invoked while that lock is held (see {@link CoreCallbackHandler}).
 */
public class CoreController {

    private static final String TAG = "CoreController";

    /** Version of this wrapper library, reported by {@link #checkVersionX()}. */
    public static final String WRAPPER_VERSION = "0.1.0";

    /** Timeout used for delay measurements, matching the 12 s HTTP timeout of the Go version. */
    public static final long MEASURE_DELAY_TIMEOUT_MS = 12_000L;

    /** {@code direct} value of {@link #queryStats(String, String)} selecting upload traffic. */
    public static final String DIRECT_UPLINK = "uplink";

    /** {@code direct} value of {@link #queryStats(String, String)} selecting download traffic. */
    public static final String DIRECT_DOWNLINK = "downlink";

    private static final int ERROR_CODE_NO_DELAY = -1;

    private static final NativeCoreFactory DEFAULT_FACTORY = new XrayNativeCoreFactory();

    private final Object lock = new Object();
    private final CoreCallbackHandler callbackHandler;
    private final NativeCoreFactory factory;

    // Guarded by lock.
    private NativeCore core;
    private final Map<String, Long> lastSeenBytes = new HashMap<>();

    /**
     * Creates a controller.
     *
     * @param callbackHandler receiver of lifecycle callbacks, must not be {@code null}
     */
    public CoreController(CoreCallbackHandler callbackHandler) {
        this(callbackHandler, DEFAULT_FACTORY);
    }

    CoreController(CoreCallbackHandler callbackHandler, NativeCoreFactory factory) {
        this.callbackHandler = Objects.requireNonNull(callbackHandler, "callbackHandler");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /**
     * Starts the core with the given xray JSON configuration.
     *
     * <p>If the core is already running this method logs and returns without changes. On success
     * {@link CoreCallbackHandler#onStartup()} and
     * {@code onEmitStatus(0, "Started successfully, running")} are invoked. On failure the
     * partially created core is released, the controller stays stopped and no callback is invoked.
     *
     * @param configContent xray JSON configuration
     * @throws XrayCoreException if the native core rejects the configuration or fails to start
     * @throws NullPointerException if {@code configContent} is {@code null}
     */
    public void startLoop(String configContent) throws XrayCoreException {
        Objects.requireNonNull(configContent, "configContent");
        synchronized (lock) {
            if (core != null) {
                Log.i(TAG, "startLoop: core is already running");
                return;
            }
            NativeCore created = factory.create(configContent, null);
            try {
                created.start();
            } catch (RuntimeException | Error e) {
                closeQuietly(created);
                throw e;
            }
            core = created;
            lastSeenBytes.clear();
            callbackHandler.onStartup();
            callbackHandler.onEmitStatus(0, "Started successfully, running");
        }
    }

    /**
     * Stops and releases the core. Does nothing if the core is not running.
     *
     * <p>The core is always released and the controller always ends up stopped, even if the
     * native {@code stop()} throws; in that case the exception is rethrown after cleanup and no
     * callbacks are invoked. On success {@link CoreCallbackHandler#onShutdown()} and
     * {@code onEmitStatus(0, "Core stopped")} are invoked.
     *
     * @throws XrayCoreException if the native core fails to stop
     */
    public void stopLoop() throws XrayCoreException {
        synchronized (lock) {
            NativeCore running = core;
            if (running == null) {
                return;
            }
            core = null;
            lastSeenBytes.clear();
            try {
                running.stop();
            } finally {
                running.close();
            }
            callbackHandler.onShutdown();
            callbackHandler.onEmitStatus(0, "Core stopped");
        }
    }

    /**
     * Returns whether the core is currently running.
     *
     * @return {@code true} between a successful {@link #startLoop(String)} and {@link #stopLoop()}
     */
    public boolean isRunning() {
        synchronized (lock) {
            return core != null;
        }
    }

    /**
     * Returns the traffic of an outbound since the previous call with the same arguments.
     *
     * <p><b>Difference from AndroidLibV2rayLite:</b> v2fly's {@code stats.Manager} counter is
     * reset by the read ({@code Value()} followed by reset to zero). The Rust FFI only offers
     * cumulative counters via {@code outboundAccountingSnapshot()}. This method emulates the reset
     * by remembering the last cumulative value per {@code (tag, direct)} pair and returning the
     * difference. Consequences:
     * <ul>
     *   <li>The first call after {@link #startLoop(String)} returns the total since start.</li>
     *   <li>Baselines are independent per {@code (tag, direct)}; reading uplink does not affect
     *       downlink.</li>
     *   <li>Baselines are cleared on start and stop. If a cumulative value is ever lower than the
     *       baseline it is treated as a counter restart and returned as is.</li>
     *   <li>Other consumers of the same core cannot "steal" bytes, but all callers of this
     *       controller share one baseline per key.</li>
     * </ul>
     *
     * @param tag    outbound tag
     * @param direct {@link #DIRECT_UPLINK} or {@link #DIRECT_DOWNLINK}
     * @return bytes transferred since the last call; {@code 0} if the core is not running, the
     *         tag is unknown or {@code direct} is not recognised
     * @throws XrayCoreException if the native snapshot cannot be read
     */
    public long queryStats(String tag, String direct) {
        synchronized (lock) {
            if (core == null || tag == null || direct == null) {
                return 0L;
            }
            boolean uplink = DIRECT_UPLINK.equals(direct);
            if (!uplink && !DIRECT_DOWNLINK.equals(direct)) {
                return 0L;
            }
            long cumulative = 0L;
            for (XrayOutboundAccounting accounting : core.outboundAccountingSnapshot().getOutbounds()) {
                if (tag.equals(accounting.getOutboundTag())) {
                    cumulative += uplink ? accounting.getUplinkBytes() : accounting.getDownlinkBytes();
                }
            }
            String key = tag + ">>>" + direct;
            Long previous = lastSeenBytes.put(key, cumulative);
            long base = previous == null ? 0L : previous;
            return cumulative >= base ? cumulative - base : cumulative;
        }
    }

    /**
     * Returns the latency of the running core, in milliseconds.
     *
     * <p>The FFI has no API for an on-demand HTTP probe through a running core. This method
     * returns the lowest {@code delayMs} among healthy outbounds from
     * {@code outboundHealthSnapshot()}, which the core's own health checker produced. The
     * {@code url} argument is therefore only validated, not requested. Use
     * {@link #measureOutboundDelay(String, String)} for a probe of a specific URL (it applies
     * {@link #MEASURE_DELAY_TIMEOUT_MS}).
     *
     * @param url probe URL, must not be empty
     * @return latency in milliseconds
     * @throws XrayCoreException if the core is not running or no delay has been measured yet
     * @throws UnsupportedOperationException if the FFI lacks
     *         {@link XrayFfiCapability#OutboundHealth}
     */
    public long measureDelay(String url) throws XrayCoreException {
        requireUrl(url);
        synchronized (lock) {
            if (core == null) {
                throw new XrayCoreException(ERROR_CODE_NO_DELAY, "core is not running");
            }
            requireCapability(factory, XrayFfiCapability.OutboundHealth);
            return bestDelay(core);
        }
    }

    /**
     * Measures outbound latency with a disposable core, without touching any running controller.
     *
     * <p>Mirrors {@code MeasureOutboundDelay} of AndroidLibV2rayLite: all inbounds are removed
     * from the configuration, a headless core is started with a startup probe for {@code url}
     * (timeout {@link #MEASURE_DELAY_TIMEOUT_MS}), the measured delay is read, and the core is
     * always stopped and closed.
     *
     * @param configContent xray JSON configuration
     * @param url           probe URL, must not be empty
     * @return latency in milliseconds
     * @throws XrayCoreException if the configuration is invalid, the probe fails or no delay was
     *         reported
     * @throws UnsupportedOperationException if the FFI lacks {@link XrayFfiCapability#StartupProbe}
     *         or {@link XrayFfiCapability#OutboundHealth}
     */
    public static long measureOutboundDelay(String configContent, String url) throws XrayCoreException {
        return measureOutboundDelay(DEFAULT_FACTORY, configContent, url);
    }

    static long measureOutboundDelay(NativeCoreFactory factory, String configContent, String url)
            throws XrayCoreException {
        Objects.requireNonNull(configContent, "configContent");
        requireUrl(url);
        requireCapability(factory, XrayFfiCapability.StartupProbe);
        requireCapability(factory, XrayFfiCapability.OutboundHealth);

        XrayStartupProbeOptions probe = new XrayStartupProbeOptions(url, MEASURE_DELAY_TIMEOUT_MS, null);
        NativeCore disposable = factory.create(stripInbounds(configContent), probe);
        try {
            disposable.start();
            return bestDelay(disposable);
        } finally {
            try {
                disposable.stop();
            } catch (RuntimeException e) {
                Log.w(TAG, "measureOutboundDelay: stop failed", e);
            } finally {
                disposable.close();
            }
        }
    }

    /**
     * Returns a version string, like {@code CheckVersionX()} of AndroidLibV2rayLite.
     *
     * @return e.g. {@code "xray-rust-mobile-java v0.1.0, xray-ffi v0.5"}
     * @throws IllegalStateException if the native FFI version is incompatible with the SDK
     */
    public static String checkVersionX() {
        return formatVersion(DEFAULT_FACTORY.ffiInfo());
    }

    static String formatVersion(XrayFfiInfo info) {
        return "xray-rust-mobile-java v" + WRAPPER_VERSION
                + ", xray-ffi v" + info.getVersion().getMajor() + "." + info.getVersion().getMinor();
    }

    private static long bestDelay(NativeCore core) {
        Long best = null;
        for (XrayOutboundHealthStatus status : core.outboundHealthSnapshot().getOutbounds()) {
            Long delay = status.getDelayMs();
            if (delay != null && status.getState() == XrayOutboundHealthState.Healthy
                    && (best == null || delay < best)) {
                best = delay;
            }
        }
        if (best == null) {
            throw new XrayCoreException(ERROR_CODE_NO_DELAY, "no outbound delay has been measured");
        }
        return best;
    }

    /** Removes inbounds so a one-shot measurement never binds local ports. */
    static String stripInbounds(String configContent) {
        try {
            JSONObject config = new JSONObject(configContent);
            config.remove("inbound");
            config.remove("inbounds");
            config.remove("inboundDetour");
            return config.toString();
        } catch (JSONException e) {
            throw new XrayCoreException(ERROR_CODE_NO_DELAY, "invalid config JSON: " + e.getMessage());
        }
    }

    private static void requireUrl(String url) {
        Objects.requireNonNull(url, "url");
        if (url.isEmpty()) {
            throw new IllegalArgumentException("url must not be empty");
        }
    }

    private static void requireCapability(NativeCoreFactory factory, XrayFfiCapability capability) {
        if (!factory.ffiInfo().supports(capability)) {
            throw new UnsupportedOperationException(
                    "The native xray FFI does not support " + capability.name()
                            + "; update the xraymobile dependency");
        }
    }

    private static void closeQuietly(NativeCore nativeCore) {
        try {
            nativeCore.close();
        } catch (RuntimeException e) {
            Log.w(TAG, "close failed", e);
        }
    }
}
