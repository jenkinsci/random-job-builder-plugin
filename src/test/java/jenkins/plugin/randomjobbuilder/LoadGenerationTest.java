package jenkins.plugin.randomjobbuilder;

import hudson.model.Job;
import hudson.model.labels.LabelAtom;
import hudson.model.queue.QueueTaskFuture;
import hudson.tasks.LogRotator;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static junit.framework.Assert.*;

/**
 * @author Sam Van Oort
 */
public class LoadGenerationTest {
    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    @Test
    public void testTrivialGeneratorSetup() throws Exception {
        WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "TrivialJob");
        job.setDefinition(new CpsFlowDefinition("echo 'I did something' "));

        LoadGeneration.TrivialLoadGenerator trivialMatch = new LoadGeneration.TrivialLoadGenerator(".*", 1);
        assertFalse("Generator should start inactive", trivialMatch.isActive());
        assertEquals("Inactive generator shouldn't try to launch jobs", 0, trivialMatch.getRunsToLaunch(0));
        assertEquals(1, trivialMatch.getConcurrentRunCount());
        assertEquals("Generator should start idle and didn't", LoadGeneration.CurrentTestMode.IDLE, trivialMatch.getCurrentTestMode());
        assertEquals(".*", trivialMatch.getJobNameRegex());
        assertNotNull(trivialMatch.getGeneratorId());
    }

    @Test
    public void testTrivialGeneratorFilterJobs() throws Exception {
        WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "TrivialJob");
        job.setDefinition(new CpsFlowDefinition("echo 'I did something' "));

        LoadGeneration.TrivialLoadGenerator trivialMatch = new LoadGeneration.TrivialLoadGenerator(".*", 1);
        List<Job> candidates = trivialMatch.getCandidateJobs();
        assertTrue("Filter should return job", candidates.contains(job));
        assertEquals(1, candidates.size());
        assertEquals(job,LoadGeneration.pickRandomJob(candidates));

        trivialMatch.setJobNameRegex("");
        assertEquals(1, trivialMatch.getCandidateJobs().size());
        trivialMatch.setJobNameRegex(null);
        assertEquals(1, trivialMatch.getCandidateJobs().size());

        LoadGeneration.TrivialLoadGenerator trivialNoMatch = new LoadGeneration.TrivialLoadGenerator("cheese", 1);
        candidates = trivialNoMatch.getCandidateJobs();
        assertEquals("Empty filter should return no matches", 0, candidates.size());
        assertNull(LoadGeneration.pickRandomJob(candidates));
    }

    @Test
    public void testTrivialLoadGeneratorStart() throws Exception {
        WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "TrivialJob");
        job.setDefinition(new CpsFlowDefinition("echo 'I did something' "));

        LoadGeneration.TrivialLoadGenerator trivial = new LoadGeneration.TrivialLoadGenerator(".*", 1);
        LoadGeneration.CurrentTestMode testMode = trivial.start();
        assertEquals(LoadGeneration.CurrentTestMode.LOAD_TEST, testMode);
        assertEquals(LoadGeneration.CurrentTestMode.LOAD_TEST, trivial.getCurrentTestMode());
        assert trivial.isActive();

        testMode = trivial.stop();
        assertEquals(LoadGeneration.CurrentTestMode.IDLE, testMode);
        assertEquals(LoadGeneration.CurrentTestMode.IDLE, trivial.getCurrentTestMode());
        assert !trivial.isActive();
    }

    @Test
    public void testBasicGeneration() throws Exception {
        WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "TrivialJob");
        job.setBuildDiscarder(new LogRotator(-1, 20, -1, 40));
        job.setDefinition(new CpsFlowDefinition("echo 'I did something' \n" +
                "sleep 1"));
        LoadGeneration.TrivialLoadGenerator trivial = new LoadGeneration.TrivialLoadGenerator(".*", 1);
        LoadGeneration.DescriptorImpl desc = LoadGeneration.getDescriptorInstance();
        LoadGeneration.getGeneratorController().registerOrUpdateGenerator(trivial);
        LoadGeneration.getGeneratorController().setAutostart(true);

        Jenkins j = jenkinsRule.getInstance();
        trivial.start();

        Thread.sleep(4000L);
        System.out.println("Currently running jobs: "+j);
        assertTrue("No jobs completed", job.getBuilds().size() > 0);

        trivial.stop();
        Thread.sleep(6000L);

        Assert.assertFalse(job.getLastBuild().isBuilding());
    }

    @Test
    public void testControllerBasics() throws Exception {
        WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "TrivialJob");
        job.setDefinition(new CpsFlowDefinition("node('doesnotexist') {\n" +
                "echo 'I did something' \n" +
                "}"));

        // Generator is unregistered as part of LoadGeneration.DescriptorImpl.generators
        // So it doesn't automatically start creating load
        LoadGeneration.TrivialLoadGenerator trivial = new LoadGeneration.TrivialLoadGenerator(".*", 8);
        trivial.start();
        LoadGeneration.GeneratorController controller = LoadGeneration.getGeneratorController();
        controller.registerOrUpdateGenerator(trivial);
        Assert.assertEquals(trivial, controller.getRegisteredGeneratorbyId(trivial.generatorId));

        // Incrementing & Decrementing Queue Item counts & seeing impact on controller & desired run count
        Assert.assertEquals(0, controller.getQueuedCount(trivial));
        Assert.assertEquals(0, controller.getQueuedAndRunningCount(trivial));
        Assert.assertEquals(8, trivial.getRunsToLaunch(0));
        controller.addQueueItem(trivial);
        Assert.assertEquals(1, controller.getQueuedCount(trivial));
        Assert.assertEquals(1, controller.getQueuedAndRunningCount(trivial));
        Assert.assertEquals(7, trivial.getRunsToLaunch(1));
        controller.removeQueueItem(trivial);
        Assert.assertEquals(0, controller.getQueuedCount(trivial));
        trivial.stop();

        // Simulate having queued an item, then run a job, then see if the controller picks it up as if the job started running
        controller.addQueueItem(trivial);
        QueueTaskFuture qtf = LoadGeneration.launchJob(trivial, job, 0);

        long started = System.currentTimeMillis();
        long maxWait = 1000L;
        while (job.getLastBuild() == null) {
            if ((System.currentTimeMillis()-started) >= maxWait) {
                Assert.fail("Job didn't start in a timely fashion!");
            }
            Thread.sleep(100);
        }

        WorkflowRun run = (WorkflowRun)(job.getLastBuild());
        Assert.assertEquals(0, controller.getQueuedCount(trivial)); // Once we start running, item no longer just queued
        Assert.assertEquals(1, controller.getRunningCount(trivial)); // ...and is now running
        Assert.assertEquals(1, controller.getQueuedAndRunningCount(trivial));
        Assert.assertEquals(0, trivial.getRunsToLaunch(1));

        // Kill the job and verify counts were decremented appropriately
        controller.stopAbruptly(trivial);
        Thread.sleep(100);
        Assert.assertEquals(0, jenkinsRule.jenkins.getQueue().getItems().length);
        Assert.assertEquals(0, controller.getRunningCount(trivial));
        Assert.assertEquals(0, controller.getQueuedAndRunningCount(trivial));
    }

    @Test
    public void testControllerQueueing() throws Exception {
        WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "TrivialJob");
        job.setDefinition(new CpsFlowDefinition("node('doesnotexist') {\n" +
                "echo 'I did something' \n" +
                "}"));
        LoadGeneration.TrivialLoadGenerator trivial = new LoadGeneration.TrivialLoadGenerator(".*", 8);
        Assert.assertEquals(8, trivial.getConcurrentRunCount());
        LoadGeneration.DescriptorImpl desc = LoadGeneration.getDescriptorInstance();
        LoadGeneration.GeneratorController controller = LoadGeneration.getGeneratorController();
        controller.registerOrUpdateGenerator(trivial);

        // Check it queued up correctly
        Jenkins j = jenkinsRule.getInstance();
        trivial.start();
        Assert.assertEquals(8, trivial.getRunsToLaunch(0));
        controller.maintainLoad(); // triggers one cycle of load generation
        trivial.stop();
        Assert.assertEquals(8, controller.getQueuedAndRunningCount(trivial));
        Thread.sleep(4000);

        // Disable generator and verify it doesn't run any more
        jenkinsRule.createOnlineSlave(new LabelAtom("doesnotexist"));
        jenkinsRule.waitUntilNoActivityUpTo(2000);

        Assert.assertEquals(8,  job.getBuilds().size());
    }

    @Test
    public void testControllerSync() throws Exception {
        LoadGeneration.GeneratorController controller = LoadGeneration.getGeneratorController();
        controller.setAutostart(false);

        LoadGeneration.TrivialLoadGenerator trivial = new LoadGeneration.TrivialLoadGenerator(".*", 8);
        trivial.start();
        LoadGeneration.TrivialLoadGenerator trivial2 = new LoadGeneration.TrivialLoadGenerator(".*foo", 4);
        trivial2.setGeneratorId(trivial.generatorId);
        trivial2.stop();

        List<LoadGeneration.LoadGenerator> originalGenerators = Arrays.asList((LoadGeneration.LoadGenerator)trivial);
        List<LoadGeneration.LoadGenerator> modifiedGenerators = Arrays.asList((LoadGeneration.LoadGenerator)trivial2);

        controller.synchGenerators(originalGenerators);
        Assert.assertEquals(1, controller.registeredGenerators.values().size());
        Assert.assertTrue(trivial == controller.getRegisteredGeneratorbyId(trivial.getGeneratorId()));
        Assert.assertEquals(trivial.getCurrentTestMode(), controller.getRegisteredGeneratorbyId(trivial.getGeneratorId()).getCurrentTestMode());

        controller.synchGenerators(modifiedGenerators);
        Assert.assertTrue(trivial2 == controller.getRegisteredGeneratorbyId(trivial.getGeneratorId()));
        Assert.assertEquals(trivial.getCurrentTestMode(), controller.getRegisteredGeneratorbyId(trivial.getGeneratorId()).getCurrentTestMode());
    }

    @Test
    public void testTriggerThenUnregisterAndStopGenerator() throws Exception {
        WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "TrivialJob");
        job.setDefinition(new CpsFlowDefinition("node('doesnotexist') {\n" +
                "echo 'I did something' \n" +
                "}"));
        LoadGeneration.TrivialLoadGenerator trivial = new LoadGeneration.TrivialLoadGenerator(".*", 8);
        Assert.assertEquals(8, trivial.getConcurrentRunCount());
        LoadGeneration.DescriptorImpl desc = LoadGeneration.getDescriptorInstance();
        LoadGeneration.GeneratorController controller = LoadGeneration.getGeneratorController();
        controller.registerOrUpdateGenerator(trivial);

        // Check it queued up correctly
        Jenkins j = jenkinsRule.getInstance();
        trivial.start();
        Assert.assertEquals(8, trivial.getRunsToLaunch(0));
        controller.checkLoadAndTriggerRuns(trivial);
        trivial.stop();
        Thread.sleep(1000);  // Slight delay for queue maintenance to happen
        Assert.assertEquals(8, controller.getQueuedAndRunningCount(trivial));
        Assert.assertEquals(8, controller.getRunningCount(trivial));

        // Stop and verify really stopped
        controller.unregisterAndStopGenerator(trivial);
        Assert.assertEquals(0, controller.getQueuedAndRunningCount(trivial));
        Assert.assertNull(controller.getRegisteredGeneratorbyId(trivial.generatorId));
        Assert.assertEquals(8, job.getBuilds().size());
    }
}
