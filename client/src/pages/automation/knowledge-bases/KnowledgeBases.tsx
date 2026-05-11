import Button from '@/components/Button/Button';
import EmptyList from '@/components/EmptyList';
import PageLoader from '@/components/PageLoader';
import CreateKnowledgeBaseDialog from '@/pages/automation/knowledge-bases/components/CreateKnowledgeBaseDialog';
import KnowledgeBaseEmbeddingInactiveAlert from '@/pages/automation/knowledge-bases/components/KnowledgeBaseEmbeddingInactiveAlert';
import KnowledgeBasesFilterTitle from '@/pages/automation/knowledge-bases/components/KnowledgeBasesFilterTitle';
import KnowledgeBasesLeftSidebarNav from '@/pages/automation/knowledge-bases/components/KnowledgeBasesLeftSidebarNav';
import useKnowledgeBaseEmbeddingActive from '@/pages/automation/knowledge-bases/components/hooks/useKnowledgeBaseEmbeddingActive';
import useKnowledgeBases from '@/pages/automation/knowledge-bases/components/hooks/useKnowledgeBases';
import KnowledgeBaseList from '@/pages/automation/knowledge-bases/components/knowledge-base-list/KnowledgeBaseList';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import StorageUsageBanner from '@/shared/components/StorageUsageBanner';
import CopilotButton from '@/shared/components/copilot/CopilotButton';
import useCopilotPostTurnRegistry from '@/shared/components/copilot/stores/useCopilotPostTurnRegistry';
import {Source} from '@/shared/components/copilot/stores/useCopilotStore';
import Header from '@/shared/layout/Header';
import LayoutContainer from '@/shared/layout/LayoutContainer';
import {useKnowledgeBaseStorageUsageQuery} from '@/shared/middleware/graphql';
import {useQueryClient} from '@tanstack/react-query';
import {DatabaseIcon} from 'lucide-react';
import {useEffect} from 'react';

const KnowledgeBases = () => {
    const currentWorkspaceId = String(useWorkspaceStore((state) => state.currentWorkspaceId));

    const {allTags, error, filteredKnowledgeBases, isLoading, knowledgeBases, tagId, tagsByKnowledgeBaseData} =
        useKnowledgeBases();

    const {embeddingActive, isLoading: embeddingActiveLoading} = useKnowledgeBaseEmbeddingActive();

    const showKnowledgeBases = embeddingActive && knowledgeBases.length > 0;

    const {data: storageUsageData} = useKnowledgeBaseStorageUsageQuery();

    const storageUsage = storageUsageData?.knowledgeBaseStorageUsage;

    const registerPostTurn = useCopilotPostTurnRegistry((state) => state.register);

    const queryClient = useQueryClient();

    // Refresh the list and the tag sidebar after a BUILD-mode copilot turn creates or retags a knowledge base.
    useEffect(() => {
        return registerPostTurn(Source.KNOWLEDGE_BASE, () => {
            queryClient.invalidateQueries({queryKey: ['knowledgeBases']});
            queryClient.invalidateQueries({queryKey: ['knowledgeBaseTags']});
            queryClient.invalidateQueries({queryKey: ['knowledgeBaseTagsByKnowledgeBase']});
            queryClient.invalidateQueries({queryKey: ['KnowledgeBaseStorageUsage']});
        });
    }, [queryClient, registerPostTurn]);

    return (
        <LayoutContainer
            header={
                <Header
                    centerTitle={true}
                    position="main"
                    right={
                        embeddingActive &&
                        (knowledgeBases.length > 0 || !isLoading) && (
                            <div className="flex items-center gap-1">
                                <CopilotButton source={Source.KNOWLEDGE_BASE} />

                                {showKnowledgeBases && (
                                    // This is the "Create knowledge base" command's target.
                                    <CreateKnowledgeBaseDialog
                                        claimsCreateIntent={true}
                                        trigger={<Button>New Knowledge Base</Button>}
                                        workspaceId={currentWorkspaceId}
                                    />
                                )}
                            </div>
                        )
                    }
                    title={
                        showKnowledgeBases ? (
                            <KnowledgeBasesFilterTitle
                                allTags={allTags}
                                tagsByKnowledgeBaseData={tagsByKnowledgeBaseData}
                            />
                        ) : (
                            ''
                        )
                    }
                />
            }
            leftSidebarBody={embeddingActive && <KnowledgeBasesLeftSidebarNav />}
            leftSidebarHeader={<Header position="sidebar" title="Knowledge Bases" />}
            leftSidebarWidth="64"
        >
            <PageLoader errors={[error]} loading={isLoading || embeddingActiveLoading}>
                {embeddingActive ? (
                    <div className="flex size-full flex-col">
                        {storageUsage && (
                            <StorageUsageBanner
                                label="Knowledge base"
                                limitBytes={storageUsage.limitBytes}
                                percentage={storageUsage.percentage}
                                unlimited={storageUsage.unlimited}
                                usedBytes={storageUsage.usedBytes}
                            />
                        )}

                        <div className="flex flex-1">
                            {filteredKnowledgeBases.length > 0 ? (
                                <KnowledgeBaseList
                                    allTags={allTags}
                                    knowledgeBases={filteredKnowledgeBases}
                                    tagsByKnowledgeBaseData={tagsByKnowledgeBaseData}
                                />
                            ) : (
                                <EmptyList
                                    button={
                                        // This is the "Create knowledge base" command's target.
                                        <CreateKnowledgeBaseDialog
                                            claimsCreateIntent={true}
                                            trigger={<Button>Create Knowledge Base</Button>}
                                            workspaceId={currentWorkspaceId}
                                        />
                                    }
                                    icon={<DatabaseIcon className="size-24 text-stroke-neutral-tertiary" />}
                                    message={
                                        tagId
                                            ? 'No knowledge bases match the selected tag.'
                                            : 'Get started by creating a new knowledge base.'
                                    }
                                    title={tagId ? 'No Matching Knowledge Bases' : 'No Knowledge Bases'}
                                />
                            )}
                        </div>
                    </div>
                ) : (
                    <KnowledgeBaseEmbeddingInactiveAlert />
                )}
            </PageLoader>
        </LayoutContainer>
    );
};

export default KnowledgeBases;
