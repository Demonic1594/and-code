package com.yugahashimoto.andcode.feature.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubRemoteTest {
    private val expected = "https://github.com/octo/repo"

    @Test
    fun `https remotes with and without git suffix`() {
        assertEquals(expected, gitHubRepositoryUrl("https://github.com/octo/repo"))
        assertEquals(expected, gitHubRepositoryUrl("https://github.com/octo/repo.git"))
        assertEquals(expected, gitHubRepositoryUrl("https://github.com/octo/repo/"))
        assertEquals(expected, gitHubRepositoryUrl("  https://github.com/octo/repo.git\n"))
        assertEquals(expected, gitHubRepositoryUrl("http://github.com/octo/repo"))
        assertEquals(expected, gitHubRepositoryUrl("https://GitHub.com/octo/repo"))
    }

    @Test
    fun `credentials in the url are dropped`() {
        assertEquals(expected, gitHubRepositoryUrl("https://x-access-token:ghp_secret@github.com/octo/repo.git"))
    }

    @Test
    fun `ssh remotes`() {
        assertEquals(expected, gitHubRepositoryUrl("git@github.com:octo/repo.git"))
        assertEquals(expected, gitHubRepositoryUrl("git@github.com:octo/repo"))
        assertEquals(expected, gitHubRepositoryUrl("ssh://git@github.com/octo/repo.git"))
        assertEquals(expected, gitHubRepositoryUrl("ssh://git@github.com:22/octo/repo.git"))
    }

    @Test
    fun `repository names keep dots, dashes and underscores`() {
        assertEquals("https://github.com/my-org/repo.name_x", gitHubRepositoryUrl("https://github.com/my-org/repo.name_x.git"))
    }

    @Test
    fun `other hosts and malformed remotes are null`() {
        assertNull(gitHubRepositoryUrl(""))
        assertNull(gitHubRepositoryUrl("https://gitlab.com/octo/repo.git"))
        assertNull(gitHubRepositoryUrl("https://github.example.com/octo/repo.git"))
        assertNull(gitHubRepositoryUrl("https://github.com.evil.com/octo/repo"))
        assertNull(gitHubRepositoryUrl("https://github.com/octo"))
        assertNull(gitHubRepositoryUrl("https://github.com/octo/repo/tree/main"))
        assertNull(gitHubRepositoryUrl("https://github.com/-bad/repo"))
        assertNull(gitHubRepositoryUrl("https://github.com/octo/.."))
        assertNull(gitHubRepositoryUrl("/home/user/repos/local"))
        assertNull(gitHubRepositoryUrl("file:///github.com/octo/repo"))
        assertNull(gitHubRepositoryUrl("github.com/octo/repo"))
    }
}
