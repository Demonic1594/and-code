package com.yugahashimoto.andcode.feature.browser.devtools

import android.content.Context
import com.yugahashimoto.andcode.R

/** [DevToolsMessages] from the app's strings, in the user's language. */
class AndroidDevToolsMessages(
    private val context: Context,
) : DevToolsMessages {
    override fun navigatedTo(url: String): String = context.getString(R.string.devtools_navigated_to, url)

    override val consoleCleared: String get() = context.getString(R.string.devtools_console_cleared)

    override val cannotRunCode: String get() = context.getString(R.string.devtools_cannot_run)
}
