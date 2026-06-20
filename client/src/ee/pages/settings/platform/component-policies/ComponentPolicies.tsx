import {Tabs, TabsContent, TabsList, TabsTrigger} from '@/components/ui/tabs';
import Header from '@/shared/layout/Header';
import LayoutContainer from '@/shared/layout/LayoutContainer';

import ComponentVisibilityTab from './components/ComponentVisibilityTab';

const ComponentPolicies = () => {
    return (
        <LayoutContainer
            header={<Header centerTitle={true} position="main" title="Component Policies" />}
            leftSidebarOpen={false}
        >
            <Tabs className="size-full p-4" defaultValue="component-visibility">
                <TabsList variant="line">
                    <TabsTrigger value="component-visibility">Component Visibility</TabsTrigger>
                </TabsList>

                <TabsContent value="component-visibility">
                    <ComponentVisibilityTab />
                </TabsContent>
            </Tabs>
        </LayoutContainer>
    );
};

export default ComponentPolicies;
