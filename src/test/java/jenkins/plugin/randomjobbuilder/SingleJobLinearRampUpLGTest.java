package jenkins.plugin.randomjobbuilder;

import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.text.MessageFormat;

/**
 * @author Sam Van Oort
 */
public class SingleJobLinearRampUpLGTest {

    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    @Test
    public void testLoadCalculation() throws Exception {
        SingleJobLinearRampUpLG.LoadCalculator calc = new SingleJobLinearRampUpLG.LoadCalculator(10, 1000L, 0, false);

        // Once ramp-up is done, we need to
        Assert.assertEquals(10, calc.computeDesiredRuns(2000L));
        Assert.assertEquals(2, calc.computeDesiredRuns(200L));
        Assert.assertEquals(4, calc.computeDesiredRuns(400L));

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
        Assert.assertEquals(25, calc.computeRunsToLaunch(250, 0));
        Assert.assertEquals(15, calc.computeRunsToLaunch(250, 10));
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

    @Test
    public void testRunAutostartThenUnregisterAndStopGenerator() throws Exception {
        WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "TrivialJob");
        job.setDefinition(new CpsFlowDefinition("node('doesnotexist') {\n" +
                "echo 'I did something' \n" +
                "}"));
        SingleJobLinearRampUpLG trivial = new SingleJobLinearRampUpLG(job.getFullName());
        trivial.setConcurrentRunCount(8);
        trivial.setUseJitter(false);
        trivial.setRampUpMillis(4000L);

        Assert.assertEquals(8, trivial.getConcurrentRunCount());
        GeneratorController controller = GeneratorController.getInstance();
        controller.registerOrUpdateGenerator(trivial);

        // Check it queued up correctly
        Jenkins j = jenkinsRule.getInstance();
        trivial.start();
        controller.setAutostart(true);
        Thread.sleep(5000L);
        Assert.assertEquals(8, trivial.getRunsToLaunch(0));
        Thread.sleep(GeneratorController.RECURRENCE_PERIOD_MILLIS+500L);
        Assert.assertEquals(8, controller.getQueuedAndRunningCount(trivial));
        Assert.assertEquals(8, controller.getRunningCount(trivial));

        // Stop and verify really stopped
        controller.unregisterAndStopGenerator(trivial);
        Assert.assertEquals(0, controller.getQueuedAndRunningCount(trivial));
        Assert.assertNull(controller.getRegisteredGeneratorbyId(trivial.generatorId));
        Assert.assertEquals(8, job.getBuilds().size());
        controller.setAutostart(false);
    }
}
