package com.yugahashimoto.andcode.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {
    @Test
    fun `code viewer route round trips special paths`() {
        val runtimeId = "remote/id ?#"
        val workspacePath = "/repo with spaces/日本語#fragment"
        val filePath = "src/a+b?c=d%20.kt"

        val segments = codeViewerRoute(runtimeId, workspacePath, filePath).split('/')

        assertEquals(ROUTE_CODE_VIEWER, segments[0])
        assertEquals(runtimeId, decodeRouteArg(segments[1]))
        assertEquals(workspacePath, decodeRouteArg(segments[2]))
        assertEquals(filePath, decodeRouteArg(segments[3]))
    }

    @Test
    fun `terminal route round trips a workspace path`() {
        val directory = "/workspace/my app/日本語 #1?x=y"

        val route = terminalRoute(directory)

        assertTrue(route.startsWith("$ROUTE_TERMINAL?$TERMINAL_ARG_DIRECTORY="))
        assertEquals(directory, decodeRouteArg(route.substringAfter("$TERMINAL_ARG_DIRECTORY=")))
    }

    @Test
    fun `terminal route without a directory is the bare route`() {
        assertEquals(ROUTE_TERMINAL, terminalRoute(null))
        assertEquals(ROUTE_TERMINAL, terminalRoute("  "))
        assertEquals(ROUTE_TERMINAL, terminalRoute())
    }
}
