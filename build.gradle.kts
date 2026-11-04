// No D8 / R8 pin any more: release-mode D8 dropped a register move in CardEditorScreen's one huge
// Scaffold-content lambda (ART: VerifyError, crash on opening the card editor; 8.10.24 was pinned as a
// workaround until the lambda grew past it too). The lambda is now split into per-section lambdas.
// Check release dexing on a phone with the releaseCheck build type (see app/build.gradle.kts).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
