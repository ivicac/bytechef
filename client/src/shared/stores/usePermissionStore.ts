import {create} from 'zustand';
import {devtools} from 'zustand/middleware';

export type WorkspaceScopePermissionStateType =
    | {readonly status: 'loading'}
    | {readonly status: 'error'}
    | {readonly scopes: readonly string[]; readonly status: 'loaded'};

export type WorkspacePermissionStateType =
    {readonly status: 'loading'} | {readonly status: 'error'} | {readonly role: string; readonly status: 'loaded'};

export type WorkspaceScopeStatesType = {
    [workspaceId: number]: {[environmentId: number]: WorkspaceScopePermissionStateType};
};

interface PermissionStoreI {
    clearPermissions: () => void;
    clearWorkspaceRole: (workspaceId: number) => void;
    setWorkspaceError: (workspaceId: number) => void;
    setWorkspaceLoading: (workspaceId: number) => void;
    setWorkspaceRole: (workspaceId: number, role: string) => void;
    setWorkspaceScopeError: (workspaceId: number, environmentId: number) => void;
    setWorkspaceScopeLoading: (workspaceId: number, environmentId: number) => void;
    setWorkspaceScopePermissions: (workspaceId: number, environmentId: number, scopes: string[]) => void;
    workspaceScopeStates: WorkspaceScopeStatesType;
    workspaceStates: {[workspaceId: number]: WorkspacePermissionStateType};
}

const withWorkspaceScopeState = (
    workspaceScopeStates: WorkspaceScopeStatesType,
    workspaceId: number,
    environmentId: number,
    workspaceScopeState: WorkspaceScopePermissionStateType
): WorkspaceScopeStatesType => ({
    ...workspaceScopeStates,
    [workspaceId]: {...workspaceScopeStates[workspaceId], [environmentId]: workspaceScopeState},
});

export const permissionStore = create<PermissionStoreI>()(
    devtools(
        (set) => ({
            clearPermissions: () =>
                set({
                    workspaceScopeStates: {},
                    workspaceStates: {},
                }),
            clearWorkspaceRole: (workspaceId) =>
                set((state) => {
                    const nextWorkspaceStates = {...state.workspaceStates};

                    delete nextWorkspaceStates[workspaceId];

                    return {workspaceStates: nextWorkspaceStates};
                }),
            setWorkspaceError: (workspaceId) =>
                set((state) => ({
                    workspaceStates: {...state.workspaceStates, [workspaceId]: {status: 'error'}},
                })),
            setWorkspaceLoading: (workspaceId) =>
                set((state) => ({
                    workspaceStates: {...state.workspaceStates, [workspaceId]: {status: 'loading'}},
                })),
            setWorkspaceRole: (workspaceId, role) =>
                set((state) => ({
                    workspaceStates: {...state.workspaceStates, [workspaceId]: {role, status: 'loaded'}},
                })),
            setWorkspaceScopeError: (workspaceId, environmentId) =>
                set((state) => ({
                    workspaceScopeStates: withWorkspaceScopeState(
                        state.workspaceScopeStates,
                        workspaceId,
                        environmentId,
                        {status: 'error'}
                    ),
                })),
            setWorkspaceScopeLoading: (workspaceId, environmentId) =>
                set((state) => ({
                    workspaceScopeStates: withWorkspaceScopeState(
                        state.workspaceScopeStates,
                        workspaceId,
                        environmentId,
                        {status: 'loading'}
                    ),
                })),
            setWorkspaceScopePermissions: (workspaceId, environmentId, scopes) =>
                set((state) => ({
                    workspaceScopeStates: withWorkspaceScopeState(
                        state.workspaceScopeStates,
                        workspaceId,
                        environmentId,
                        {scopes, status: 'loaded'}
                    ),
                })),
            workspaceScopeStates: {},
            workspaceStates: {},
        }),
        {name: 'PermissionStore'}
    )
);

export const usePermissionStore = permissionStore;
