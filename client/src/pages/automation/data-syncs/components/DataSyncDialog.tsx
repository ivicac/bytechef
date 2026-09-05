import Button from '@/components/Button/Button';
import {Input} from '@/components/Input/Input';
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
import invalidateDataSyncQueries from '@/pages/automation/data-syncs/utils/invalidateDataSyncQueries';
import {useWorkspaceStore} from '@/pages/automation/stores/useWorkspaceStore';
import {useCreateDataSyncMutation, useUpdateDataSyncMutation} from '@/shared/middleware/graphql';
import {zodResolver} from '@hookform/resolvers/zod';
import {useQueryClient} from '@tanstack/react-query';
import {ReactNode, useState} from 'react';
import {useForm} from 'react-hook-form';
import {useNavigate} from 'react-router-dom';
import {z} from 'zod';

const formSchema = z.object({
    description: z.string(),
    title: z.string().min(1, {message: 'Title is required'}),
});

type FormValuesType = z.infer<typeof formSchema>;

interface DataSyncDialogProps {
    /** Present = edit an existing data sync's name and description; absent = create a new one. */
    dataSync?: {description?: string | null; id: string; title: string};
    onOpenChange?: (open: boolean) => void;
    open?: boolean;
    triggerNode?: ReactNode;
}

const DataSyncDialog = ({dataSync, onOpenChange, open: controlledOpen, triggerNode}: DataSyncDialogProps) => {
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

    const form = useForm<FormValuesType>({
        defaultValues: {
            description: dataSync?.description ?? '',
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
                    title: values.title,
                    workspaceId: currentWorkspaceId + '',
                },
            },
            {
                onSuccess: (data) => {
                    invalidateDataSyncQueries(queryClient);

                    setOpen(false);

                    navigate(`/automation/data-syncs/${data.createDataSync.id}`);
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
