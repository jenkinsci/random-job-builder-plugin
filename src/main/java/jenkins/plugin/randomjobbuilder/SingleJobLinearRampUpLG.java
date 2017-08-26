package jenkins.plugin.randomjobbuilder;

import hudson.Extension;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Job;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.export.Exported;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;

/** LoadGenerator that lets you pick a single job and linear ramps-up load */
public class SingleJobLinearRampUpLG extends LoadGenerator {

    private String jobName = null;

    private int concurrentRunCount = 1;

    private long rampUpMillis = 0;

    private boolean useJitter = true;

    private transient LoadCalculator calculator = new LoadCalculator(concurrentRunCount, rampUpMillis, 0, useJitter);

    private transient long startTimeMillis = 0;

    @Exported
    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    /** Computes expected load */
    static class LoadCalculator {
        int finalConcurrentLoad = 1;
        long rampUpMillis = -1;
        long startTimeMillis = -1;
        boolean useJitter = true;

        public LoadCalculator(int finalConcurrentLoad, long rampUpMillis, long startTimeMillis, boolean useJitter) {
            this.finalConcurrentLoad = finalConcurrentLoad;
            this.rampUpMillis = rampUpMillis;
            this.startTimeMillis = startTimeMillis;
            this.useJitter = useJitter;
        }

        /** Target runs */
        public int computeDesiredRuns(long currentTime) {
            if (currentTime >= (startTimeMillis +rampUpMillis)) {
                return finalConcurrentLoad;
            } else if (rampUpMillis <=0) {
                return finalConcurrentLoad;
            } else if (currentTime >= startTimeMillis) {
                double fractionDone = ((double)(currentTime- startTimeMillis)/(double)(rampUpMillis));
                return (int)(Math.round((double)(finalConcurrentLoad)*fractionDone));
            } else {
                return 0;
            }
        }

        /** Based on linear ramp-up, compute how many runs to launch */
        public int computeRunsToLaunch(long currentTime, int currentRuns) {
            int target = computeDesiredRuns(currentTime);
            int delta = target-currentRuns;
            if (delta <= 0) {
                return 0;
            }

            if (useJitter) {
                // On average will launch the targetted number of runs, but will randomly launch up to 2x as many
                // And as little as none
                return (int)(Math.round(Math.random()*2.0*(double)delta));
            } else {
                return delta;
            }
        }
    }

    private LoadCalculator getCalculator() {
        if (calculator == null) {
            calculator = new LoadCalculator(concurrentRunCount, rampUpMillis, System.currentTimeMillis(), useJitter);
        }
        return calculator;
    }


    @Override
    public List<Job> getCandidateJobs() {
        Job j = Jenkins.getActiveInstance().getItemByFullName(getJobName(), Job.class);
        if (j == null) {
            return Collections.emptyList();
        }
        return Collections.singletonList(j);
    }

    @Override
    protected LoadTestMode startInternal() {
        if (this.getLoadTestMode() == LoadTestMode.IDLE || this.getLoadTestMode() == LoadTestMode.RAMP_DOWN) {
            this.startTimeMillis = System.currentTimeMillis();
            LoadCalculator calc = getCalculator();
            calc.startTimeMillis = this.startTimeMillis;
            calc.finalConcurrentLoad = this.getConcurrentRunCount();
            calc.useJitter = this.useJitter;
            calc.rampUpMillis = this.rampUpMillis;

            // If we have no ramp-up time, then ramp up immediately
            return (this.rampUpMillis > 0) ? LoadTestMode.RAMP_UP : LoadTestMode.LOAD_TEST;
        } else { // NO-OP
            return this.getLoadTestMode();
        }
    }

    @Override
    public LoadTestMode stopInternal() {
        return LoadTestMode.IDLE;
    }

    /**
     * Get the intended number of concurrent runs at once
     * @return &lt; 0 or (or negative) will result in no runs triggered, or positive integer for intended count
     */
    @Exported
    public int getConcurrentRunCount() {
        return concurrentRunCount;
    }

    @DataBoundSetter
    public void setConcurrentRunCount(int concurrentRunCount) {
        this.concurrentRunCount = concurrentRunCount;
    }

    @DataBoundSetter
    public void setGeneratorId(@Nonnull String generatorId) {
        super.setGeneratorId(generatorId);
    }

    @Override
    public int getRunsToLaunch(int currentRuns) {
        if (!isActive()) {
            return 0;
        }

        LoadCalculator calc = getCalculator();
        calc.startTimeMillis = this.startTimeMillis;
        calc.finalConcurrentLoad = this.concurrentRunCount;
        calc.rampUpMillis = this.rampUpMillis;
        calc.useJitter = this.useJitter;

        long time = System.currentTimeMillis();
        if (time > (calc.startTimeMillis+calc.rampUpMillis)) {
            if (this.getLoadTestMode() != LoadTestMode.LOAD_TEST) {
                // Engines already at full speed cap'n I canna go any faster
                setLoadTestMode(LoadTestMode.LOAD_TEST);
            }
        }
        return calc.computeRunsToLaunch(time, currentRuns);
    }

    @DataBoundConstructor
    public SingleJobLinearRampUpLG(String jobName) {
        this.jobName = jobName;
    }

    @Exported
    public long getRampUpMillis() {
        return rampUpMillis;
    }

    @DataBoundSetter
    public void setRampUpMillis(long rampUpMillis) {
        this.rampUpMillis = rampUpMillis;
        LoadCalculator calc = this.getCalculator();
        calc.rampUpMillis = rampUpMillis;
    }

    /** If true, we use randomization in the number of runs we launch, anywhere from 0 to 2x number needed to hit goal amt */
    @Exported
    public boolean isUseJitter() {
        return useJitter;
    }

    @DataBoundSetter
    public void setUseJitter(boolean useJitter) {
        this.useJitter = useJitter;
        LoadCalculator calc = this.getCalculator();
        calc.useJitter = useJitter;
    }

    @Extension
    public static class DescriptorImpl extends DescriptorBase {
        /**
         * Provides autocompletion for the jobName when looking up jobs
         * @param value
         *      The text that the user entered.
         */
        public AutoCompletionCandidates doAutoCompleteJobName(@QueryParameter String value) {
            AutoCompletionCandidates c = new AutoCompletionCandidates();
            if (StringUtils.isEmpty(value) || value.length() < 2) {
                return c;
            }
            for (Job job : Jenkins.getActiveInstance().getItems(Job.class)) {
                if (job.getFullName().contains(value)) {
                    c.add(job.getFullName());
                }
            }
            return c;
        }

        @Override
        public String getDisplayName() {
            return "Single job load generator, with load ramp-up";
        }

        public FormValidation doCheckJobName(@QueryParameter String jobName) {
            if (StringUtils.isEmpty(jobName)) {
                return FormValidation.ok();
            }

            if (Jenkins.getActiveInstance().getItemByFullName(jobName, Job.class) == null) {
                return FormValidation.error("No job with that name exists");
            }

            return FormValidation.ok();
        }
    }
}
