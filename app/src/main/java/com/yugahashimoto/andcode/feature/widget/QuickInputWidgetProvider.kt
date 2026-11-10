package com.yugahashimoto.andcode.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.locale.AppLanguage
import com.yugahashimoto.andcode.data.connection.SecureSettingsRepository

class QuickInputWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        const val ACTION_SEND = "com.yugahashimoto.andcode.widget.SEND"
        const val ACTION_MIC = "com.yugahashimoto.andcode.widget.MIC"
        const val EXTRA_TEXT = "widget_text"

        /** Redraws every placed widget, so a new app language reaches it without waiting for the next update. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val localized = appLanguageContext(context)
            manager.getAppWidgetIds(ComponentName(context, QuickInputWidgetProvider::class.java)).forEach { id ->
                updateWidget(context, manager, id, localized)
            }
        }

        /**
         * A context in the language picked in AndCode, or null when it follows the system: then the
         * launcher already resolves the layout's strings in the system's current language, and
         * setting them here would freeze them until the next update.
         */
        private fun appLanguageContext(context: Context): Context? =
            if (SecureSettingsRepository.readLanguage(context) == AppLanguage.SYSTEM) null else AppLanguage.applyTo(context)

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            localized: Context? = appLanguageContext(context),
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_input)
            // The launcher inflates the layout in the system's language; these follow the app's.
            if (localized != null) {
                views.setTextViewText(R.id.widget_input, localized.getString(R.string.widget_quick_input_hint))
                views.setContentDescription(R.id.widget_send_button, localized.getString(R.string.cd_send))
                views.setContentDescription(R.id.widget_mic_button, localized.getString(R.string.cd_microphone))
            }

            val sendIntent =
                Intent(context, QuickInputActivity::class.java).apply {
                    action = ACTION_SEND
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            val sendPendingIntent =
                PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    sendIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )

            val micIntent =
                Intent(context, QuickInputActivity::class.java).apply {
                    action = ACTION_MIC
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            val micPendingIntent =
                PendingIntent.getActivity(
                    context,
                    appWidgetId + 1000,
                    micIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )

            views.setOnClickPendingIntent(R.id.widget_send_button, sendPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_mic_button, micPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_input, sendPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
