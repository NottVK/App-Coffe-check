package co.agrovision.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.int(alias: String): Int =
    findVersion(alias).get().requiredVersion.toInt()

internal fun VersionCatalog.string(alias: String): String =
    findVersion(alias).get().requiredVersion

/** Lee una propiedad de `gradle.properties`, o el valor por defecto si no está. */
internal fun Project.stringProperty(name: String, default: String = ""): String =
    providers.gradleProperty(name).orNull ?: default

/**
 * Configuración común a cualquier módulo Android del proyecto.
 *
 * Las opciones del compilador Kotlin no son cosmética:
 *  - `-Xjvm-default=all` evita clases sintéticas por cada interfaz con default.
 *  - `allWarningsAsErrors` se activa solo en CI para no entorpecer el trabajo
 *    local, pero impide que una advertencia entre a `main`.
 */
internal fun Project.configureKotlinAndroid(extension: CommonExtension<*, *, *, *, *, *>) {
    extension.apply {
        compileSdk = libs.int("compileSdk")

        defaultConfig {
            minSdk = libs.int("minSdk")
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
            // Obligatorio con minSdk 22 (ADR-012): `java.time` y parte de
            // `java.util.stream` no existen antes de API 26, y tanto Room como
            // kotlinx las dan por hechas.
            isCoreLibraryDesugaringEnabled = true
        }

        packaging {
            resources.excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }

        lint {
            warningsAsErrors = providers.environmentVariable("CI").isPresent
            abortOnError = true
            checkDependencies = true
            xmlReport = true
            sarifReport = true
            baseline = file("lint-baseline.xml").takeIf { it.exists() }
        }
    }

    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
            allWarningsAsErrors = providers.environmentVariable("CI").isPresent
            freeCompilerArgs.addAll(
                "-Xjvm-default=all",
                "-opt-in=kotlin.RequiresOptIn",
            )
        }
    }
}

internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    extensions.configure<KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
            allWarningsAsErrors = providers.environmentVariable("CI").isPresent
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }
}

/** JUnit 5 en módulos JVM puros (§18.3). */
internal fun Project.configureJUnitPlatform() {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            showStackTraces = true
        }
    }
}
