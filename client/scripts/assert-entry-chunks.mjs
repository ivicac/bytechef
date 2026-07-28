// Guards the embedded-builder fast-boot invariant: neither the main app entry ('index.html') nor
// the dedicated workflow-builder entry ('workflow-builder.html') may STATICALLY reach the heavy
// Monaco, assistant-ui, or posthog chunks. Those packages must only be pulled in through dynamic
// `import()` boundaries (React.lazy, etc.) so the fast-boot entries stay small on first paint.
//
// This walks only the manifest's "imports" edges (static ESM imports emitted by Rollup/Vite),
// never "dynamicImports" — a chunk that is only reachable via a dynamic import is, by definition,
// not part of the static/eager graph and is exactly what we want to allow.

import {readFile} from 'node:fs/promises';
import * as path from 'node:path';
import {fileURLToPath} from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const MANIFEST_PATH = path.resolve(__dirname, '../dist/.vite/manifest.json');

const ENTRY_KEYS = ['index.html', 'workflow-builder.html'];

const FORBIDDEN_PATTERN = /monaco|assistant-ui|posthog/i;

async function loadManifest() {
    const raw = await readFile(MANIFEST_PATH, 'utf-8');

    return JSON.parse(raw);
}

// Walks static imports only (never dynamicImports) starting from `entryKey`, returning the set of
// manifest keys reachable that way, along with the first offending chunk if one matches the
// forbidden pattern.
function walkStaticImports(manifest, entryKey) {
    const visited = new Set();
    const stack = [entryKey];
    let offender;

    while (stack.length > 0) {
        const key = stack.pop();

        if (visited.has(key)) {
            continue;
        }

        visited.add(key);

        const chunk = manifest[key];

        if (!chunk) {
            continue;
        }

        if (chunk.file && FORBIDDEN_PATTERN.test(chunk.file) && !offender) {
            offender = {chunkFile: chunk.file, chunkKey: key};
        }

        for (const importedKey of chunk.imports ?? []) {
            if (!visited.has(importedKey)) {
                stack.push(importedKey);
            }
        }
    }

    return {offender, visited};
}

async function main() {
    const manifest = await loadManifest();

    const results = [];

    for (const entryKey of ENTRY_KEYS) {
        if (!manifest[entryKey]) {
            console.error(`Entry "${entryKey}" not found in manifest at ${MANIFEST_PATH}.`);

            process.exit(1);

            return;
        }

        const {offender, visited} = walkStaticImports(manifest, entryKey);

        if (offender) {
            console.error(
                `Entry "${entryKey}" statically reaches a forbidden chunk: "${offender.chunkFile}" ` +
                    `(manifest key "${offender.chunkKey}").`
            );
            console.error(
                'Monaco, assistant-ui, and posthog must only be reachable via a dynamic import() ' +
                    '(React.lazy, etc.) from these fast-boot entries.'
            );

            process.exit(1);

            return;
        }

        results.push({entryKey, staticChunkCount: visited.size});
    }

    for (const {entryKey, staticChunkCount} of results) {
        console.log(`${entryKey}: ${staticChunkCount} statically reachable chunk(s), none forbidden.`);
    }

    process.exit(0);
}

main().catch((error) => {
    console.error(error);

    process.exit(1);
});
