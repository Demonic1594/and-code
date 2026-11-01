package com.yugahashimoto.andcode.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileEditPolicyTest {
    @Test
    fun `ordinary files pass`() {
        assertNull(FileEditPolicy.refusalFor("fun main() {\n    println()\n}\n".toByteArray()))
    }

    @Test
    fun `a single line past the cap is refused however small the file`() {
        val line = "a".repeat(FileEditPolicy.MAX_LINE_BYTES.toInt() + 1)

        assertEquals(FileEditFailure.LINE_TOO_LONG, FileEditPolicy.refusalFor(line.toByteArray()))
    }

    @Test
    fun `a line at the cap passes and a last line without a newline still counts`() {
        val atCap = "a".repeat(FileEditPolicy.MAX_LINE_BYTES.toInt())

        assertNull(FileEditPolicy.refusalFor(atCap.toByteArray()))
        assertEquals(
            FileEditFailure.LINE_TOO_LONG,
            FileEditPolicy.refusalFor(("x\n" + atCap + "x").toByteArray()),
        )
    }

    @Test
    fun `many short lines never trip the per-line cap`() {
        assertNull(FileEditPolicy.refusalFor("ab\n".repeat(50_000).toByteArray()))
    }
}
