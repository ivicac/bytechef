import useWorkflowDataStore from '@/pages/platform/workflow-editor/stores/useWorkflowDataStore';
import useWorkflowEditorStore from '@/pages/platform/workflow-editor/stores/useWorkflowEditorStore';
import {ComponentDefinition} from '@/shared/middleware/platform/configuration';
import {QueryClient, QueryClientProvider} from '@tanstack/react-query';
import {act, renderHook, waitFor} from '@testing-library/react';
import {ReactNode} from 'react';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import useClusterElementNodes from './useClusterElementNodes';

// ComponentDefinition declares clusterElement/clusterRoot/connectionRequired as required (non-optional)
// fields -- the brief's fixture omitted them, which would leave this test not actually typechecking as
// a real cluster root definition. Hoisted (rather than a plain module const) because the mock factory
// below needs it and vi.mock calls hoist above ordinary const declarations -- see the "Vitest mock
// factory hoisting" note in CLAUDE.md.
const {AI_AGENT_DEFINITION} = vi.hoisted(() => {
    const AI_AGENT_DEFINITION: ComponentDefinition = {
        clusterElement: false,
        clusterElementTypes: [
            {label: 'Model', multipleElements: false, name: 'MODEL'},
            {label: 'Tools', multipleElements: true, name: 'TOOLS'},
        ],
        clusterRoot: true,
        connectionRequired: false,
        name: 'aiAgent',
        version: 1,
    };

    return {AI_AGENT_DEFINITION};
});

// getClusterRootQueryParameters queries a component definition for EVERY filled cluster element, not
// only ones that turn out to be nested roots -- see the comment preserved in useClusterElementNodes.ts.
// The filled MODEL slot below is 'openai' (and, for the two-root test, 'anthropic'), so those
// definitions must resolve for definitionsReady to become true. Neither declares clusterElementTypes
// of its own, so both stay leaves. 'aiAgent' is the ROOT's own component -- fetched whenever a test
// clears clusterRootComponentDefinitions, exercising the path useClusterElementNodes now covers itself.
vi.mock('@/shared/middleware/platform/configuration', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@/shared/middleware/platform/configuration')>()),
    ComponentDefinitionApi: class {
        getComponentDefinition({componentName}: {componentName: string}) {
            if (componentName === 'openai' || componentName === 'anthropic') {
                return Promise.resolve({clusterElementTypes: [], name: componentName, version: 1});
            }

            if (componentName === 'aiAgent') {
                return Promise.resolve(AI_AGENT_DEFINITION);
            }

            return Promise.reject(new Error(`Unexpected component: ${componentName}`));
        }
    },
}));

const WORKFLOW_DEFINITION = JSON.stringify({
    tasks: [
        {
            clusterElements: {
                model: {label: 'GPT', name: 'model_1', type: 'openai/v1/model'},
            },
            label: 'AI Agent',
            name: 'aiAgent_1',
            type: 'aiAgent/v1/chat',
        },
    ],
});

const WORKFLOW_DEFINITION_TWO_ROOTS = JSON.stringify({
    tasks: [
        {
            clusterElements: {model: {label: 'GPT', name: 'model_1', type: 'openai/v1/model'}},
            label: 'AI Agent 1',
            name: 'aiAgent_1',
            type: 'aiAgent/v1/chat',
        },
        {
            clusterElements: {model: {label: 'Claude', name: 'model_2', type: 'anthropic/v1/model'}},
            label: 'AI Agent 2',
            name: 'aiAgent_2',
            type: 'aiAgent/v1/chat',
        },
    ],
});

// A second root whose OWN component ('brokenAgent') the mock above rejects, unlike its MODEL slot
// ('openai'), which resolves fine -- isolating a root-definition failure from a nested one.
const WORKFLOW_DEFINITION_WITH_BROKEN_ROOT = JSON.stringify({
    tasks: [
        {
            clusterElements: {model: {label: 'GPT', name: 'model_1', type: 'openai/v1/model'}},
            label: 'AI Agent 1',
            name: 'aiAgent_1',
            type: 'aiAgent/v1/chat',
        },
        {
            clusterElements: {model: {label: 'GPT', name: 'model_2', type: 'openai/v1/model'}},
            label: 'Broken Agent',
            name: 'brokenAgent_1',
            type: 'brokenAgent/v1/chat',
        },
    ],
});

// Two healthy roots (own component 'aiAgent', resolves fine) whose MODEL slots point at DIFFERENT
// nested components -- aiAgent_1 at 'openai' (resolves), aiAgent_2 at 'brokenModel' (the mock above
// rejects) -- isolating a nested-definition failure from a root-definition one.
const WORKFLOW_DEFINITION_WITH_BROKEN_NESTED_MODEL = JSON.stringify({
    tasks: [
        {
            clusterElements: {model: {label: 'GPT', name: 'model_1', type: 'openai/v1/model'}},
            label: 'AI Agent 1',
            name: 'aiAgent_1',
            type: 'aiAgent/v1/chat',
        },
        {
            clusterElements: {model: {label: 'Broken', name: 'model_2', type: 'brokenModel/v1/model'}},
            label: 'AI Agent 2',
            name: 'aiAgent_2',
            type: 'aiAgent/v1/chat',
        },
    ],
});

// A stable, single-root array: the hook itself works with an unmemoised array too, but reusing one
// reference here keeps this test focused on the hook's own logic rather than on render churn.
const CLUSTER_ROOT_IDS = ['aiAgent_1'];
const TWO_CLUSTER_ROOT_IDS = ['aiAgent_1', 'aiAgent_2'];
const HEALTHY_AND_BROKEN_ROOT_IDS = ['aiAgent_1', 'brokenAgent_1'];
const EMPTY_ROOT_IDS: string[] = [];

describe('useClusterElementNodes', () => {
    let queryClient: QueryClient;

    function wrapper({children}: {children: ReactNode}) {
        return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
    }

    beforeEach(() => {
        // retry: false -- several tests deliberately reject a fetch to exercise the fail-open path;
        // React Query's default retry (3 attempts, exponential backoff) would otherwise leave each of
        // those tests waiting several real seconds per rejected query before the catch ever runs.
        queryClient = new QueryClient({defaultOptions: {queries: {retry: false}}});

        useWorkflowDataStore.setState({
            workflow: {definition: WORKFLOW_DEFINITION, id: 'workflow_1'},
        } as Parameters<typeof useWorkflowDataStore.setState>[0]);
        useWorkflowEditorStore.setState({
            clusterRootComponentDefinitions: {aiAgent_1: AI_AGENT_DEFINITION},
            nestedClusterRootsComponentDefinitions: {},
        });
    });

    it('builds one node per declared element type, all parented to the root', async () => {
        const {result} = renderHook(() => useClusterElementNodes(CLUSTER_ROOT_IDS), {wrapper});

        await waitFor(() => {
            expect(result.current.definitionsReady).toBe(true);
        });

        const nodeIds = result.current.nodesByRootId.aiAgent_1.map((node) => node.id);

        // The filled MODEL slot renders its element; the empty multi-valued TOOLS slot renders a
        // placeholder. Both parent to the root. No root card is included here -- that is only added
        // for the root whose surface is open (rootClusterElementNodeData), which this test never sets.
        expect(nodeIds).toContain('model_1');
        expect(result.current.nodesByRootId.aiAgent_1.every((node) => node.parentId === 'aiAgent_1')).toBe(true);
        expect(result.current.nodesByRootId.aiAgent_1.some((node) => node.type === 'placeholder')).toBe(true);
    });

    it('returns no nodes at all for a root whose own definition has not resolved yet', () => {
        useWorkflowEditorStore.setState({clusterRootComponentDefinitions: {}});

        const {result} = renderHook(() => useClusterElementNodes(CLUSTER_ROOT_IDS), {wrapper});

        expect(result.current.nodesByRootId.aiAgent_1).toEqual([]);
    });

    it("fetches a root's own component definition itself when nothing has pre-seeded it, the main-canvas box path", async () => {
        // The cluster elements dialog seeds clusterRootComponentDefinitions itself (useClusterElementsLayout /
        // useDataStreamEditor / useAiAgentEditor), which is why every other test in this file pre-seeds it in
        // beforeEach too. The main canvas has no dialog open for a box-mode root, so this hook must fetch the
        // root's own definition itself -- without that, the root's entry would never resolve and the canvas
        // would eventually report readiness (from the nested 'openai' fetch alone) while never actually
        // building a box, since nodesByRootId requires BOTH the task and its own component definition.
        useWorkflowEditorStore.setState({clusterRootComponentDefinitions: {}});

        const {result} = renderHook(() => useClusterElementNodes(CLUSTER_ROOT_IDS), {wrapper});

        await waitFor(() => {
            expect(result.current.definitionsReady).toBe(true);
        });

        expect(useWorkflowEditorStore.getState().clusterRootComponentDefinitions.aiAgent_1).toEqual(
            AI_AGENT_DEFINITION
        );
        expect(result.current.nodesByRootId.aiAgent_1.map((node) => node.id)).toContain('model_1');
    });

    it('is not ready when the shared nested-definitions map holds only an unrelated component', () => {
        // The bug this guards against: definitionsReady used to mean "nestedClusterRootsComponentDefinitions
        // is non-empty", not "every required component is in it". A resolved definition for some other,
        // unrelated component (from a different root's fetch) would already make the map non-empty here,
        // which the old check read as "ready" even though 'openai' -- what THIS root's MODEL slot actually
        // needs -- was never fetched.
        useWorkflowEditorStore.setState({
            nestedClusterRootsComponentDefinitions: {
                unrelatedComponent: {
                    actionClusterElementTypes: {},
                    clusterElementClusterElementTypes: {},
                    clusterElementTypes: [],
                },
            },
        });

        const {result} = renderHook(() => useClusterElementNodes(CLUSTER_ROOT_IDS), {wrapper});

        expect(result.current.definitionsReady).toBe(false);
    });

    it('resolves nested definitions independently across multiple roots', async () => {
        useWorkflowDataStore.setState({
            workflow: {definition: WORKFLOW_DEFINITION_TWO_ROOTS, id: 'workflow_1'},
        } as Parameters<typeof useWorkflowDataStore.setState>[0]);
        useWorkflowEditorStore.setState({
            clusterRootComponentDefinitions: {aiAgent_1: AI_AGENT_DEFINITION, aiAgent_2: AI_AGENT_DEFINITION},
            nestedClusterRootsComponentDefinitions: {},
        });

        const {result} = renderHook(() => useClusterElementNodes(TWO_CLUSTER_ROOT_IDS), {wrapper});

        // Neither root's nested fetch (for 'openai' and 'anthropic' respectively) has resolved yet.
        expect(result.current.definitionsReady).toBe(false);

        await waitFor(() => {
            expect(result.current.definitionsReady).toBe(true);
        });

        expect(result.current.nodesByRootId.aiAgent_1.map((node) => node.id)).toContain('model_1');
        expect(result.current.nodesByRootId.aiAgent_2.map((node) => node.id)).toContain('model_2');
    });

    // The regression this guards: useClusterElementNodes is called unconditionally by useLayout, even
    // with box mode off (clusterRootIds always []). workflow.definition (a dependency of the builder
    // memo) changes on every workflow save regardless -- a save unrelated to any cluster root must not
    // hand back a freshly-allocated {} pair, because THIS pair's identity is itself a dependency of
    // useLayout's own layout effect, and a fresh identity there re-runs the whole canvas layout.
    it('returns the same nodesByRootId/edgesByRootId references across renders when no roots are requested', () => {
        const {rerender, result} = renderHook(() => useClusterElementNodes(EMPTY_ROOT_IDS), {wrapper});

        const firstNodesByRootId = result.current.nodesByRootId;
        const firstEdgesByRootId = result.current.edgesByRootId;

        act(() => {
            // Stands in for an unrelated workflow save (e.g. typing in a property field elsewhere).
            useWorkflowDataStore.setState({
                workflow: {definition: WORKFLOW_DEFINITION_TWO_ROOTS, id: 'workflow_1'},
            } as Parameters<typeof useWorkflowDataStore.setState>[0]);
        });

        rerender();

        expect(result.current.nodesByRootId).toBe(firstNodesByRootId);
        expect(result.current.edgesByRootId).toBe(firstEdgesByRootId);
    });

    // The regression this guards: a shared try/catch used to mean ANY failure -- nested or root --
    // left rootComponentQueryParameters permanently non-empty for the failed root, so
    // definitionsReady never settled and useLayout's guard blocked the ENTIRE canvas relayout forever,
    // not just that one root. Fail open instead: the broken root renders unboxed, everything else
    // (including its OWN nested 'openai' fetch, and the healthy sibling root) proceeds normally.
    it("fails open when one root's own definition fetch rejects: that root stays unboxed and everything else still settles", async () => {
        useWorkflowDataStore.setState({
            workflow: {definition: WORKFLOW_DEFINITION_WITH_BROKEN_ROOT, id: 'workflow_1'},
        } as Parameters<typeof useWorkflowDataStore.setState>[0]);
        // Neither root's own definition is pre-seeded -- both must be fetched by this hook itself.
        useWorkflowEditorStore.setState({
            clusterRootComponentDefinitions: {},
            nestedClusterRootsComponentDefinitions: {},
        });

        const {result} = renderHook(() => useClusterElementNodes(HEALTHY_AND_BROKEN_ROOT_IDS), {wrapper});

        await waitFor(() => {
            expect(result.current.definitionsReady).toBe(true);
        });

        expect(useWorkflowEditorStore.getState().clusterRootComponentDefinitions.aiAgent_1).toEqual(
            AI_AGENT_DEFINITION
        );
        expect(useWorkflowEditorStore.getState().clusterRootComponentDefinitions.brokenAgent_1).toBeUndefined();
        expect(result.current.nodesByRootId.aiAgent_1.map((node) => node.id)).toContain('model_1');
        expect(result.current.nodesByRootId.brokenAgent_1).toEqual([]);
    });

    // The regression this guards: the nested loop used to abort ITS ENTIRE BATCH on a first rejection
    // (one shared try/catch around a sequential for-loop), so one bad nested component took out every
    // OTHER nested definition in the same run too -- and, same as the root case, left
    // definitionsReady false forever, blocking the whole canvas from laying out at all.
    //
    // Unlike a failed ROOT definition (which blanks that root -- its own definition never resolves,
    // so createClusterElementsNodes has nothing to build from), a failed NESTED definition does not
    // remove the element's own node: createClusterElementsNodes always creates the element's node from
    // its OWN clusterElements entry, and only consults nestedClusterRootsDefinitions to decide whether
    // to additionally recurse into that element's children (see getFilteredClusterElementTypes, which
    // returns [] rather than throwing when the nested definition is missing). So "reaches the canvas
    // unboxed rather than vanishing" plays out here as: the affected root still builds and still gets
    // its own box, with model_2 present as an ordinary (non-nested) element -- what actually vanished
    // under the bug was the WHOLE canvas (via definitionsReady), not specifically this element.
    it("fails open when one nested element's definition fetch rejects: definitionsReady still settles and both roots still build", async () => {
        useWorkflowDataStore.setState({
            workflow: {definition: WORKFLOW_DEFINITION_WITH_BROKEN_NESTED_MODEL, id: 'workflow_1'},
        } as Parameters<typeof useWorkflowDataStore.setState>[0]);
        useWorkflowEditorStore.setState({
            clusterRootComponentDefinitions: {aiAgent_1: AI_AGENT_DEFINITION, aiAgent_2: AI_AGENT_DEFINITION},
            nestedClusterRootsComponentDefinitions: {},
        });

        const {result} = renderHook(() => useClusterElementNodes(TWO_CLUSTER_ROOT_IDS), {wrapper});

        await waitFor(() => {
            expect(result.current.definitionsReady).toBe(true);
        });

        // The healthy root's nested element still resolved and still builds.
        expect(useWorkflowEditorStore.getState().nestedClusterRootsComponentDefinitions.openai).toBeDefined();
        expect(result.current.nodesByRootId.aiAgent_1.map((node) => node.id)).toContain('model_1');

        // The broken component's definition genuinely never resolved (this is fail-OPEN, not a
        // silently-faked success) -- yet the affected root still reaches the canvas, model_2 included.
        expect(useWorkflowEditorStore.getState().nestedClusterRootsComponentDefinitions.brokenModel).toBeUndefined();
        expect(result.current.nodesByRootId.aiAgent_2.map((node) => node.id)).toContain('model_2');
    });
});
