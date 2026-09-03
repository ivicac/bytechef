import {Tabs, TabsList, TabsTrigger} from '@/components/ui/tabs';
import AiGuardrails from '@/ee/pages/settings/automation/ai/guardrails/AiGuardrails';
import ComponentRulesTab from '@/ee/pages/settings/automation/ai/rules/ComponentRulesTab';
import WorkspaceSystemPrompt from '@/ee/pages/settings/automation/ai/system-prompt/WorkspaceSystemPrompt';
import Header from '@/shared/layout/Header';
import LayoutContainer from '@/shared/layout/LayoutContainer';
import {useNavigate, useParams} from 'react-router-dom';

const RULES_TAB = 'rules';
const SYSTEM_PROMPT_TAB = 'system-prompt';

/**
 * Guardrails, the workspace system prompt, and rules are three halves of one policy: what agents in this workspace
 * may say, what they are standing-instructed to do, and which tool calls they may make. They were separate sidebar
 * entries (guardrails and the prompt under an "AI" heading, rules under Components); folding them into one page lets
 * that heading go and leaves the AI hub connectors entry standing on its own.
 *
 * The tab lives in the URL rather than in state so each half stays linkable — the previous routes redirect onto their
 * tab rather than dumping the reader on the default one.
 */
const AiAgents = () => {
    const {tab} = useParams();

    const navigate = useNavigate();

    const currentTab = tab === SYSTEM_PROMPT_TAB || tab === RULES_TAB ? tab : 'guardrails';

    return (
        <LayoutContainer
            header={
                <Header
                    centerTitle
                    description="Set the content guardrails, the standing system prompt, and which tool calls agents may make in this workspace."
                    position="main"
                    title="AI Agents"
                />
            }
            leftSidebarOpen={false}
        >
            <div className="flex size-full flex-col">
                <Tabs
                    className="px-6 pt-2 pb-4"
                    onValueChange={(value) => navigate(`/automation/settings/ai/agents/${value}`)}
                    value={currentTab}
                >
                    <TabsList>
                        <TabsTrigger value="guardrails">Guardrails</TabsTrigger>

                        <TabsTrigger value={SYSTEM_PROMPT_TAB}>System Prompt</TabsTrigger>

                        <TabsTrigger value={RULES_TAB}>Rules</TabsTrigger>
                    </TabsList>
                </Tabs>

                {currentTab === SYSTEM_PROMPT_TAB && <WorkspaceSystemPrompt />}

                {currentTab === RULES_TAB && <ComponentRulesTab />}

                {currentTab === 'guardrails' && <AiGuardrails />}
            </div>
        </LayoutContainer>
    );
};

export default AiAgents;
