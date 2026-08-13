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

const useMcpComponentDialog = ({
    mcpComponent,
    mcpServerId,
    onOpenChange,
    open,
}: {
    mcpComponent?: McpComponent;
    mcpServerId: string;
    open?: boolean;
    onOpenChange?: (open: boolean) => void;
}) => {
    const [currentStep, setCurrentStep] = useState<StepType>(mcpComponent ? 'tools' : 'components');
    const [selectedComponent, setSelectedComponent] = useState<ComponentDefinition | null>(
        mcpComponent
            ? ({
                  name: mcpComponent.componentName,
                  title: mcpComponent.title || mcpComponent.componentName,
                  version: mcpComponent.componentVersion,
              } as ComponentDefinition)
            : null
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

    const handleComponentSelect = (component: ComponentDefinition) => {
        setSelectedComponent(component);
        setSelectedTools([]);
        setCurrentStep('tools');
    };

    const handleClose = () => {
        if (onOpenChange) {
            onOpenChange(false);
        }

        setCurrentStep(mcpComponent ? 'tools' : 'components');
        setSelectedComponent(
            mcpComponent
                ? ({
                      name: mcpComponent.componentName,
                      title: mcpComponent.title || mcpComponent.componentName,
                      version: mcpComponent.componentVersion,
                  } as ComponentDefinition)
                : null
        );
        setRequiredAuthorities(mcpComponent?.requiredAuthorities ?? []);

        if (!mcpComponent) {
            setSelectedTools([]);
        }
    };

    const handleSaveSuccess = () => {
        if (onOpenChange) {
            onOpenChange(false);
        }

        setCurrentStep(mcpComponent ? 'tools' : 'components');

        if (!mcpComponent) {
            setSelectedComponent(null);
        }

        setSelectedTools([]);
        setRequiredAuthorities(mcpComponent?.requiredAuthorities ?? []);
    };

    const handleSave = () => {
        if (!selectedComponent) {
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
                },
                {onSuccess: handleSaveSuccess}
            );
        }
    };

    const handleBack = () => {
        if (mcpComponent) {
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
            setCurrentStep(mcpComponent ? 'tools' : 'components');
            setSelectedComponent(
                mcpComponent
                    ? ({
                          name: mcpComponent.componentName,
                          title: mcpComponent.title || mcpComponent.componentName,
                          version: mcpComponent.componentVersion,
                      } as ComponentDefinition)
                    : null
            );
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
