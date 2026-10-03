package com.yugahashimoto.andcode.di

import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.core.api.GitHubApiClient
import com.yugahashimoto.andcode.data.connection.SecureSettingsRepository
import com.yugahashimoto.andcode.data.repository.AndroidRuntimeActivityMessages
import com.yugahashimoto.andcode.data.repository.AndroidRuntimeCatalogMessages
import com.yugahashimoto.andcode.data.repository.PullRequestStatusRepository
import com.yugahashimoto.andcode.data.settings.AppPreferencesRepository
import com.yugahashimoto.andcode.data.settings.DraftRepository
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import com.yugahashimoto.andcode.runtime.local.AndroidLocalRuntimeMessages
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeAccessCoordinator
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeMessages
import com.yugahashimoto.andcode.runtime.local.OpencodeDatabaseMaintenance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

/**
 * Shares the application's own singletons; it deliberately constructs almost nothing.
 *
 * Every instance this module used to build in parallel - a second installer, launcher, command
 * runner, manager, registry, activity repository - diverged from the hand-built graph in ways
 * that stayed invisible until they broke: two runtime managers could double-start the same
 * proot, the registry dropped Claude Code, the activity repository never armed its stall
 * watchdog or reported errors. The application's properties are `lateinit`, so each `single`
 * resolves lazily, after [AndCodeApplication.onCreate] has assigned them - the same pattern the
 * database-maintenance and Codex entries established first.
 */
val appModule =
    module {
        fun app(): AndCodeApplication = androidContext().applicationContext as AndCodeApplication

        single<File> { File(androidContext().filesDir, "runtime") }

        single { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

        single { app().settings }

        single { app().preferences }

        single { DraftRepository(androidContext()) }

        single { AndroidRuntimeActivityMessages(androidContext()) }

        single { AndroidRuntimeCatalogMessages(androidContext()) }

        single { app().providerCredentials }

        single { app().customProviders }

        single { app().voskModels }

        single { OkHttpClient() }

        single { app().accessCoordinator }

        single<LocalRuntimeMessages> { app().runtimeMessages }

        single { app().localRuntimeInstaller }

        single { app().processLauncher }

        single { app().commandRunner }

        single<OpencodeDatabaseMaintenance> { app().opencodeDatabaseMaintenance }

        single { app().localRuntimeManager }

        single { app().localRuntimeController }

        single { app().runtimeRegistry }

        single { app().catalogRepository }

        single { app().activityRepository }

        single { app().pullRequestStatusRepository }

        single {
            val settings: SecureSettingsRepository = get()
            GitHubApiClient(token = { settings.githubToken }, client = get())
        }

        single {
            PullRequestStatusRepository(api = get(), scope = get())
        }
    }
