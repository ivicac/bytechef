import {getTask} from './getTask';

/**
 * Whether a DataStream cluster root's simple editor can still represent its current configuration.
 * The simple editor assumes the root's `processor` cluster element is the `fieldMapper` operation of
 * `dataStreamProcessor` -- once a user hand-configures the processor to anything else (through the
 * canvas view or the advanced editor), switching back to the simple editor would silently discard that
 * configuration, so callers use this to decide whether to offer the switch at all.
 *
 * Shared by `useClusterElementsCanvasDialog` (the dialog's own toggle-editor button) and
 * `ClusterFrameShell` (the box header's "Switch to DataStream editor" button) -- previously each
 * carried its own copy of this exact computation, which is the kind of thing that drifts silently
 * (a wizard button appearing for the wrong roots) if only one copy is ever updated.
 *
 * Fails open (returns `true`, meaning "offer the simple editor") on anything short of a definite
 * fieldMapper mismatch: no definition yet, an unparseable definition, no processor configured, or the
 * root missing from the definition entirely all return `true`, matching the two call sites' own
 * fail-open behavior before this extraction.
 */
export function isDataStreamSimpleModeAvailable(
    workflowDefinition: string | undefined,
    workflowNodeName: string | undefined
): boolean {
    if (!workflowNodeName || !workflowDefinition) {
        return true;
    }

    let definition;

    try {
        definition = JSON.parse(workflowDefinition);
    } catch {
        return true;
    }

    const rootTask = getTask({tasks: definition.tasks ?? [], workflowNodeName});

    if (!rootTask?.clusterElements) {
        return true;
    }

    const processorValue = rootTask.clusterElements['processor'];

    if (!processorValue) {
        return true;
    }

    // The processor slot is a single-valued cluster element, but stored/serialized as an array in some
    // paths and a bare object in others -- both shapes are handled the same way the two prior copies
    // of this logic did.
    const processorElement = Array.isArray(processorValue) ? processorValue[0] : processorValue;

    const typeSegments = processorElement?.type?.split('/') ?? [];
    const componentName = typeSegments[0] ?? '';
    const operationName = typeSegments[2] ?? '';

    return componentName === 'dataStreamProcessor' && operationName === 'fieldMapper';
}
