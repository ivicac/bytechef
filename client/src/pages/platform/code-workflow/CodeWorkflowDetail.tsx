import Badge from '@/components/Badge/Badge';
import Button from '@/components/Button/Button';
import LoadingIcon from '@/components/LoadingIcon';
import PageLoader from '@/components/PageLoader';
import Header from '@/shared/layout/Header';
import LayoutContainer from '@/shared/layout/LayoutContainer';
import {
    CodeWorkflowLanguage,
    useCodeWorkflowSourceQuery,
    useUpdateCodeWorkflowSourceMutation,
} from '@/shared/middleware/graphql';
import {useQueryClient} from '@tanstack/react-query';
import {Suspense, lazy, useRef, useState} from 'react';

const MonacoEditorWrapper = lazy(() => import('@/shared/components/MonacoEditorWrapper'));

const MONACO_LANGUAGE_BY_CODE_WORKFLOW_LANGUAGE: Record<CodeWorkflowLanguage, string> = {
    [CodeWorkflowLanguage.Javascript]: 'javascript',
    [CodeWorkflowLanguage.Python]: 'python',
    [CodeWorkflowLanguage.Ruby]: 'ruby',
};

interface CodeWorkflowDetailHeaderProps {
    isSaveDisabled: boolean;
    isSaving: boolean;
    language: string;
    onSave: () => void;
}

const CodeWorkflowDetailHeader = ({isSaveDisabled, isSaving, language, onSave}: CodeWorkflowDetailHeaderProps) => (
    <Header
        centerTitle
        position="main"
        right={<Button disabled={isSaveDisabled} label={isSaving ? 'Saving...' : 'Save'} onClick={onSave} size="sm" />}
        title={
            <div className="flex items-center gap-2">
                <span>Code Workflow</span>

                <Badge label={language} styleType="secondary-filled" weight="semibold" />
            </div>
        }
    />
);

interface CodeWorkflowSourceEditorProps {
    monacoLanguage: string;
    onChange: (value: string | undefined) => void;
    value: string;
}

const CodeWorkflowSourceEditor = ({monacoLanguage, onChange, value}: CodeWorkflowSourceEditorProps) => (
    <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
        <div className="relative min-h-0 flex-1">
            <div className="absolute inset-0">
                <Suspense
                    fallback={
                        <div className="flex items-center justify-center p-8">
                            <LoadingIcon />
                        </div>
                    }
                >
                    <MonacoEditorWrapper
                        defaultLanguage={monacoLanguage}
                        onChange={onChange}
                        onMount={() => {}}
                        options={{
                            automaticLayout: true,
                            folding: true,
                            lineNumbers: 'on',
                            minimap: {enabled: false},
                            scrollBeyondLastLine: false,
                            wordWrap: 'on',
                        }}
                        value={value}
                    />
                </Suspense>
            </div>
        </div>
    </div>
);

interface CodeWorkflowDetailProps {
    language: string;
    projectId: string;
}

const CodeWorkflowDetail = ({language, projectId}: CodeWorkflowDetailProps) => {
    const [isSourceDirty, setIsSourceDirty] = useState(false);

    const latestSourceRef = useRef('');

    const queryClient = useQueryClient();

    const {
        data: sourceData,
        error: sourceError,
        isLoading: sourceLoading,
    } = useCodeWorkflowSourceQuery({projectId}, {enabled: !!projectId});

    const updateCodeWorkflowSourceMutation = useUpdateCodeWorkflowSourceMutation({
        onSuccess: () => {
            setIsSourceDirty(false);

            queryClient.invalidateQueries({queryKey: ['codeWorkflowSource', {projectId}]});
        },
    });

    const sourceValue = sourceData?.codeWorkflowSource ?? '';
    const monacoLanguage = MONACO_LANGUAGE_BY_CODE_WORKFLOW_LANGUAGE[language as CodeWorkflowLanguage];

    const handleSave = () => {
        updateCodeWorkflowSourceMutation.mutate({content: latestSourceRef.current, projectId});
    };

    const handleSourceChange = (value: string | undefined) => {
        setIsSourceDirty(true);
        latestSourceRef.current = value ?? '';
    };

    return (
        <LayoutContainer
            header={
                <CodeWorkflowDetailHeader
                    isSaveDisabled={!isSourceDirty || updateCodeWorkflowSourceMutation.isPending}
                    isSaving={updateCodeWorkflowSourceMutation.isPending}
                    language={language}
                    onSave={handleSave}
                />
            }
            leftSidebarOpen={false}
        >
            <PageLoader errors={[sourceError]} loading={sourceLoading}>
                {monacoLanguage && (
                    <CodeWorkflowSourceEditor
                        monacoLanguage={monacoLanguage}
                        onChange={handleSourceChange}
                        value={isSourceDirty ? latestSourceRef.current : sourceValue}
                    />
                )}
            </PageLoader>
        </LayoutContainer>
    );
};

export default CodeWorkflowDetail;
