package jenkins.plugin.randomjobbuilder;

import hudson.Extension;
import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Failure;
import hudson.model.Job;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;

/** Base for all load generators that run jobs */
@ExportedBean
@Restricted(NoExternalUse.class) // Until the APIs are more rigidly defined
public abstract class LoadGenerator extends AbstractDescribableImpl<LoadGenerator> implements ExtensionPoint {
    /** Identifies the generator for causes */
    @Nonnull
    String generatorId;

    /** Human-visible name to use */
    @Nonnull
    String shortName;

    /** User-readable description */
    @CheckForNull
    String description;

    protected LoadTestMode loadTestMode = LoadTestMode.IDLE;


    @Exported
    public LoadTestMode getLoadTestMode() {
        return loadTestMode;
    }

    public LoadGenerator() {
        this.generatorId = DescriptorBase.getNewGeneratorId();
        this.shortName = this.generatorId;
    }

    public LoadGenerator(@Nonnull String generatorId) {
        this.generatorId = generatorId;
        this.shortName = generatorId;
    }

    @Nonnull
    @Exported
    public String getGeneratorId() {
        return generatorId;
    }

    @DataBoundSetter
    @Restricted(NoExternalUse.class)
    public void setGeneratorId(final String generatorId) {
        if (StringUtils.isEmpty(generatorId)) {
            this.generatorId = DescriptorBase.getNewGeneratorId();
        } else {
            Jenkins.checkGoodName(generatorId);
            this.generatorId = generatorId;
        }
    }

    @Nonnull
    @Exported
    public String getShortName() {
        return shortName;
    }

    @DataBoundSetter
    @Restricted(NoExternalUse.class)
    public void setShortName(final String shortName) {
        if (StringUtils.isEmpty(shortName)) {
            throw new IllegalArgumentException("Short name is empty and may not be");
        }
        Jenkins.checkGoodName(shortName);
        this.shortName = shortName;
    }

    @Exported
    @CheckForNull
    public String getDescription() {
        return this.description;
    }

    /**
     * Set the description - may be null
     * @param desc Description for generator
     */
    @Restricted(NoExternalUse.class)
    @DataBoundSetter
    public void setDescription(final String desc) {
        this.description = desc;
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

    /** Copy over internal state information from a newly configured instance of the same {@link LoadGenerator} type.
     *  This is to allow users to reconfigure load generators on the fly without removing and recreating them.
     *  This is *probably* a workaround until we can get this to correctly use {@link hudson.model.ReconfigurableDescribable}
     *
     *  Classes extending LoadGenerator should call super() on this
     */
     void copyStateFrom(LoadGenerator original) {
         this.loadTestMode = original.getLoadTestMode();
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
        @Nonnull
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

        public FormValidation doCheckShortName(@QueryParameter String shortName) {
            if (StringUtils.isEmpty(shortName)) {
                return FormValidation.error("Short name is empty and may not be");
            }
            try {
                Jenkins.checkGoodName(shortName);
            } catch (Failure fail) {
                return FormValidation.error(fail.getMessage());
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
