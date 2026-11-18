package com.yugahashimoto.andcode.runtime.local

import java.io.File
import java.io.IOException

/**
 * Where the selected preset is written inside the guest filesystem.
 *
 * Listed in the `instructions` OpenCode is launched with (see
 * `AND_CODE_OPENCODE_CONFIG_CONTENT`), and deliberately separate from the AndCode environment
 * blurb at `and-code-context.md`: that one is a file the user may take over and edit, which
 * [installAndCodeAgentContext] then stops managing, while this one is rewritten on every switch.
 */
internal const val OPENCODE_SYSTEM_PROMPT_PATH = "root/.config/opencode/and-code-system-prompt.md"

/**
 * Puts the selected system-prompt preset where OpenCode will read it.
 *
 * OpenCode has no per-message system prompt to pass - the `system` field its message endpoint
 * accepts is recorded on the message and never reaches the model - so the preset travels as an
 * instructions file instead. `Instruction.system()` re-reads the file's *content* on every turn and
 * appends it alongside the environment and skill prompts, so switching presets takes effect on the
 * next message. Only the file's *path* is fixed at launch, by the config the server starts with.
 *
 * Two consequences the UI has to own, which is why this is offered as an OpenCode-wide default
 * rather than a per-chat chip like Claude Code's:
 * - every session on this runtime shares it, including ones already open;
 * - a server already running from before the path was added to its config ignores the file until
 *   the runtime restarts.
 *
 * Best-effort like the rest of the guest-filesystem writes: a preset that cannot be written simply
 * is not applied, rather than failing a runtime start or a settings tap.
 *
 * The new prompt goes through [GuestFiles.write]: staged beside the target under a unique name and
 * renamed onto it, never written into it in place. OpenCode re-reads this file every turn, so
 * truncating the live file would let a turn that starts mid-write see an empty or half-written
 * prompt. A rename swaps the name in one step, so every read sees either the old prompt or the new
 * one - and if two writers overlap, the later rename wins whole rather than the two interleaving.
 * The same rename replaces a link the guest left at the target instead of writing through it, and
 * a linked folder above it is refused, so a switch can never write to one of this app's own files.
 */
internal fun applyOpenCodeSystemPrompt(
    rootfs: File,
    prompt: String?,
) {
    try {
        if (prompt.isNullOrBlank()) {
            // Removed rather than blanked: an empty file would still be read and announced to the
            // model as "Instructions from: ...", which is worse than no instruction at all.
            GuestFiles.delete(rootfs, OPENCODE_SYSTEM_PROMPT_PATH)
            return
        }
        GuestFiles.write(rootfs, OPENCODE_SYSTEM_PROMPT_PATH, prompt)
    } catch (e: IOException) {
        // Skipped, as above: a folder in the way, a linked folder above it, a failing write.
    }
}
