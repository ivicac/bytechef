/**
 * An empty chunking box means "take the platform's own default" on create and "leave the stored value alone" on
 * update. In both cases the field is omitted from the mutation input, which is exactly what its optionality on
 * `KnowledgeBaseInput`, `CreateEmbeddedKnowledgeBaseInput` and `UpdateEmbeddedKnowledgeBaseInput` is for.
 *
 * The create dialog therefore holds no chunking default of its own. A number written on this side would be a second
 * copy of `KnowledgeBase`'s field initializers with nothing connecting the two -- which is how the dialog came to
 * send a minimum chunk size of 1 against the entity's 100 for as long as it existed. Both sides were correct read
 * alone; the contradiction lived in the gap, and no test could see it because neither side named the other.
 *
 * Sending `parseInt('')` instead would put a NaN on the wire, and clearing a number input to retype it is a normal
 * thing to do mid-edit.
 */
export const toOptionalChunkingInt = (value: string): number | undefined => {
    const parsed = parseInt(value);

    return Number.isNaN(parsed) ? undefined : parsed;
};
