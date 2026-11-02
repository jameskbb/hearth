package io.github.jameskbb.hearth.updates

import org.junit.Assert.assertEquals
import org.junit.Test

class ApkIdentityTest {
    private val installed = ApkIdentity("com.example.hearth", 4, 21, setOf("release-certificate"))
    private val candidate = installed.copy(versionCode = 5)

    @Test fun acceptsOnlyTheExpectedNewVersionOfTheSamePackageAndSigner() {
        assertEquals(ApkVerification.Valid, verify(candidate))
    }

    @Test fun rejectsAnotherPackageEvenWhenItsVersionAndSignerMatch() {
        assertEquals(ApkVerification.InvalidPackage, verify(candidate.copy(packageName = "com.example.other")))
    }

    @Test fun refusesReinstallsDowngradesAndVersionsDifferentFromTheReleaseManifest() {
        for (version in listOf(3L, 4L, 6L)) {
            assertEquals(ApkVerification.InvalidVersion, verify(candidate.copy(versionCode = version)))
        }
    }

    @Test fun rejectsAnApkThatRequiresANewerAndroidVersion() {
        assertEquals(ApkVerification.Incompatible, verify(candidate.copy(minSdk = 29)))
        assertEquals(ApkVerification.Valid, verify(candidate.copy(minSdk = 28)))
    }

    @Test fun refusesUnsignedApksAndDifferentCertificatesIncludingPartialSignerMatches() {
        for (signers in listOf(emptySet(), setOf("other-certificate"), setOf("release-certificate", "other-certificate"))) {
            assertEquals(ApkVerification.SignatureMismatch, verify(candidate.copy(signers = signers)))
        }
    }

    @Test fun refusesToProceedWhenTheInstalledIdentityHasNoVerifiableSigner() {
        assertEquals(ApkVerification.SignatureMismatch,
            verifyApkIdentity(installed.copy(signers = emptySet()), candidate, 5, 28))
    }

    @Test fun signerSetComparisonDoesNotDependOnCertificateOrdering() {
        assertEquals(ApkVerification.Valid,
            verifyApkIdentity(installed.copy(signers = setOf("one", "two")),
                candidate.copy(signers = setOf("two", "one")), 5, 28))
    }

    private fun verify(identity: ApkIdentity) = verifyApkIdentity(installed, identity, 5, 28)
}
