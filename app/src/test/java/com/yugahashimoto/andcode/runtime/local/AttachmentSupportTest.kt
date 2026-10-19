package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AttachmentSupportTest {
    @Test
    fun `only images and pdf are model-sendable`() {
        assertTrue(AttachmentSupport.isModelSendable("image/png"))
        assertTrue(AttachmentSupport.isModelSendable("image/jpeg"))
        assertTrue(AttachmentSupport.isModelSendable("application/pdf"))
        assertFalse(AttachmentSupport.isModelSendable("application/zip"))
        assertFalse(AttachmentSupport.isModelSendable("text/plain"))
        assertFalse(AttachmentSupport.isModelSendable("video/mp4"))
    }

    @Test
    fun `host workspace maps guest paths under slash-workspace`() {
        val root = File("/data/app/runtime/workspace")
        assertEquals(root, AttachmentSupport.hostWorkspace("/workspace", root))
        assertEquals(File(root, "proj"), AttachmentSupport.hostWorkspace("/workspace/proj", root))
        assertEquals(File(root, "proj"), AttachmentSupport.hostWorkspace("/workspace/proj/", root))
        assertNull(AttachmentSupport.hostWorkspace("/home/remote/proj", root))
        assertNull(AttachmentSupport.hostWorkspace(null, root))
    }

    @Test
    fun `host workspace rejects a path that escapes the root`() {
        val root = File("/data/app/runtime/workspace")
        assertNull(AttachmentSupport.hostWorkspace("/workspace/../etc", root))
        assertNull(AttachmentSupport.hostWorkspace("/workspace/proj/../../etc", root))
    }

    @Test
    fun `guest workspace path is the inverse of host mapping`() {
        val root = File("/data/app/runtime/workspace")
        val file = File(root, "proj/.andcode-attachments/archive.zip")
        assertEquals(
            "/workspace/proj/.andcode-attachments/archive.zip",
            AttachmentSupport.guestWorkspacePath(file, root),
        )
        assertNull(AttachmentSupport.guestWorkspacePath(File("/tmp/archive.zip"), root))
    }
}
