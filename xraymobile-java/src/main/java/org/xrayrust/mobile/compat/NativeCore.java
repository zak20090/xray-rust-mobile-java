package org.xrayrust.mobile.compat;

import org.xrayrust.mobile.XrayOutboundAccountingSnapshot;
import org.xrayrust.mobile.XrayOutboundHealthSnapshot;

/**
 * Package-private seam over {@code org.xrayrust.mobile.XrayCore}.
 *
 * <p>{@code XrayCore} loads native libraries in its static initializer, so it cannot be used in
 * JVM unit tests. {@link CoreController} only talks to this interface; tests substitute a fake.
 */
interface NativeCore {

    void start();

    void stop();

    void close();

    XrayOutboundAccountingSnapshot outboundAccountingSnapshot();

    XrayOutboundHealthSnapshot outboundHealthSnapshot();
}
