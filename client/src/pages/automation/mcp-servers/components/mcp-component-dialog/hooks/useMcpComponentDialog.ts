import useCeEdition from '@/shared/edition/useCeEdition';
import {Connection} from '@/shared/middleware/automation/configuration';
import {
    McpComponent,
    useAuthoritiesQuery,
    useCreateMcpComponentWithToolsMutation,
    useMcpToolsByComponentIdQuery,
    useUpdateMcpComponentWithToolsMutation,
} from '@/shared/middleware/graphql';
import {ComponentDefinitionBasic} from '@/shared/middleware/platform/configuration';
import {getRoleLabel} from '@/shared/util/role-utils';
import {useQueryClient} from '@tanstack/react-query';
import {useMemo, useState} from 'react';

import {SelectedToolType} from './useMcpComponentDialogToolSelectionStep';

export type StepType = 'components' | 'tools';

interface UseMcpComponentDialogProps {
    mcpComponent?: McpComponent;
    mcpServerId: string;
    open?: boolean;
    onOpenChange?: (open: boolean) => void;
}

const useMcpComponentDialog = ({mcpComponent, mcpServerId, onOpenChange, open}: UseMcpComponentDialogProps) => {
    const [currentStep, setCurrentStep] = useState<StepType>(mcpComponent ? 'tools' : 'components');
    const [selectedComponent, setSelectedComponent] = useState<ComponentDefinitionBasic | null>(
        mcpComponent
            ? ({
                  name: mcpComponent.componentName,
                  title: mcpComponent.title || mcpComponent.componentName,
                  version: mcpComponent.componentVersion,
              } as ComponentDefinitionBasic)
            : null
    );
    const [selectedTools, setSelectedTools] = useState<SelectedToolType[]>([]);
    const [selectedConnection, setSelectedConnection] = useState<Connection | null>(null);
    const [requiredAuthorities, setRequiredAuthorities] = useState<string[]>(mcpComponent?.requiredAuthorities ?? []);
    const [requiredAuthorityInput, setRequiredAuthorityInput] = useState('');

    const {data: existingTools} = useMcpToolsByComponentIdQuery(
        {
            mcpComponentId: mcpComponent?.id?.toString() || '',
        },
        {
            enabled: !!mcpComponent?.id && open,
        }
    );

    const queryClient = useQueryClient();

    const invalidateMcpQueries = () => {
        queryClient.invalidateQueries({queryKey: ['mcpComponentsByServerId']});
        queryClient.invalidateQueries({queryKey: ['workspaceMcpServers']});
    };

    const createMcpComponentWithToolsMutation = useCreateMcpComponentWithToolsMutation({
        onSuccess: invalidateMcpQueries,
    });

    const updateMcpComponentWithToolsMutation = useUpdateMcpComponentWithToolsMutation({
        onSuccess: invalidateMcpQueries,
    });

    const authorityOptions = useMemo(() => {
        const authorities = new Set([...(authoritiesData?.authorities ?? []), ...requiredAuthorities]);

        return Array.from(authorities)
            .map((authority) => ({label: getRoleLabel(authority), value: authority}))
            .sort((option, otherOption) => option.label.localeCompare(otherOption.label));
    }, [authoritiesData, requiredAuthorities]);

    const handleComponentSelect = (component: ComponentDefinitionBasic) => {
        setSelectedComponent(component);
        setSelectedTools([]);
        setSelectedConnection(null);
        setCurrentStep('tools');
    };

    const handleAddRequiredAuthority = () => {
        const trimmed = requiredAuthorityInput.trim();

        if (trimmed && !requiredAuthorities.includes(trimmed)) {
            setRequiredAuthorities([...requiredAuthorities, trimmed]);
            setRequiredAuthorityInput('');
        }
    };

    const handleRemoveRequiredAuthority = (requiredAuthority: string) => {
        setRequiredAuthorities(
            requiredAuthorities.filter((existingAuthority) => existingAuthority !== requiredAuthority)
        );
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
                  } as ComponentDefinitionBasic)
                : null
        );
        setRequiredAuthorities(mcpComponent?.requiredAuthorities ?? []);
        setRequiredAuthorityInput('');

        if (!mcpComponent) {
            setSelectedTools([]);
            setSelectedConnection(null);
        }
    };

    const handleSave = () => {
        if (!selectedComponent) {
            return;
        }

        const onMutationSuccess = () => {
            if (onOpenChange) {
                onOpenChange(false);
            }

            setCurrentStep(mcpComponent ? 'tools' : 'components');

            if (!mcpComponent) {
                setSelectedComponent(null);
            }

            setSelectedTools([]);
            setSelectedConnection(null);
            setRequiredAuthorities(mcpComponent?.requiredAuthorities ?? []);
            setRequiredAuthorityInput('');
        };

        if (mcpComponent?.id) {
            updateMcpComponentWithToolsMutation.mutate(
                {
                    id: mcpComponent.id.toString(),
                    input: {
                        componentName: selectedComponent.name,
                        componentVersion: selectedComponent.version,
                        connectionId: selectedConnection?.id?.toString() || undefined,
                        mcpServerId,
                        requiredAuthorities,
                        tools: selectedTools.map((tool) => ({
                            name: tool.name,
                            parameters: {},
                        })),
                        version: mcpComponent.version,
                    },
                },
                {onSuccess: onMutationSuccess}
            );
        } else {
            createMcpComponentWithToolsMutation.mutate(
                {
                    input: {
                        componentName: selectedComponent.name,
                        componentVersion: selectedComponent.version,
                        connectionId: selectedConnection?.id?.toString() || undefined,
                        mcpServerId,
                        requiredAuthorities,
                        tools: selectedTools.map((tool) => ({
                            name: tool.name,
                            parameters: {},
                        })),
                    },
                },
                {onSuccess: onMutationSuccess}
            );
        }
    };

    const handleBack = () => {
        if (mcpComponent) {
            handleClose();

            return;
        }

        setCurrentStep('components');
        setSelectedComponent(null);
        setSelectedTools([]);
        setSelectedConnection(null);
    };

    const handleOpenChange = (newOpen: boolean) => {
        if (onOpenChange) {
            onOpenChange(newOpen);
        }

        if (newOpen) {
            return;
        }

        setCurrentStep(mcpComponent ? 'tools' : 'components');

        setSelectedComponent(
            mcpComponent
                ? ({
                      name: mcpComponent.componentName,
                      title: mcpComponent.title || mcpComponent.componentName,
                      version: mcpComponent.componentVersion,
                  } as ComponentDefinitionBasic)
                : null
        );
        setRequiredAuthorities(mcpComponent?.requiredAuthorities ?? []);
        setRequiredAuthorityInput('');

        if (!mcpComponent) {
            setSelectedTools([]);
            setSelectedConnection(null);
        }
    };

    return {
        authoritiesLoading,
        authorityOptions,
        currentStep,
        existingTools,
        handleAddRequiredAuthority,
        handleBack,
        handleClose,
        handleComponentSelect,
        handleOpenChange,
        handleRemoveRequiredAuthority,
        handleSave,
        requiredAuthorities,
        requiredAuthorityInput,
        selectedComponent,
        selectedConnection,
        selectedTools,
        setRequiredAuthorityInput,
        setSelectedConnection,
        setSelectedTools,
    };
};

export default useMcpComponentDialog;
