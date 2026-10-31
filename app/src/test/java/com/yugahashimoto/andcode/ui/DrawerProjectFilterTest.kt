package com.yugahashimoto.andcode.ui

import com.yugahashimoto.andcode.runtime.WorkspaceRef
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerProjectFilterTest {
    private val projects =
        listOf(
            WorkspaceRef("1", "and-code", "/workspace/and-code"),
            WorkspaceRef("2", "solo-learning", "/workspace/solo-learning"),
            WorkspaceRef("3", "Solo-Learning-Site", "/workspace/site"),
        )

    @Test
    fun `a blank query keeps every project`() {
        assertEquals(projects, filterDrawerProjects(projects, ""))
        assertEquals(projects, filterDrawerProjects(projects, "   "))
    }

    @Test
    fun `matches a substring of the name, ignoring case`() {
        assertEquals(projects.drop(1), filterDrawerProjects(projects, "SOLO"))
        assertEquals(listOf(projects[2]), filterDrawerProjects(projects, " site "))
    }

    @Test
    fun `matches the name, not the path`() {
        assertEquals(emptyList<WorkspaceRef>(), filterDrawerProjects(projects, "workspace"))
    }
}
