package jenkins.plugin.randomjobbuilder;

import org.junit.Assert;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.text.MessageFormat;

/**
 * @author Sam Van Oort
 */
public class SingleJobLinearRampUpLGTest {

    @Test
    public void testLoadCalculation() throws Exception {
        SingleJobLinearRampUpLG.LoadCalculator calc = new SingleJobLinearRampUpLG.LoadCalculator(5, 1000L, 0, false);

        // No ramp-up, no jitter, just return enough runs to bring up to the expected val
        calc.finalConcurrentLoad = 37;
        calc.rampUpMillis = -1;
        Assert.assertEquals(37, calc.computeDesiredRuns(188));
        Assert.assertEquals(37, calc.computeRunsToLaunch(188, 0));
        Assert.assertEquals(36, calc.computeRunsToLaunch(188, 1));
        Assert.assertEquals(0, calc.computeRunsToLaunch(188, calc.finalConcurrentLoad+5));

        // Test with some randomization
        calc.useJitter = true;
        calc.finalConcurrentLoad = 14;
        for(int i=0; i<10; i++) {
            int expected = calc.computeRunsToLaunch(-1, 7);
            Assert.assertTrue("Suggesting negative runs, that's bogus!", expected >= 0);
            Assert.assertTrue(MessageFormat.format("Launched too many runs ({0}), should not suggest more than 2x goal ({1})", expected, 14), expected <= 14);
        }
        for(int i=0; i<10; i++) {
            Assert.assertEquals(0, calc.computeRunsToLaunch(-1, calc.finalConcurrentLoad));
            Assert.assertEquals(0, calc.computeRunsToLaunch(System.currentTimeMillis(), calc.finalConcurrentLoad+1));
        }

        // No jitter, linear ramp-up time
        calc.useJitter = false;
        calc.startTimeMillis = 0;
        calc.finalConcurrentLoad = 100;
        calc.rampUpMillis = 1000;
        Assert.assertEquals(0, calc.computeDesiredRuns(-50));
        Assert.assertEquals(50, calc.computeDesiredRuns(500));
        Assert.assertEquals(25, calc.computeDesiredRuns(250));
        Assert.assertEquals(100, calc.computeDesiredRuns(99999));
        Assert.assertEquals(100, calc.computeRunsToLaunch(99999,0));

        calc.useJitter = true;
        calc.finalConcurrentLoad = 100;
        calc.rampUpMillis = 1000;
        for (int i=0; i<10; i++) {
            int expected = calc.computeRunsToLaunch(500, 50);
            Assert.assertTrue("Suggesting negative runs, that's bogus!", expected >= 0);
            Assert.assertTrue(MessageFormat.format("Launched too many runs ({0}), should not suggest more than 2x goal ({1})", expected, 100), expected <= 50);
        }
    }
}
