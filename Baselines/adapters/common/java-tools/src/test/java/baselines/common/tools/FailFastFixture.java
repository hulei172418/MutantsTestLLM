package baselines.common.tools;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import static org.junit.Assert.fail;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class FailFastFixture {
    @Test
    public void test01KillsMutant() {
        fail("expected mutant-killing failure");
    }

    @Test
    public void test02MustNotRunInFailFastMode() {
        // Passing test used to prove that mutant execution stops after test01.
    }
}
