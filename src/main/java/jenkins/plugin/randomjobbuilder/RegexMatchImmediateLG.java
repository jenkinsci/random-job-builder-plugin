package jenkins.plugin.randomjobbuilder;

import com.google.common.base.Predicate;
import hudson.Extension;
import hudson.model.Job;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.export.Exported;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.regex.Pattern;

/** LoadGenerator that selects jobs matching a regex and starts load immediately */
public class RegexMatchImmediateLG extends LoadGenerator {

    private String jobNameRegex = null;

    private int concurrentRunCount = 1;

    @Override
    public List<Job> getCandidateJobs() {
        return LoadGeneration.filterJobsByCondition(new JobNameFilter(jobNameRegex));
    }

    @Override
    public LoadTestMode start() {
        this.loadTestMode = LoadTestMode.LOAD_TEST;
        return LoadTestMode.LOAD_TEST;
    }

    @Override
    public LoadTestMode stop() {
        this.loadTestMode = LoadTestMode.IDLE;
        return LoadTestMode.IDLE;
    }


    @Exported
    public String getJobNameRegex() {
        return jobNameRegex;
    }

    @DataBoundSetter
    public void setJobNameRegex(String jobNameRegex) {
        this.jobNameRegex = jobNameRegex;
    }

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

    @DataBoundConstructor
    public RegexMatchImmediateLG() {

    }

    public RegexMatchImmediateLG(@CheckForNull String jobNameRegex, int concurrentRunCount) {
        setJobNameRegex(jobNameRegex);
        this.concurrentRunCount = concurrentRunCount;
    }

    @Extension
    public static class DescriptorImpl extends DescriptorBase {

        @Override
        public String getDisplayName() {
            return "Jobs by regex match, immediate load";
        }
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
            if (!StringUtils.isEmpty(filterCondition)) {
                this.nameMatchRegex = filterCondition;
                this.namePattern = Pattern.compile(filterCondition);
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
            } else if (StringUtils.isEmpty(nameMatchRegex)) {
                return true;
            } else {
                return namePattern.matcher(input.getFullName()).matches();
            }
        }
    }
}
