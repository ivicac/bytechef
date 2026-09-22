import Button from '@/components/Button/Button';
import {ButtonGroup} from '@/components/ui/button-group';
import {DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger} from '@/components/ui/dropdown-menu';
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip';
import {useConvertN8nToWorkflow} from '@/pages/automation/project/hooks/useConverterN8nToWorkflow';
import handleImportN8nWorkflow from '@/pages/automation/project/utils/handleImportN8nWorkflow';
import handleImportWorkflow from '@/pages/automation/project/utils/handleImportWorkflow';
import useOpenCopilot from '@/shared/components/copilot/hooks/useOpenCopilot';
import {MODE, Source} from '@/shared/components/copilot/stores/useCopilotStore';
import WorkflowDialog from '@/shared/components/workflow/WorkflowDialog';
import {useAnalytics} from '@/shared/hooks/useAnalytics';
import useButtonGroupDropdownAlign from '@/shared/hooks/useButtonGroupDropdownAlign';
import {useHasEnabledAiProvider} from '@/shared/hooks/useHasEnabledAiProvider';
import {Project} from '@/shared/middleware/automation/configuration';
import {useCreateProjectWorkflowMutation} from '@/shared/mutations/automation/workflows.mutations';
import {ProjectKeys} from '@/shared/queries/automation/projects.queries';
import {useGetWorkflowQuery} from '@/shared/queries/automation/workflows.queries';
import {useApplicationInfoStore} from '@/shared/stores/useApplicationInfoStore';
import {useQueryClient} from '@tanstack/react-query';
import {ChevronDownIcon, LayoutTemplateIcon, LoaderCircleIcon, SparklesIcon, UploadIcon} from 'lucide-react';
import {useRef, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {toast} from 'sonner';

interface ProjectWorkflowCreationActionsProps {
    placement: 'emptyState' | 'tabRow';
    project: Project;
}

const ProjectWorkflowCreationActions = ({placement, project}: ProjectWorkflowCreationActionsProps) => {
    const [showWorkflowDialog, setShowWorkflowDialog] = useState(false);

    const hiddenFileInputRef = useRef<HTMLInputElement>(null);
    const converterHiddenFileInputRef = useRef<HTMLInputElement>(null);

    const {captureProjectWorkflowCreated, captureProjectWorkflowImported} = useAnalytics();
    const {alignOffset, buttonGroupRef, dropdownMenuTriggerRef, handleOpenChange} = useButtonGroupDropdownAlign();
    const navigate = useNavigate();
    const openCopilot = useOpenCopilot();
    const copilotEnabled = useApplicationInfoStore((state) => state.ai.copilot.enabled);

    const queryClient = useQueryClient();

    const {convertN8nWorkflow} = useConvertN8nToWorkflow();
    const {hasEnabledAiProvider, isPending: isAiProviderCheckPending} = useHasEnabledAiProvider();

    const importN8nWorkflowDisabled = !isAiProviderCheckPending && !hasEnabledAiProvider;
    const [isImportingN8nWorkflow, setIsImportingN8nWorkflow] = useState(false);

    const compact = placement === 'tabRow';

    const createProjectWorkflowMutation = useCreateProjectWorkflowMutation({
        onSuccess: (response) => {
            captureProjectWorkflowCreated();

            queryClient.invalidateQueries({queryKey: ProjectKeys.projects});

            navigate(`/automation/projects/${project.id}/project-workflows/${response.projectWorkflowId}`);
        },
    });

    const importProjectWorkflowMutation = useCreateProjectWorkflowMutation({
        onSuccess: () => {
            captureProjectWorkflowImported();

            queryClient.invalidateQueries({queryKey: ProjectKeys.project(project.id!)});
            queryClient.invalidateQueries({queryKey: ProjectKeys.projects});

            if (hiddenFileInputRef.current) {
                hiddenFileInputRef.current.value = '';
            }

            toast('Workflow is imported.');
        },
    });

    return (
        <>
            <ButtonGroup
                aria-label="Workflow Creation Actions"
                className={compact ? undefined : 'mx-auto'}
                ref={buttonGroupRef}
            >
                <Button
                    aria-label="Create Workflow"
                    onClick={(event) => {
                        event.stopPropagation();

                        setShowWorkflowDialog(true);
                    }}
                    size={compact ? 'sm' : undefined}
                    variant={compact ? 'outline' : 'default'}
                >
                    {compact ? 'New Workflow' : 'Create Workflow'}
                </Button>

                <DropdownMenu onOpenChange={handleOpenChange}>
                    <DropdownMenuTrigger asChild>
                        <Button
                            aria-label="More Workflow Creation Actions"
                            icon={
                                isImportingN8nWorkflow ? (
                                    <LoaderCircleIcon className="animate-spin" />
                                ) : (
                                    <ChevronDownIcon />
                                )
                            }
                            ref={dropdownMenuTriggerRef}
                            size={compact ? 'sm' : undefined}
                            variant={compact ? 'outline' : 'default'}
                        >
                            <> </>
                        </Button>
                    </DropdownMenuTrigger>

                    <DropdownMenuContent
                        align={compact ? 'end' : 'start'}
                        alignOffset={compact ? undefined : alignOffset}
                        className="p-0"
                    >
                        {copilotEnabled && (
                            <DropdownMenuItem
                                aria-label="Generate Workflow with AI"
                                className="dropdown-menu-item"
                                onClick={(event) => {
                                    event.stopPropagation();

                                    openCopilot({
                                        composerPlaceholder:
                                            'When a new Gmail email arrives, post a summary to a Slack channel.',
                                        mode: MODE.BUILD,
                                        parameters: {
                                            intent: 'generate_workflow',
                                            projectId: project.id,
                                        },
                                        source: Source.PROJECT,
                                    });
                                }}
                            >
                                <SparklesIcon /> Generate with AI
                            </DropdownMenuItem>
                        )}

                        <DropdownMenuItem
                            aria-label="Create Workflow from Template"
                            className="dropdown-menu-item"
                            onClick={(event) => {
                                event.stopPropagation();

                                navigate(`./${project.id}/templates`);
                            }}
                        >
                            <LayoutTemplateIcon /> From Template
                        </DropdownMenuItem>

                        <DropdownMenuItem
                            aria-label="Import Workflow"
                            className="dropdown-menu-item"
                            onClick={(event) => {
                                event.stopPropagation();

                                if (hiddenFileInputRef.current) {
                                    hiddenFileInputRef.current.click();
                                }
                            }}
                        >
                            <UploadIcon /> Import Workflow
                        </DropdownMenuItem>

                        <Tooltip>
                            <TooltipTrigger asChild>
                                <span className="block">
                                    <DropdownMenuItem
                                        aria-label="Import n8n Workflow"
                                        className="dropdown-menu-item"
                                        disabled={importN8nWorkflowDisabled}
                                        onClick={(event) => {
                                            event.stopPropagation();

                                            if (converterHiddenFileInputRef.current) {
                                                converterHiddenFileInputRef.current.click();
                                            }
                                        }}
                                    >
                                        <UploadIcon /> Import n8n Workflow
                                    </DropdownMenuItem>
                                </span>
                            </TooltipTrigger>

                            {importN8nWorkflowDisabled && (
                                <TooltipContent>Enable an AI provider to import n8n workflows.</TooltipContent>
                            )}
                        </Tooltip>
                    </DropdownMenuContent>
                </DropdownMenu>
            </ButtonGroup>

            {showWorkflowDialog && (
                <WorkflowDialog
                    createWorkflowMutation={createProjectWorkflowMutation}
                    onClose={() => setShowWorkflowDialog(false)}
                    parentId={project.id}
                    useGetWorkflowQuery={useGetWorkflowQuery}
                />
            )}

            <input
                accept=".json,.yaml,.yml"
                alt="file"
                className="hidden"
                onChange={(event) => handleImportWorkflow(event, project.id!, importProjectWorkflowMutation)}
                ref={hiddenFileInputRef}
                type="file"
            />

            <input
                accept=".json"
                className="hidden"
                onChange={async (event) => {
                    if (!event.target.files?.length) {
                        return;
                    }

                    try {
                        setIsImportingN8nWorkflow(true);
                        await handleImportN8nWorkflow(
                            event,
                            project.id!,
                            importProjectWorkflowMutation,
                            convertN8nWorkflow
                        );
                    } finally {
                        setIsImportingN8nWorkflow(false);

                        if (converterHiddenFileInputRef.current) {
                            converterHiddenFileInputRef.current.value = '';
                        }
                    }
                }}
                ref={converterHiddenFileInputRef}
                type="file"
            />
        </>
    );
};

export default ProjectWorkflowCreationActions;
export type {ProjectWorkflowCreationActionsProps};
