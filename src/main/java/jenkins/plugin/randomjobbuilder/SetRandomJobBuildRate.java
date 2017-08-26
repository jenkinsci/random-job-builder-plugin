package jenkins.plugin.randomjobbuilder;

import hudson.Extension;
import hudson.cli.CLICommand;
import jenkins.model.Jenkins;
import org.kohsuke.args4j.Argument;

/**
 * @author Stephen Connolly
 */
@Extension
public class SetRandomJobBuildRate extends CLICommand {

    @Argument(index = 0, metaVar = "RATE", usage = "Average number of builds per minute", required = true)
    public Double buildsPerMinute;

    @Override
    public String getShortDescription() {
        return "Sets the rate of builds per minute";
    }

    @Override
    protected int run() throws Exception {
        RandomJobBuilder.DescriptorImpl d =
                Jenkins.getActiveInstance().getDescriptorByType(RandomJobBuilder.DescriptorImpl.class);
        d.setBuildsPerMin(buildsPerMinute == null ? 0 : buildsPerMinute);
        return 0;
    }
}
