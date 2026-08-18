const INPUT_REQUIRED_PREFIX = 'INPUT_REQUIRED:';
const MISSING_CONNECTION_PREFIX = 'MISSING_CONNECTION:';

/**
 * Turns the server's derived `attentionReason` code into a short sentence a connected user can act
 * on. `MISSING_CONNECTION:<component>` and `INPUT_REQUIRED:<name>` carry the missing piece after the
 * colon, and a bare prefix with nothing after it falls back to a generic noun; `UPDATE_PENDING` and any
 * other non-empty reason fall back to the re-enable message, since turning the automation off and on
 * again is what re-derives the flag server-side.
 */
export const describeAttentionReason = (attentionReason: string | undefined): string | undefined => {
    if (!attentionReason) {
        return undefined;
    }

    if (attentionReason.startsWith(MISSING_CONNECTION_PREFIX)) {
        const componentName = attentionReason.slice(MISSING_CONNECTION_PREFIX.length);

        return `Connect ${componentName || 'the missing connection'} to keep this running`;
    }

    if (attentionReason.startsWith(INPUT_REQUIRED_PREFIX)) {
        const inputName = attentionReason.slice(INPUT_REQUIRED_PREFIX.length);

        return `Fill in ${inputName || 'the required input'} to keep this running`;
    }

    return 'Turn it on again to apply the latest update';
};
