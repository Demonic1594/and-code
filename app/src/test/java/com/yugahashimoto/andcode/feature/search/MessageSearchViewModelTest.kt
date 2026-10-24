package com.yugahashimoto.andcode.feature.search

import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.data.repository.MessageSearchRepository
import com.yugahashimoto.andcode.data.repository.RuntimeSessionRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // No runtimes: chats are found by title only, which is all these tests need.
    private val repository = MessageSearchRepository(targets = { emptyList() }, ioDispatcher = dispatcher)

    private fun chat(
        id: String,
        title: String,
        parentId: String? = null,
    ) = RuntimeSessionRef("local", null, OpenCodeSession(id = id, title = title, parentId = parentId))

    @Test
    fun `typing waits for a pause and clears the previous results at once`() =
        runTest(dispatcher) {
            val viewModel =
                MessageSearchViewModel(repository, activeChats = { listOf(chat("a", "Gradle build")) }, archivedChats = { emptyList() })

            viewModel.setQuery("gradle")
            advanceUntilIdle()
            assertEquals(listOf("a"), viewModel.state.value.hits.map { it.ref.session.id })

            viewModel.setQuery("gradlex")
            assertTrue(viewModel.state.value.hits.isEmpty())
            assertTrue(viewModel.state.value.searching)
            advanceTimeBy(MessageSearchViewModel.DEBOUNCE_MILLIS / 2)
            assertTrue(viewModel.state.value.searching)

            advanceUntilIdle()
            assertTrue(viewModel.state.value.hits.isEmpty())
            assertTrue(!viewModel.state.value.searching)
        }

    @Test
    fun `archived chats are searched only when included, subagent chats never`() =
        runTest(dispatcher) {
            val viewModel =
                MessageSearchViewModel(
                    repository,
                    activeChats = { listOf(chat("a", "Fix login"), chat("sub", "Fix login helper", parentId = "a")) },
                    archivedChats = { listOf(chat("old", "Fix login again")) },
                    initialQuery = "login",
                )
            advanceUntilIdle()
            assertEquals(listOf("a"), viewModel.state.value.hits.map { it.ref.session.id })

            viewModel.setIncludeArchived(true)
            advanceUntilIdle()
            assertEquals(setOf("a", "old"), viewModel.state.value.hits.map { it.ref.session.id }.toSet())
        }
}
