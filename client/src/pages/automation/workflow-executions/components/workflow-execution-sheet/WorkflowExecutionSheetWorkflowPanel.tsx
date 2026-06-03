import LoadingIcon from '@/components/LoadingIcon';
import PageLoader from '@/components/PageLoader';
import {useWorkflowLayout} from '@/pages/platform/workflow-editor/hooks/useWorkflowLayout';
import useWorkflowDataStore from '@/pages/platform/workflow-editor/stores/useWorkflowDataStore';
import {WorkflowExecution} from '@/shared/middleware/automation/workflow/execution';
import {useGetWorkflowQuery} from '@/shared/queries/automation/workflows.queries';
import {ReactFlowProvider} from '@xyflow/react';
import {Suspense, lazy, useEffect, useState} from 'react';

import useWorkflowExecutionSheetWorkflowPanel from '../../hooks/useWorkflowExecutionSheetWorkflowPanel';

const WorkflowEditor = lazy(() => import('@/pages/platform/workflow-editor/components/WorkflowEditor'));

const WorkflowExecutionSheetWorkflowPanel = ({workflowExecution}: {workflowExecution: WorkflowExecution}) => {
    const {workflow} = workflowExecution;

    const [ready, setReady] = useState(false);

    const {canvasWidth, rootDivRef} = useWorkflowExecutionSheetWorkflowPanel();

    const {
        componentDefinitions,
        componentsError,
        componentsIsLoading,
        taskDispatcherDefinitions,
        taskDispatcherDefinitionsError,
        taskDispatcherDefinitionsLoading,
    } = useWorkflowLayout();

    const {data: workflowDetails, isLoading: isWorkflowDetailsLoading} = useGetWorkflowQuery(
        workflow!.id as string,
        !!workflow?.id
    );

    useEffect(() => {
        setReady(false);

        const frameId = requestAnimationFrame(() => {
            setReady(true);
        });

        return () => {
            cancelAnimationFrame(frameId);

            useWorkflowDataStore.getState().reset();
        };
    }, [workflowExecution.id]);

    return (
        <div className="flex size-full flex-col" ref={rootDivRef}>
            {ready && (
                <ReactFlowProvider>
                    <PageLoader
                        errors={[componentsError, taskDispatcherDefinitionsError]}
                        loading={componentsIsLoading || taskDispatcherDefinitionsLoading || isWorkflowDetailsLoading}
                    >
                        {componentDefinitions && taskDispatcherDefinitions && workflow && (
                            // Local Suspense boundary for the lazily-imported WorkflowEditor chunk. Without it,
                            // the first-time chunk load suspends up to the nearest ancestor boundary — in the AI
                            // Hub that is the route-level LazyLoadWrapper, so the entire page would blank out
                            // (reading as a full-page reload). Containing it here keeps the flash inside the panel.
                            <Suspense
                                fallback={
                                    <div className="flex size-full items-center justify-center">
                                        <LoadingIcon className="size-6" />
                                    </div>
                                }
                            >
                                <WorkflowEditor
                                    componentDefinitions={componentDefinitions}
                                    customCanvasWidth={canvasWidth}
                                    readOnlyWorkflow={workflowDetails}
                                    taskDispatcherDefinitions={taskDispatcherDefinitions}
                                />
                            </Suspense>
                        )}
                    </PageLoader>
                </ReactFlowProvider>
            )}
        </div>
    );
};

export default WorkflowExecutionSheetWorkflowPanel;
