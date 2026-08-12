package com.yanjiyu.terminalspike.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ConnectionModelsTest {
    @Test
    fun hostProfileSupportsEveryCommonAndOverrideField() {
        val profile = HostProfile(
            id = HOST_ID,
            displayName = "Production shell",
            hostname = "2001:db8::10",
            port = 2_222,
            username = "operator",
            protocol = ConnectionProtocol.MOSH,
            credentialId = CREDENTIAL_ID,
            terminalProfileId = TERMINAL_PROFILE_ID,
            keyboardProfileId = KEYBOARD_PROFILE_ID,
            isFavorite = true,
            group = "Production",
            tag = "Primary",
            startupCommand = "cd /srv/app\nprintf 'ready\\n'",
            keepaliveIntervalSeconds = 30,
            reconnectPolicy = ReconnectPolicy.AUTOMATIC,
            moshPortRange = MoshPortRange(60_000, 61_000),
            moshServerCommand = "mosh-server new -s",
            moshLocale = "en_GB.UTF-8",
            moshFallbackPolicy = MoshFallbackPolicy.ASK,
            createdAtEpochMillis = 100,
            updatedAtEpochMillis = 200,
        )

        assertEquals(ConnectionProtocol.MOSH, profile.protocol)
        assertEquals(CREDENTIAL_ID, profile.credentialId)
        assertEquals("Primary", profile.tag)
        assertEquals(60_000, profile.moshPortRange?.first)
        assertEquals("en_GB.UTF-8", profile.moshLocale)
        assertEquals(MoshFallbackPolicy.ASK, profile.moshFallbackPolicy)
        assertNull(profile.moshPort)
    }

    @Test
    fun sshProfileRejectsMoshOnlyConfiguration() {
        assertThrows(IllegalArgumentException::class.java) {
            validSshHost(moshPort = 60_001)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validSshHost(moshServerCommand = "mosh-server new")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validSshHost(moshLocale = "C.UTF-8")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validSshHost(moshFallbackPolicy = MoshFallbackPolicy.ASK)
        }
    }

    @Test
    fun hostProfileRejectsInvalidIdentityEndpointAndOverrides() {
        assertThrows(IllegalArgumentException::class.java) {
            validSshHost(id = "not-a-uuid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validSshHost(hostname = "host name")
        }
        listOf(
            "ssh://shell.example",
            "user@shell.example",
            "256.1.1.1",
            "01.2.3.4",
            "-bad.example",
            "bad_.example",
            "[2001:db8::1]",
            "2001:db8:::1",
        ).forEach { invalidHost ->
            assertThrows(IllegalArgumentException::class.java) {
                validSshHost(hostname = invalidHost)
            }
        }
        listOf(
            "shell.example",
            "localhost",
            "example.test.",
            "xn--bcher-kva.example",
            "bücher.example",
            "192.0.2.10",
            "2001:db8::10",
        ).forEach { validHost ->
            assertEquals(validHost, validSshHost(hostname = validHost).hostname)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validSshHost(port = 65_536)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validSshHost(keepaliveIntervalSeconds = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validSshHost(tag = " surrounding ")
        }
    }

    @Test
    fun zeroKeepaliveExplicitlyDisablesHostKeepalive() {
        assertEquals(0, validSshHost(keepaliveIntervalSeconds = 0).keepaliveIntervalSeconds)
    }

    @Test
    fun removedCredentialRemainsAnExplicitRepairableReferenceState() {
        assertNull(validSshHost(credentialId = null).credentialId)
    }

    @Test
    fun moshRangeIsInclusiveBoundedAndMutuallyExclusiveWithSinglePort() {
        assertEquals(1_001, MoshPortRange(60_000, 61_000).last - 60_000 + 1)
        assertThrows(IllegalArgumentException::class.java) { MoshPortRange(61_000, 60_000) }
        assertThrows(IllegalArgumentException::class.java) { MoshPortRange(1, 10_001) }
        assertThrows(IllegalArgumentException::class.java) {
            HostProfile(
                id = HOST_ID,
                displayName = "Mosh",
                hostname = "shell.example",
                port = 22,
                username = "user",
                protocol = ConnectionProtocol.MOSH,
                credentialId = CREDENTIAL_ID,
                moshPort = 60_000,
                moshPortRange = MoshPortRange(60_000, 61_000),
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 1,
            )
        }
    }

    @Test
    fun moshLocaleAndFallbackPolicyAreExplicitAndValidated() {
        val base = HostProfile(
            id = HOST_ID,
            displayName = "Mosh",
            hostname = "shell.example",
            port = 22,
            username = "user",
            protocol = ConnectionProtocol.MOSH,
            credentialId = null,
            moshLocale = "C.UTF-8",
            moshFallbackPolicy = MoshFallbackPolicy.AUTOMATIC,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        )

        assertEquals("C.UTF-8", base.moshLocale)
        assertEquals(MoshFallbackPolicy.AUTOMATIC, base.moshFallbackPolicy)
        listOf("C.utf-8", "en_GB.UTF-16", "en GB.UTF-8", "💻.UTF-8").forEach { locale ->
            assertThrows(IllegalArgumentException::class.java) { base.copy(moshLocale = locale) }
        }
    }

    @Test
    fun credentialVariantsContainOnlyOpaqueReferences() {
        val password = validCredential(SshAuthentication.Password(SECRET_ID))
        val promptedPassword = validCredential(SshAuthentication.Password())
        val privateKey = validCredential(
            SshAuthentication.PrivateKey(
                keyIdentityId = KEY_ID,
                passphraseSecretReferenceId = PASSPHRASE_SECRET_ID,
            ),
        )
        val keyboardInteractive = validCredential(SshAuthentication.KeyboardInteractive())

        assertEquals(SshCredentialKind.PASSWORD, password.kind)
        assertEquals(SshCredentialKind.PASSWORD, promptedPassword.kind)
        assertEquals(SshCredentialKind.PRIVATE_KEY, privateKey.kind)
        assertEquals(SshCredentialKind.KEYBOARD_INTERACTIVE, keyboardInteractive.kind)
        assertThrows(IllegalArgumentException::class.java) {
            SshAuthentication.PrivateKey("7")
        }
    }

    @Test
    fun keyIdentityCarriesPublicMetadataAndEncryptedPayloadReference() {
        val identity = SshKeyIdentity(
            id = KEY_ID,
            name = "Work Ed25519",
            algorithm = "ssh-ed25519",
            publicKeyFingerprint = "SHA256:ZmFrZUZpbmdlcnByaW50",
            publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFake",
            privateKeySecretReferenceId = SECRET_ID,
            origin = SshKeyOrigin.IMPORTED,
            isPassphraseProtected = true,
            createdAtEpochMillis = 10,
            updatedAtEpochMillis = 11,
            comment = "Imported through the system picker",
        )

        assertEquals(SECRET_ID, identity.privateKeySecretReferenceId)
        assertEquals(SshKeyOrigin.IMPORTED, identity.origin)
        assertThrows(IllegalArgumentException::class.java) {
            identity.copy(updatedAtEpochMillis = 9)
        }
        assertNull(identity.copy(publicKey = null).publicKey)
        assertThrows(IllegalArgumentException::class.java) {
            identity.copy(origin = SshKeyOrigin.GENERATED, publicKey = null)
        }
    }

    @Test
    fun knownHostRequiresCanonicalHostAndOrderedObservationTimes() {
        val knownHost = KnownHost(
            id = KNOWN_HOST_ID,
            host = "shell.example",
            port = 22,
            keyAlgorithm = "ssh-ed25519",
            fingerprint = "SHA256:ZmFrZUZpbmdlcnByaW50",
            publicHostKey = "AAAAC3NzaC1lZDI1NTE5AAAAIFake",
            firstSeenAtEpochMillis = 10,
            lastSeenAtEpochMillis = 20,
        )

        assertEquals("shell.example", knownHost.host)
        assertThrows(IllegalArgumentException::class.java) { knownHost.copy(host = "Shell.Example") }
        assertThrows(IllegalArgumentException::class.java) {
            knownHost.copy(lastSeenAtEpochMillis = 9)
        }
        assertNull(
            knownHost.copy(
                firstSeenAtEpochMillis = null,
                lastSeenAtEpochMillis = null,
            ).firstSeenAtEpochMillis,
        )
        assertThrows(IllegalArgumentException::class.java) {
            knownHost.copy(firstSeenAtEpochMillis = null)
        }
    }

    private fun validSshHost(
        id: String = HOST_ID,
        hostname: String = "shell.example",
        port: Int = 22,
        credentialId: String? = CREDENTIAL_ID,
        keepaliveIntervalSeconds: Int? = null,
        tag: String? = null,
        moshPort: Int? = null,
        moshServerCommand: String? = null,
        moshLocale: String? = null,
        moshFallbackPolicy: MoshFallbackPolicy = MoshFallbackPolicy.NEVER,
    ) = HostProfile(
        id = id,
        displayName = "Shell",
        hostname = hostname,
        port = port,
        username = "user",
        protocol = ConnectionProtocol.SSH,
        credentialId = credentialId,
        tag = tag,
        keepaliveIntervalSeconds = keepaliveIntervalSeconds,
        moshPort = moshPort,
        moshServerCommand = moshServerCommand,
        moshLocale = moshLocale,
        moshFallbackPolicy = moshFallbackPolicy,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    private fun validCredential(authentication: SshAuthentication) = SshCredential(
        id = CREDENTIAL_ID,
        displayName = "Authentication",
        authentication = authentication,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    private companion object {
        const val HOST_ID = "00000000-0000-4000-8000-000000000001"
        const val CREDENTIAL_ID = "00000000-0000-4000-8000-000000000002"
        const val TERMINAL_PROFILE_ID = "00000000-0000-4000-8000-000000000003"
        const val KEYBOARD_PROFILE_ID = "00000000-0000-4000-8000-000000000004"
        const val KEY_ID = "00000000-0000-4000-8000-000000000005"
        const val SECRET_ID = "00000000-0000-4000-8000-000000000006"
        const val PASSPHRASE_SECRET_ID = "00000000-0000-4000-8000-000000000007"
        const val KNOWN_HOST_ID = "00000000-0000-4000-8000-000000000008"
    }
}
