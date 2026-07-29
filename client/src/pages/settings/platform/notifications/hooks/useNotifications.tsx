import {Notification, NotificationEventTypeEnum, NotificationTypeEnum} from '@/shared/middleware/platform/notification';
import {
    useCreateNotificationMutation,
    useDeleteNotificationMutation,
    useRegenerateNotificationSigningSecretMutation,
    useTestNotificationMutation,
    useUpdateNotificationMutation,
} from '@/shared/mutations/platform/notifications.mutations';
import {useGetNotificationEventsQuery} from '@/shared/queries/platform/notificationEvents.queries';
import {NotificationKeys, useGetNotificationsQuery} from '@/shared/queries/platform/notifications.queries';
import {coreTableFeatures} from '@/shared/util/table-features';
import {zodResolver} from '@hookform/resolvers/zod';
import {useQueryClient} from '@tanstack/react-query';
import {createColumnHelper} from '@tanstack/react-table';
import {useEffect, useMemo, useState} from 'react';
import {useForm} from 'react-hook-form';
import z from 'zod';

import {ActionsCell, EventsCell} from '../components/NotificationTableCell';

export type NotificationFormValuesType = {
    name: string;
    notificationEventIds: string[];
    settings: Record<string, unknown>;
    type: NotificationTypeEnum;
};

const formSchema = z.object({
    name: z.string().min(1, 'Name is required').max(256, 'Name cannot be longer than 256 characters'),
    notificationEventIds: z.array(z.string()).nonempty('Please select at least one notification event.'),
    settings: z.record(z.string(), z.any()),
    type: z.enum(['EMAIL', 'SLACK', 'WEBHOOK'], {
        message: 'Please select a notification type.',
    }),
});

interface BuildNotificationPayloadPropsI {
    formValues: NotificationFormValuesType;
    latestNotification?: Notification;
    selectedNotification?: Notification;
}

function buildNotificationPayload({
    formValues,
    latestNotification,
    selectedNotification,
}: BuildNotificationPayloadPropsI): Notification {
    return {
        ...selectedNotification,
        ...formValues,
        ...{notificationEventIds: formValues.notificationEventIds.map((id: string) => Number(id))},
        settings: trimUrlSettings(formValues.settings),
        version: latestNotification?.version ?? selectedNotification?.version,
    };
}

const emptyFormValues = {
    name: '',
    notificationEventIds: [],
    settings: {},
    type: NotificationTypeEnum.Email,
};

export default function useNotifications() {
    const [isEditDialogOpen, setIsEditDialogOpen] = useState(false);
    const [isDeleteDialogOpen, setIsDeleteDialogOpen] = useState(false);
    const [selectedNotification, setSelectedNotification] = useState<Notification | undefined>(undefined);
    const [regeneratedNotificationId, setRegeneratedNotificationId] = useState<number | undefined>(undefined);
    const [signingSecret, setSigningSecret] = useState<string | undefined>(undefined);

    const {
        data: notificationsData,
        error: notificationsError,
        isLoading: isNotificationsLoading,
    } = useGetNotificationsQuery();

    const {data: allNotificationEvents, isLoading: isNotificationEventsLoading} = useGetNotificationEventsQuery();

    const queryClient = useQueryClient();

    const deleteNotificationMutation = useDeleteNotificationMutation({
        onSuccess: () => {
            void queryClient.invalidateQueries({
                queryKey: NotificationKeys,
            });
        },
    });

    const onSuccess = () => {
        void queryClient.invalidateQueries({
            queryKey: NotificationKeys,
        });

        closeEditDialog();
    };

    const onSaveSuccess = (result?: Notification) => {
        onSuccess();

        if (result?.signingSecret) {
            setSigningSecret(result.signingSecret);
        }
    };

    const createNotificationMutation = useCreateNotificationMutation({onSuccess: onSaveSuccess});
    const updateNotificationMutation = useUpdateNotificationMutation({onSuccess: onSaveSuccess});
    const regenerateNotificationSigningSecretMutation = useRegenerateNotificationSigningSecretMutation({
        onSuccess: (result, notificationId) => {
            void queryClient.invalidateQueries({
                queryKey: NotificationKeys,
            });

            setRegeneratedNotificationId(notificationId);

            setSigningSecret(result.signingSecret);
        },
    });

    const testNotificationMutation = useTestNotificationMutation();

    function regenerateSigningSecret(notificationId: number) {
        regenerateNotificationSigningSecretMutation.mutate(notificationId);
    }

    function closeSigningSecretDialog() {
        setSigningSecret(undefined);
    }

    const handleDeleteNotification = (notificationId: number) => {
        if (notificationId) {
            deleteNotificationMutation.mutate(notificationId);

            setIsDeleteDialogOpen(false);
        }
    };

    function openDeleteDialog(notification: Notification) {
        setSelectedNotification(notification);

        setIsDeleteDialogOpen(true);
    }

    function openEditDialog(notification?: Notification) {
        setSelectedNotification(notification || undefined);

        setIsEditDialogOpen(true);
    }

    function closeDeleteDialog() {
        setIsDeleteDialogOpen(false);
    }

    function closeEditDialog() {
        setIsEditDialogOpen(false);

        setSelectedNotification(undefined);

        reset();
    }

    function getNotificationPayload(): Notification {
        const latestNotification = notificationsData?.find(
            (notification) => notification.id === selectedNotification?.id
        );

        return buildNotificationPayload({
            formValues: getValues(),
            latestNotification,
            selectedNotification,
        });
    }

    function testNotification() {
        return testNotificationMutation.mutateAsync(getNotificationPayload());
    }

    function saveNotification() {
        const updatedFormData = getNotificationPayload();

        if (selectedNotification?.id) {
            updateNotificationMutation.mutate(updatedFormData);
        } else {
            createNotificationMutation.mutate(updatedFormData);
        }
    }

    const currentNotification = useMemo(
        () =>
            selectedNotification && selectedNotification.id === regeneratedNotificationId
                ? {...selectedNotification, hasSigningSecret: true}
                : selectedNotification,
        [regeneratedNotificationId, selectedNotification]
    );

    const notificationEvents = useMemo(
        () =>
            allNotificationEvents?.filter(
                (notificationEvent) => notificationEvent.type !== NotificationEventTypeEnum.JobCancelled
            ),
        [allNotificationEvents]
    );

    const columnHelper = createColumnHelper<typeof coreTableFeatures, Notification>();

    const columns = columnHelper.columns([
        columnHelper.accessor('name', {
            cell: (name) => <>{name.getValue()}</>,
            header: 'Name',
            meta: {
                width: '25%',
            },
        }),
        columnHelper.accessor('notificationEvents', {
            cell: (events) => <EventsCell notificationEvents={events.getValue() ?? []} />,
            header: 'Events',
        }),
        columnHelper.accessor('lastModifiedDate', {
            cell: (lastModified) => (
                <span className="whitespace-nowrap">{lastModified.getValue()?.toLocaleString()}</span>
            ),
            header: 'Last Modified Date',
        }),
        columnHelper.accessor('lastModifiedBy', {
            cell: (lastModifiedBy) => <span className="whitespace-nowrap">{lastModifiedBy.getValue()}</span>,
            header: 'Last Modified By',
        }),
        columnHelper.display({
            cell: (table) => (
                <ActionsCell
                    notification={table.row.original}
                    openDeleteDialog={openDeleteDialog}
                    openEditDialog={openEditDialog}
                />
            ),
            header: 'Actions',
            id: 'actions',
        }),
    ]);

    const form = useForm<z.infer<typeof notificationFormSchema>>({
        defaultValues: emptyFormValues,
        resolver: zodResolver(notificationFormSchema),
    });

    const {getValues, reset} = form;

    useEffect(() => {
        if (selectedNotification) {
            form.reset({
                name: selectedNotification.name || '',
                notificationEventIds: selectedNotification.notificationEvents?.map((event) => String(event.id)) || [],
                settings: selectedNotification.settings || {},
                type: selectedNotification.type || NotificationTypeEnum.Email,
            });
        } else {
            form.reset(emptyFormValues);
        }
    }, [selectedNotification, form]);

    return {
        closeDeleteDialog,
        closeEditDialog,
        closeSigningSecretDialog,
        columns,
        deleteNotificationMutation,
        form,
        handleDeleteNotification,
        isDeleteDialogOpen,
        isEditDialogOpen,
        isNotificationEventsLoading,
        isNotificationsLoading,
        notificationEvents,
        notificationsData,
        notificationsError,
        openDeleteDialog,
        openEditDialog,
        queryClient,
        regenerateSigningSecret,
        saveNotification,
        selectedNotification: currentNotification,
        setSelectedNotification,
        signingSecret,
        testNotification,
    };
}
