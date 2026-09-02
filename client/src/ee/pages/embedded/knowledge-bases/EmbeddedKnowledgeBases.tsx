import Button from '@/components/Button/Button';
import EmptyList from '@/components/EmptyList';
import PageLoader from '@/components/PageLoader';
import EmbeddedKnowledgeBaseList from '@/ee/pages/embedded/knowledge-bases/components/EmbeddedKnowledgeBaseList';
import EnvironmentSelect from '@/shared/components/EnvironmentSelect';
import CreateKnowledgeBaseDialog from '@/shared/components/knowledge-bases/components/CreateKnowledgeBaseDialog';
import useKnowledgeBases from '@/shared/components/knowledge-bases/components/hooks/useKnowledgeBases';
import Header from '@/shared/layout/Header';
import LayoutContainer from '@/shared/layout/LayoutContainer';
import {BookOpenIcon} from 'lucide-react';

const EmbeddedKnowledgeBases = () => {
    const {error, isLoading, knowledgeBases} = useKnowledgeBases({type: 'EMBEDDED'});

    return (
        <PageLoader errors={[error]} loading={isLoading}>
            <LayoutContainer
                header={
                    <Header
                        centerTitle={true}
                        position="main"
                        right={
                            <div className="flex items-center gap-1">
                                <EnvironmentSelect />

                                <CreateKnowledgeBaseDialog
                                    scope={{type: 'EMBEDDED'}}
                                    trigger={<Button>New Knowledge Base</Button>}
                                />
                            </div>
                        }
                        title="Knowledge Bases"
                    />
                }
            >
                {knowledgeBases.length > 0 ? (
                    <EmbeddedKnowledgeBaseList knowledgeBases={knowledgeBases} />
                ) : (
                    <EmptyList
                        button={
                            <CreateKnowledgeBaseDialog
                                scope={{type: 'EMBEDDED'}}
                                trigger={<Button>Create Knowledge Base</Button>}
                            />
                        }
                        icon={<BookOpenIcon className="size-24 text-stroke-neutral-tertiary" />}
                        message="Knowledge bases you create appear here. Every account in this environment sees them, and each account's chunks stay its own."
                        title="No Knowledge Bases"
                    />
                )}
            </LayoutContainer>
        </PageLoader>
    );
};

export default EmbeddedKnowledgeBases;
