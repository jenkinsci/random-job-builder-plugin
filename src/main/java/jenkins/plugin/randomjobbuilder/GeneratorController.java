package jenkins.plugin.randomjobbuilder;

import com.google.common.collect.Sets;
import hudson.Extension;
import hudson.model.Executor;
import hudson.model.Job;
import hudson.model.PeriodicWork;
import hudson.model.ReconfigurableDescribable;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import org.acegisecurity.context.SecurityContext;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controls load generation for a set of registered set of LoadGenerators
 *
 * <ul>
 *   <li>Uses listeners to manage tasks going from queue to becoming running jobs</li>
 *   <li>Manage load by starting new jobs as needed for loak</li>
 *   <li>Provides way to kill all tasks for a load generator</li>
 *   <li>Provides synchronization and update of the generator list with on-the-fly modifications</li>
 * </ul>
 *
 */
@Extension
public final class GeneratorController extends RunListener<Run> {
    private boolean autostart = false;

    public static final long RECURRENCE_PERIOD_MILLIS = 2000L;

    ConcurrentHashMap<String, LoadGenerator> registeredGenerators = new ConcurrentHashMap<String, LoadGenerator>();

    /** Map {@link LoadGenerator#generatorId} to that LoadGenerator's queued item count */
    ConcurrentHashMap<String, AtomicInteger> queueTaskCount = new ConcurrentHashMap<>();

    /** Map {@link LoadGenerator#generatorId} to that LoadGenerator's {@link Run}s,
     *   needed in order to cancel runs in progress */
    ConcurrentHashMap<String, Collection<Run>> generatorToRuns = new ConcurrentHashMap<>();

    /** Register the generator in {@link #registeredGenerators} or
     *   replace existing generator with the same generator ID
     *   Eventually we'll just use {@link ReconfigurableDescribable} for direct updates of generator lists.
     *   @param generator Generator to register/update
     */
     void registerOrUpdateGenerator(@Nonnull LoadGenerator generator) {
        LoadGenerator previous = registeredGenerators.put(generator.getGeneratorId(), generator);
        synchronized (generator) {
            if (previous != null) {  // Copy some transitory state in, but honestly this is a hack
                GeneratorControllerListener.fireGeneratorReconfigured(previous, generator);
                generator.copyStateFrom(previous);
            } else {
                GeneratorControllerListener.fireGeneratorAdded(generator);
            }
        }
    }

    /** Returns a snapshot of current registered load generators */
    public List<LoadGenerator> getRegisteredGenerators() {
        return new ArrayList<LoadGenerator>(registeredGenerators.values());
    }

    /**
     * Unregister the generator and stop all jobs and tasks from it
     * @param generator Generator to unregister/remove
     */
    void unregisterAndStopGenerator(@Nonnull LoadGenerator generator) {
        synchronized (generator) {
            generator.stop();
            this.stopAbruptly(generator);
            registeredGenerators.remove(generator.getGeneratorId());
            GeneratorControllerListener.fireGeneratorRemoved(generator);
        }
    }

    /** Find generator by its unique ID or return null if not registered
     * @param generatorId ID of registered generator
     */
    @CheckForNull
    public LoadGenerator getRegisteredGeneratorbyId(@Nonnull String generatorId) {
        return registeredGenerators.get(generatorId);
    }

    /** Find generator by its unique ID or return null if not registered
     * @param shortName ID of registered generator
     */
    @CheckForNull
    public LoadGenerator getRegisteredGeneratorbyShortName(@Nonnull String shortName) {
        for (LoadGenerator lg : registeredGenerators.values()) {
            if (lg.getShortName().equals(shortName)) {
                return lg;
            }
        }
        return null;
    }

    /** Ensure that the registered generators match input set, registering any new ones and unregistering ones not in input,
     *  which will kill any jobs or tasks linked to them
     *  @param generators List of generators to add/remove/update
     */
     synchronized void syncGenerators(@Nonnull List<LoadGenerator> generators) {
        Set<LoadGenerator> registeredSet = new HashSet<LoadGenerator>(registeredGenerators.values());
        Set<LoadGenerator> inputSet = new HashSet<LoadGenerator>(generators);

        for (LoadGenerator gen : Sets.difference(registeredSet, inputSet)) {
            unregisterAndStopGenerator(gen);
        }

        for (LoadGenerator lg : generators) {
            registerOrUpdateGenerator(lg);
        }
    }

    /**
     * Track adding a queued task for the given generator
     * @param generator Generator that created the task
     */
    public void addQueueItem(@Nonnull LoadGenerator generator) {
        synchronized (generator) {
            AtomicInteger val = queueTaskCount.get(generator.getGeneratorId());
            if (val != null) {
                val.incrementAndGet();
            } else {
                queueTaskCount.put(generator.getGeneratorId(), new AtomicInteger(1));
            }
        }
    }

    /**
     * Decrement queued item count for generator
     * @param generator Generator that generated the task
     */
    public void removeQueueItem(@Nonnull LoadGenerator generator) {
        synchronized (generator) {
            AtomicInteger val = queueTaskCount.get(generator.getGeneratorId());
            if (val != null && val.get() > 0) {
                val.decrementAndGet();
            }
        }
    }

    /**
     * Track a run originating from a given generator
     * @param generator
     * @param run
     */
    public void addRun(@Nonnull LoadGenerator generator, @Nonnull Run run) {
        synchronized (generator) {
            Collection<Run> runs = generatorToRuns.get(generator.getGeneratorId());
            if (runs != null) {
                runs.add(run);
            } else {
                HashSet<Run> runCollection = new HashSet<>();
                runCollection.add(run);
                generatorToRuns.put(generator.getGeneratorId(), runCollection);
            }
        }
    }

    /**
     * Remove a run (as if completed) that was tracked against a given generator
     * @param generator
     * @param run
     */
    public void removeRun(@Nonnull LoadGenerator generator, Run run) {
        synchronized (generator) {
            Collection<Run> runs = generatorToRuns.get(generator.getGeneratorId());
            if (runs != null) {
                runs.remove(run);
            }
        }
    }

    /**
     * Get number of tracked queued items for generator
     * @param gen
     * @return Queued count
     */
    public int getQueuedCount(@Nonnull LoadGenerator gen) {
        synchronized (gen) {
            AtomicInteger val = queueTaskCount.get(gen.getGeneratorId());
            int count = (val != null) ? val.get() : 0;
            return count;
        }
    }

    /**
     * Get the count of actually running jobs for the generator
     * @param gen
     * @return
     */
    public int getRunningCount(@Nonnull LoadGenerator gen) {
        synchronized (gen) {
            Collection<Run> runColl = generatorToRuns.get(gen.getGeneratorId());
            return (runColl != null) ? runColl.size() : 0;
        }
    }

    /** Sum of both queued and actively running runs for the given generator
     * @param gen
     */
    public int getQueuedAndRunningCount(@Nonnull LoadGenerator gen) {
        synchronized (gen) {
            AtomicInteger val = queueTaskCount.get(gen.getGeneratorId());
            int count = (val != null) ? val.get() : 0;
            Collection<Run> runColl = generatorToRuns.get(gen.getGeneratorId());
            if (runColl != null) {
                count += runColl.size();
            }
            return count;
        }
    }

    /** Returns a snapshot of current tracked runs for generator
     *  @param generator
     */
    @Nonnull
    private Collection<Run> getRuns(@Nonnull LoadGenerator generator) {
        synchronized (generator) {
            Collection<Run> runs = generatorToRuns.get(generator.getGeneratorId());
            return (runs != null && runs.size() > 0) ? new ArrayList<Run>(runs) : Collections.<Run>emptySet();
        }
    }

    /** Triggers runs as requested by the LoadGenerator for a REGISTERED generator only
     *  @param gen
     *  @return Number of runs triggered as a result of load check
     */
    public int checkLoadAndTriggerRuns(@Nonnull LoadGenerator gen) {
        LoadGenerator registeredGen = getRegisteredGeneratorbyId(gen.generatorId);
        if (registeredGen == null) {
            // Not a registered generator, bomb out
            return 0;
        }
        synchronized (registeredGen) {
            if (!registeredGenerators.containsKey(registeredGen.getGeneratorId())) {
                return 0; // Not a registered generator
            }
            int count = getQueuedAndRunningCount(registeredGen);
            int launchCount = registeredGen.getRunsToLaunch(count);
            List<Job> candidates = registeredGen.getCandidateJobs();
            if (candidates == null || candidates.size() == 0) {
                return 0;  // Can't trigger
            }
            for (int i=0; i<launchCount; i++) {
                Job j = LoadGeneration.pickRandomJob(candidates);
                if (j != null) {
                    LoadGeneration.launchJob(registeredGen, j, 0);
                    addQueueItem(registeredGen);
                }
            }
            return launchCount;
        }
    }

    /** Shut down the generator, kill all its queued items, and cancel all its current runs
     * @param inputGen
     */
    public void stopAbruptly(@Nonnull final LoadGenerator inputGen) {
        // TODO find the FlyWeightTask too and kill that, if we aren't already
        // Find the appropriate registered generator, don't just blindly use supplied instance
        final LoadGenerator gen = getRegisteredGeneratorbyId(inputGen.generatorId);
        if (gen == null) {
            return;
        }
        gen.stop();
        SecurityContext context;
        synchronized (gen) {
            ACL.impersonate(ACL.SYSTEM, new Runnable() {
                @Override
                public void run() {
                    synchronized (gen) {
                        LoadGeneration.cancelItems(LoadGeneration.getQueueItemsFromLoadGenerator(gen));
                        for (Run r : getRuns(gen)) {
                            Executor ex = r.getExecutor();
                            if (ex == null) {
                                ex = r.getOneOffExecutor();
                            }
                            if (ex != null) {
                                ex.doStop();
                            } // May need to do //WorkflowRun.doKill();
                            removeRun(gen, r);
                        }
                    }
                }
            });
        }
    }

    /** Triggers load as needed for all the registered generators */
    public void maintainLoad() {
        for (LoadGenerator lg : registeredGenerators.values()) {
            if (lg.isActive()) {
                this.checkLoadAndTriggerRuns(lg);
            }
        }
    }

    /** Run began - if generator is registered, track the new run and decrement queued items count for the generator
     *  @param run
     *  @param listener
     */
    @Override
    public void onStarted(@Nonnull Run run, TaskListener listener) {
        String genId = LoadGeneration.getGeneratorCauseId(run);
        LoadGenerator gen = null;
        if (genId != null) {
            gen = registeredGenerators.get(genId);
        }
        if (gen != null) {
            synchronized (gen) {
                removeQueueItem(gen);
                addRun(gen, run);
            }
        }
    }

    @Override
    public void onFinalized(@Nonnull Run run) {
        String generatorId = LoadGeneration.getGeneratorCauseId(run);
        if (generatorId != null && registeredGenerators.containsKey(generatorId)) {
            LoadGenerator gen = registeredGenerators.get(generatorId);
            removeRun(gen, run);
            checkLoadAndTriggerRuns(gen);
        }
    }

    public boolean isAutostart() {
        return autostart;
    }

    public void setAutostart(boolean autostart) {
        this.autostart = autostart;
    }

    public static GeneratorController getInstance() {
        return Jenkins.getActiveInstance().getExtensionList(GeneratorController.class).get(0);
    }

    /** Periodically starts up load again if toggled */
    @Extension
    public static class PeriodicWorkImpl extends PeriodicWork {
        GeneratorController controller = getInstance();

        @Override
        public long getRecurrencePeriod() {
            return RECURRENCE_PERIOD_MILLIS;
        }

        @Override
        protected void doRun() throws Exception {
            if (controller.isAutostart()) {
                controller.maintainLoad();
            }
        }
    }
}
