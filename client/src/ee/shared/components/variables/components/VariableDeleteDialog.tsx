import AlertDialog from '@/components/AlertDialog';
import useVariables from '@/ee/shared/components/variables/hooks/useVariables';
import {useVariablesStore} from '@/ee/shared/components/variables/stores/useVariablesStore';
import {useShallow} from 'zustand/react/shallow';

const VariableDeleteDialog = () => {
    const {currentVariable, setCurrentVariable, setShowDeleteDialog} = useVariablesStore(
        useShallow((state) => ({
            currentVariable: state.currentVariable,
            setCurrentVariable: state.setCurrentVariable,
            setShowDeleteDialog: state.setShowDeleteDialog,
        }))
    );

    const {handleDelete} = useVariables();

    const handleOpenChange = () => {
        setShowDeleteDialog(false);
        setCurrentVariable(undefined);
    };

    const handleConfirm = () => {
        if (currentVariable) {
            handleDelete(currentVariable.id);
        }
    };

    return (
        <AlertDialog
            description={`Are you sure you want to delete "${currentVariable?.name}"? Workflows referencing \${vars.${currentVariable?.name}} will see the literal expression instead of a value.`}
            onCancel={handleOpenChange}
            onConfirm={handleConfirm}
            open={true}
            title="Delete variable"
        />
    );
};

export default VariableDeleteDialog;
