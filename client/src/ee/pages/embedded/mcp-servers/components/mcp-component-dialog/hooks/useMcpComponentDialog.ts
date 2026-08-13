import {
    ComponentDefinition,
    McpComponent,
    useAuthoritiesQuery,
    useCreateEmbeddedMcpComponentMutation,
    useEmbeddedMcpToolsByComponentIdQuery,
    useUpdateEmbeddedMcpComponentMutation,
} from '@/shared/middleware/graphql';
import {getRoleLabel} from '@/shared/util/role-utils';
import {useQueryClient} from '@tanstack/react-query';
import {useMemo, useState} from 'react';

import {SelectedToolI} from './useMcpComponentDialogToolSelectionStep';

export type StepType = 'components' | 'tools';

export interface McpIntegrationComponentOptionI extends ComponentDefinition {
    id?: string;
    integrationId?: string;
    version: number | null;
}

const isIntegrationLocked = (mcpComponent?: McpComponent, integrationUnavailable?: boolean) =>
    !!mcpComponent?.integrationId && !integrationUnavailable;

const getInitialStep = (mcpComponent?: McpComponent, integrationUnavailable?: boolean): StepType =>
    isIntegrationLocked(mcpComponent, integrationUnavailable) ? 'tools' : 'components';

const getInitialSelectedComponent = (
    mcpComponent?: McpComponent,
    integrationUnavailable?: boolean
): McpIntegrationComponentOptionI | null =>
    mcpComponent?.integrationId && isIntegrationLocked(mcpComponent, integrationUnavailable)
        ? {
              integrationId: mcpComponent.integrationId,
              name: mcpComponent.componentName,
              title: mcpComponent.title || mcpComponent.componentName,
              version: mcpComponent.componentVersion,
          }
        : null;

const useMcpComponentDialog = ({
    integrationUnavailable,
    mcpComponent,
    mcpServerId,
    onOpenChange,
    open,
}: {
    integrationUnavailable?: boolean;
    mcpComponent?: McpComponent;
    mcpServerId: string;
    open?: boolean;
    onOpenChange?: (open: boolean) => void;
}) => {
    const [currentStep, setCurrentStep] = useState<StepType>(getInitialStep(mcpComponent, integrationUnavailable));
    const [selectedComponent, setSelectedComponent] = useState<McpIntegrationComponentOptionI | null>(
        getInitialSelectedComponent(mcpComponent, integrationUnavailable)
    );
    const [selectedTools, setSelectedTools] = useState<SelectedToolI[]>([]);
    const [requiredAuthorities, setRequiredAuthorities] = useState<string[]>(mcpComponent?.requiredAuthorities ?? []);

    // The authority list is the same one the user-management dialogs offer, so an MCP component can only require an
    // authority that actually exists. The query is ADMIN-only server-side; for a non-admin it simply yields no options
    // rather than failing the dialog, and any authority already saved on the component stays selected regardless.
    const {data: authoritiesData, isLoading: authoritiesLoading} = useAuthoritiesQuery({});

    const authorityOptions = useMemo(() => {
        const authorities = new Set([...(authoritiesData?.authorities ?? []), ...requiredAuthorities]);

        return Array.from(authorities)
            .map((authority) => ({label: getRoleLabel(authority), value: authority}))
            .sort((option, otherOption) => option.label.localeCompare(otherOption.label));
    }, [authoritiesData, requiredAuthorities]);

    const {data: existingTools} = useEmbeddedMcpToolsByComponentIdQuery(
        {
            mcpComponentId: mcpComponent?.id?.toString() || '',
        },
        {
            enabled: !!mcpComponent?.id && open,
        }
    );

    const queryClient = useQueryClient();

    const invalidateMcpQueries = () => {
        queryClient.invalidateQueries({queryKey: ['embeddedMcpComponentsByServerId']});
        queryClient.invalidateQueries({queryKey: ['embeddedMcpServers']});
    };

    const createEmbeddedMcpComponentMutation = useCreateEmbeddedMcpComponentMutation({
        onSuccess: invalidateMcpQueries,
    });

    const updateEmbeddedMcpComponentMutation = useUpdateEmbeddedMcpComponentMutation({
        onSuccess: invalidateMcpQueries,
    });

    const handleComponentSelect = (component: McpIntegrationComponentOptionI) => {
        setSelectedComponent(component);
        setSelectedTools([]);
        setCurrentStep('tools');
    };

    const handleClose = () => {
        if (onOpenChange) {
            onOpenChange(false);
        }

        setCurrentStep(getInitialStep(mcpComponent, integrationUnavailable));
        setSelectedComponent(getInitialSelectedComponent(mcpComponent, integrationUnavailable));
        setRequiredAuthorities(mcpComponent?.requiredAuthorities ?? []);

        if (!mcpComponent) {
            setSelectedTools([]);
        }
    };

    const handleSaveSuccess = () => {
        if (onOpenChange) {
            onOpenChange(false);
        }

        setCurrentStep(getInitialStep(mcpComponent, integrationUnavailable));

        if (!mcpComponent) {
            setSelectedComponent(null);
        }

        setSelectedTools([]);
        setRequiredAuthorities(mcpComponent?.requiredAuthorities ?? []);
    };

    const handleSave = () => {
        if (!selectedComponent?.integrationId) {
            return;
        }

        if (mcpComponent?.id) {
            updateEmbeddedMcpComponentMutation.mutate(
                {
                    id: mcpComponent.id.toString(),
                    input: {
                        componentName: selectedComponent.name,
                        componentVersion: selectedComponent.version ?? 1,
                        mcpServerId,
                        requiredAuthorities,
                        tools: selectedTools.map((tool) => ({
                            name: tool.name,
                            parameters: {},
                        })),
                        version: mcpComponent.version,
                    },
                    integrationId: isIntegrationLocked(mcpComponent, integrationUnavailable)
                        ? undefined
                        : selectedComponent.integrationId,
                },
                {onSuccess: handleSaveSuccess}
            );
        } else {
            createEmbeddedMcpComponentMutation.mutate(
                {
                    input: {
                        componentName: selectedComponent.name,
                        componentVersion: selectedComponent.version ?? 1,
                        mcpServerId,
                        requiredAuthorities,
                        tools: selectedTools.map((tool) => ({
                            name: tool.name,
                            parameters: {},
                        })),
                    },
                    integrationId: selectedComponent.integrationId,
                },
                {onSuccess: handleSaveSuccess}
            );
        }
    };

    const handleBack = () => {
        if (isIntegrationLocked(mcpComponent, integrationUnavailable)) {
            handleClose();
        } else {
            setCurrentStep('components');
            setSelectedComponent(null);
            setSelectedTools([]);
        }
    };

    const handleOpenChange = (newOpen: boolean) => {
        if (onOpenChange) {
            onOpenChange(newOpen);
        }

        if (!newOpen) {
            setCurrentStep(getInitialStep(mcpComponent, integrationUnavailable));
            setSelectedComponent(getInitialSelectedComponent(mcpComponent, integrationUnavailable));
            setRequiredAuthorities(mcpComponent?.requiredAuthorities ?? []);

            if (!mcpComponent) {
                setSelectedTools([]);
            }
        }
    };

    return {
        authoritiesLoading,
        authorityOptions,
        currentStep,
        existingTools,
        handleBack,
        handleClose,
        handleComponentSelect,
        handleOpenChange,
        handleSave,
        requiredAuthorities,
        selectedComponent,
        selectedTools,
        setRequiredAuthorities,
        setSelectedTools,
    };
};

export default useMcpComponentDialog;
