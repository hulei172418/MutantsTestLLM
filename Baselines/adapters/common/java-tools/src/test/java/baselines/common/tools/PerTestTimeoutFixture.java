package baselines.common.tools;

import org.junit.Test;

/** Fixture proving that a suite may outlive one method's timeout budget. */
public class PerTestTimeoutFixture {
    @Test
    public void testOne() throws Exception {
        Thread.sleep(1200L);
    }

    @Test
    public void testTwo() throws Exception {
        Thread.sleep(1200L);
    }
}
