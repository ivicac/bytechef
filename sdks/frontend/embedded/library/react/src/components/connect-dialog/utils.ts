/**
 * Extracts the `sub` (external user id) claim from a JWT without verifying its signature. The SDK needs the external
 * user id for action-execution URLs; the server independently verifies the token, so client-side decoding is safe to
 * read claims from. Returns `undefined` when the token is malformed.
 */
export const decodeJwtSubject = (jwtToken: string): string | undefined => {
    try {
        const payloadSegment = jwtToken.split('.')[1];

        if (!payloadSegment) {
            return undefined;
        }

        const normalized = payloadSegment.replace(/-/g, '+').replace(/_/g, '/');
        const payload = JSON.parse(atob(normalized)) as {sub?: string};

        return payload.sub;
    } catch {
        return undefined;
    }
};

/**
 * Builds the cache key under which a dynamic input's resolved options are stored in (and read from) the
 * `workflowInputOptions` map. The key mirrors the identity tuple passed to `loadWorkflowInputOptions`
 * (`workflowUuid`, the input/group name, the property/member name, and the resolved dependency values) so that two
 * distinct inputs — or two group members in different groups — that happen to share a property/member name do not
 * collide on the same slot. The write side that populates `workflowInputOptions` MUST key with this same function.
 */
export const optionsCacheKey = (
    workflowUuid: string,
    inputName: string,
    propertyName: string,
    dependencyValues: Record<string, unknown>
): string => `${workflowUuid}:${inputName}:${propertyName}:${JSON.stringify(dependencyValues ?? {})}`;
