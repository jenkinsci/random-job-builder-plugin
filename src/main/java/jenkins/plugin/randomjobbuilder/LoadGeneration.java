package jenkins.plugin.randomjobbuilder;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Job;
import hudson.model.Run;
import hudson.util.DescribableList;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.CheckForNull;
import java.io.IOException;
import java.util.List;

/**
 * Configurable load generator that runs jobs
 */
public class LoadGeneration extends AbstractDescribableImpl<LoadGeneration>  {

    @Extension
    public static class DescriptorImpl extends Descriptor<LoadGeneration> {

        private DescribableList<LoadGenerator, LoadGenerator.DescriptorBase> loadGenerators = new DescribableList<LoadGenerator, LoadGenerator.DescriptorBase>(this);

        public DescribableList<LoadGenerator, LoadGenerator.DescriptorBase> getLoadGenerators() {
            return loadGenerators;
        }

        public String getDisplayName() {
            return "Load Generation";
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
        LOAD, // active at full load
        SHUT_DOWN
    }

    /** Base for all load generators that run jobs */
    public static abstract class LoadGenerator extends AbstractDescribableImpl<LoadGenerator> {

        public abstract List<Run> getAllRunningLoad();

        public abstract List<Run> getAllQueuedLoad();

        public abstract CurrentTestMode getTestMode();

        public abstract List<Job> getCandidateJobs();

        /** Descriptors neeed to extend this */
        @Extension
        public static class DescriptorBase extends Descriptor<LoadGenerator> {

            @Override
            public String getDisplayName() {
                return "";
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
        public List<Run> getAllRunningLoad() {
            return null;
        }

        @Override
        public List<Run> getAllQueuedLoad() {
            return null;
        }

        @Override
        public CurrentTestMode getTestMode() {
            return null;
        }

        @Override
        public List<Job> getCandidateJobs() {
            return null;
        }

        @Extension
        public static class DescriptorImpl extends LoadGenerator.DescriptorBase {
            public String getDisplayName() {
                return "Load Generation";
            }
        }
    }
}
