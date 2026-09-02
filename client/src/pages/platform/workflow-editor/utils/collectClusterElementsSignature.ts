import {NodeDataType} from '@/shared/types';

import {getNestedClusterElements} from '../../cluster-element-editor/utils/clusterElementsUtils';

/**
 * Builds a structural signature for one cluster root's attached elements: each element's identity
 * (`name`, `type`, `label`) and where it sits (`metadata.ui.nodePosition`), recursing into nested
 * cluster roots. Sibling of `collectGraphLayoutSignature` in `useLayout`, for the same reason: a box
 * is sized from where its elements sit, so an element's identity or position is what should trigger
 * the outer canvas to relayout.
 *
 * `label` is in because a cluster element's rendered name comes from the node data this signature
 * gates the rebuild of -- unlike an ordinary task, whose label `getNodeLabel` re-reads live from the
 * workflow store on every render.
 *
 * Deliberately excludes `parameters` and `connections` (both present on `ClusterElementItemType`):
 * those are exactly what changes on every keystroke in a cluster element's own property form, and
 * folding them in here would fire the fingerprint on every debounced property save -- the opposite
 * of what `getTasksStructuralFingerprint` exists for. See the two guarded fixtures in
 * `getTasksStructuralFingerprint.test.ts` ("should produce the same fingerprint for tasks differing
 * only in parameter values" and its cluster-element counterpart).
 *
 * Lives in its own module rather than in `useLayout`, because `useClusterElementNodes` keys its node
 * builder on it and `useLayout` imports that hook -- sharing it the other way round would be a cycle.
 */
export default function collectClusterElementsSignature(value: unknown): string {
    if (Array.isArray(value)) {
        return value.map((item) => collectClusterElementsSignature(item)).join(',');
    }

    if (!value || typeof value !== 'object') {
        return '';
    }

    const record = value as Record<string, unknown>;

    // A single element (carries its own name/type) vs. a slot map (slot key -> element/array/null). The workflow
    // definition names an element `name` and nests under `clusterElements`; a workflow task names it
    // `workflowNodeName` and nests under `extensions.clusterElements` -- both shapes must sign the same.
    const elementName = typeof record.name === 'string' ? record.name : record.workflowNodeName;

    if (typeof elementName === 'string' && typeof record.type === 'string') {
        const nodePosition = (record.metadata as NodeDataType['metadata'])?.ui?.nodePosition;
        const positionSignature = nodePosition ? `${nodePosition.x},${nodePosition.y}` : '';
        const nestedSignature = collectClusterElementsSignature(getNestedClusterElements(record));
        const labelSignature = typeof record.label === 'string' ? record.label : '';

        return `${elementName}:${record.type}:${labelSignature}@${positionSignature}${nestedSignature ? `[${nestedSignature}]` : ''}`;
    }

    return Object.keys(record)
        .sort()
        .map((key) => `${key}=${collectClusterElementsSignature(record[key])}`)
        .join('|');
}
