package jenkins.plugin.randomjobbuilder;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.Job;
import hudson.model.PeriodicWork;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import hudson.model.queue.QueueTaskFuture;
import hudson.security.ACL;
import hudson.util.DescribableList;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import net.sf.json.JSONObject;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

/**
 * Configurable load generator that runs jobs
 */
public class LoadGeneration extends AbstractDescribableImpl<LoadGeneration>  {

    final static Random rand = new Random();

    public static DescriptorImpl getDescriptorInstance() {
        return Jenkins.getActiveInstance().getExtensionList(LoadGeneration.DescriptorImpl.class).get(0);
    }

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

        public void addGenerator(LoadGenerator lg) {
            loadGenerators.add(lg);
            save();
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            try {
                // FixMe need to deactivate any removed generators
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
    static QueueTaskFuture<? extends Run> launchJob(@Nonnull LoadGenerator generator, @Nonnull Job job, int quietPeriod) {
        if (job instanceof ParameterizedJobMixIn.ParameterizedJob) {
            SecurityContext old = null;
            try {
                old = ACL.impersonate(ACL.SYSTEM);
                QueueTaskFuture<? extends Run> future = new ParameterizedBuilder(job).scheduleBuild2(quietPeriod, new CauseAction(new LoadGeneratorCause(generator)));
                return future;
            } finally {
                if (old != null) {
                    SecurityContextHolder.setContext(old);
                }
            }

        } else {
            return null;
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

    /** Registers run for lookup, so we know which ones to kill if we stop load suddenly...
     *   or in which cases we need to start new jobs to maintain load level
     *
     *   And triggers new builds when needed
     *
     *
     */
    @Extension
    public static class GeneratorController extends RunListener<Run> {
        ConcurrentHashMap<Run, LoadGenerator> mapRunToGenerator = new ConcurrentHashMap<>();
        ConcurrentHashMap<LoadGenerator, Collection<Run>> generatorToRuns = new ConcurrentHashMap<>();

        public void registerRun(@Nonnull Run run, @Nonnull LoadGenerator generator) {
            synchronized (generator) {
                mapRunToGenerator.put(run, generator);
                Collection<Run> runList = generatorToRuns.get(generator);
                if (runList != null) {
                    runList.add(run);
                } else {
                    HashSet<Run> set = new HashSet<Run>();
                    set.add(run);
                    generatorToRuns.put(generator, set);
                }
            }
        }

        /** Return number of new runs we need to schedule or 0 if none */
        int scheduleMoreCount(@Nonnull LoadGenerator gen) {
            if (gen.getTestMode() == CurrentTestMode.IDLE || gen.getTestMode() == CurrentTestMode.RAMP_DOWN) {
                return 0; // We're trying to shut DOWN load here
            }
            int desired = gen.getDesiredRunCount();
            int existing = generatorToRuns.get(gen).size();
            return desired-existing;
        }

        int getRunCount(@Nonnull LoadGenerator gen) {
            synchronized (gen) {
                Collection<Run> existing = generatorToRuns.get(gen);
                return (existing != null) ? existing.size() : 0;
            }
        }

        /** Triggers runs as requested by the LoadGenerator */
        public int triggerRuns(@Nonnull LoadGenerator gen) {
            int count = getRunCount(gen);
            int launchCount = gen.getRunsToLaunch(count);
            synchronized (gen) {
                List<Job> candidates = gen.getCandidateJobs();
                for (int i=0; i<launchCount; i++) {
                    Job j = pickRandomJob(candidates);
                    QueueTaskFuture<? extends Run> qtf = launchJob(gen, j, 0);
                    try {
                        // TODO do we get a run or a Task or what, here????
                        registerRun(qtf.get(), gen);
                    } catch (InterruptedException | ExecutionException ex) {
                        // Nope
                    }
                }
            }
            return launchCount;
        }

        // TODO delegate somehow to the LoadGenerator for how it's going to create new runs


        public void stopAbruptly(@Nonnull final LoadGenerator gen) {
            SecurityContext context;
            synchronized (gen) {
                ACL.impersonate(ACL.SYSTEM, new Runnable() {
                    @Override
                    public void run() {
                        Collection<Run> runs = generatorToRuns.get(gen);
                        if (runs == null || runs.isEmpty()) {
                            return;
                        }

                        for (Run r : runs) {
                            // Cancel if in queue
                            Executor ex = r.getExecutor();
                            if (ex == null) {
                                ex = r.getOneOffExecutor();
                            }
                            if (ex != null) {
                                ex.doStop();
                            } else {
                                //WorkflowRun.doKill();
                            }
                            unregisterRun(r);
                        }
                    }
                });
            }
        }

        /** Unregister run, return its generator */
        @CheckForNull
        public LoadGenerator unregisterRun(@Nonnull Run run) {
            LoadGenerator gen =  mapRunToGenerator.remove(run);
            synchronized (gen) {
                if (gen != null) {
                    Collection<Run> running = generatorToRuns.get(gen);
                    if (running != null && !running.isEmpty()) {
                        running.remove(run);
                    }
                }
                return gen;
            }
        }

        @Override
        public void onFinalized(Run run) {
            LoadGenerator gen = unregisterRun(run);
            if (gen != null) {
                triggerRuns(gen);
            }
        }

        @Override
        public void onDeleted(Run run) {
            LoadGenerator gen = unregisterRun(run);
            if (gen != null) {
                triggerRuns(gen);
            }
        }
    }

    /** Periodically starts up load again if toggled */
    @Extension
    public static class PeriodicWorkImpl extends PeriodicWork {
        GeneratorController controller = Jenkins.getActiveInstance().getExtensionList(GeneratorController.class).get(0);

        @Override
        public long getRecurrencePeriod() {
            return 3000L;
        }

        @Override
        protected void doRun() throws Exception {
            LoadGeneration.DescriptorImpl impl = Jenkins.getActiveInstance().getExtensionList(LoadGeneration.DescriptorImpl.class).get(0);
            if (impl != null) {
                List<LoadGenerator> loadGenerators = impl.loadGenerators;
                for (LoadGenerator lg : loadGenerators) {
                    if (lg.isActive()) {
                        controller.triggerRuns(lg);
                    }
                }
            } else {
                throw new RuntimeException("Something wicked this descriptor causes");
            }
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

        public boolean isActive() {
            return getTestMode() == CurrentTestMode.RAMP_UP || getTestMode() == CurrentTestMode.LOAD_TEST;
        }

        /** Given current number of runs, launch more if needed.  Return number to fire now, or less than 0 for none
         *  This allows for ramp-up behavior.
         */
        public int getRunsToLaunch(int currentRuns) {
            if (isActive()) {
                return getDesiredRunCount()-currentRuns;
            }
            return 0;
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

        // TODO some sort of API for how to shut down jobs and how fast?

        /**
         * Get the intended number of concurrent runs at once
         * @return -1 for unlimited, 0 if none, or positive integer for intended count
         */
        public abstract int getDesiredRunCount();

        /** Descriptors neeed to extend this */
        @Extension
        public static class DescriptorBase extends Descriptor<LoadGenerator> {

            @Override
            public String getDisplayName() {
                return "";
            }
        }

        @Override
        public int hashCode() {
            return getGeneratorId().hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            } else if (o == null || !(o instanceof LoadGenerator)) {
                return false;
            }
            return this.getGeneratorId().equals(((LoadGenerator)o).getGeneratorId());
        }
    }

    /** Simple job runner that immediately starts them and keeps restarting then they finish or die */
    public static class TrivialLoadGenerator extends LoadGenerator {

        private String jobNameRegex = null;

        private int desiredRunCount = 1;

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

        @Override
        public int getDesiredRunCount() {
            return desiredRunCount;
        }

        public void setDesiredRunCount(int desiredRunCount) {
            this.desiredRunCount = desiredRunCount;
        }

        public TrivialLoadGenerator(@CheckForNull String jobNameRegex, int desiredRunCount) {
            setJobNameRegex(jobNameRegex);
            if (desiredRunCount < 0) {  // TODO Jelly form validation to reject this
                this.desiredRunCount = 1;
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

        private int desiredRunCount = 1;

        private long rampUpTimeMillis;

        private long shutDownTimeoutMillis;

        private Class<? extends Job> jobType;

        public String getJobNameFilter() {
            return jobNameFilter;
        }

        public void setJobNameFilter(String jobNameFilter) {
            this.jobNameFilter = jobNameFilter;
        }

        @Override
        public int getDesiredRunCount() {
            return desiredRunCount;
        }

        public void setDesiredRunCount(int desiredRunCount) {
            this.desiredRunCount = desiredRunCount;
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
        public BellsAndWhistlesLoadGenerator(@CheckForNull String jobNameFilter, int desiredRunCount, long rampUpTimeMillis, long shutDownTimeoutMillis) {
            setJobNameFilter(jobNameFilter);
            setDesiredRunCount(desiredRunCount);
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
