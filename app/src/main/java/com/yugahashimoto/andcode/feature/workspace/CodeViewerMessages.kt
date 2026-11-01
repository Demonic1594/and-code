package com.yugahashimoto.andcode.feature.workspace

import android.content.Context
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.FileEditFailure
import com.yugahashimoto.andcode.runtime.FileEditPolicy

/**
 * User-visible text the code viewer's editor produces.
 *
 * Why a file could not be opened for editing or saved reaches the screen as a message, so it has to
 * be localised like any other UI string; the view model stays testable by depending on this
 * interface instead of on a [Context].
 */
interface CodeViewerMessages {
    /** [failure] in the user's language. */
    fun editFailure(failure: FileEditFailure): String

    /** For a failure the app has no specific reason for. */
    val editFailed: String

    /**
     * English fallbacks, used by unit tests and by any construction path that has no [Context].
     * Production wiring always injects [AndroidCodeViewerMessages].
     */
    companion object Default : CodeViewerMessages {
        override fun editFailure(failure: FileEditFailure): String =
            when (failure) {
                FileEditFailure.OUTSIDE_WORKSPACE -> "This file is outside the workspace and cannot be edited"
                FileEditFailure.NOT_FOUND -> "The file no longer exists"
                FileEditFailure.NOT_TEXT -> "Only UTF-8 text files can be edited"
                FileEditFailure.TOO_LARGE -> "This file is too large to edit (limit: ${FileEditPolicy.MAX_EDIT_KIBIBYTES} KB)"
                FileEditFailure.LINE_TOO_LONG -> "One line of this file is too long to edit (limit: ${FileEditPolicy.MAX_LINE_BYTES} characters)"
                FileEditFailure.READ_ONLY -> "This file is read-only"
                FileEditFailure.CHANGED_ON_DISK -> "The file was changed by something else since you opened it"
                FileEditFailure.WRITE_FAILED -> "Could not save the file"
            }

        override val editFailed = "Could not edit the file"
    }
}

class AndroidCodeViewerMessages(private val context: Context) : CodeViewerMessages {
    override fun editFailure(failure: FileEditFailure): String =
        when (failure) {
            FileEditFailure.OUTSIDE_WORKSPACE -> context.getString(R.string.file_edit_error_outside_workspace)
            FileEditFailure.NOT_FOUND -> context.getString(R.string.file_edit_error_not_found)
            FileEditFailure.NOT_TEXT -> context.getString(R.string.file_edit_error_not_text)
            FileEditFailure.TOO_LARGE -> context.getString(R.string.file_edit_error_too_large, FileEditPolicy.MAX_EDIT_KIBIBYTES)
            FileEditFailure.LINE_TOO_LONG -> context.getString(R.string.file_edit_error_line_too_long, FileEditPolicy.MAX_LINE_BYTES)
            FileEditFailure.READ_ONLY -> context.getString(R.string.file_edit_error_read_only)
            FileEditFailure.CHANGED_ON_DISK -> context.getString(R.string.file_edit_error_changed_on_disk)
            FileEditFailure.WRITE_FAILED -> context.getString(R.string.file_edit_error_write_failed)
        }

    override val editFailed: String
        get() = context.getString(R.string.file_edit_error_generic)
}
