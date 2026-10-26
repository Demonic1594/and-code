package com.yugahashimoto.andcode.feature.usage

import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.data.repository.RuntimeSessionRef
import com.yugahashimoto.andcode.data.repository.UsageRepository
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsageViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // No runtimes are registered, so every chat reads as unreadable: enough to follow the flow.
    private val repository = UsageRepository(targets = { emptyList() }, ioDispatcher = dispatcher)

    private fun chat(id: String) = RuntimeSessionRef("local", null, OpenCodeSession(id = id))

    private fun viewModel(archived: List<RuntimeSessionRef>) =
        UsageViewModel(
            repository,
            activeChats = { listOf(chat("a"), chat("b")) },
            archivedChats = { archived },
            computeDispatcher = dispatcher,
        )

    @Test
    fun `reads active and archived chats once each, and can leave archived out`() =
        runTest(dispatcher) {
            val viewModel = viewModel(archived = listOf(chat("b"), chat("c")))
            advanceUntilIdle()

            assertEquals(3, viewModel.state.value.total)
            assertFalse(viewModel.state.value.loading)
            assertEquals(3, viewModel.state.value.report?.unreadableChats)

            viewModel.setIncludeArchived(false)
            advanceUntilIdle()
            assertEquals(2, viewModel.state.value.total)
        }

    @Test
    fun `changing the range rebuilds the report for it`() =
        runTest(dispatcher) {
            val viewModel = viewModel(archived = emptyList())
            advanceUntilIdle()
            assertEquals(30, viewModel.state.value.report?.days?.size)

            viewModel.setRange(UsageRange.WEEK)
            advanceUntilIdle()
            assertEquals(UsageRange.WEEK, viewModel.state.value.range)
            assertEquals(7, viewModel.state.value.report?.days?.size)
        }
}
