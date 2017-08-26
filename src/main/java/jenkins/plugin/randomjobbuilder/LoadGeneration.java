package jenkins.plugin.randomjobbuilder;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Descriptor;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.queue.QueueTaskFuture;
import hudson.security.ACL;
import hudson.util.DescribableList;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import net.sf.json.JSONObject;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

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

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            try {
                // TODO Revisit my attempt at using ReconfigurableDescribable to reconfigure existing instances
                // Instead of making new ones
                loadGenerators.rebuildHetero(req, json, Jenkins.getActiveInstance().getExtensionList(LoadGenerator.DescriptorBase.class), "loadGenerators");
                GeneratorController.getInstance().syncGenerators(loadGenerators);
                save();
            } catch (IOException ioe) {
                throw new RuntimeException("Something failed horribly around descriptors", ioe);
            }
            return true;
        }

        public synchronized void load() {
            super.load();
            GeneratorController.getInstance().syncGenerators(loadGenerators);
        }

        public DescriptorImpl() {
            load();
        }
    }

    /** We need to attach this when trying to launch tasks otherwise the Queue will reject most of them as duplicates! */
    static class LoadGeneratorQueueAction implements Queue.QueueAction {

        final LoadGeneratorCause loadGeneratorCause;

        public LoadGeneratorQueueAction(@Nonnull LoadGeneratorCause lgc) {
            this.loadGeneratorCause = lgc;
        }

        @Override
        public boolean shouldSchedule(List<Action> actions) {
            if (actions != null && actions.size() > 0) {
                List<LoadGeneratorQueueAction> possibleMatch = Util.filter(actions, LoadGeneratorQueueAction.class);
                for (LoadGeneratorQueueAction lga : possibleMatch) {
                    if (lga.equals(this)) {
                        return false;
                    }
                    return true;
                }
            }
            return true;
        }

        @Override
        public String getIconFileName() {
            return null;
        }

        @Override
        public String getDisplayName() {
            return "LoadGenerator Queued";
        }

        @Override
        public String getUrlName() {
            return "bob";
        }
    }

    /** Attached so we have information on the {@link LoadGenerator} triggering an {@link hudson.model.Queue.Item} or {@link Run}*/
    protected static final class LoadGeneratorCause extends Cause {
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

    /** Helper to provide a shim for triggering builds */
    private static class ParameterizedBuilder extends ParameterizedJobMixIn {
        Job job;

        ParameterizedBuilder(Job j) {
            this.job = j;
        }

        @Override protected Job asJob() {
            return job;
        }
    }

    @CheckForNull
    static void launchJob(@Nonnull LoadGenerator generator, @Nonnull Job job, int quietPeriod) {
        if (job instanceof ParameterizedJobMixIn.ParameterizedJob) {
            SecurityContext old = null;
            try {
                old = ACL.impersonate(ACL.SYSTEM);
                LoadGeneratorCause lgc = new LoadGeneratorCause(generator);
                // You MUST attach a LoadGeneratorQueueAction or most of the submitted items will be treated as duplicates
                // and not scheduled
                QueueTaskFuture<? extends Run> future = new ParameterizedBuilder(job).scheduleBuild2(quietPeriod, new CauseAction(lgc), new LoadGeneratorQueueAction(lgc));
            } finally {
                if (old != null) {
                    SecurityContextHolder.setContext(old);
                }
            }

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
     * @return Candidate job to run, or null if none in the inputs
     */
    @CheckForNull
    static Job pickRandomJob(@Nonnull List<Job> candidates) {
        if (candidates.size() == 0) {
            return null;
        } else if (candidates.size() == 1) {
            return candidates.get(0);
        }
        return candidates.get(rand.nextInt(candidates.size()));
    }

    @Nonnull
    static Collection<Queue.Item> getQueueItemsFromLoadGenerator(@Nonnull final LoadGenerator generator) {
        SecurityContext old = null;
        try {
            old = ACL.impersonate(ACL.SYSTEM);
            Queue myQueue = Jenkins.getActiveInstance().getQueue();
            Queue.Item[] items = myQueue.getItems();
            ArrayList<Queue.Item> output = new ArrayList<Queue.Item>();
            for (Queue.Item it : items) {
                List<Cause> causes = it.getCauses();
                for (Cause c : causes) {
                    if (c instanceof LoadGeneratorCause && generator.getGeneratorId().equals(((LoadGeneratorCause)c).getGeneratorId())) {
                        output.add(it);
                    }
                }
            }
            return output;
        } finally {
            SecurityContextHolder.setContext(old);
        }
    }

    static void cancelItems(Collection<Queue.Item> items) {
        SecurityContext old = null;
        try {
            old = ACL.impersonate(ACL.SYSTEM);
            Queue myQueue = Jenkins.getActiveInstance().getQueue();
            for (Queue.Item it : items) {
                myQueue.cancel(it);
            }
        } finally {
            SecurityContextHolder.setContext(old);
        }
    }

    /** Get the generator ID that triggered a run, or null if it wasn't triggered by one */
    @CheckForNull
    public static String getGeneratorCauseId(@Nonnull Run run) {
        Cause cause = run.getCause(LoadGeneratorCause.class);
        return (cause != null) ? ((LoadGeneratorCause)cause).getGeneratorId() : null;
    }

}
