import {DEFAULT_NODE_POSITION} from '@/shared/constants';
import {ComponentDefinitionApi} from '@/shared/middleware/platform/configuration';
import {ComponentDefinitionKeys} from '@/shared/queries/platform/componentDefinitions.queries';
import {ClusterElementItemType, ClusterElementsType, NestedClusterRootComponentDefinitionType} from '@/shared/types';
import {useQueryClient} from '@tanstack/react-query';
import {Edge, Node} from '@xyflow/react';
import {useCallback, useEffect, useMemo, useState} from 'react';
import {useShallow} from 'zustand/react/shallow';

import useWorkflowDataStore from '../../workflow-editor/stores/useWorkflowDataStore';
import useWorkflowEditorStore from '../../workflow-editor/stores/useWorkflowEditorStore';
import {getTask} from '../../workflow-editor/utils/getTask';
import {getFilteredClusterElementTypes, isPlainObject} from '../utils/clusterElementsUtils';
import createClusterElementsEdges from '../utils/createClusterElementsEdges';
import createClusterElementsNodes from '../utils/createClusterElementsNodes';

interface UseClusterElementNodesResultI {
    definitionsReady: boolean;
    edgesByRootId: Record<string, Edge[]>;
    nodesByRootId: Record<string, Node[]>;
}

// Returned when no roots are requested -- box mode off is the default, overwhelmingly common path.
// `workflow.definition` (a dependency of the builder memo below) changes on every workflow save
// regardless of whether box mode is on, so without a fast path keyed on IDENTITY (not just content)
// that memo would hand back a freshly-allocated empty pair on every save. Their identity feeds
// straight into useLayout's own effect dependency array, so a fresh identity here re-runs the whole
// canvas layout on every save -- exactly the churn getTasksStructuralFingerprint exists to prevent
// one level up. See the fast path inside the builder memo.
const EMPTY_EDGES_BY_ROOT_ID: Record<string, Edge[]> = {};
const EMPTY_NODES_BY_ROOT_ID: Record<string, Node[]> = {};

// A cluster root's own component fetch and a nested element's component fetch are attempted-and-
// failed in the SAME "definitions we tried and could not get" set (failedDefinitionKeys below) --
// namespaced by these two key builders rather than by two separate records, since a clusterRootId
// (a workflow node name, e.g. "aiAgent_1") and a componentName (e.g. "aiAgent") could otherwise
// collide.
function rootFailureKey(clusterRootId: string): string {
    return `root:${clusterRootId}`;
}

function nestedFailureKey(componentName: string): string {
    return `nested:${componentName}`;
}

/**
 * Builds the cluster element nodes/edges for each root workflow node name in clusterRootIds, and
 * resolves the component definitions their nested cluster roots need. Shared by the cluster elements
 * dialog (a single root, always the one whose surface is open) and the main canvas box layout (many
 * roots at once, none of which need be the open one).
 *
 * A root's OWN component definition is read from useWorkflowEditorStore.clusterRootComponentDefinitions.
 * The three dialog-side hooks (useClusterElementsLayout, useDataStreamEditor, useAiAgentEditor) each seed
 * their single open root's entry there already, via useGetComponentDefinitionQuery -- a hook, so it
 * cannot be called N times for N roots. This hook instead fetches every requested root's own definition
 * itself, alongside its nested ones, so the main-canvas box path (which has no open dialog and would
 * otherwise never populate the map for any of its roots) works the same way the dialog does. A root
 * whose own definition has not resolved yet produces no nodes/edges at all: building it before the
 * definition is known would draw a narrow, empty box that immediately re-flows once the definition
 * lands.
 */
export default function useClusterElementNodes(clusterRootIds: string[]): UseClusterElementNodesResultI {
    const {
        clusterRootComponentDefinitions,
        nestedClusterRootsComponentDefinitions,
        rootClusterElementNodeData,
        setClusterRootComponentDefinition,
        setNestedClusterRootsComponentDefinitions,
    } = useWorkflowEditorStore(
        useShallow((state) => ({
            clusterRootComponentDefinitions: state.clusterRootComponentDefinitions,
            nestedClusterRootsComponentDefinitions: state.nestedClusterRootsComponentDefinitions,
            rootClusterElementNodeData: state.rootClusterElementNodeData,
            setClusterRootComponentDefinition: state.setClusterRootComponentDefinition,
            setNestedClusterRootsComponentDefinitions: state.setNestedClusterRootsComponentDefinitions,
        }))
    );

    const {workflow} = useWorkflowDataStore(
        useShallow((state) => ({
            workflow: state.workflow,
        }))
    );

    // Every definition fetch (a root's own component, or a nested element's) that was attempted and
    // failed, tracked separately from clusterRootComponentDefinitions/nestedClusterRootsComponentDefinitions
    // (which only ever record a SUCCESS). A permanently-failing fetch must still count as "attempted"
    // -- otherwise rootComponentQueryParameters/definitionsReady below never settle for it, and
    // useLayout's guard blocks the ENTIRE canvas relayout forever over one bad fetch. Fail open
    // instead: that one root/element renders unboxed, everything else proceeds normally. One set for
    // both kinds of failure (see rootFailureKey/nestedFailureKey above) rather than two parallel
    // records, so the readiness computation below has one thing to consult.
    const [failedDefinitionKeys, setFailedDefinitionKeys] = useState<Record<string, true>>({});

    const queryClient = useQueryClient();

    // clusterRootIds is expected to be a fresh array on every render (callers are not required to
    // memoise it -- useClusterElementsLayout does, but a plain inline literal is a reasonable thing
    // to pass too). Every memo below keys off this joined string instead of the array reference, so
    // an unmemoised caller still gets stable results instead of an effect that re-fires -- and
    // re-fetches -- on every render.
    const clusterRootIdsKey = clusterRootIds.join(',');

    const workflowDefinitionTasks = useMemo(() => {
        if (!workflow.definition) {
            return [];
        }

        // A definition with no tasks key at all (e.g. a brand-new workflow) parses to undefined here,
        // not []; getTask assumes an array and throws on undefined.
        return JSON.parse(workflow.definition).tasks || [];
    }, [workflow.definition]);

    const clusterElementsByRootId = useMemo(() => {
        const clusterElementsMap: Record<string, ClusterElementsType> = {};

        for (const clusterRootId of clusterRootIds) {
            const task = getTask({tasks: workflowDefinitionTasks, workflowNodeName: clusterRootId});

            clusterElementsMap[clusterRootId] = task?.clusterElements || {};
        }

        return clusterElementsMap;
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [clusterRootIdsKey, workflowDefinitionTasks]);

    // Definitions are collected for EVERY element, not only those already carrying a clusterElements object.
    // That object is seeded once, when the element is added, so an element added before its component declared
    // child types would otherwise never be recognised as a nested root. The definition is the source of truth;
    // the seeded object only records what has been attached so far. Fetches dedupe by component name.
    const getClusterRootQueryParameters = useCallback(
        (elements: ClusterElementsType): Array<{componentName: string; componentVersion: number}> =>
            Object.values(elements).flatMap((value) => {
                if (Array.isArray(value)) {
                    return value.flatMap((item: ClusterElementItemType) => [
                        {
                            componentName: item.type.split('/')[0],
                            componentVersion: Number(item.type?.split('/')[1]?.replace(/^v/, '')) || 1,
                        },
                        ...getClusterRootQueryParameters(item.clusterElements ?? {}),
                    ]);
                } else if (isPlainObject(value)) {
                    return [
                        {
                            componentName: value.type.split('/')[0],
                            componentVersion: Number(value.type?.split('/')[1]?.replace(/^v/, '')) || 1,
                        },
                        ...getClusterRootQueryParameters(value.clusterElements ?? {}),
                    ];
                }

                return [];
            }),
        []
    );

    const clusterRootQueryParameters = useMemo(
        () =>
            clusterRootIds.flatMap((clusterRootId) =>
                getClusterRootQueryParameters(clusterElementsByRootId[clusterRootId] ?? {})
            ),
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [clusterElementsByRootId, clusterRootIdsKey, getClusterRootQueryParameters]
    );

    // Each requested root's OWN component (not its nested elements) -- skipping any root already
    // present in clusterRootComponentDefinitions (a dialog-side hook already seeded it, or this hook
    // already fetched it on an earlier run) OR already recorded in failedDefinitionKeys (a fetch was
    // attempted and failed; retrying forever is not the fix -- fail open and let that one root render
    // unboxed instead). Depending on both means this recomputes as each fetch resolves or fails, which
    // is what lets the effect below shrink its work to only the roots still genuinely unresolved.
    const rootComponentQueryParameters = useMemo(() => {
        const parameters: Array<{clusterRootId: string; componentName: string; componentVersion: number}> = [];

        for (const clusterRootId of clusterRootIds) {
            if (clusterRootComponentDefinitions[clusterRootId] || failedDefinitionKeys[rootFailureKey(clusterRootId)]) {
                continue;
            }

            const task = getTask({tasks: workflowDefinitionTasks, workflowNodeName: clusterRootId});

            if (!task) {
                continue;
            }

            parameters.push({
                clusterRootId,
                componentName: task.type.split('/')[0],
                componentVersion: Number(task.type?.split('/')[1]?.replace(/^v/, '')) || 1,
            });
        }

        return parameters;
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [clusterRootComponentDefinitions, clusterRootIdsKey, failedDefinitionKeys, workflowDefinitionTasks]);

    const getClusterRootDefinitionQuery = useCallback(
        (roots: Array<{componentName: string; componentVersion: number}>) =>
            roots.map((root) => ({
                componentName: root.componentName,
                componentVersion: root.componentVersion,
                queryFn: () =>
                    new ComponentDefinitionApi().getComponentDefinition({
                        componentName: root.componentName,
                        componentVersion: root.componentVersion,
                    }),
                queryKey: ComponentDefinitionKeys.componentDefinition({
                    componentName: root.componentName,
                    componentVersion: root.componentVersion,
                }),
            })),
        []
    );

    const {edgesByRootId, nodesByRootId} = useMemo(() => {
        // See EMPTY_EDGES_BY_ROOT_ID/EMPTY_NODES_BY_ROOT_ID above: identity matters here, not just
        // content, because this pair's identity is a dependency of useLayout's own layout effect.
        if (clusterRootIdsKey === '') {
            return {edgesByRootId: EMPTY_EDGES_BY_ROOT_ID, nodesByRootId: EMPTY_NODES_BY_ROOT_ID};
        }

        const edges: Record<string, Edge[]> = {};
        const nodes: Record<string, Node[]> = {};

        for (const clusterRootId of clusterRootIds) {
            const task = getTask({tasks: workflowDefinitionTasks, workflowNodeName: clusterRootId});
            const currentRootComponentDefinition = clusterRootComponentDefinitions[clusterRootId];

            nodes[clusterRootId] = [];
            edges[clusterRootId] = [];

            if (!task || !currentRootComponentDefinition || !workflow.definition) {
                continue;
            }

            const clusterElements = clusterElementsByRootId[clusterRootId] ?? {};

            const rootNodes: Node[] = [];

            // Only the root whose surface is open gets its own card here -- every other requested root
            // is assumed to already have its own node elsewhere (the main canvas's own workflow node).
            if (rootClusterElementNodeData && rootClusterElementNodeData.workflowNodeName === clusterRootId) {
                const rootFilteredTypes = getFilteredClusterElementTypes({
                    clusterRootComponentDefinition: currentRootComponentDefinition,
                    isNestedClusterRoot: false,
                    operationName: rootClusterElementNodeData.operationName,
                });

                rootNodes.push({
                    data: {
                        ...rootClusterElementNodeData,
                        clusterElementTypesCount: rootFilteredTypes.length,
                    },
                    id: clusterRootId,
                    position: DEFAULT_NODE_POSITION,
                    type: 'workflow',
                });
            }

            const clusterElementNodes = createClusterElementsNodes({
                clusterElements,
                clusterRootId,
                currentRootComponentDefinition,
                nestedClusterRootsDefinitions: nestedClusterRootsComponentDefinitions || {},
                operationName: rootClusterElementNodeData?.operationName,
            });

            const rootAndElementNodes = [...rootNodes, ...clusterElementNodes];

            nodes[clusterRootId] = rootAndElementNodes;

            edges[clusterRootId] = createClusterElementsEdges({
                clusterRootComponentDefinition: currentRootComponentDefinition,
                clusterRootId,
                nestedClusterRootsDefinitions: nestedClusterRootsComponentDefinitions || {},
                nodes: rootAndElementNodes,
            });
        }

        return {edgesByRootId: edges, nodesByRootId: nodes};
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [
        clusterElementsByRootId,
        clusterRootComponentDefinitions,
        clusterRootIdsKey,
        nestedClusterRootsComponentDefinitions,
        rootClusterElementNodeData,
        workflow.definition,
        workflowDefinitionTasks,
    ]);

    // Per-root, not "is the shared map non-empty": nestedClusterRootsComponentDefinitions is one map
    // shared across every requested root, keyed by component name. With a single root the two framings
    // coincide, but with several roots the map going non-empty because ONE root's nested definition
    // resolved must not mark every other root ready too -- each of THEIR required component names has
    // to actually be a key in the map.
    //
    // A root's OWN definition gates readiness too: rootComponentQueryParameters already excludes any
    // root present in clusterRootComponentDefinitions, so "ready" here means nothing is left in that
    // list still waiting on a fetch. A component is also counted ready if its fetch was attempted and
    // failed (failedDefinitionKeys) -- otherwise one bad nested component blocks definitionsReady, and
    // with it the whole canvas relayout, forever, exactly like an unresolved root would.
    const definitionsReady = useMemo(() => {
        const requiredComponentNames = new Set(clusterRootQueryParameters.map((parameter) => parameter.componentName));

        const nestedDefinitionsReady = Array.from(requiredComponentNames).every(
            (componentName) =>
                nestedClusterRootsComponentDefinitions?.[componentName] !== undefined ||
                failedDefinitionKeys[nestedFailureKey(componentName)]
        );

        return nestedDefinitionsReady && rootComponentQueryParameters.length === 0;
    }, [
        clusterRootQueryParameters,
        failedDefinitionKeys,
        nestedClusterRootsComponentDefinitions,
        rootComponentQueryParameters,
    ]);

    useEffect(() => {
        const processClusterElementsRequirementsMet =
            !!workflow.definition && (clusterRootQueryParameters.length > 0 || rootComponentQueryParameters.length > 0);

        if (!processClusterElementsRequirementsMet) {
            return;
        }

        const getClusterRootComponentDefinitions = async () => {
            // Nested component definitions -- deduped by componentName (a `for` loop skipping a key
            // already in `nestedDefinitions` used to do this dedup; a Map does the same job ahead of
            // the fetch wave instead). Fired as one wave (Promise.all), each with its OWN try/catch,
            // so one bad component cannot delay or fail any other component's fetch -- a single shared
            // try/catch here used to mean one failing component aborted the WHOLE nested batch, which
            // is finding 3's exact failure mode one level down: a nested component's definition
            // failing to fetch left definitionsReady false forever, silently blanking the entire
            // canvas from one failed request. Failures are recorded in failedDefinitionKeys
            // (nestedFailureKey) so definitionsReady can still settle -- that element's root renders
            // unboxed (createClusterElementsNodes has nothing to build it from), everything else
            // proceeds normally.
            const clusterRootDefinitionQueries = getClusterRootDefinitionQuery(clusterRootQueryParameters);
            const uniqueNestedQueriesByComponentName = new Map<string, (typeof clusterRootDefinitionQueries)[number]>();

            for (const query of clusterRootDefinitionQueries) {
                // Skip a component already recorded as failed -- clusterRootQueryParameters, unlike
                // rootComponentQueryParameters, is NOT itself filtered by failedDefinitionKeys (it
                // still has to list every required component so definitionsReady's readiness check
                // below can see them). Without this skip, a permanently-failing component would be
                // refetched (and re-fail, and re-record) on every effect run, and since
                // setFailedDefinitionKeys always returns a NEW object, that write changes
                // rootComponentQueryParameters' identity too (it depends on failedDefinitionKeys) --
                // re-triggering this very effect via ITS OWN dependency array. An infinite loop.
                if (
                    !uniqueNestedQueriesByComponentName.has(query.componentName) &&
                    !failedDefinitionKeys[nestedFailureKey(query.componentName)]
                ) {
                    uniqueNestedQueriesByComponentName.set(query.componentName, query);
                }
            }

            const resolvedNestedDefinitions: Record<string, NestedClusterRootComponentDefinitionType> = {};
            const newlyFailedComponentNames: string[] = [];

            await Promise.all(
                Array.from(uniqueNestedQueriesByComponentName.entries()).map(async ([componentName, query]) => {
                    try {
                        const definition = await queryClient.fetchQuery({
                            queryFn: query.queryFn,
                            queryKey: query.queryKey,
                        });

                        resolvedNestedDefinitions[componentName] = {
                            actionClusterElementTypes: definition.actionClusterElementTypes || {},
                            clusterElementClusterElementTypes: definition.clusterElementClusterElementTypes || {},
                            clusterElementTypes: definition.clusterElementTypes || [],
                        };
                    } catch (error) {
                        console.error(
                            `Error fetching nested cluster root definition for component "${componentName}":`,
                            error
                        );

                        newlyFailedComponentNames.push(componentName);
                    }
                })
            );

            // Merged onto the CURRENT store value (read fresh, not the value this closure captured at
            // render time) rather than a blind replace: clusterRootQueryParameters is rebuilt -- and
            // this whole batch re-fetched, cache-backed -- on every effect run regardless of
            // resolution status, so replacing wholesale with only THIS run's successes would wipe an
            // already-resolved component whenever a later run's fetch for a DIFFERENT component fails.
            if (Object.keys(resolvedNestedDefinitions).length > 0) {
                setNestedClusterRootsComponentDefinitions({
                    ...useWorkflowEditorStore.getState().nestedClusterRootsComponentDefinitions,
                    ...resolvedNestedDefinitions,
                });
            }

            if (newlyFailedComponentNames.length > 0) {
                setFailedDefinitionKeys((previousFailedDefinitionKeys) => {
                    const nextFailedDefinitionKeys = {...previousFailedDefinitionKeys};

                    for (const failedComponentName of newlyFailedComponentNames) {
                        nextFailedDefinitionKeys[nestedFailureKey(failedComponentName)] = true;
                    }

                    return nextFailedDefinitionKeys;
                });
            }

            // Each requested root's OWN definition, resolved alongside its nested ones above. The
            // canvas box path has no open dialog to seed clusterRootComponentDefinitions the way
            // useClusterElementsLayout/useDataStreamEditor/useAiAgentEditor do for the single root
            // they each have open, so this is what populates it for every other root instead.
            //
            // Built straight from rootComponentQueryParameters (not zipped afterwards against a
            // separately-built query array by index) so each fetch descriptor is paired with its
            // clusterRootId by construction, not by two arrays happening to stay the same length.
            //
            // Fired as one wave (Promise.all), each with its OWN try/catch, so one root's fetch
            // failing cannot delay or fail the others -- and a transient network failure degrades that
            // one box (rendered unboxed) rather than blocking definitionsReady, and with it the whole
            // canvas relayout, forever. Fail open, warn once: this codebase's established precedent
            // for exactly this shape (see WorkflowVariablesResolver in CLAUDE.md).
            const rootDefinitionFetches = rootComponentQueryParameters.map((parameter) => ({
                clusterRootId: parameter.clusterRootId,
                queryFn: () =>
                    new ComponentDefinitionApi().getComponentDefinition({
                        componentName: parameter.componentName,
                        componentVersion: parameter.componentVersion,
                    }),
                queryKey: ComponentDefinitionKeys.componentDefinition({
                    componentName: parameter.componentName,
                    componentVersion: parameter.componentVersion,
                }),
            }));

            const newlyFailedClusterRootIds: string[] = [];

            await Promise.all(
                rootDefinitionFetches.map(async (rootDefinitionFetch) => {
                    try {
                        const definition = await queryClient.fetchQuery({
                            queryFn: rootDefinitionFetch.queryFn,
                            queryKey: rootDefinitionFetch.queryKey,
                        });

                        setClusterRootComponentDefinition(rootDefinitionFetch.clusterRootId, definition);
                    } catch (error) {
                        console.error(
                            `Error fetching component definition for cluster root "${rootDefinitionFetch.clusterRootId}":`,
                            error
                        );

                        newlyFailedClusterRootIds.push(rootDefinitionFetch.clusterRootId);
                    }
                })
            );

            if (newlyFailedClusterRootIds.length > 0) {
                setFailedDefinitionKeys((previousFailedDefinitionKeys) => {
                    const nextFailedDefinitionKeys = {...previousFailedDefinitionKeys};

                    for (const failedClusterRootId of newlyFailedClusterRootIds) {
                        nextFailedDefinitionKeys[rootFailureKey(failedClusterRootId)] = true;
                    }

                    return nextFailedDefinitionKeys;
                });
            }
        };

        getClusterRootComponentDefinitions();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [
        workflow.definition,
        clusterRootQueryParameters,
        rootComponentQueryParameters,
        getClusterRootDefinitionQuery,
        setClusterRootComponentDefinition,
        setNestedClusterRootsComponentDefinitions,
    ]);

    return {definitionsReady, edgesByRootId, nodesByRootId};
}
