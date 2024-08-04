import {UnifiedIntegrationModel} from '@/shared/middleware/embedded/configuration';
import {ComponentDefinitionBasicModel} from '@/shared/middleware/platform/configuration';
import UnifiedAPIIntegrationListItem from 'pages/embedded/integrations/components/UnifiedAPIIntegrationListItem';

const UnifiedAPIIntegrationList = ({
    componentDefinitions,
    unifiedIntegrations,
}: {
    componentDefinitions: ComponentDefinitionBasicModel[];
    unifiedIntegrations: UnifiedIntegrationModel[];
}) => {
    return (
        <div className="w-full px-2 2xl:mx-auto 2xl:w-4/5">
            {componentDefinitions.map((componentDefinition) => {
                return (
                    <UnifiedAPIIntegrationListItem
                        componentDefinition={componentDefinition}
                        enabled={
                            !!unifiedIntegrations.find(
                                (unifiedIntegration) => unifiedIntegration.componentName === componentDefinition.name
                            )
                        }
                        key={componentDefinition.name}
                    />
                );
            })}
        </div>
    );
};
export default UnifiedAPIIntegrationList;
