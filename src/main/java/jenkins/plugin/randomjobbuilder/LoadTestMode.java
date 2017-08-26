package jenkins.plugin.randomjobbuilder;

/**
 * Marks the different stats a {@link LoadGenerator} may be in when running tests
 */
public enum LoadTestMode {
    /** Doing nothing, upon starting it will go to {@link #RAMP_UP} or straight to {@link #LOAD_TEST} */
    IDLE,

    /** Ramping up to full load, after which it converts to {@link #LOAD_TEST} */
    RAMP_UP,

    /** Load generator is at full load and maintaining it, when stopped it will go to {@link #RAMP_DOWN} or straight to
     * {@link #IDLE} */
    LOAD_TEST, // active at full load

    /** Load generator is ramping down from full load, and will eventually convert to {@link #IDLE} */
    RAMP_DOWN
}
