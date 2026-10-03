package org.xrayrust.mobile.compat;

import org.xrayrust.mobile.XrayCore;
import org.xrayrust.mobile.XrayDnsBootstrapMode;
import org.xrayrust.mobile.XrayFfiInfo;
import org.xrayrust.mobile.XrayOutboundAccountingSnapshot;
import org.xrayrust.mobile.XrayOutboundHealthSnapshot;
import org.xrayrust.mobile.XrayStartupProbeOptions;
import org.xrayrust.mobile.XrayTunRuntimeProfile;

/** Production {@link NativeCoreFactory} backed by the real {@link XrayCore}. */
final class XrayNativeCoreFactory implements NativeCoreFactory {

    @Override
    public NativeCore create(String configJson, XrayStartupProbeOptions startupProbe) {
        // Kotlin default arguments are not visible from Java, so every parameter is passed.
        XrayCore core = XrayCore.Companion.create(
                configJson,
                null, // vpnService: headless, no socket protection
                null, // tunFileDescriptor: headless, no TUN
                false, // collectTcpTimings
                XrayTunRuntimeProfile.Default,
                startupProbe,
                XrayDnsBootstrapMode.System,
                null); // fileLoggingDirectory
        return new Adapter(core);
    }

    @Override
    public XrayFfiInfo ffiInfo() {
        return XrayCore.ffiInfo();
    }

    private static final class Adapter implements NativeCore {
        private final XrayCore core;

        Adapter(XrayCore core) {
            this.core = core;
        }

        @Override
        public void start() {
            core.start();
        }

        @Override
        public void stop() {
            core.stop();
        }

        @Override
        public void close() {
            core.close();
        }

        @Override
        public XrayOutboundAccountingSnapshot outboundAccountingSnapshot() {
            return core.outboundAccountingSnapshot();
        }

        @Override
        public XrayOutboundHealthSnapshot outboundHealthSnapshot() {
            return core.outboundHealthSnapshot();
        }
    }
}
