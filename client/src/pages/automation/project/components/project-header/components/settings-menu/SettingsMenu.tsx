import Button from '@/components/Button/Button';
import DeleteAlertDialog from '@/components/DeleteAlertDialog';
import {DropdownMenu, DropdownMenuContent, DropdownMenuTrigger} from '@/components/ui/dropdown-menu';
import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs';
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip';
import AgentDialog from '@/pages/automation/agents/components/AgentDialog';
import useImportAiAgent from '@/pages/automation/agents/hooks/useImportAiAgent';
import DataSyncDialog from '@/pages/automation/data-syncs/components/DataSyncDialog';
import ErrorWorkflowDialog from '@/pages/automation/project/components/ErrorWorkflowDialog';
import {ProjectShareDialog} from '@/pages/automation/project/components/ProjectShareDialog';
import ProjectVersionHistorySheet from '@/pages/automation/project/components/ProjectVersionHistorySheet';
import ProjectVisibilityDialog from '@/pages/automation/project/components/ProjectVisibilityDialog';
import {WorkflowShareDialog} from '@/pages/automation/project/components/WorkflowShareDialog';
import DeleteProjectAlertDialog from '@/pages/automation/project/components/project-header/components/settings-menu/components/DeleteProjectAlertDialog';
import ProjectTabButtons from '@/pages/automation/project/components/project-header/components/settings-menu/components/ProjectTabButtons/ProjectTabButtons';
import WorkflowErrorHandlingDialog from '@/pages/automation/project/components/project-header/components/settings-menu/components/WorkflowErrorHandlingDialog';
import WorkflowTabButtons from '@/pages/automation/project/components/project-header/components/settings-menu/components/WorkflowTabButtons';
import {useSettingsMenu} from '@/pages/automation/project/components/project-header/components/settings-menu/hooks/useSettingsMenu';
import {useCreateProjectWorkflow} from '@/pages/automation/project/hooks/useCreateProjectWorkflow';
import {useImportProjectWorkflow} from '@/pages/automation/project/hooks/useImportProjectWorkflow';
import ProjectDialog from '@/pages/automation/projects/components/ProjectDialog';
import useWorkflowEditorStore from '@/pages/platform/workflow-editor/stores/useWorkflowEditorStore';
import WorkflowDialog from '@/shared/components/workflow/WorkflowDialog';
import EEVersion from '@/shared/edition/EEVersion';
import {Project, Workflow} from '@/shared/middleware/automation/configuration';
import {ProjectWorkflowKeys} from '@/shared/queries/automation/projectWorkflows.queries';
import {useGetWorkflowQuery} from '@/shared/queries/automation/workflows.queries';
import {UpdateWorkflowMutationType} from '@/shared/types';
import {useQueryClient} from '@tanstack/react-query';
import {LoaderCircleIcon, SettingsIcon} from 'lucide-react';
import {ReactNode, RefObject, Suspense, lazy, useState} from 'react';
import {PanelImperativeHandle} from 'react-resizable-panels';
import {useNavigate} from 'react-router-dom';
import {useShallow} from 'zustand/react/shallow';

export interface SettingsMenuFirstTabProps {
    ariaLabel: string;
    /** Receives the same close-dropdown callback WorkflowTabButtons gets, so a caller-supplied tab can close
     *  the menu on its own button clicks too. */
    content: (onCloseDropdownMenu: () => void) => ReactNode;
    label: string;
    value: string;
}

interface ProjectHeaderSettingsMenuProps {
    bottomResizablePanelRef?: RefObject<PanelImperativeHandle | null>;
    /** Replaces the default Workflow tab with a caller-supplied one — the agent page has no workflow of its
     *  own and plugs in an Agent tab instead. Defaults to the Workflow tab built from `workflow` below. */
    firstTab?: SettingsMenuFirstTabProps;
    project: Project;
    updateWorkflowMutation?: UpdateWorkflowMutationType;
    workflow?: Workflow;
}

const ProjectGitConfigurationDialog = lazy(
    () => import('@/ee/pages/automation/project/components/ProjectGitConfigurationDialog')
);

const SettingsMenu = ({
    bottomResizablePanelRef,
    firstTab,
    project,
    updateWorkflowMutation,
    workflow,
}: ProjectHeaderSettingsMenuProps) => {
    const [openDropdownMenu, setOpenDropdownMenu] = useState(false);
    const [showAgentDialog, setShowAgentDialog] = useState(false);
    const [showCreateWorkflowDialog, setShowCreateWorkflowDialog] = useState(false);
    const [showDataSyncDialog, setShowDataSyncDialog] = useState(false);
    const [showDeleteProjectAlertDialog, setShowDeleteProjectAlertDialog] = useState(false);
    const [showDeleteWorkflowAlertDialog, setShowDeleteWorkflowAlertDialog] = useState(false);
    const [showEditProjectDialog, setShowEditProjectDialog] = useState(false);
    const [showErrorWorkflowDialog, setShowErrorWorkflowDialog] = useState(false);
    const [showProjectGitConfigurationDialog, setShowProjectGitConfigurationDialog] = useState(false);
    const [showProjectShareDialog, setShowProjectShareDialog] = useState(false);
    const [showProjectVersionHistorySheet, setShowProjectVersionHistorySheet] = useState(false);
    const [showProjectVisibilityDialog, setShowProjectVisibilityDialog] = useState(false);
    const [showWorkflowErrorHandlingDialog, setShowWorkflowErrorHandlingDialog] = useState(false);
    const [showWorkflowShareDialog, setShowWorkflowShareDialog] = useState(false);

    const {setShowEditWorkflowDialog, showEditWorkflowDialog} = useWorkflowEditorStore(
        useShallow((state) => ({
            setShowEditWorkflowDialog: state.setShowEditWorkflowDialog,
            showEditWorkflowDialog: state.showEditWorkflowDialog,
        }))
    );

    const {
        fileInputRef: agentFileInputRef,
        handleImportFileChange: handleImportAgentFileChange,
        isImporting: isImportingAgent,
        triggerImport: triggerAgentImport,
    } = useImportAiAgent({projectId: project.id!, workspaceId: project.workspaceId});

    const createProjectWorkflowMutation = useCreateProjectWorkflow({bottomResizablePanelRef, projectId: project.id!});

    const {
        handleN8nWorkflowFileChange,
        handleWorkflowFileChange,
        importN8nWorkflowDisabled,
        isImportingN8nWorkflow,
        n8nWorkflowFileInputRef,
        workflowFileInputRef,
    } = useImportProjectWorkflow(project.id!);

    const navigate = useNavigate();
    const queryClient = useQueryClient();

    const {
        handleDeleteProjectAlertDialogClick,
        handleDeleteWorkflowAlertDialogClick,
        handleDuplicateProjectClick,
        handleDuplicateWorkflowClick,
        handlePullProjectFromGitClick,
        handleUpdateProjectGitConfigurationSubmit,
        projectGitConfiguration,
        projectVersions,
    } = useSettingsMenu({project, workflow});

    const resolvedFirstTab: SettingsMenuFirstTabProps | undefined =
        firstTab ??
        (workflow
            ? {
                  ariaLabel: 'Workflow tab',
                  content: (onCloseDropdownMenu: () => void) => (
                      <WorkflowTabButtons
                          onCloseDropdownMenu={onCloseDropdownMenu}
                          onDuplicateWorkflow={handleDuplicateWorkflowClick}
                          onShareWorkflow={() => setShowWorkflowShareDialog(true)}
                          onShowDeleteWorkflowAlertDialog={() => setShowDeleteWorkflowAlertDialog(true)}
                          onShowEditWorkflowDialog={() => setShowEditWorkflowDialog(true)}
                          onShowErrorHandlingDialog={() => setShowWorkflowErrorHandlingDialog(true)}
                          workflowId={workflow.id!}
                      />
                  ),
                  label: 'Workflow',
                  value: 'workflow',
              }
            : undefined);

    return (
        <>
            <DropdownMenu onOpenChange={setOpenDropdownMenu} open={openDropdownMenu}>
                <Tooltip>
                    <DropdownMenuTrigger
                        asChild
                        className="cursor-pointer data-[state=open]:bg-surface-brand-secondary data-[state=open]:text-content-brand-primary"
                    >
                        <TooltipTrigger asChild>
                            <Button
                                aria-label="Settings"
                                icon={
                                    isImportingAgent || isImportingN8nWorkflow ? (
                                        <LoaderCircleIcon className="animate-spin text-primary" />
                                    ) : (
                                        <SettingsIcon />
                                    )
                                }
                                size="icon"
                                variant="ghost"
                            />
                        </TooltipTrigger>
                    </DropdownMenuTrigger>

                    <TooltipContent>Project and workflow settings</TooltipContent>
                </Tooltip>

                <DropdownMenuContent align="end" className="p-0">
                    <Tabs aria-label="Settings menu" defaultValue={resolvedFirstTab?.value ?? 'project'}>
                        {resolvedFirstTab && (
                            <TabsList className="rounded-none">
                                <TabsTrigger
                                    aria-label={resolvedFirstTab.ariaLabel}
                                    className="w-1/2 px-9 py-1 data-[state=active]:shadow-none"
                                    value={resolvedFirstTab.value}
                                >
                                    {resolvedFirstTab.label}
                                </TabsTrigger>

                                <TabsTrigger
                                    aria-label="Project tab"
                                    className="w-1/2 px-9 py-1 data-[state=active]:shadow-none"
                                    value="project"
                                >
                                    Project
                                </TabsTrigger>
                            </TabsList>
                        )}

                        {resolvedFirstTab && (
                            <TabsContent className="mt-0" value={resolvedFirstTab.value}>
                                {resolvedFirstTab.content(() => setOpenDropdownMenu(false))}
                            </TabsContent>
                        )}

                        <TabsContent className="mt-0" value="project">
                            <ProjectTabButtons
                                importN8nWorkflowDisabled={importN8nWorkflowDisabled}
                                onCloseDropdownMenuClick={() => setOpenDropdownMenu(false)}
                                onDeleteProjectClick={() => setShowDeleteProjectAlertDialog(true)}
                                onDuplicateProjectClick={handleDuplicateProjectClick}
                                onImportAgentClick={triggerAgentImport}
                                onImportN8nWorkflowClick={() => n8nWorkflowFileInputRef.current?.click()}
                                onImportWorkflowClick={() => workflowFileInputRef.current?.click()}
                                onNewAgentClick={() => setShowAgentDialog(true)}
                                onNewDataSyncClick={() => setShowDataSyncDialog(true)}
                                onNewWorkflowClick={() => setShowCreateWorkflowDialog(true)}
                                onNewWorkflowFromTemplateClick={() =>
                                    navigate(`/automation/projects/${project.id}/templates`)
                                }
                                onPullProjectFromGitClick={handlePullProjectFromGitClick}
                                onShareProject={() => setShowProjectShareDialog(true)}
                                onShowEditProjectDialogClick={() => setShowEditProjectDialog(true)}
                                onShowErrorWorkflowDialog={() => setShowErrorWorkflowDialog(true)}
                                onShowProjectGitConfigurationDialog={() => setShowProjectGitConfigurationDialog(true)}
                                onShowProjectVersionHistorySheet={() => setShowProjectVersionHistorySheet(true)}
                                onShowVisibilityDialog={() => setShowProjectVisibilityDialog(true)}
                                projectGitConfigurationEnabled={projectGitConfiguration?.enabled ?? false}
                                projectId={project.id!}
                                workflowCreationEnabled={!project.codeWorkflow}
                            />
                        </TabsContent>
                    </Tabs>
                </DropdownMenuContent>
            </DropdownMenu>

            <input
                accept=".json,.yaml,.yml"
                className="hidden"
                onChange={handleWorkflowFileChange}
                ref={workflowFileInputRef}
                type="file"
            />

            <input
                accept=".json"
                className="hidden"
                onChange={handleN8nWorkflowFileChange}
                ref={n8nWorkflowFileInputRef}
                type="file"
            />

            <input
                accept=".json"
                className="hidden"
                onChange={handleImportAgentFileChange}
                ref={agentFileInputRef}
                type="file"
            />

            {showAgentDialog && (
                <AgentDialog onOpenChange={setShowAgentDialog} open={showAgentDialog} projectId={project.id} />
            )}

            {showCreateWorkflowDialog && (
                <WorkflowDialog
                    createWorkflowMutation={createProjectWorkflowMutation}
                    onClose={() => setShowCreateWorkflowDialog(false)}
                    parentId={project.id}
                    useGetWorkflowQuery={useGetWorkflowQuery}
                />
            )}

            {showDataSyncDialog && (
                <DataSyncDialog onOpenChange={setShowDataSyncDialog} open={showDataSyncDialog} projectId={project.id} />
            )}

            {showDeleteProjectAlertDialog && (
                <DeleteProjectAlertDialog
                    onClose={() => setShowDeleteProjectAlertDialog(false)}
                    onDelete={handleDeleteProjectAlertDialogClick}
                />
            )}

            {showDeleteWorkflowAlertDialog && workflow && (
                <DeleteAlertDialog
                    onCancel={() => setShowDeleteWorkflowAlertDialog(false)}
                    onDelete={() => {
                        handleDeleteWorkflowAlertDialogClick();

                        setShowDeleteWorkflowAlertDialog(false);
                    }}
                    open={showDeleteWorkflowAlertDialog}
                />
            )}

            {showEditProjectDialog && (
                <ProjectDialog onClose={() => setShowEditProjectDialog(false)} project={project} />
            )}

            {showEditWorkflowDialog && workflow && (
                <WorkflowDialog
                    onClose={() => setShowEditWorkflowDialog(false)}
                    onSave={() =>
                        queryClient.invalidateQueries({
                            queryKey: ProjectWorkflowKeys.projectWorkflow(project.id!, parseInt(workflow.id!)),
                        })
                    }
                    updateWorkflowMutation={updateWorkflowMutation}
                    useGetWorkflowQuery={useGetWorkflowQuery}
                    workflowId={workflow.id!}
                />
            )}

            {showErrorWorkflowDialog && (
                <ErrorWorkflowDialog
                    onClose={() => setShowErrorWorkflowDialog(false)}
                    projectId={String(project.id!)}
                    projectVersion={project.lastProjectVersion!}
                />
            )}

            {showProjectGitConfigurationDialog && (
                <EEVersion hidden={true}>
                    <Suspense fallback={null}>
                        <ProjectGitConfigurationDialog
                            onClose={() => setShowProjectGitConfigurationDialog(false)}
                            onUpdateProjectGitConfigurationSubmit={handleUpdateProjectGitConfigurationSubmit}
                            projectGitConfiguration={projectGitConfiguration}
                            projectId={project.id!}
                        />
                    </Suspense>
                </EEVersion>
            )}

            {showProjectShareDialog && (
                <ProjectShareDialog
                    onOpenChange={() => setShowProjectShareDialog(false)}
                    open={showProjectShareDialog}
                    projectId={project.id!}
                    projectUuid={project.uuid!}
                    projectVersion={project.lastProjectVersion!}
                />
            )}

            {showProjectVersionHistorySheet && projectVersions && (
                <ProjectVersionHistorySheet
                    onSheetOpenChange={setShowProjectVersionHistorySheet}
                    projectVersions={projectVersions}
                    sheetOpen={showProjectVersionHistorySheet}
                />
            )}

            {showProjectVisibilityDialog && (
                <ProjectVisibilityDialog
                    onClose={() => setShowProjectVisibilityDialog(false)}
                    projectId={project.id!}
                    visibility={project.visibility}
                />
            )}

            {showWorkflowErrorHandlingDialog && workflow && (
                <WorkflowErrorHandlingDialog
                    onClose={() => setShowWorkflowErrorHandlingDialog(false)}
                    projectId={String(project.id!)}
                    projectVersion={project.lastProjectVersion!}
                    projectWorkflowId={String(workflow.projectWorkflowId)}
                />
            )}

            {showWorkflowShareDialog && workflow && (
                <WorkflowShareDialog
                    onOpenChange={() => setShowWorkflowShareDialog(false)}
                    open={showWorkflowShareDialog}
                    projectVersion={project.lastProjectVersion!}
                    workflowId={workflow.id!}
                    workflowUuid={workflow.workflowUuid!}
                />
            )}
        </>
    );
};

export default SettingsMenu;
