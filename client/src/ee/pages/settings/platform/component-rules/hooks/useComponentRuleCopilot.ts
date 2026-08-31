import {parseJson} from '@/shared/components/ai-chat/messages/toToolResultDataPart';
import useCopilotPostTurnRegistry from '@/shared/components/copilot/stores/useCopilotPostTurnRegistry';
import useCopilotStateContributorRegistry from '@/shared/components/copilot/stores/useCopilotStateContributorRegistry';
import {MODE, Source, useCopilotStore} from '@/shared/components/copilot/stores/useCopilotStore';
import useCopilotToolResultHandlerRegistry from '@/shared/components/copilot/stores/useCopilotToolResultHandlerRegistry';
import {useCallback, useEffect, useRef} from 'react';

const APPLIED_MESSAGE = '✓ Applied the condition to the rule editor.';
const PROPOSE_TOOL_NAME = 'proposeComponentRuleCondition';

interface UseComponentRuleCopilotParamsI {
    actionName?: string;
    componentName: string;
    currentCondition: string;
    onConditionGenerated: (condition: string) => void;
}

interface UseComponentRuleCopilotResultI {
    handleCopilotClose: () => void;
    openCopilot: () => void;
}

/**
 * Opens the Copilot panel scoped to one component/action and drops the condition it proposes into the rule editor.
 * Nothing is saved here: the panel proposes, the admin reviews in the textarea, and Save is a separate, deliberate
 * click.
 */
const useComponentRuleCopilot = ({
    actionName,
    componentName,
    currentCondition,
    onConditionGenerated,
}: UseComponentRuleCopilotParamsI): UseComponentRuleCopilotResultI => {
    const conversationTokenRef = useRef<string | null>(null);
    const pendingAppliedRef = useRef<boolean>(false);

    const openCopilot = useCallback(() => {
        const {context, generateConversationId, resetMessages, saveConversationState, setContext} =
            useCopilotStore.getState();

        conversationTokenRef.current = saveConversationState();

        resetMessages();
        generateConversationId();

        setContext({
            ...context,
            mode: MODE.ASK,
            parameters: {actionName, componentName},
            source: Source.COMPONENT_RULE,
        });
    }, [actionName, componentName]);

    const handleCopilotClose = useCallback(() => {
        useCopilotStore.getState().restoreConversationState(conversationTokenRef.current);
    }, []);

    useEffect(() => {
        const unregisterContributor = useCopilotStateContributorRegistry.getState().register(() => ({
            actionName,
            componentName,
            currentCondition,
        }));

        const unregisterToolResult = useCopilotToolResultHandlerRegistry
            .getState()
            .register(PROPOSE_TOOL_NAME, (content) => {
                const result = parseJson<{condition?: string; valid?: boolean}>(content, `${PROPOSE_TOOL_NAME} result`);

                // An invalid proposal is the model's problem to fix on its next turn — never put a condition the
                // server already rejected into the editor, where Save would reject it a second time.
                if (result?.valid !== true || !result.condition) {
                    return;
                }

                pendingAppliedRef.current = true;

                onConditionGenerated(result.condition);
            });

        const unregisterPostTurn = useCopilotPostTurnRegistry.getState().register(Source.COMPONENT_RULE, () => {
            if (!pendingAppliedRef.current) {
                return;
            }

            pendingAppliedRef.current = false;

            useCopilotStore.getState().appendToLastAssistantMessage(APPLIED_MESSAGE);
        });

        return () => {
            unregisterContributor();
            unregisterToolResult();
            unregisterPostTurn();
        };
    }, [actionName, componentName, currentCondition, onConditionGenerated]);

    return {handleCopilotClose, openCopilot};
};

export default useComponentRuleCopilot;
