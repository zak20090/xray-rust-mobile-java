package org.xrayrust.mobile.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;
import org.xrayrust.mobile.XrayCoreException;
import org.xrayrust.mobile.XrayFfiCapability;
import org.xrayrust.mobile.XrayFfiInfo;
import org.xrayrust.mobile.XrayFfiVersion;
import org.xrayrust.mobile.XrayOutboundAccounting;
import org.xrayrust.mobile.XrayOutboundAccountingSnapshot;
import org.xrayrust.mobile.XrayOutboundHealthSnapshot;
import org.xrayrust.mobile.XrayOutboundHealthState;
import org.xrayrust.mobile.XrayOutboundHealthStatus;
import org.xrayrust.mobile.XrayStartupProbeOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class CoreControllerTest {

    private static final long ALL_CAPS = (1L << 19) - 1;

    private static final class RecordingHandler implements CoreCallbackHandler {
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public int onStartup() {
            events.add("startup");
            return 0;
        }

        @Override
        public int onShutdown() {
            events.add("shutdown");
            return 0;
        }

        @Override
        public int onEmitStatus(int code, String message) {
            events.add("status:" + code + ":" + message);
            return 0;
        }
    }

    private static final class FakeCore implements NativeCore {
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        RuntimeException startError;
        RuntimeException stopError;
        XrayOutboundAccountingSnapshot accounting = accounting();
        XrayOutboundHealthSnapshot health = health();

        @Override
        public void start() {
            calls.add("start");
            if (startError != null) {
                throw startError;
            }
        }

        @Override
        public void stop() {
            calls.add("stop");
            if (stopError != null) {
                throw stopError;
            }
        }

        @Override
        public void close() {
            calls.add("close");
        }

        @Override
        public XrayOutboundAccountingSnapshot outboundAccountingSnapshot() {
            return accounting;
        }

        @Override
        public XrayOutboundHealthSnapshot outboundHealthSnapshot() {
            return health;
        }
    }

    private static final class FakeFactory implements NativeCoreFactory {
        final AtomicInteger created = new AtomicInteger();
        final FakeCore core = new FakeCore();
        long mask = ALL_CAPS;
        String lastConfig;
        XrayStartupProbeOptions lastProbe;

        @Override
        public NativeCore create(String configJson, XrayStartupProbeOptions startupProbe) {
            created.incrementAndGet();
            lastConfig = configJson;
            lastProbe = startupProbe;
            return core;
        }

        @Override
        public XrayFfiInfo ffiInfo() {
            return new XrayFfiInfo(new XrayFfiVersion(0, 5), mask);
        }
    }

    private static XrayOutboundAccountingSnapshot accounting(Object... tagUpDown) {
        List<XrayOutboundAccounting> list = new ArrayList<>();
        for (int i = 0; i < tagUpDown.length; i += 3) {
            list.add(new XrayOutboundAccounting(
                    (String) tagUpDown[i], 0, 0, 0, (Long) tagUpDown[i + 1], (Long) tagUpDown[i + 2]));
        }
        return new XrayOutboundAccountingSnapshot(1, 1, list);
    }

    private static XrayOutboundHealthSnapshot health(Long... delays) {
        List<XrayOutboundHealthStatus> list = new ArrayList<>();
        for (int i = 0; i < delays.length; i++) {
            list.add(new XrayOutboundHealthStatus("o" + i,
                    delays[i] == null ? XrayOutboundHealthState.Unknown : XrayOutboundHealthState.Healthy,
                    delays[i], null, null, 0, null, null));
        }
        return new XrayOutboundHealthSnapshot(1, 1, list);
    }

    private static CoreController controller(RecordingHandler handler, FakeFactory factory) {
        return new CoreController(handler, factory);
    }

    @Test
    public void startLoopStartsCoreAndInvokesCallbacks() {
        RecordingHandler handler = new RecordingHandler();
        FakeFactory factory = new FakeFactory();
        CoreController c = controller(handler, factory);

        c.startLoop("{}");

        assertTrue(c.isRunning());
        assertEquals(Arrays.asList("start"), factory.core.calls);
        assertEquals(Arrays.asList("startup", "status:0:Started successfully, running"), handler.events);
        assertEquals(null, factory.lastProbe);
    }

    @Test
    public void startLoopIsIdempotent() {
        RecordingHandler handler = new RecordingHandler();
        FakeFactory factory = new FakeFactory();
        CoreController c = controller(handler, factory);

        c.startLoop("{}");
        c.startLoop("{}");

        assertEquals(1, factory.created.get());
        assertEquals(2, handler.events.size());
    }

    @Test
    public void stopLoopStopsClosesAndInvokesCallbacks() {
        RecordingHandler handler = new RecordingHandler();
        FakeFactory factory = new FakeFactory();
        CoreController c = controller(handler, factory);
        c.startLoop("{}");
        handler.events.clear();

        c.stopLoop();
        c.stopLoop();

        assertFalse(c.isRunning());
        assertEquals(Arrays.asList("start", "stop", "close"), factory.core.calls);
        assertEquals(Arrays.asList("shutdown", "status:0:Core stopped"), handler.events);
    }

    @Test
    public void stopWithoutStartIsNoOp() {
        RecordingHandler handler = new RecordingHandler();
        FakeFactory factory = new FakeFactory();
        controller(handler, factory).stopLoop();
        assertTrue(handler.events.isEmpty());
        assertTrue(factory.core.calls.isEmpty());
    }

    @Test
    public void startFailurePropagatesClosesCoreAndStaysStopped() {
        RecordingHandler handler = new RecordingHandler();
        FakeFactory factory = new FakeFactory();
        XrayCoreException error = new XrayCoreException(7, "bad config");
        factory.core.startError = error;
        CoreController c = controller(handler, factory);

        try {
            c.startLoop("{}");
            fail("expected exception");
        } catch (XrayCoreException e) {
            assertSame(error, e);
            assertEquals(7, e.getCode());
        }

        assertFalse(c.isRunning());
        assertEquals(Arrays.asList("start", "close"), factory.core.calls);
        assertTrue(handler.events.isEmpty());
    }

    @Test
    public void stopFailureStillReleasesCore() {
        RecordingHandler handler = new RecordingHandler();
        FakeFactory factory = new FakeFactory();
        CoreController c = controller(handler, factory);
        c.startLoop("{}");
        factory.core.stopError = new XrayCoreException(3, "stop failed");

        try {
            c.stopLoop();
            fail("expected exception");
        } catch (XrayCoreException e) {
            assertEquals(3, e.getCode());
        }

        assertFalse(c.isRunning());
        assertEquals("close", factory.core.calls.get(factory.core.calls.size() - 1));
    }

    @Test
    public void concurrentStartCreatesSingleCore() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        FakeFactory factory = new FakeFactory();
        final CoreController c = controller(handler, factory);
        final CountDownLatch go = new CountDownLatch(1);
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    c.startLoop("{}");
                }
            });
            threads[i].start();
        }
        go.countDown();
        for (Thread t : threads) {
            t.join();
        }

        assertEquals(1, factory.created.get());
        assertEquals(2, handler.events.size());
    }

    @Test
    public void queryStatsReturnsDeltasPerKey() {
        FakeFactory factory = new FakeFactory();
        CoreController c = controller(new RecordingHandler(), factory);
        c.startLoop("{}");

        factory.core.accounting = accounting("proxy", 100L, 1000L, "direct", 5L, 6L);
        assertEquals(100L, c.queryStats("proxy", "uplink"));
        assertEquals(0L, c.queryStats("proxy", "uplink"));

        factory.core.accounting = accounting("proxy", 150L, 1500L, "direct", 5L, 6L);
        assertEquals(50L, c.queryStats("proxy", "uplink"));
        // downlink baseline is independent
        assertEquals(1500L, c.queryStats("proxy", "downlink"));
        assertEquals(6L, c.queryStats("direct", "downlink"));
    }

    @Test
    public void queryStatsHandlesCounterRestartUnknownInputsAndStopped() {
        FakeFactory factory = new FakeFactory();
        CoreController c = controller(new RecordingHandler(), factory);
        assertEquals(0L, c.queryStats("proxy", "uplink"));
        c.startLoop("{}");

        factory.core.accounting = accounting("proxy", 500L, 0L);
        assertEquals(500L, c.queryStats("proxy", "uplink"));
        factory.core.accounting = accounting("proxy", 20L, 0L);
        assertEquals(20L, c.queryStats("proxy", "uplink"));
        assertEquals(0L, c.queryStats("missing", "uplink"));
        assertEquals(0L, c.queryStats("proxy", "sideways"));
        assertEquals(0L, c.queryStats(null, "uplink"));
    }

    @Test
    public void queryStatsBaselineResetsOnRestart() {
        FakeFactory factory = new FakeFactory();
        CoreController c = controller(new RecordingHandler(), factory);
        c.startLoop("{}");
        factory.core.accounting = accounting("proxy", 100L, 0L);
        assertEquals(100L, c.queryStats("proxy", "uplink"));
        c.stopLoop();
        c.startLoop("{}");
        assertEquals(100L, c.queryStats("proxy", "uplink"));
    }

    @Test
    public void measureDelayReturnsLowestHealthyDelay() {
        FakeFactory factory = new FakeFactory();
        CoreController c = controller(new RecordingHandler(), factory);
        c.startLoop("{}");
        factory.core.health = health(120L, null, 80L);
        assertEquals(80L, c.measureDelay("https://example.com"));
    }

    @Test
    public void measureDelayErrors() {
        FakeFactory factory = new FakeFactory();
        CoreController c = controller(new RecordingHandler(), factory);
        try {
            c.measureDelay("https://example.com");
            fail();
        } catch (XrayCoreException expected) {
            // not running
        }
        c.startLoop("{}");
        factory.core.health = health((Long) null);
        try {
            c.measureDelay("https://example.com");
            fail();
        } catch (XrayCoreException expected) {
            // nothing measured
        }
        factory.mask = 0;
        try {
            c.measureDelay("https://example.com");
            fail();
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("OutboundHealth"));
        }
    }

    @Test
    public void measureOutboundDelayUsesDisposableCoreWithoutInbounds() throws Exception {
        FakeFactory factory = new FakeFactory();
        factory.core.health = health(42L);

        long delay = CoreController.measureOutboundDelay(factory,
                "{\"inbounds\":[{\"port\":1}],\"outbounds\":[{\"tag\":\"p\"}]}",
                "https://example.com/generate_204");

        assertEquals(42L, delay);
        JSONObject sent = new JSONObject(factory.lastConfig);
        assertFalse(sent.has("inbounds"));
        assertTrue(sent.has("outbounds"));
        assertEquals("https://example.com/generate_204", factory.lastProbe.getUrl());
        assertEquals(CoreController.MEASURE_DELAY_TIMEOUT_MS, factory.lastProbe.getTimeoutMs());
        assertEquals(Arrays.asList("start", "stop", "close"), factory.core.calls);
    }

    @Test
    public void measureOutboundDelayClosesCoreOnFailure() {
        FakeFactory factory = new FakeFactory();
        factory.core.startError = new XrayCoreException(9, "probe failed");
        try {
            CoreController.measureOutboundDelay(factory, "{}", "https://example.com");
            fail();
        } catch (XrayCoreException e) {
            assertEquals(9, e.getCode());
        }
        assertEquals(Arrays.asList("start", "stop", "close"), factory.core.calls);
    }

    @Test
    public void measureOutboundDelayRequiresCapabilities() {
        FakeFactory factory = new FakeFactory();
        factory.mask = XrayFfiCapability.OutboundHealth.getMask();
        try {
            CoreController.measureOutboundDelay(factory, "{}", "https://example.com");
            fail();
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("StartupProbe"));
        }
        assertEquals(0, factory.created.get());
    }

    @Test
    public void measureOutboundDelayRejectsInvalidJson() {
        try {
            CoreController.measureOutboundDelay(new FakeFactory(), "not json", "https://example.com");
            fail();
        } catch (XrayCoreException expected) {
            // invalid config
        }
    }

    @Test
    public void formatVersionIncludesWrapperAndFfiVersion() {
        XrayFfiInfo info = new XrayFfiInfo(new XrayFfiVersion(1, 7), 0);
        assertEquals("xray-rust-mobile-java v" + CoreController.WRAPPER_VERSION + ", xray-ffi v1.7",
                CoreController.formatVersion(info));
    }
}
