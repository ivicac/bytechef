import Button from '@/components/Button/Button';
import {Thread} from '@/components/assistant-ui/thread';
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import ModeSwitch from '@/shared/components/ModeSwitch/ModeSwitch';
import ModelPicker from '@/shared/components/ai/model-picker/ModelPicker';
import {readLastUsedModel, writeLastUsedModel} from '@/shared/components/ai/model-picker/lastUsedModel';
import CopilotPanelBoundary from '@/shared/components/copilot/CopilotPanelBoundary';
import {CopilotRuntimeProvider} from '@/shared/components/copilot/runtime-providers/CopilotRuntimeProvider';
import useCopilotPanelStore from '@/shared/components/copilot/stores/useCopilotPanelStore';
import {MODE, Source, useCopilotStore} from '@/shared/components/copilot/stores/useCopilotStore';
import {canApplyToEditor} from '@/shared/components/copilot/utils/canApplyToEditor';
import {extractDefinitionFromMessage} from '@/shared/components/copilot/utils/extractDefinitionFromMessage';
import {useEnvironmentStore} from '@/shared/stores/useEnvironmentStore';
import {BotMessageSquareIcon, MessageSquareXIcon, SparklesIcon, XIcon} from 'lucide-react';
import {useEffect, useRef, useState} from 'react';
import {useLocation} from 'react-router-dom';
import {twMerge} from 'tailwind-merge';
import {useShallow} from 'zustand/react/shallow';

const ANIMATION_DURATION_MS = 300;

interface CopilotPanelProps {
    className?: string;
    headerClassName?: string;
    onApply?: (value: string) => void;
    onClose?: () => void;
    open: boolean;
    source?: Source;
}

const CopilotPanelContent = ({
    className,
    headerClassName,
    onApply,
    onClose,
    source,
}: Omit<CopilotPanelProps, 'open'>) => {
    const {
        context,
        generateConversationId,
        messages,
        resetMessages,
        selectedLlmModel,
        selectedLlmProvider,
        setContext,
        setSelectedLlm,
    } = useCopilotStore(
        useShallow((state) => ({
            context: state.context,
            generateConversationId: state.generateConversationId,
            messages: state.messages,
            resetMessages: state.resetMessages,
            selectedLlmModel: state.selectedLlmModel,
            selectedLlmProvider: state.selectedLlmProvider,
            setContext: state.setContext,
            setSelectedLlm: state.setSelectedLlm,
        }))
    );
    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);
    const currentEnvironmentId = useEnvironmentStore((state) => state.currentEnvironmentId);
    const setCopilotPanelOpen = useCopilotPanelStore((state) => state.setCopilotPanelOpen);
    const location = useLocation();

    const lastAssistantMessage = [...messages].reverse().find((message) => message.role === 'assistant');

    const applyToEditorEnabled = canApplyToEditor(source, context?.mode, !!onApply && !!lastAssistantMessage);

    const handleApplyClick = () => {
        if (!onApply || !lastAssistantMessage) {
            return;
        }

        const definition = extractDefinitionFromMessage(lastAssistantMessage.content);

        if (definition) {
            onApply(definition);
        }
    };

    const handleCleanMessages = () => {
        resetMessages();
        generateConversationId();
    };

    const handleCloseClick = () => {
        if (onClose) {
            onClose();
        } else {
            setContext({
                mode: MODE.ASK,
                parameters: {},
                source: Source.WORKFLOW_EDITOR,
            });
            setCopilotPanelOpen(false);
        }
    };

    const previousPathnameRef = useRef(location.pathname);

    useEffect(() => {
        if (previousPathnameRef.current !== location.pathname) {
            previousPathnameRef.current = location.pathname;

            generateConversationId();
            resetMessages();
        }
    }, [generateConversationId, location.pathname, resetMessages]);

    return (
        <div className={twMerge('relative h-full min-h-[50vh] w-[450px] bg-surface-main', className)}>
            <div className={twMerge('flex items-center justify-between px-4 py-3', headerClassName)}>
                <div className="flex items-center space-x-1">
                    <BotMessageSquareIcon className="size-6" /> <h4>AI Copilot</h4>
                </div>

                <div className="flex items-center gap-1">
                    {/*
                     * The Ask / Build mode control moved into the composer footer as a single labeled switch
                     * (ModeSwitch, passed via Thread's composerActions below) — one toggle near the send
                     * button replaces the two-button segmented control that used to live here.
                     */}

                    {applyToEditorEnabled && (
                        <Tooltip>
                            <TooltipTrigger asChild>
                                <Button
                                    aria-label="Apply to editor"
                                    icon={<SparklesIcon />}
                                    onClick={handleApplyClick}
                                    size="icon"
                                    variant="ghost"
                                />
                            </TooltipTrigger>

                            <TooltipContent>Apply to editor</TooltipContent>
                        </Tooltip>
                    )}

                    <Tooltip>
                        <TooltipTrigger asChild>
                            <Button
                                icon={<MessageSquareXIcon />}
                                onClick={handleCleanMessages}
                                size="icon"
                                variant="ghost"
                            />
                        </TooltipTrigger>

                        <TooltipContent>Clean messages</TooltipContent>
                    </Tooltip>

                    <Button icon={<XIcon />} onClick={handleCloseClick} size="icon" variant="ghost" />
                </div>
            </div>

            <div className="absolute inset-x-0 top-16 bottom-0 -mx-1">
                <CopilotRuntimeProvider source={source}>
                    {/*
                     * Per-conversation LLM picker. Selection persists across messages in the same
                     * conversation, resets when generateConversationId() fires (clean-messages or new
                     * conversation). Hidden when no workspace is resolved yet — the picker's GraphQL
                     * queries are workspace-scoped, so showing it before currentWorkspaceId is known
                     * would render an empty list. Rendered inside Thread's composer footer (left of the
                     * built-in attachment button) so the user picks a model at send time, not in the
                     * panel header.
                     */}

                    <Thread
                        composerActions={
                            !source ? (
                                <ModeSwitch
                                    build={context?.mode === MODE.BUILD}
                                    onBuildChange={(build) =>
                                        setContext({...context, mode: build ? MODE.BUILD : MODE.ASK})
                                    }
                                />
                            ) : null
                        }
                        leadingComposerActions={
                            currentWorkspaceId != null ? (
                                <ModelPicker
                                    environment={currentEnvironmentId}
                                    onChange={(provider, model) => {
                                        writeLastUsedModel(currentWorkspaceId, provider, model);
                                        setSelectedLlm(provider, model);
                                    }}
                                    selectedModel={
                                        selectedLlmModel ?? readLastUsedModel(currentWorkspaceId)?.model ?? null
                                    }
                                    selectedProvider={
                                        selectedLlmProvider ?? readLastUsedModel(currentWorkspaceId)?.provider ?? null
                                    }
                                />
                            ) : null
                        }
                    />
                </CopilotRuntimeProvider>
            </div>
        </div>
    );
};

const CopilotPanel = ({className, headerClassName, onApply, onClose, open, source}: CopilotPanelProps) => {
    const [shouldRender, setShouldRender] = useState(open);
    const [isVisible, setIsVisible] = useState(open);

    const isSlideAnimation = className?.split(/\s+/).some((cls) => cls === 'fixed' || cls === 'absolute');

    const contentClassName = isSlideAnimation
        ? twMerge(
              'transition-transform duration-300 ease-in-out',
              isVisible ? 'translate-x-0' : 'translate-x-full',
              className
          )
        : className;

    useEffect(() => {
        let outerRafId: number | undefined;
        let innerRafId: number | undefined;
        let timerId: ReturnType<typeof setTimeout> | undefined;

        if (open) {
            setShouldRender(true);

            outerRafId = requestAnimationFrame(() => {
                innerRafId = requestAnimationFrame(() => {
                    setIsVisible(true);
                });
            });
        } else {
            setIsVisible(false);

            timerId = setTimeout(() => setShouldRender(false), ANIMATION_DURATION_MS);
        }

        return () => {
            if (outerRafId !== undefined) {
                cancelAnimationFrame(outerRafId);
            }

            if (innerRafId !== undefined) {
                cancelAnimationFrame(innerRafId);
            }

            if (timerId !== undefined) {
                clearTimeout(timerId);
            }
        };
    }, [open]);

    return (
        <CopilotPanelBoundary open={open}>
            <div
                className={twMerge(
                    'overflow-hidden',
                    !isSlideAnimation && 'h-full transition-[width] duration-300 ease-in-out',
                    !isSlideAnimation && (isVisible ? 'w-[450px]' : 'w-0')
                )}
            >
                {shouldRender && (
                    <CopilotPanelContent
                        className={contentClassName}
                        headerClassName={headerClassName}
                        onApply={onApply}
                        onClose={onClose}
                        source={source}
                    />
                )}
            </div>
        </CopilotPanelBoundary>
    );
};

export default CopilotPanel;
