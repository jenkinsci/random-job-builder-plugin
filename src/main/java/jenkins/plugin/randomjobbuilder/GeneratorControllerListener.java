package jenkins.plugin.randomjobbuilder;

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import jenkins.model.Jenkins;

import javax.annotation.Nonnull;


/**
 * Listens for events on the controller
 * Used to register changes in scenarios
 */
public abstract class GeneratorControllerListener implements ExtensionPoint {

    public abstract void onGeneratorAdded(@Nonnull LoadGenerator gen);
    public abstract void onGeneratorRemoved(@Nonnull LoadGenerator gen);

    /** Fired just before state change kicks in */
    public abstract void onGeneratorReconfigured(@Nonnull LoadGenerator oldConfig, LoadGenerator newConfig);
    public abstract void onGeneratorStarted(@Nonnull LoadGenerator gen);
    public abstract void onGeneratorStopped(@Nonnull LoadGenerator gen);


    public static final void fireGeneratorAdded(@Nonnull LoadGenerator gen){
        for (GeneratorControllerListener listener : GeneratorControllerListener.all()) {
            listener.onGeneratorAdded(gen);
        }
    }

    public static final void fireGeneratorRemoved(@Nonnull LoadGenerator gen){
        for (GeneratorControllerListener listener : GeneratorControllerListener.all()) {
            listener.onGeneratorRemoved(gen);
        }
    }

    public static final void fireGeneratorReconfigured(@Nonnull LoadGenerator oldGen, @Nonnull LoadGenerator newGen){
        for (GeneratorControllerListener listener : GeneratorControllerListener.all()) {
            listener.onGeneratorReconfigured(oldGen, newGen);
        }
    }

    public static final void fireGeneratorStarted(@Nonnull LoadGenerator gen){
        for (GeneratorControllerListener listener : GeneratorControllerListener.all()) {
            listener.onGeneratorStarted(gen);
        }
    }

    public static final void fireGeneratorStopped(@Nonnull LoadGenerator gen){
        for (GeneratorControllerListener listener : GeneratorControllerListener.all()) {
            listener.onGeneratorStopped(gen);
        }
    }


    public static ExtensionList<GeneratorControllerListener> all() {
        return Jenkins.getActiveInstance().getExtensionList(GeneratorControllerListener.class);
    }
}
