import {create} from 'zustand';
import {devtools} from 'zustand/middleware';

export type ReferencedResourceKindType =
    | 'aiAgent'
    | 'apiCollection'
    | 'dataTable'
    | 'file'
    | 'knowledgeBase'
    | 'mcpServer'
    | 'chat'
    | 'workflow'
    | 'workflowExecution';

export interface ReferencedResourceI {
    id: string;
    kind: ReferencedResourceKindType;
    name: string;
    /**
     * Whether removing this reference should CLOSE {@link tabId}, as opposed to merely detaching it. True
     * only when the attach itself opened the tab; a tab that was already open before the attach belongs to
     * the user, so removing the chip detaches it but leaves it on screen.
     */
    ownsTab?: boolean;
    /**
     * Id of the right-panel tab this reference is attached to. Attaching is a two-store write — a chip here
     * plus a viewer tab in {@link aiHubTabsStore} — so removal has to undo both, and this field is the link
     * that lets it.
     *
     * Without this link the tab outlived the chip, and since the home -> chat hand-off carries attached
     * home-view tabs into the chat the next prompt creates, useRecordReferencedArtifacts persisted it as a
     * chat artifact and buildStateToSend shipped it to the agent as `currentTabs` — a resource the user had
     * removed came back attached to the new chat.
     */
    tabId?: string;
}

/**
 * A skill picked through the composer's '/' menu. Unlike a referenced resource, a skill isn't sent as
 * agent state — it is prefixed onto the outgoing message as `/<name>` (see AiHubRuntimeProvider.onNew),
 * which is what the agent's prompt already teaches it to read. Holding the selection here instead of in
 * the composer text is what makes picking several skills possible, and lets each one render as a
 * removable chip rather than as raw text the user has to edit by hand.
 */
export interface SelectedSkillI {
    id: string;
    name: string;
}

interface AiHubComposerStateI {
    referencedResources: ReferencedResourceI[];
    /**
     * Whether the resource picker popover is showing. Lives in the store rather than inside
     * {@link ResourcePickerMenu} because the picker has two triggers that sit in different components: the "+"
     * button (rendered by AiHubComposer, which owns the menu) and the '@' key (pressed in the textarea, which
     * AiHubChatComposer owns). A keystroke in a sibling component has no other way to reach the menu's own
     * state, which is why '@' was inert for as long as it was.
     */
    resourcePickerOpen: boolean;
    selectedSkills: SelectedSkillI[];

    addReference: (resource: ReferencedResourceI) => void;
    addSkill: (skill: SelectedSkillI) => void;
    clear: () => void;
    removeReference: (id: string, kind: ReferencedResourceKindType) => void;
    removeSkill: (id: string) => void;
    setResourcePickerOpen: (open: boolean) => void;
}

/* eslint-disable sort-keys */
export const aiHubComposerStore = create<AiHubComposerStateI>()(
    devtools((set) => ({
        referencedResources: [],
        resourcePickerOpen: false,
        selectedSkills: [],

        addReference: (resource) =>
            set((state) => {
                const alreadyAdded = state.referencedResources.some(
                    (existingResource) => existingResource.id === resource.id && existingResource.kind === resource.kind
                );

                if (alreadyAdded) {
                    return state;
                }

                return {...state, referencedResources: [...state.referencedResources, resource]};
            }),

        addSkill: (skill) =>
            set((state) => {
                const alreadyAdded = state.selectedSkills.some((existingSkill) => existingSkill.id === skill.id);

                if (alreadyAdded) {
                    return state;
                }

                return {...state, selectedSkills: [...state.selectedSkills, skill]};
            }),

        clear: () =>
            set((state) => ({...state, referencedResources: [], resourcePickerOpen: false, selectedSkills: []})),

        removeReference: (id, kind) =>
            set((state) => ({
                ...state,
                referencedResources: state.referencedResources.filter(
                    (resource) => !(resource.id === id && resource.kind === kind)
                ),
            })),

        removeSkill: (id) =>
            set((state) => ({
                ...state,
                selectedSkills: state.selectedSkills.filter((skill) => skill.id !== id),
            })),

        setResourcePickerOpen: (open) => set((state) => ({...state, resourcePickerOpen: open})),
    }))
);

export const useAiHubComposerStore = aiHubComposerStore;
