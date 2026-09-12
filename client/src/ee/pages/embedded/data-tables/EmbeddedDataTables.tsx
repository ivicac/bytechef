import Button from '@/components/Button/Button';
import EmptyList from '@/components/EmptyList';
import PageLoader from '@/components/PageLoader';
import EmbeddedDataTableList from '@/ee/pages/embedded/data-tables/components/EmbeddedDataTableList';
import EnvironmentSelect from '@/shared/components/EnvironmentSelect';
import CreateDataTableDialog from '@/shared/components/data-tables/components/CreateDataTableDialog';
import useDataTables from '@/shared/components/data-tables/components/hooks/useDataTables';
import Header from '@/shared/layout/Header';
import LayoutContainer from '@/shared/layout/LayoutContainer';
import {Table2Icon} from 'lucide-react';

const EmbeddedDataTables = () => {
    const {error, isLoading, tables} = useDataTables({type: 'EMBEDDED'});

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

                                <CreateDataTableDialog
                                    scope={{type: 'EMBEDDED'}}
                                    trigger={<Button>New Table</Button>}
                                />
                            </div>
                        }
                        title="Data Tables"
                    />
                }
            >
                {tables.length > 0 ? (
                    <EmbeddedDataTableList dataTables={tables} />
                ) : (
                    <EmptyList
                        button={
                            <CreateDataTableDialog scope={{type: 'EMBEDDED'}} trigger={<Button>Create Table</Button>} />
                        }
                        icon={<Table2Icon className="size-24 text-stroke-neutral-tertiary" />}
                        message="Data tables you create appear here. Every account in this environment sees them, and each account's rows stay its own."
                        title="No Data Tables"
                    />
                )}
            </LayoutContainer>
        </PageLoader>
    );
};

export default EmbeddedDataTables;
