import {KnowledgeBase} from '@/shared/middleware/graphql';
import {MouseEvent, useState} from 'react';
import {useNavigate} from 'react-router-dom';

interface UseKnowledgeBaseListItemProps {
    knowledgeBase: KnowledgeBase;
}

export default function useKnowledgeBaseListItem({knowledgeBase}: UseKnowledgeBaseListItemProps) {
    const [showDeleteDialog, setShowDeleteDialog] = useState(false);
    const [showEditDialog, setShowEditDialog] = useState(false);
    const [showRechunkDialog, setShowRechunkDialog] = useState(false);

    const navigate = useNavigate();

    const handleShowDeleteDialog = () => {
        setShowDeleteDialog(true);
    };

    const handleCloseDeleteDialog = () => {
        setShowDeleteDialog(false);
    };

    const handleEditClick = (event: MouseEvent) => {
        event.stopPropagation();

        setShowEditDialog(true);
    };

    const handleEditDialogOpenChange = (open: boolean) => {
        setShowEditDialog(open);
    };

    const handleShowRechunkDialog = () => {
        setShowRechunkDialog(true);
    };

    const handleCloseRechunkDialog = () => {
        setShowRechunkDialog(false);
    };

    const handleKnowledgeBaseClick = () => {
        navigate(`/automation/knowledge-bases/${knowledgeBase.id}`);
    };

    const handleTagListClick = (event: MouseEvent) => {
        event.preventDefault();
        event.stopPropagation();
    };

    return {
        handleCloseDeleteDialog,
        handleCloseRechunkDialog,
        handleEditClick,
        handleEditDialogOpenChange,
        handleKnowledgeBaseClick,
        handleShowDeleteDialog,
        handleShowRechunkDialog,
        handleTagListClick,
        showDeleteDialog,
        showEditDialog,
        showRechunkDialog,
    };
}
