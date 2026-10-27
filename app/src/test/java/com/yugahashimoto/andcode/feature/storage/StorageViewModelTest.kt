package com.yugahashimoto.andcode.feature.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class StorageViewModelTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var files: File
    private var busy = false

    private fun write(
        path: String,
        bytes: Int,
    ) = File(files, path).apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(bytes))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        files = temp.newFolder("files")
        write("runtime/workspace/app/main.kt", 100)
        write("runtime/cache/debian.tar.gz", 1_000)
        write("runtime/logs/a.log", 10)
        write("runtime/environment/rootfs/usr/bin/sh", 500)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() =
        StorageViewModel(
            areas = StorageAreas(files, temp.newFolder()),
            deviceSpace = { 10_000L to 4_000L },
            busy = { busy },
            io = dispatcher,
        )

    @Test
    fun `a scan fills the overview`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertFalse(state.scanning)
            assertEquals(1_610L, state.appBytes)
            assertEquals(4_000L, state.deviceFree)
            assertEquals(1_010L, state.reclaimable)
            assertEquals(StorageArea.CACHES, state.areas.first().area)
        }

    @Test
    fun `nothing is removed while an install runs`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            busy = true

            viewModel.cleanAll()
            advanceUntilIdle()

            assertEquals(StorageNotice.Busy, viewModel.state.value.notice)
            assertTrue(File(files, "runtime/cache/debian.tar.gz").exists())
        }

    @Test
    fun `cleaning frees space and measures again`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.cleanAll()
            advanceUntilIdle()

            assertEquals(StorageNotice.Freed(1_010L), viewModel.state.value.notice)
            assertEquals(0L, viewModel.state.value.reclaimable)
            assertEquals(600L, viewModel.state.value.appBytes)
            viewModel.consumeNotice()
            assertNull(viewModel.state.value.notice)
        }

    @Test
    fun `the browser lists by size, deletes only what is safe, and walks back up`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.open(File(files, "runtime"))
            advanceUntilIdle()
            val entries = viewModel.state.value.entries
            assertEquals(listOf("cache", "environment", "workspace", "logs"), entries.map { it.file.name })
            assertFalse(entries.single { it.file.name == "environment" }.deletable)

            viewModel.delete(File(files, "runtime/environment/rootfs/usr/bin/sh"))
            advanceUntilIdle()
            assertTrue("the Linux system is not deletable", File(files, "runtime/environment/rootfs/usr/bin/sh").exists())

            viewModel.delete(File(files, "runtime/workspace/app"))
            advanceUntilIdle()
            assertFalse(File(files, "runtime/workspace/app").exists())

            viewModel.up()
            advanceUntilIdle()
            assertEquals(files, viewModel.state.value.folder)
            viewModel.close()
            assertNull(viewModel.state.value.folder)
        }
}
