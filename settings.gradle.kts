pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AndCode"
include(":app")
include(":benchmark")

// Patched versions of libraries that only arrive through other dependencies: the Gradle plugins'
// classpath, AGP's own tool configurations, and the app and benchmark graphs. A rule only ever
// raises a version, and stays within the line that was asked for unless the fix only exists in
// the next one. The Kotlin Gradle plugin is left alone: its build-cache fix only exists in a 2.4.20
// pre-release.
object PatchedVersions {
    private const val NETTY = "4.1.137.Final"
    private const val GUAVA = "33.7.2"

    fun forDependency(
        group: String,
        name: String,
        requested: String,
    ): String? {
        val target =
            when (group) {
                "io.netty" -> NETTY.takeIf { !name.startsWith("netty-tcnative") && requested.startsWith("4.1.") }
                "org.bouncycastle" -> "1.85".takeIf { name.endsWith("-jdk18on") }
                "com.google.protobuf" -> "3.25.5".takeIf { name.startsWith("protobuf-java") && requested.startsWith("3.") }
                "com.google.guava" -> if (name == "guava") GUAVA + if (requested.endsWith("-android")) "-android" else "-jre" else null
                "com.squareup.wire" -> "6.4.5".takeIf { name.startsWith("wire-runtime") && requested.startsWith("6.") }
                "commons-io" -> "2.20.0".takeIf { name == "commons-io" }
                "org.apache.commons" ->
                    when (name) {
                        "commons-compress" -> "1.26.0"
                        "commons-lang3" -> "3.18.0"
                        else -> null
                    }
                "org.bitbucket.b_c" -> "0.9.6".takeIf { name == "jose4j" }
                "org.jdom" -> "2.0.6.1".takeIf { name == "jdom2" }
                "org.eclipse.jgit" -> "6.10.1.202505221210-r".takeIf { requested.startsWith("6.") }
                else -> null
            }
        return target?.takeIf { isOlder(requested, it) }
    }

    // Numeric comparison of the leading version parts; qualifiers such as "-jre" or ".Final" are ignored.
    private fun isOlder(
        requested: String,
        target: String,
    ): Boolean {
        val have = numbers(requested)
        val want = numbers(target)
        for (i in 0 until maxOf(have.size, want.size)) {
            val a = have.getOrElse(i) { 0L }
            val b = want.getOrElse(i) { 0L }
            if (a != b) return a < b
        }
        return false
    }

    private fun numbers(version: String): List<Long> =
        version.split('.', '-').map { it.toLongOrNull() }.takeWhile { it != null }.filterNotNull()
}

gradle.beforeProject {
    val patch =
        Action<DependencyResolveDetails> {
            val version = requested.version ?: return@Action
            PatchedVersions.forDependency(requested.group, requested.name, version)?.let {
                useVersion(it)
                because("known vulnerabilities in $version")
            }
        }
    buildscript.configurations.configureEach { resolutionStrategy.eachDependency(patch) }
    configurations.configureEach { resolutionStrategy.eachDependency(patch) }
}
