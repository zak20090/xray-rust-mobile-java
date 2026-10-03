package org.xrayrust.mobile.compat;

/**
 * Lifecycle callbacks of a {@link CoreController}.
 *
 * <p>Mirrors {@code CoreCallbackHandler} from {@code 2dust/AndroidLibV2rayLite}
 * ({@code Startup()}, {@code Shutdown()}, {@code OnEmitStatus(int, string)}). As in the Go
 * version, the return value is an application-defined status code that the controller ignores;
 * return {@code 0} for success.
 *
 * <p>Callbacks are invoked synchronously on the thread that called
 * {@link CoreController#startLoop(String)} / {@link CoreController#stopLoop()} while the
 * controller's lock is held. Implementations must be quick and must not block on other threads
 * that call into the same controller. Calling back into the same controller from the same
 * thread is allowed.
 */
public interface CoreCallbackHandler {

    /**
     * Called after the core has started successfully.
     *
     * @return application-defined status code (ignored by the controller)
     */
    int onStartup();

    /**
     * Called after the core has been stopped by {@link CoreController#stopLoop()}.
     *
     * @return application-defined status code (ignored by the controller)
     */
    int onShutdown();

    /**
     * Called to report a human-readable status message.
     *
     * @param code    status code, {@code 0} for informational messages
     * @param message status text
     * @return application-defined status code (ignored by the controller)
     */
    int onEmitStatus(int code, String message);
}
