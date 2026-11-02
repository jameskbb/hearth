package io.github.jameskbb.hearth.updates

import java.io.File

/** The platform confirms every installation. No settings or packages are changed silently. */
interface UpdateInstaller {
    suspend fun verify(file: File, expectedVersionCode: Long): ApkVerification
    suspend fun install(file: File, expectedVersionCode: Long): InstallResult
    fun openPermissionSettings(): Boolean
    fun canRequestInstalls(): Boolean
}

enum class ApkVerification { Valid, InvalidPackage, InvalidVersion, Incompatible, SignatureMismatch, Unreadable }
enum class InstallResult { Started, PermissionRequired, Unavailable, VerificationFailed }

/** APK identity policy is separate from Android's archive parser so failures stay testable. */
internal data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val minSdk: Int,
    val signers: Set<String>,
)

internal fun verifyApkIdentity(
    installed: ApkIdentity,
    candidate: ApkIdentity,
    expectedVersionCode: Long,
    deviceSdk: Int,
): ApkVerification = when {
    candidate.packageName != installed.packageName -> ApkVerification.InvalidPackage
    candidate.versionCode != expectedVersionCode || candidate.versionCode <= installed.versionCode ->
        ApkVerification.InvalidVersion
    candidate.minSdk > deviceSdk -> ApkVerification.Incompatible
    candidate.signers.isEmpty() || installed.signers.isEmpty() || candidate.signers != installed.signers ->
        ApkVerification.SignatureMismatch
    else -> ApkVerification.Valid
}
