import Button from '@/components/Button/Button';
import {Input} from '@/components/Input/Input';
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/Select/Select';
import {
    Dialog,
    DialogClose,
    DialogCloseButton,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
    DialogTrigger,
} from '@/components/ui/dialog';
import {Form, FormControl, FormField, FormItem, FormLabel, FormMessage} from '@/components/ui/form';
import {Textarea} from '@/components/ui/textarea';
import getDataSyncPath from '@/pages/automation/data-syncs/utils/getDataSyncPath';
import invalidateDataSyncQueries from '@/pages/automation/data-syncs/utils/invalidateDataSyncQueries';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import {useCreateDataSyncMutation, useUpdateDataSyncMutation} from '@/shared/middleware/graphql';
import {useGetWorkspaceProjectsQuery} from '@/shared/queries/automation/projects.queries';
import {zodResolver} from '@hookform/resolvers/zod';
import {useQueryClient} from '@tanstack/react-query';
import {ReactNode, useState} from 'react';
import {useForm} from 'react-hook-form';
import {useNavigate} from 'react-router-dom';
import {z} from 'zod';

const formSchema = z.object({
    description: z.string(),
    projectId: z.string().optional(),
    title: z.string().min(1, {message: 'Title is required'}),
});

type FormValuesType = z.infer<typeof formSchema>;

interface DataSyncDialogProps {
    /** Present = edit an existing data sync's name and description; absent = create a new one. */
    dataSync?: {description?: string | null; id: string; title: string};
    onOpenChange?: (open: boolean) => void;
    open?: boolean;
    /** Locks the target project — the dialog was opened from inside it. Absent = the user picks, defaulting to a new project. */
    projectId?: number;
    triggerNode?: ReactNode;
}

const DataSyncDialog = ({
    dataSync,
    onOpenChange,
    open: controlledOpen,
    projectId,
    triggerNode,
}: DataSyncDialogProps) => {
    const [uncontrolledOpen, setUncontrolledOpen] = useState(false);

    // Controlled when opened from a menu item (which unmounts its own trigger on select), uncontrolled when it
    // owns a trigger node of its own.
    const open = controlledOpen ?? uncontrolledOpen;

    const setOpen = (nextOpen: boolean) => {
        (onOpenChange ?? setUncontrolledOpen)(nextOpen);
    };

    const currentWorkspaceId = useWorkspaceStore((state) => state.currentWorkspaceId);

    const navigate = useNavigate();
    const queryClient = useQueryClient();

    // Only fetched when the dialog can actually offer the picker — editing an existing data sync or opening
    // it with a locked project never renders the Select, so the query would otherwise fire without being used.
    const {data: projects} = useGetWorkspaceProjectsQuery({id: currentWorkspaceId}, !dataSync && !projectId);

    const form = useForm<FormValuesType>({
        defaultValues: {
            description: dataSync?.description ?? '',
            projectId: projectId ? String(projectId) : 'new',
            title: dataSync?.title ?? '',
        },
        resolver: zodResolver(formSchema),
    });

    const createDataSyncMutation = useCreateDataSyncMutation();
    const updateDataSyncMutation = useUpdateDataSyncMutation();

    const onSubmit = (values: FormValuesType) => {
        if (dataSync) {
            updateDataSyncMutation.mutate(
                {input: {description: values.description, id: dataSync.id, title: values.title}},
                {
                    onSuccess: () => {
                        invalidateDataSyncQueries(queryClient);

                        setOpen(false);
                    },
                }
            );

            return;
        }

        createDataSyncMutation.mutate(
            {
                input: {
                    description: values.description,
                    projectId: values.projectId && values.projectId !== 'new' ? values.projectId : undefined,
                    title: values.title,
                    workspaceId: currentWorkspaceId + '',
                },
            },
            {
                onSuccess: (data) => {
                    // A "new project" create just created one behind the scenes — without this the projects
                    // list would keep showing its stale state until something else happened to refetch it.
                    invalidateDataSyncQueries(queryClient, {projects: true});

                    setOpen(false);

                    navigate(getDataSyncPath(data.createDataSync));
                },
            }
        );

        form.reset({});
    };

    return (
        <Dialog onOpenChange={setOpen} open={open}>
            {triggerNode && <DialogTrigger asChild>{triggerNode}</DialogTrigger>}

            <DialogContent>
                <DialogHeader className="flex flex-row items-center justify-between space-y-0">
                    <div className="flex flex-col space-y-1">
                        <DialogTitle>{dataSync ? 'Edit Data Sync' : 'Create Data Sync'}</DialogTitle>

                        <DialogDescription>
                            {dataSync
                                ? "Change the data sync's name and description."
                                : 'Create a new data sync by filling out the form below.'}
                        </DialogDescription>
                    </div>

                    <DialogCloseButton />
                </DialogHeader>

                <Form {...form}>
                    <form className="space-y-4" onSubmit={form.handleSubmit(onSubmit)}>
                        <FormField
                            control={form.control}
                            name="title"
                            render={({field}) => (
                                <FormItem>
                                    <FormLabel>Title</FormLabel>

                                    <FormControl>
                                        <Input placeholder="Enter data sync title" {...field} />
                                    </FormControl>

                                    <FormMessage />
                                </FormItem>
                            )}
                        />

                        {!dataSync && !projectId && (
                            <FormField
                                control={form.control}
                                name="projectId"
                                render={({field}) => (
                                    <FormItem>
                                        <FormLabel>Project</FormLabel>

                                        <FormControl>
                                            <Select onValueChange={field.onChange} value={field.value}>
                                                <SelectTrigger>
                                                    <SelectValue />
                                                </SelectTrigger>

                                                <SelectContent>
                                                    <SelectItem value="new">
                                                        New project named after this data sync
                                                    </SelectItem>

                                                    {projects?.map((project) => (
                                                        <SelectItem key={project.id} value={String(project.id)}>
                                                            {project.name}
                                                        </SelectItem>
                                                    ))}
                                                </SelectContent>
                                            </Select>
                                        </FormControl>

                                        <FormMessage />
                                    </FormItem>
                                )}
                            />
                        )}

                        <FormField
                            control={form.control}
                            name="description"
                            render={({field}) => (
                                <FormItem>
                                    <FormLabel>Description</FormLabel>

                                    <FormControl>
                                        <Textarea
                                            placeholder="Enter a description for the data sync"
                                            rows={3}
                                            {...field}
                                        />
                                    </FormControl>

                                    <FormMessage />
                                </FormItem>
                            )}
                        />

                        <DialogFooter>
                            <DialogClose asChild>
                                <Button label="Cancel" type="button" variant="outline" />
                            </DialogClose>

                            <Button label="Save" type="submit" />
                        </DialogFooter>
                    </form>
                </Form>
            </DialogContent>
        </Dialog>
    );
};

export default DataSyncDialog;
