package jenkins.plugin.randomjobbuilder;

import hudson.model.Job;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

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
        assertEquals(1, trivialMatch.getDesiredRunCount());
        assertEquals("Generator should start idle and didn't", LoadGeneration.CurrentTestMode.IDLE, trivialMatch.getTestMode());
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

        LoadGeneration.TrivialLoadGenerator trivialNoMatch = new LoadGeneration.TrivialLoadGenerator("cheese", 1);
        candidates = trivialNoMatch.getCandidateJobs();
        assertEquals("Empty filter should return no matches", 0, candidates.size());
        assertNull(LoadGeneration.pickRandomJob(candidates));
    }

    @Test
    public void testLoadGenerationDescriptor() throws Exception {
        LoadGeneration.DescriptorImpl desc = LoadGeneration.getDescriptorInstance();
        LoadGeneration.TrivialLoadGenerator trivial = new LoadGeneration.TrivialLoadGenerator(".*", 1);
        desc.addGenerator(trivial);

        assertTrue(desc.getLoadGenerators().contains(trivial));
        assertEquals(trivial, desc.getGeneratorbyId(trivial.getGeneratorId()));
    }

    @Test
    public void testTrivialLoadGeneratorStart() throws Exception {
        WorkflowJob job = jenkinsRule.jenkins.createProject(WorkflowJob.class, "TrivialJob");
        job.setDefinition(new CpsFlowDefinition("echo 'I did something' "));

        LoadGeneration.TrivialLoadGenerator trivial = new LoadGeneration.TrivialLoadGenerator(".*", 1);
        LoadGeneration.CurrentTestMode testMode = trivial.start();
        assertEquals(LoadGeneration.CurrentTestMode.LOAD_TEST, testMode);
        assertEquals(LoadGeneration.CurrentTestMode.LOAD_TEST, trivial.getTestMode());
        assert trivial.isActive();

        testMode = trivial.stop();
        assertEquals(LoadGeneration.CurrentTestMode.IDLE, testMode);
        assertEquals(LoadGeneration.CurrentTestMode.IDLE, trivial.getTestMode());
        assert !trivial.isActive();
    }
}
