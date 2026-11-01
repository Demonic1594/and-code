package com.yugahashimoto.andcode.feature.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.yugahashimoto.andcode.AndCodeApplication
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Runs a schedule now for another app (Tasker, an automation, `adb shell am broadcast`) that knows
 * the user's token. Agents run shell commands, so an unauthenticated trigger would let any
 * installed app start one: without a token nothing is accepted, and the token is shown only in
 * AndCode. The run itself goes through an exact alarm, the one start a background app may make.
 */
class ScheduleTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ScheduleTriggerToken.ACTION) return
        val app = context.applicationContext as AndCodeApplication
        val scheduleId = intent.getStringExtra(ScheduleTriggerToken.EXTRA_SCHEDULE_ID)
        val schedule = scheduleId?.let(app.scheduleRepository::schedule)
        when {
            !app.scheduleTriggerToken.accepts(intent.getStringExtra(ScheduleTriggerToken.EXTRA_TOKEN)) ->
                Log.w(TAG, "Refused a schedule trigger: wrong or missing token, or triggers are off")
            schedule == null -> Log.w(TAG, "Refused a schedule trigger: no such schedule")
            !schedule.enabled -> Log.w(TAG, "Refused a schedule trigger: the schedule is off")
            else -> app.scheduleManager.runSoon(schedule.id)
        }
    }

    private companion object {
        const val TAG = "ScheduleTrigger"
    }
}

/** The user's token for [ScheduleTriggerReceiver]; none until they turn triggers on. */
class ScheduleTriggerToken(
    private val preferences: SharedPreferences,
    private val random: SecureRandom = SecureRandom(),
) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    fun current(): String? = preferences.getString(KEY_TOKEN, null)

    /** A new token, replacing (and so revoking) the old one. */
    fun renew(): String {
        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        preferences.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    fun turnOff() {
        preferences.edit().remove(KEY_TOKEN).apply()
    }

    /** Whether [given] is the token, compared in constant time; never while triggers are off. */
    fun accepts(given: String?): Boolean {
        val expected = current() ?: return false
        if (given.isNullOrEmpty()) return false
        return MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
    }

    companion object {
        const val ACTION = "com.yugahashimoto.andcode.TRIGGER_SCHEDULE"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_TOKEN = "token"
        private const val PREFS_NAME = "andcode_schedule_trigger"
        private const val KEY_TOKEN = "token"
        private const val TOKEN_BYTES = 24
    }
}
