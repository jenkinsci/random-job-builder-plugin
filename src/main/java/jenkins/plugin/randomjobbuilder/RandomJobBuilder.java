package jenkins.plugin.randomjobbuilder;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Cause;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.PeriodicWork;
import hudson.model.Project;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.kohsuke.stapler.StaplerRequest;

import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * @author Stephen Connolly
 */
public class RandomJobBuilder extends AbstractDescribableImpl<RandomJobBuilder> {

    private static final Logger LOGGER = Logger.getLogger(RandomJobBuilder.class.getName());

    @Extension
    public static class DescriptorImpl extends Descriptor<RandomJobBuilder> {

        private double buildsPerMin = 0.0;

        @Override
        public String getDisplayName() {
            return Messages.RandomJobBuilder_DisplayName();
        }

        public double getBuildsPerMin() {
            return buildsPerMin;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            setBuildsPerMin(json.optDouble("buildsPerMin", 0.0));
            return true;
        }

        public void setBuildsPerMin(double buildsPerMinute) {
            this.buildsPerMin = Math.max(0.0, Math.min(buildsPerMinute, 10000));
            save();
        }
    }

    @Extension
    public static class PeriodicWorkImpl extends PeriodicWork {

        private static final long ONE_SECOND_IN_MILLIS = TimeUnit.SECONDS.toMillis(1);
        private final Random entropy = new Random();

        private long lastTime = System.currentTimeMillis();

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS.toMillis(2);
        }

        @Override
        protected void doRun() throws Exception {
            DescriptorImpl d = Jenkins.getActiveInstance().getDescriptorByType(DescriptorImpl.class);
            if (d.getBuildsPerMin() <= 0) {
                lastTime = System.currentTimeMillis();
                return;
            }
            for (; lastTime < System.currentTimeMillis(); lastTime += ONE_SECOND_IN_MILLIS) {
                double buildsPerSec = d.getBuildsPerMin() / TimeUnit.MINUTES.toSeconds(1);
                while (buildsPerSec >= 1.0) {
                    startBuild();
                    buildsPerSec--;
                }
                if (entropy.nextDouble() <= buildsPerSec) {
                    startBuild();
                }
            }
        }

        private boolean startBuild() {
            SecurityContext context = ACL.impersonate(ACL.SYSTEM);
            try {
                ItemGroup<? extends Item> group = Jenkins.getActiveInstance();
                if (group.getItems().isEmpty()) {
                    return false;
                }
                while (true) {
                    if (group == null) {
                        return false;
                    }
                    Item item = pickItem(group);
                    if (item instanceof Project) {
                        ((Project) item).scheduleBuild2(0, new RandomJobBuilderCause());
                        return true;
                    }
                    if (item instanceof ItemGroup) {
                        group = (ItemGroup<? extends Item>) item;
                        if (group.getItems().isEmpty()) {
                            group = item.getParent();
                        }
                    }
                }
            } finally {
                SecurityContextHolder.setContext(context);
            }
        }

        private Item pickItem(ItemGroup<? extends Item> group) {
            Collection<? extends Item> items = group.getItems();
            int index = entropy.nextInt(items.size());
            if (items instanceof List) {
                return ((List<? extends Item>) items).get(index);
            } else {
                for (Item i : items) {
                    if (index == 0) {
                        return i;
                    }
                    index--;
                }
            }
            return null;
        }

        private static class RandomJobBuilderCause extends Cause {
            @Override
            public String getShortDescription() {
                return Messages.RandomJobBuilder_DisplayName();
            }
        }
    }
}
