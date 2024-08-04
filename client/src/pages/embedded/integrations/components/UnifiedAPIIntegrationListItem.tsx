import {Switch} from '@/components/ui/switch';
import {useEnableUnifiedIntegrationMutation} from '@/mutations/embedded/unifiedIntegrations.mutations';
import {ComponentDefinitionBasicModel} from '@/shared/middleware/platform/configuration';
import {UnifiedIntegrationKeys} from '@/shared/queries/embedded/unifiedIntegrations.queries';
import {useQueryClient} from '@tanstack/react-query';
import InlineSVG from 'react-inlinesvg';

interface UnifiedAPIIntegrationListItemProps {
    componentDefinition: ComponentDefinitionBasicModel;
    enabled: boolean;
}

const UnifiedAPIIntegrationListItem = ({componentDefinition, enabled}: UnifiedAPIIntegrationListItemProps) => {
    const queryClient = useQueryClient();

    const enableUnifiedIntegrationMutation = useEnableUnifiedIntegrationMutation({
        onSuccess: () =>
            queryClient.invalidateQueries({
                queryKey: UnifiedIntegrationKeys.unifiedIntegrations,
            }),
    });

    return (
        <div className="flex w-full items-center justify-between rounded-md px-2 hover:bg-gray-50">
            <div className="flex flex-1 items-center border-b border-muted py-5">
                <div className="flex-1">
                    <div className="flex items-center justify-between">
                        <div className="relative flex items-center gap-2">
                            <div className="flex items-center gap-2">
                                {componentDefinition?.icon && (
                                    <InlineSVG className="size-6 flex-none" src={componentDefinition.icon} />
                                )}

                                <span className="text-base font-semibold text-gray-900">
                                    {componentDefinition?.title}
                                </span>
                            </div>

                            {/*{componentDefinition.category && (*/}

                            {/*    <span className="text-xs uppercase text-gray-700">{componentDefinition.category.name}</span>*/}

                            {/*)}*/}
                        </div>
                    </div>

                    <div className="relative mt-2 sm:flex sm:items-center sm:justify-between">
                        <div className="flex items-center"></div>
                    </div>
                </div>

                <div className="flex items-center justify-end gap-x-6">
                    <div className="flex flex-col items-end gap-y-4">
                        <Switch
                            checked={enabled}
                            onCheckedChange={(checked) => {
                                enableUnifiedIntegrationMutation.mutate({
                                    componentName: componentDefinition.name!,
                                    enable: checked,
                                });
                            }}
                        />
                    </div>
                </div>
            </div>
        </div>
    );
};

export default UnifiedAPIIntegrationListItem;
