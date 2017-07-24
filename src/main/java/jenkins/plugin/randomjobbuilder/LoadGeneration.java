package jenkins.plugin.randomjobbuilder;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Cause;
import hudson.model.Descriptor;
import hudson.model.Job;
import hudson.model.Run;
import hudson.util.DescribableList;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Configurable load generator that runs jobs
 */
public class LoadGeneration extends AbstractDescribableImpl<LoadGeneration>  {

    final static Random rand = new Random();

    @Extension
    public static class DescriptorImpl extends Descriptor<LoadGeneration> {

        private DescribableList<LoadGenerator, LoadGenerator.DescriptorBase> loadGenerators = new DescribableList<LoadGenerator, LoadGenerator.DescriptorBase>(this);

        public DescribableList<LoadGenerator, LoadGenerator.DescriptorBase> getLoadGenerators() {
            return loadGenerators;
        }

        public String getDisplayName() {
            return "Load Generation";
        }

        /** Find generator by its unique ID or return null */
        @CheckForNull
        public LoadGenerator getGeneratorbyId(@Nonnull String generatorId) {
            for (LoadGenerator lg : loadGenerators) {
                if (lg.getGeneratorId().equals(generatorId)) {
                    return lg;
                }
            }
            return null;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            try {
                loadGenerators.rebuildHetero(req, json, Jenkins.getActiveInstance().getExtensionList(LoadGenerator.DescriptorBase.class), "loadGenerators");
                save();
            } catch (IOException ioe) {
                throw new RuntimeException("Something failed horribly around descriptors", ioe);
            }
            return true;
        }

        public DescriptorImpl() {
            load();
        }
    }

    public enum CurrentTestMode {
        IDLE,
        RAMP_UP,
        LOAD_TEST, // active at full load
        RAMP_DOWN
    }

    public static class LoadGeneratorCause extends Cause {
        final String generatorId;
        final String generatorType;

        public LoadGeneratorCause(@Nonnull LoadGenerator generator) {
            this.generatorId = generator.generatorId;
            this.generatorType = generator.getClass().getName();
        }

        public String getGeneratorId(){
            return generatorId;
        }

        public String getGeneratorType(){
            return generatorType;
        }

        @Override
        public String getShortDescription() {
            return "LoadGenerator "+getGeneratorType()+" ID: "+getGeneratorId();
        }
    }

    static class ParameterizedBuilder extends ParameterizedJobMixIn {
        Job job;

        ParameterizedBuilder(Job j) {
            this.job = j;
        }

        @Override protected Job asJob() {
            return job;
        }
    }


    @CheckForNull
    static void launchJob(@Nonnull LoadGenerator generator, @Nonnull Job job, int quietPeriod) {
        if (job instanceof ParameterizedJobMixIn.ParameterizedJob) {
            ParameterizedJobMixIn.ParameterizedJob pj = (ParameterizedJobMixIn.ParameterizedJob)job;
            new ParameterizedBuilder(job).scheduleBuild(quietPeriod, new LoadGeneratorCause(generator));
        } else {
            // Can't schedule me, I know, so sad.
        }
    }

    /**
     * Convenience: filter a list of candidate jobs by a condition
     * @param filterCondition Predicate to filter jobs by
     * @return Filtered list of jobs
     */
    @Nonnull
    static List<Job> filterJobsByCondition(@Nonnull Predicate<Job> filterCondition) {
        ArrayList<Job> output = new ArrayList<Job>();
        Iterables.addAll(output, Iterables.filter(Jenkins.getActiveInstance().getAllItems(Job.class), filterCondition));
        return output;
    }

    /**
     * Convenience: pick a random job from list to run
     * @param candidates
     * @return
     */
    @CheckForNull
    static Job pickRandomJob(@Nonnull List<Job> candidates) {
        if (candidates.size() == 0) {
            return null;
        }
        return candidates.get(rand.nextInt(candidates.size()));
    }

    /** Filters candidate jobs by pattern matching on fullName */
    static class JobNameFilter implements Predicate<Job> {
        String nameMatchRegex = null;
        Pattern namePattern = null;

        public JobNameFilter(){

        }

        public JobNameFilter(String nameMatchRegex) {
            if (!StringUtils.isEmpty(nameMatchRegex)) {
                this.nameMatchRegex = nameMatchRegex;
                this.namePattern = Pattern.compile(nameMatchRegex);
            }
        }

        /** Sets name pattern with fluent-style API  */
        JobNameFilter setNamePattern(String filterCondition) {
            if (!StringUtils.isEmpty(nameMatchRegex)) {
                this.nameMatchRegex = nameMatchRegex;
                this.namePattern = Pattern.compile(nameMatchRegex);
            } else {
                this.nameMatchRegex = null;
                this.namePattern = null;
            }
            return this;
        }

        @Override
        public boolean apply(@Nullable Job input) {
            if (input == null) {
                return false;
            }
            return namePattern.matcher(input.getFullName()).matches();
        }
    }

    /** Base for all load generators that run jobs */
    public static abstract class LoadGenerator extends AbstractDescribableImpl<LoadGenerator> {
        /** Identifies the generator for causes */
        protected String generatorId = UUID.randomUUID().toString();

        protected CurrentTestMode currentTestMode = CurrentTestMode.IDLE;

        /** Pool of generated jobs */
        protected HashSet<Run> generated = new HashSet<Run>();

        public CurrentTestMode getTestMode() {
            return currentTestMode;
        }

        public String getGeneratorId() {
            return generatorId;
        }

        public abstract List<Job> getCandidateJobs();

        /** Begin running load test and then switch to full load
         *  @return {@link CurrentTestMode} phase as we transition to starting load test, i.e. LOAD_TEST or RAMP_UP
         */
        public abstract CurrentTestMode start();

        /**
         * Start shutting down the load test and then stop it
         * @return {@link CurrentTestMode} as we transition to shutting load load test, i.e. RAMP_DOWN or IDLE
         */
        public abstract CurrentTestMode stop();

        /** Descriptors neeed to extend this */
        @Extension
        public static class DescriptorBase extends Descriptor<LoadGenerator> {

            @Override
            public String getDisplayName() {
                return "";
            }
        }
    }

    /** Simple job runner that immediately starts them and keeps restarting then they finish or die */
    public static class TrivialLoadGenerator extends LoadGenerator {

        private String jobNameRegex = null;

        private int jobConcurrency = 1;

        @Override
        public List<Job> getCandidateJobs() {
            return LoadGeneration.filterJobsByCondition(new JobNameFilter(jobNameRegex));
        }

        @Override
        public CurrentTestMode start() {
            return CurrentTestMode.LOAD_TEST;
        }

        @Override
        public CurrentTestMode stop() {
            return CurrentTestMode.IDLE;
        }

        public String getJobNameRegex() {
            return jobNameRegex;
        }

        public void setJobNameRegex(String jobNameRegex) {
            this.jobNameRegex = jobNameRegex;
        }

        public int getJobConcurrency() {
            return jobConcurrency;
        }

        public void setJobConcurrency(int jobConcurrency) {
            this.jobConcurrency = jobConcurrency;
        }

        public TrivialLoadGenerator(@CheckForNull String jobNameRegex, int jobConcurrency) {
            setJobNameRegex(jobNameRegex);
            if (jobConcurrency < 0) {  // TODO Jelly form validation to reject this
                this.jobConcurrency = 1;
            }
        }

        @Extension
        public static class DescriptorImpl extends DescriptorBase {

            @Override
            public String getDisplayName() {
                return "Trivial Filtered Job Builder";
            }
        }
    }

    // TODO refactor into constant rate impl and constant concurrency level
    // TODO multiple implementations, i.e. constant-rate build
    // Constant queue loading/parallel loading, etc
    public static class BellsAndWhistlesLoadGenerator extends LoadGenerator {
        private String jobNameFilter = null;

        private double desiredCount = 0.0;

        private long rampUpTimeMillis;

        private long shutDownTimeoutMillis;

        private Class<? extends Job> jobType;

        public String getJobNameFilter() {
            return jobNameFilter;
        }

        public void setJobNameFilter(String jobNameFilter) {
            this.jobNameFilter = jobNameFilter;
        }

        public double getDesiredCount() {
            return desiredCount;
        }

        public void setDesiredCount(double desiredCount) {
            this.desiredCount = desiredCount;
        }

        /** Ramp up time for load*/
        public long getRampUpTimeMillis() {
            return rampUpTimeMillis;
        }

        public void setRampUpTimeMillis(long rampUpTimeMillis) {
            this.rampUpTimeMillis = rampUpTimeMillis;
        }

        /** Once load test is complete, wait this long before explicitly terminating jobs, or -1 to allow them to complete normally */
        public long getShutDownTimeoutMillis() {
            return shutDownTimeoutMillis;
        }

        public void setShutDownTimeoutMillis(long shutDownTimeoutMillis) {
            this.shutDownTimeoutMillis = shutDownTimeoutMillis;
        }

        /** Filter for jobs allow  */
        public Class<? extends Job> getJobType() {
            return jobType;
        }

        public void setJobType(Class<? extends Job> jobType) {
            this.jobType = jobType;
        }

        @DataBoundConstructor
        public BellsAndWhistlesLoadGenerator(@CheckForNull String jobNameFilter, double desiredCount, long rampUpTimeMillis, long shutDownTimeoutMillis) {
            setJobNameFilter(jobNameFilter);
            setDesiredCount(desiredCount);
            setRampUpTimeMillis(rampUpTimeMillis);
            setShutDownTimeoutMillis(shutDownTimeoutMillis);
        }

        @Override
        public List<Job> getCandidateJobs() {
            return Collections.EMPTY_LIST;
        }

        @Override
        public CurrentTestMode start() {
            return CurrentTestMode.IDLE;
        }

        @Override
        public CurrentTestMode stop() {
            return CurrentTestMode.LOAD_TEST;
        }

        @Extension
        public static class DescriptorImpl extends LoadGenerator.DescriptorBase {
            public String getDisplayName() {
                return "Load Generation";
            }
        }
    }
}
