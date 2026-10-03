package com.yugahashimoto.andcode.di

import android.content.Context
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.core.api.GitHubApiClient
import com.yugahashimoto.andcode.data.connection.SecureSettingsRepository
import com.yugahashimoto.andcode.data.repository.AndroidRuntimeActivityMessages
import com.yugahashimoto.andcode.data.repository.AndroidRuntimeCatalogMessages
import com.yugahashimoto.andcode.data.settings.DraftRepository
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeMessages
import com.yugahashimoto.andcode.runtime.local.OpencodeDatabaseMaintenance
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** Resolves the application whose `lateinit` properties carry the one true instance of each graph node. */
private fun appFrom(context: Context): AndCodeApplication = context.applicationContext as AndCodeApplication

/**
 * Shares the application's own singletons; it deliberately constructs almost nothing.
 *
 * Every instance this module used to build in parallel - a second installer, launcher, command
 * runner, manager, registry, activity repository - diverged from the hand-built graph in ways
 * that stayed invisible until they broke: two runtime managers could double-start the same
 * proot, the registry dropped Claude Code, the activity repository never armed its stall
 * watchdog or reported errors. The application's properties are `lateinit`, so each `single`
 * resolves lazily, after [AndCodeApplication.onCreate] has assigned them.
 */
val appModule =
    module {
        // androidContext() resolves inside a definition's lambda, not in the module body - hence
        // this per-single shape instead of a helper closing over it.
        single { appFrom(androidContext()).settings }

        single { appFrom(androidContext()).preferences }

        single { DraftRepository(androidContext()) }

        single { AndroidRuntimeActivityMessages(androidContext()) }

        single { AndroidRuntimeCatalogMessages(androidContext()) }

        single { appFrom(androidContext()).providerCredentials }

        single { appFrom(androidContext()).customProviders }

        single { appFrom(androidContext()).voskModels }

        single { OkHttpClient() }

        single { appFrom(androidContext()).accessCoordinator }

        single<LocalRuntimeMessages> { appFrom(androidContext()).runtimeMessages }

        single { appFrom(androidContext()).localRuntimeInstaller }

        single { appFrom(androidContext()).processLauncher }

        single { appFrom(androidContext()).commandRunner }

        single<OpencodeDatabaseMaintenance> { appFrom(androidContext()).opencodeDatabaseMaintenance }

        single { appFrom(androidContext()).localRuntimeManager }

        single { appFrom(androidContext()).localRuntimeController }

        single { appFrom(androidContext()).runtimeRegistry }

        single { appFrom(androidContext()).catalogRepository }

        single { appFrom(androidContext()).activityRepository }

        single { appFrom(androidContext()).pullRequestStatusRepository }

        single {
            val settings: SecureSettingsRepository = get()
            GitHubApiClient(token = { settings.githubToken }, client = get())
        }
    }
