package com.yugahashimoto.andcode.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlLauncherTest {
    @Test
    fun `http and https URLs are openable`() {
        assertTrue(UrlLauncher.isOpenableUrl("http://example.com"))
        assertTrue(UrlLauncher.isOpenableUrl("https://example.com/path?q=1"))
        assertTrue(UrlLauncher.isOpenableUrl("  HTTPS://EXAMPLE.COM  "))
    }

    @Test
    fun `file URL from issue 300 is refused`() {
        assertFalse(UrlLauncher.isOpenableUrl("file:///workspace/my-workspace"))
    }

    @Test
    fun `non-http schemes and bare paths are refused`() {
        assertFalse(UrlLauncher.isOpenableUrl("content://com.example/x"))
        assertFalse(UrlLauncher.isOpenableUrl("intent://example.com#Intent;end"))
        assertFalse(UrlLauncher.isOpenableUrl("javascript:alert(1)"))
        assertFalse(UrlLauncher.isOpenableUrl("data:text/plain,hello"))
        assertFalse(UrlLauncher.isOpenableUrl("/workspace/my-workspace"))
        assertFalse(UrlLauncher.isOpenableUrl("workspace/my-workspace"))
        assertFalse(UrlLauncher.isOpenableUrl(""))
        assertFalse(UrlLauncher.isOpenableUrl("   "))
        assertFalse(UrlLauncher.isOpenableUrl("http:example.com"))
        assertFalse(UrlLauncher.isOpenableUrl("1http://example.com"))
    }

    @Test
    fun `only https links to GitHub and its gists count as GitHub links`() {
        assertTrue(isGitHubUrl("https://github.com/owner/repo/pull/1"))
        assertTrue(isGitHubUrl("https://www.github.com/owner/repo"))
        assertTrue(isGitHubUrl("https://gist.github.com/someone/abc"))
        assertTrue(isGitHubUrl(" https://GitHub.com/owner "))
        assertFalse(isGitHubUrl("http://github.com/owner/repo"))
        assertFalse(isGitHubUrl("https://github.com.evil.example/owner"))
        assertFalse(isGitHubUrl("https://raw.githubusercontent.com/owner/repo/main/a"))
        assertFalse(isGitHubUrl("not a url"))
        assertFalse(isGitHubUrl("https://github.com/o/r/releases/download/v1/app.apk"))
        assertFalse(isGitHubUrl("https://github.com/o/r/archive/refs/heads/main.zip"))
        assertFalse(isGitHubUrl("https://github.com/o/r/raw/main/README.md"))
    }
}
