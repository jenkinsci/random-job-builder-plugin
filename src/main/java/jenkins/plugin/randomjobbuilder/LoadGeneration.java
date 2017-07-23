package jenkins.plugin.randomjobbuilder;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Job;
import hudson.util.DescribableList;
import hudson.util.ListBoxModel;
import jenkins.model.ArtifactManagerFactoryDescriptor;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.CheckForNull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Configurable load generator that runs jobs
 */
public class LoadGeneration extends AbstractDescribableImpl<LoadGeneration>  {

    @Extension
    public static class DescriptorImpl extends Descriptor<LoadGeneration> {

        private DescribableList<LoadGenerator, LoadGenerator.DescriptorImpl> loadGenerators = new DescribableList<LoadGenerator, LoadGenerator.DescriptorImpl>(this);

        public DescribableList<LoadGenerator, LoadGenerator.DescriptorImpl> getLoadGenerators() {
            return loadGenerators;
        }

        public String getDisplayName() {
            return "Load Generation";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            try {
                // FIXME can't find the descriptor, throws exceptions
                loadGenerators.rebuildHetero(req, json, Jenkins.getActiveInstance().getExtensionList(LoadGeneration.LoadGenerator.DescriptorImpl.class), "loadGenerators");
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

    // TODO refactor into constant rate impl and constant concurrency level
    // TODO multiple implementations, i.e. constant-rate build
    // Constant queue loading/parallel loading, etc
    public static class LoadGenerator extends AbstractDescribableImpl<LoadGenerator> {
        private String jobNameFilter = null;

        private double desiredCount = 0.0;

        private long rampUpTimeMillis;

        private long shutDownTimeoutMillis;

        private Class<? extends Job> jobType;

        private LoadTestMode myMode = null;

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

        public LoadTestMode getMyMode() {
            return myMode;
        }

        public void setMyMode(LoadTestMode myMode) {
            this.myMode = myMode;
        }

        public enum LoadTestMode {
            CONTINUOUS_RUN_RATE,
            MAINTAIN_RUN_COUNT
        };

        public enum CurrentTestMode {
            IDLE,
            RAMP_UP,
            LOAD,
            SHUT_DOWN
        }

        @DataBoundConstructor
        public LoadGenerator(@CheckForNull String jobNameFilter, double desiredCount, long rampUpTimeMillis, long shutDownTimeoutMillis) {
            setJobNameFilter(jobNameFilter);
            setDesiredCount(desiredCount);
            setRampUpTimeMillis(rampUpTimeMillis);
            setShutDownTimeoutMillis(shutDownTimeoutMillis);
        }

        @Extension
        public static class DescriptorImpl extends Descriptor<LoadGenerator> {
            public String getDisplayName() {
                return "Load Generation";
            }
        }
    }
}
