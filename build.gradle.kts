import java.time.Duration

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.aboutlibraries) apply false
}

// Wall-clock budget for every unit-test task, in every module.
//
// Why this exists: on 2026-07-28 a `:app` unit-test task wedged mid-run and the
// android-ci `build` job sat there until GitHub killed it at the 6-hour
// maximum-execution ceiling. The version bump and the `v2026.07.28.223` tag had
// already been pushed by then, and the `release` job never got to run, so the
// tag shipped with no APK and F-Droid could not verify the release (issue #93).
// The same stall had happened once before on a feature branch, so a test task
// that stops making progress has to fail the build rather than idle.
//
// Gradle's per-task `timeout` interrupts the task's execution thread once the
// budget is spent and marks the build FAILED. Gradle documents its built-in
// tasks — `Test` included — as responsive to that interrupt, but a task wedged
// somewhere unresponsive could still outlive it, so treat this as the inner of
// two nets: the CI job carries its own `timeout-minutes` as the outer one.
//
// Sizing: on CI a whole `./gradlew test` across every module takes about 35
// seconds wall-clock (run 29286428853), and no single module's task runs for
// more than a few of those. Ten minutes is far past any legitimate run — even a
// cold, CPU-starved runner — while still bounding a wedged task at minutes
// instead of hours.
//
// `withType<Test>` covers AGP's `testDebugUnitTest` / `testReleaseUnitTest`
// too: `AndroidUnitTest` extends `org.gradle.api.tasks.testing.Test`.
subprojects {
    tasks.withType<Test>().configureEach {
        timeout.set(Duration.ofMinutes(10))
    }
}
