import AiHubChatComposer from '@/pages/automation/ai-hub/composer/AiHubChatComposer';
import {aiHubStore} from '@/pages/automation/ai-hub/stores/useAiHubStore';
import {aiHubTasksStore, useAiHubTasksStore} from '@/pages/automation/ai-hub/tasks/stores/useAiHubTasksStore';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import EnvironmentSelect from '@/shared/components/EnvironmentSelect';
import ModelPicker from '@/shared/components/ai/model-picker/ModelPicker';
import {useNavigate} from 'react-router-dom';

// Note: this view stays mounted inside the same `AiHubRuntimeProvider` (hoisted to
// AiHubContent), so the runtime instance survives the home -> task transition. Without that
// hoist the auto-create-task flow would unmount the provider mid-`onNew`, abort the AG-UI agent run,
// and the user would land on the task page with their message but no streaming reply.
//
// No retry banner here on purpose: agent / tool failures surface via the global toast layer (sonner).
// Rendering both a banner here and a toast would double-notify the user for the same event.
const AiHubHomePanel = () => {
    const navigate = useNavigate();

    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);
    // Draft slot: the home composer has no task to scope a selection to until the user sends a
    // message (AiHubRuntimeProvider.onNew auto-creates the task on first send). The draft is migrated
    // into taskLlmSelections[newTaskId] inside onNew via consumeDraftLlmSelection so the override
    // applies to the first turn AND shows up in the task panel's own ModelPicker after the transition.
    const draftLlmSelection = useAiHubTasksStore((state) => state.draftLlmSelection);
    const setDraftLlmSelection = useAiHubTasksStore((state) => state.setDraftLlmSelection);

    return (
        <div className="relative flex size-full flex-col bg-background">
            {/*
             * Header strip with EnvironmentSelect tucked to the right edge — same position the selector
             * occupies on the task panel header (AiHubPanel). Without this, navigating
             * from a task back to the home view loses the env selector entirely (it lived in the
             * page-level top header before that header was removed). Putting it here keeps env switching
             * one click away regardless of whether the user has an active task.
             */}

            <div className="flex items-center justify-end px-4 py-3">
                <EnvironmentSelect
                    onChange={() => {
                        aiHubTasksStore.getState().setCurrentTaskId(undefined);

                        aiHubStore.getState().resetMessages();
                        aiHubStore.getState().generateTaskId();

                        navigate('/automation/ai-hub');
                    }}
                />
            </div>

            <div className="flex flex-1 items-center justify-center px-4">
                <div className="flex w-full max-w-2xl flex-col gap-6">
                    <div className="text-center">
                        <h2 className="text-2xl font-semibold text-foreground">What should we get done?</h2>

                        <p className="mt-2 text-sm text-muted-foreground">
                            Ask anything, mention files / workflows / data tables, or try a builder command.
                        </p>
                    </div>

                    <AiHubChatComposer
                        modelPicker={
                            currentWorkspaceId != null ? (
                                <ModelPicker
                                    onChange={setDraftLlmSelection}
                                    selectedModel={draftLlmSelection?.model ?? null}
                                    selectedProvider={draftLlmSelection?.provider ?? null}
                                    workspaceId={currentWorkspaceId}
                                />
                            ) : null
                        }
                    />
                </div>
            </div>
        </div>
    );
};

export default AiHubHomePanel;
