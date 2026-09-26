import {ToolInvocations} from '@/pages/automation/tool-invocations/ToolInvocations';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';

export const EmbeddedToolInvocations = () => {
    const currentEnvironmentId = useEnvironmentStore((state) => state.currentEnvironmentId);

    return <ToolInvocations environmentId={currentEnvironmentId} key={currentEnvironmentId} />;
};
