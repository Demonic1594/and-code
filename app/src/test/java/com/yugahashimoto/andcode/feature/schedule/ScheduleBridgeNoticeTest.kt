package com.yugahashimoto.andcode.feature.schedule

import com.yugahashimoto.andcode.data.schedule.Schedule
import com.yugahashimoto.andcode.data.schedule.ScheduleAgentChange
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * The bridge runs an agent's schedule changes without asking the user, so it tells them afterwards:
 * which operations are told, what the notice says, the marker left on the schedule, and whether
 * the runs will ask for approval.
 */
class ScheduleBridgeNoticeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val notifier = FakeScheduleNotifier()

    private fun bridgeWith(
        store: ScheduleStore,
        approvals: ScheduleApprovalSettings = FakeApprovalSettings(),
    ): ScheduleBridge = ScheduleBridge(folder.root, store, notifier, approvals).also { it.pollOnce() }

    private fun runOne(
        bridge: ScheduleBridge,
        op: String,
        args: JSONObject,
    ): JSONObject {
        val id = UUID.randomUUID().toString()
        val request = File(bridge.bridgeDir, "pending/$id.json")
        request.parentFile?.mkdirs()
        request.writeText(JSONObject().put("op", op).put("args", args).toString())
        bridge.pollOnce()
        return JSONObject(File(bridge.bridgeDir, "responses/$id.json").readText())
    }

    private fun createArgs(
        runtimeId: String = "local",
        name: String = "",
    ): JSONObject =
        JSONObject()
            .put("name", name)
            .put("runtimeId", runtimeId)
            .put("prompt", "summarise yesterday")
            .put("cron", "0 9 * * *")

    private fun existingSchedule(
        autoAcceptPermissions: Boolean? = null,
        enabled: Boolean = true,
        runtimeId: String = "local",
    ) = Schedule(
        id = "s1",
        name = "Nightly tests",
        runtimeId = runtimeId,
        workspacePath = "/workspace/app",
        prompt = "run the tests",
        cron = "0 2 * * *",
        enabled = enabled,
        autoAcceptPermissions = autoAcceptPermissions,
    )

    @Test
    fun `create tells the user what was added, where it runs and when`() {
        val store = FakeStore()
        val next = Instant.parse("2026-11-16T09:00:00Z")
        store.nextFire = next
        val bridge = bridgeWith(store)

        val response = runOne(bridge, "create", createArgs(name = "Morning summary").put("workspacePath", "/workspace/app"))

        assertTrue(response.getBoolean("ok"))
        assertTrue(response.getJSONObject("data").getBoolean("userNotified"))
        val notice = notifier.notices.single()
        assertEquals(AgentScheduleChangeKind.CREATED, notice.kind)
        assertEquals("Morning summary", notice.schedule.displayName)
        assertEquals("local", notice.schedule.runtimeId)
        assertEquals("/workspace/app", notice.schedule.workspacePath)
        assertEquals(next, notice.nextRunAt)
        assertFalse(notice.runsWithoutAsking)
        assertEquals(ScheduleAgentChange.CREATED, store.schedules.single().agentChange)
    }

    @Test
    fun `a schedule created turned off has no next run`() {
        val store = FakeStore()
        store.nextFire = Instant.parse("2026-11-16T09:00:00Z")
        val bridge = bridgeWith(store)

        runOne(bridge, "create", createArgs().put("enabled", false))

        assertEquals(null, notifier.notices.single().nextRunAt)
    }

    @Test
    fun `every change to what runs or when tells the user and marks the schedule`() {
        val changes =
            mapOf(
                "prompt" to JSONObject().put("prompt", "run the slow tests too"),
                "runtime" to JSONObject().put("runtimeId", "claude-code"),
                "folder" to JSONObject().put("workspacePath", "/workspace/other"),
                "provider" to JSONObject().put("providerId", "anthropic"),
                "model" to JSONObject().put("modelId", "some-model"),
                "agent" to JSONObject().put("agentId", "plan"),
                "cron" to JSONObject().put("cron", "0 3 * * *"),
                "one-time trigger" to JSONObject().put("oneTimeAt", 1_900_000_000_000L),
            )
        for ((label, change) in changes) {
            val store = FakeStore()
            store.schedules += existingSchedule()
            notifier.notices.clear()
            val bridge = bridgeWith(store)

            val response = runOne(bridge, "update", change.put("scheduleId", "s1"))

            assertTrue(label, response.getBoolean("ok"))
            assertTrue(label, response.getJSONObject("data").getBoolean("userNotified"))
            assertEquals(label, AgentScheduleChangeKind.CHANGED, notifier.notices.single().kind)
            assertEquals(label, ScheduleAgentChange.CHANGED, store.schedule("s1")?.agentChange)
        }
    }

    @Test
    fun `turning a schedule on tells the user, through update or setEnabled`() {
        for ((op, args) in listOf("update" to JSONObject().put("enabled", true), "setEnabled" to JSONObject().put("enabled", true))) {
            val store = FakeStore()
            store.schedules += existingSchedule(enabled = false)
            notifier.notices.clear()
            val bridge = bridgeWith(store)

            val response = runOne(bridge, op, args.put("scheduleId", "s1"))

            assertTrue(op, response.getBoolean("ok"))
            assertTrue(op, response.getJSONObject("data").getBoolean("userNotified"))
            assertEquals(op, AgentScheduleChangeKind.CHANGED, notifier.notices.single().kind)
            assertTrue(op, store.schedule("s1")!!.enabled)
            assertEquals(op, ScheduleAgentChange.CHANGED, store.schedule("s1")?.agentChange)
        }
    }

    /** Turning off, renaming or a no-op only reduce or relabel what runs: nothing to warn about. */
    @Test
    fun `renaming, turning off or changing nothing does not tell the user`() {
        val quiet =
            listOf(
                "update" to JSONObject().put("name", "Renamed"),
                "update" to JSONObject().put("enabled", false),
                "update" to JSONObject().put("prompt", "run the tests").put("cron", "0 2 * * *"),
                "update" to JSONObject().put("autoAcceptPermissions", false),
                "setEnabled" to JSONObject().put("enabled", false),
                "setEnabled" to JSONObject().put("enabled", true),
            )
        for ((op, args) in quiet) {
            val store = FakeStore()
            store.schedules += existingSchedule()
            notifier.notices.clear()
            val bridge = bridgeWith(store)

            val response = runOne(bridge, op, args.put("scheduleId", "s1"))

            assertTrue("$op $args", response.getBoolean("ok"))
            assertFalse("$op $args", response.getJSONObject("data").getBoolean("userNotified"))
            assertTrue("$op $args", notifier.notices.isEmpty())
            assertEquals("$op $args", null, store.schedule("s1")?.agentChange)
        }
    }

    @Test
    fun `reading and running a schedule do not tell the user anything`() {
        val store = FakeStore()
        store.schedules += existingSchedule()
        val bridge = bridgeWith(store)

        runOne(bridge, "list", JSONObject())
        runOne(bridge, "get", JSONObject().put("scheduleId", "s1"))
        runOne(bridge, "runs", JSONObject())
        runOne(bridge, "runNow", JSONObject().put("scheduleId", "s1"))

        assertTrue(notifier.notices.isEmpty())
        assertEquals(null, store.schedule("s1")?.agentChange)
    }

    @Test
    fun `a refused request tells the user nothing`() {
        val store = FakeStore()
        store.schedules += existingSchedule(autoAcceptPermissions = true)
        val bridge = bridgeWith(store)

        runOne(bridge, "create", createArgs().put("autoAcceptPermissions", true))
        runOne(bridge, "update", JSONObject().put("scheduleId", "s1").put("prompt", "something else"))
        runOne(bridge, "update", JSONObject().put("scheduleId", "s1").put("cron", "broken"))

        assertTrue(notifier.notices.isEmpty())
        assertEquals(null, store.schedule("s1")?.agentChange)
    }

    /**
     * Deleting leaves no row to carry a marker, and cannot be undone, so it gets a notice of its own
     * - a quiet one, since it only takes work away.
     */
    @Test
    fun `delete leaves a notice naming the schedule that went`() {
        val store = FakeStore()
        store.schedules += existingSchedule()
        val bridge = bridgeWith(store)

        val response = runOne(bridge, "delete", JSONObject().put("scheduleId", "s1"))

        assertTrue(response.getJSONObject("data").getBoolean("userNotified"))
        val notice = notifier.notices.single()
        assertEquals(AgentScheduleChangeKind.DELETED, notice.kind)
        assertEquals("Nightly tests", notice.schedule.displayName)
        assertEquals(null, notice.nextRunAt)
    }

    /** The marker tells the user even then; a deleted schedule has nothing left to carry one. */
    @Test
    fun `with notifications off the agent hears that a new schedule was still told, a deletion not`() {
        val store = FakeStore()
        store.schedules += existingSchedule()
        notifier.shows = false
        val bridge = bridgeWith(store)

        val created = runOne(bridge, "create", createArgs())
        val deleted = runOne(bridge, "delete", JSONObject().put("scheduleId", "s1"))

        assertTrue(created.getJSONObject("data").getBoolean("userNotified"))
        assertEquals(ScheduleAgentChange.CREATED, store.schedules.single().agentChange)
        assertFalse(deleted.getJSONObject("data").getBoolean("userNotified"))
    }

    @Test
    fun `a schedule an agent added stays marked as added through its later changes`() {
        val store = FakeStore()
        store.schedules += existingSchedule().copy(agentChange = ScheduleAgentChange.CREATED)
        val bridge = bridgeWith(store)

        runOne(bridge, "update", JSONObject().put("scheduleId", "s1").put("prompt", "something else"))

        assertEquals(ScheduleAgentChange.CREATED, store.schedule("s1")?.agentChange)
        assertEquals(AgentScheduleChangeKind.CHANGED, notifier.notices.single().kind)
    }

    @Test
    fun `an agent's quiet change leaves the mark the user has not seen yet`() {
        val store = FakeStore()
        store.schedules += existingSchedule().copy(agentChange = ScheduleAgentChange.CHANGED)
        val bridge = bridgeWith(store)

        runOne(bridge, "update", JSONObject().put("scheduleId", "s1").put("name", "Renamed"))

        assertEquals(ScheduleAgentChange.CHANGED, store.schedule("s1")?.agentChange)
        assertTrue(notifier.notices.isEmpty())
    }

    /**
     * The schedule's own choice wins; with none (null) it follows the app-wide setting - the same
     * rule ScheduleExecutionService answers approvals by.
     */
    @Test
    fun `the will-not-ask warning follows the schedule's choice, then the app setting`() {
        val cases =
            listOf(
                // schedule's flag, app setting, expected
                Triple(true, false, true),
                Triple(true, true, true),
                Triple(false, false, false),
                Triple(false, true, false),
                Triple(null, false, false),
                Triple(null, true, true),
            )
        for ((flag, app, expected) in cases) {
            val store = FakeStore()
            store.schedules += existingSchedule(autoAcceptPermissions = flag)
            notifier.notices.clear()
            val bridge = bridgeWith(store, approvals = FakeApprovalSettings(appAutoApproves = app))

            // Retiming is allowed even on a schedule the user set to auto-approve.
            runOne(bridge, "update", JSONObject().put("scheduleId", "s1").put("cron", "0 4 * * *"))

            assertEquals("schedule $flag, app $app", expected, notifier.notices.single().runsWithoutAsking)
        }
    }

    @Test
    fun `a new schedule warns when the app setting approves for it`() {
        for ((flag, app, expected) in listOf(Triple(null, true, true), Triple(null, false, false), Triple(false, true, false))) {
            val store = FakeStore()
            notifier.notices.clear()
            val bridge = bridgeWith(store, approvals = FakeApprovalSettings(appAutoApproves = app))
            val args = createArgs().apply { if (flag != null) put("autoAcceptPermissions", flag) }

            runOne(bridge, "create", args)

            assertEquals("schedule $flag, app $app", expected, notifier.notices.single().runsWithoutAsking)
        }
    }

    /** A runtime in full-access mode never asks, whatever the schedule or the app says. */
    @Test
    fun `a schedule on a runtime in full-access mode warns even when it asks itself`() {
        val approvals = FakeApprovalSettings(appAutoApproves = false, fullAccessRuntimes = setOf("claude-code"))
        val store = FakeStore()
        val bridge = bridgeWith(store, approvals = approvals)

        runOne(bridge, "create", createArgs(runtimeId = "claude-code").put("autoAcceptPermissions", false))
        runOne(bridge, "create", createArgs(runtimeId = "local").put("autoAcceptPermissions", false))

        assertEquals(listOf(true, false), notifier.notices.map { it.runsWithoutAsking })
    }

    @Test
    fun `moving a schedule onto a full-access runtime is told with the warning`() {
        val store = FakeStore()
        store.schedules += existingSchedule(autoAcceptPermissions = false)
        val bridge = bridgeWith(store, approvals = FakeApprovalSettings(fullAccessRuntimes = setOf("antigravity")))

        runOne(bridge, "update", JSONObject().put("scheduleId", "s1").put("runtimeId", "antigravity"))

        assertTrue(notifier.notices.single().runsWithoutAsking)
    }

    /** false to null hands the schedule to the app setting: with that on, its runs stop asking. */
    @Test
    fun `handing a schedule that asks to an approving app setting tells the user`() {
        for ((app, told) in listOf(true to true, false to false)) {
            val store = FakeStore()
            store.schedules += existingSchedule(autoAcceptPermissions = false)
            notifier.notices.clear()
            val bridge = bridgeWith(store, approvals = FakeApprovalSettings(appAutoApproves = app))

            val response = runOne(bridge, "update", JSONObject().put("scheduleId", "s1").put("autoAcceptPermissions", JSONObject.NULL))

            assertEquals("app $app", told, response.getJSONObject("data").getBoolean("userNotified"))
            assertEquals("app $app", told, notifier.notices.isNotEmpty())
            if (told) assertTrue(notifier.notices.single().runsWithoutAsking)
        }
    }

    @Test
    fun `the approval rule is the one scheduled runs answer by`() {
        assertTrue(Schedule(autoAcceptPermissions = true).autoApproves(appAutoApproves = false))
        assertFalse(Schedule(autoAcceptPermissions = false).autoApproves(appAutoApproves = true))
        assertTrue(Schedule(autoAcceptPermissions = null).autoApproves(appAutoApproves = true))
        assertFalse(Schedule(autoAcceptPermissions = null).autoApproves(appAutoApproves = false))
    }

    /** The change already happened; telling the agent it failed would have it try again. */
    @Test
    fun `a notification that cannot be posted does not turn a done change into an error`() {
        val store = FakeStore()
        notifier.fails = true
        val bridge = bridgeWith(store)

        val response = runOne(bridge, "create", createArgs())

        assertTrue(response.getBoolean("ok"))
        assertEquals(1, store.schedules.size)
        assertEquals(ScheduleAgentChange.CREATED, store.schedules.single().agentChange)
    }
}
