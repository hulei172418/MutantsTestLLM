package baselines.common.tools;

import org.junit.Test;

/** Fixture proving that the active method is stopped at its own deadline. */
public class SingleTestTimeoutFixture {
    @Test
    public void testTooSlow() throws Exception {
        Thread.sleep(5000L);
    }
}
