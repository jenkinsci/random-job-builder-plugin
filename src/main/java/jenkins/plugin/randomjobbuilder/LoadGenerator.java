package jenkins.plugin.randomjobbuilder;

import hudson.Extension;
import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Job;
import hudson.util.FormValidation;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;

/** Base for all load generators that run jobs */
@ExportedBean
public abstract class LoadGenerator extends AbstractDescribableImpl<LoadGenerator> implements ExtensionPoint {
    /** Identifies the generator for causes */
    String generatorId;

    protected LoadTestMode loadTestMode = LoadTestMode.IDLE;


    @Exported
    public LoadTestMode getLoadTestMode() {
        return loadTestMode;
    }

    public LoadGenerator() {
        this.generatorId = DescriptorBase.getNewGeneratorId();
    }

    public LoadGenerator(@Nonnull String generatorId) {
        this.generatorId = generatorId;
    }

    @Nonnull
    @Exported
    public String getGeneratorId() {
        return generatorId;
    }

    @DataBoundSetter
    @Restricted(NoExternalUse.class)
    public void setGeneratorId(String generatorId) {
        this.generatorId = generatorId;
    }

    public boolean isActive() {
        return getLoadTestMode() == LoadTestMode.RAMP_UP || getLoadTestMode() == LoadTestMode.LOAD_TEST;
    }

    /** Given current number of runs, launch more if needed.  Return number to fire now, or &lt;= 0 for none
     *  This allows for ramp-up behavior.
     */
    public int getRunsToLaunch(int currentRuns) {
        if (isActive() && getConcurrentRunCount() > 0) {
            return getConcurrentRunCount()-currentRuns;
        }
        return 0;
    }

    public abstract List<Job> getCandidateJobs();

    /** Begin running load test and then switch to full load
     *  @return {@link LoadTestMode} phase as we transition to starting load test, i.e. LOAD_TEST or RAMP_UP
     */
    public abstract LoadTestMode start();

    /**
     * Start shutting down the load test and then stop it
     * @return {@link LoadTestMode} as we transition to shutting load load test, i.e. RAMP_DOWN or IDLE
     */
    public abstract LoadTestMode stop();

    // TODO some sort of API for how to shut down jobs and how fast?

    /**
     * Get the intended number of concurrent runs at once
     * @return -1 for unlimited, 0 if none, or positive integer for intended count
     */
    @Exported
    public abstract int getConcurrentRunCount();

    /** Descriptors neeed to extend this */
    @Extension
    public static class DescriptorBase extends Descriptor<LoadGenerator> {

        /** Creates a new, generally unique generator ID */
        public static String getNewGeneratorId() {
            return UUID.randomUUID().toString();
        }

        @Override
        public String getDisplayName() {
            return "";
        }

        public FormValidation doCheckGeneratorId(@QueryParameter String generatorId) {
            if (StringUtils.isEmpty(generatorId)) {
                return FormValidation.error("Generator ID \"{0}\" is empty!");
            }
            return FormValidation.ok();
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
