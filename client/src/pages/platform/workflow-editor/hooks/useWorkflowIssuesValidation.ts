import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import {useValidateWorkflowQuery} from '@/shared/middleware/graphql';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {useEffect} from 'react';
import {useShallow} from 'zustand/react/shallow';

import useWorkflowDataStore from '../stores/useWorkflowDataStore';
import useWorkflowIssuesStore, {WorkflowIssueI} from '../stores/useWorkflowIssuesStore';

export default function useWorkflowIssuesValidation(): void {
    const workflow = useWorkflowDataStore((state) => state.workflow);
    const currentEnvironmentId = useEnvironmentStore((state) => state.currentEnvironmentId);
    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);
    const {clearLiveIssues, setValidatorIssues} = useWorkflowIssuesStore(
        useShallow((state) => ({
            clearLiveIssues: state.clearLiveIssues,
            setValidatorIssues: state.setValidatorIssues,
        }))
    );

    // The workspace is what the server authorizes an unsaved definition against — a definition with no workflow id
    // names no stored resource, so without it the query is refused rather than answered. Embedded workflows have no
    // workspace and always carry an id, which is gated on instead.
    const {data} = useValidateWorkflowQuery(
        {
            environmentId: currentEnvironmentId,
            workflowDefinition: workflow.definition!,
            workflowId: workflow.id,
            workspaceId: currentWorkspaceId,
        },
        {enabled: !!workflow.definition}
    );

    useEffect(() => {
        clearLiveIssues();
        setValidatorIssues([]);
    }, [clearLiveIssues, setValidatorIssues, workflow.definition]);

    useEffect(() => {
        if (!data) {
            return;
        }

        const validatorIssues: Array<WorkflowIssueI> = data.validateWorkflow.nodeIssues.map((nodeIssue) => ({
            kind: nodeIssue.kind,
            message: nodeIssue.message,
            nodeName: nodeIssue.nodeName,
            propertyPath: nodeIssue.propertyPath ?? undefined,
            severity: nodeIssue.severity,
            source: 'VALIDATOR',
        }));

        setValidatorIssues(validatorIssues);
    }, [data, setValidatorIssues]);
}
