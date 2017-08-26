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

    private LoadTestMode loadTestMode = LoadTestMode.IDLE;


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
    public abstract int getRunsToLaunch(int currentRuns);

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

    /** Begin running load test and then switch to full load after any ramp-up time, firing {@link GeneratorControllerListener#onGeneratorStarted(LoadGenerator)}
     *  Implementations should provide logic for this in {@link #startInternal()}
     *  @return {@link LoadTestMode} phase as we transition to starting load test, i.e. LOAD_TEST or RAMP_UP
     */
    public final LoadTestMode start() {
        GeneratorControllerListener.fireGeneratorStarted(this);
        LoadTestMode lt = startInternal();
        setLoadTestMode(lt);
        return lt;
    }

    /** Provide the actual implementation of state change in the start method and return new state */
    protected abstract LoadTestMode startInternal();

    /**
     * Start shutting down the load test and then stop it after ramp-down, firing {@link GeneratorControllerListener#onGeneratorStopped(LoadGenerator)} (LoadGenerator)}
     *  Implementations should provide logic for this in {@link #stopInternal()}
     * @return {@link LoadTestMode} as we transition to shutting load load test, i.e. RAMP_DOWN or IDLE
     */
    public final LoadTestMode stop() {
        GeneratorControllerListener.fireGeneratorStopped(this);
        LoadTestMode lt = stopInternal();
        setLoadTestMode(lt);
        return lt;
    }

    /** Between this and the getter, this may be used to trigger events on change */
    protected void setLoadTestMode(LoadTestMode testMode) {
        this.loadTestMode = testMode;
    }

    /** Provide the actual implementation of state change in the stop method and return new state */
    protected abstract LoadTestMode stopInternal();

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
