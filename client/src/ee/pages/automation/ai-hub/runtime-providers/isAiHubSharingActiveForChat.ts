/**
 * Whether AI Hub chat sharing is active for {@code chatId} right now. This is the single gate for
 * every effect or callback that would otherwise write to or poll presence data for a specific chat —
 * the presence heartbeat, the focused-chat status poll (both in {@code AiHubRuntimeProvider}), and the
 * composer's typing-presence callback all call it, and none of them repeats the condition inline.
 * Keeping it in one exported function rather than three separately-written checks means the three call
 * sites cannot drift apart from each other.
 *
 * Lives in its own module rather than alongside {@code AiHubRuntimeProvider}'s other extracted pure
 * helpers so a caller outside that ~2,900-line file (the composer) can import it without pulling in
 * the provider's own deep dependency tree — the same reason {@code inFlightRunClient} and
 * {@code stripLeakedToolMarkup} are their own small modules in this directory rather than living
 * inside the provider itself.
 *
 * {@code persistedChatId} is {@code aiHubChatsStore}'s {@code currentChatId} — the database row id,
 * which is {@code undefined} until {@code createAiHubChat} has returned one. A present {@code chatId}
 * alone does not mean the server knows the thread: {@code useAiHubStore} generates a thread id up
 * front, so a freshly opened "New Chat" carries one before any row exists, and both the {@code
 * /presence} write and the {@code /status} read answer "unknown thread" for it — the former as a 404
 * per heartbeat, the latter as an omission the focused-chat poll counts toward abandoning the chat.
 * The two stores are always written together (see {@code useSwitchChat} and {@code
 * createChatIfNeeded}), so the row id is a sound stand-in for "this thread exists server-side".
 *
 * A user-defined type guard ({@code chatId is string}) rather than a plain boolean return, so a caller
 * that checks this before using {@code chatId} keeps the compiler's non-null narrowing.
 */
export function isAiHubSharingActiveForChat(
    chatId: string | undefined,
    sharingEnabled: boolean,
    persistedChatId: number | undefined
): chatId is string {
    return chatId != null && sharingEnabled && persistedChatId != null;
}
