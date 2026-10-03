package org.xrayrust.mobile.compat;

import org.xrayrust.mobile.XrayFfiInfo;
import org.xrayrust.mobile.XrayStartupProbeOptions;

/** Package-private seam for creating {@link NativeCore} instances and querying FFI info. */
interface NativeCoreFactory {

    /**
     * Creates a headless core (no VPN service, no TUN descriptor).
     *
     * @param configJson   xray JSON configuration
     * @param startupProbe optional probe executed by {@code start()}, may be {@code null}
     */
    NativeCore create(String configJson, XrayStartupProbeOptions startupProbe);

    XrayFfiInfo ffiInfo();
}
