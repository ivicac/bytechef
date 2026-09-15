import {WorkflowTrigger} from '@/shared/middleware/platform/configuration';

export default function upsertTrigger(triggers: WorkflowTrigger[], newTrigger: WorkflowTrigger): WorkflowTrigger[] {
    const existingIndex = triggers.findIndex((trigger) => trigger.name === newTrigger.name);

    if (existingIndex === -1) {
        return [...triggers, newTrigger];
    }

    const existingTrigger = triggers[existingIndex];

    // A trigger that is a cluster root (the browser voice session) keeps its slots when a save of the
    // same trigger type carries only its own fields, e.g. a parameter edit from the details panel. A
    // different type -- replacing the trigger, or switching its operation, both of which reuse the name
    // -- starts without them, or the voice slots would survive onto a Manual trigger.
    const keptClusterElements = existingTrigger.type === newTrigger.type ? existingTrigger.clusterElements : undefined;

    const mergedTrigger: WorkflowTrigger = {
        ...newTrigger,
        clusterElements: newTrigger.clusterElements ?? keptClusterElements,
        metadata: newTrigger.metadata ?? existingTrigger.metadata,
    };

    return [...triggers.slice(0, existingIndex), mergedTrigger, ...triggers.slice(existingIndex + 1)];
}
