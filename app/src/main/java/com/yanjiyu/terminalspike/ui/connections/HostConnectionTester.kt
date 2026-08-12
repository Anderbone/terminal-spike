package com.yanjiyu.terminalspike.ui.connections

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSchException
import com.yanjiyu.terminalspike.R
import com.yanjiyu.terminalspike.connection.AuthenticatedJschSession
import com.yanjiyu.terminalspike.connection.HostKeyFailure
import com.yanjiyu.terminalspike.connection.HostIdentityDecision
import com.yanjiyu.terminalspike.connection.HostIdentityPrompt
import com.yanjiyu.terminalspike.connection.JschAuthenticatedSessionFactory
import com.yanjiyu.terminalspike.connection.KeyboardInteractiveChallenge
import com.yanjiyu.terminalspike.connection.KnownHostManager
import com.yanjiyu.terminalspike.connection.SshAuthentication
import com.yanjiyu.terminalspike.connection.SshConnectionConfig
import com.yanjiyu.terminalspike.connection.SshPtyTarget
import com.yanjiyu.terminalspike.connection.VerifyingHostKeyRepository
import com.yanjiyu.terminalspike.connection.configureSshPty
import com.yanjiyu.terminalspike.connection.safeJschFailureMessage
import com.yanjiyu.terminalspike.core.model.TerminalProfile
import com.yanjiyu.terminalspike.ui.UiText
import com.yanjiyu.terminalspike.ui.uiText
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class HostConnectionTestSpec(
    val host: String,
    val port: Int,
    val username: String,
    val authentication: SshAuthentication,
    val terminalType: String = TerminalProfile.DEFAULT_TERM_VALUE,
    /** Test Connection deliberately validates the shell without running saved startup input. */
    val startupCommandConfigured: Boolean = false,
)

internal interface HostConnectionTester {
    suspend fun test(
        spec: HostConnectionTestSpec,
        interaction: HostConnectionTestInteraction = HostConnectionTestInteraction.Ignore,
    ): HostConnectionTestResult
}

/**
 * Non-secret presentation bridge for one live Test Connection operation. The ViewModel owns the
 * registered prompt owner and must close it on cancellation/replacement.
 */
internal interface HostConnectionTestInteraction {
    fun registerPromptOwner(owner: HostConnectionTestPromptOwner): Boolean

    fun showHostIdentityPrompt(prompt: HostIdentityPrompt)

    fun showKeyboardInteractiveChallenge(challenge: KeyboardInteractiveChallenge)

    /** One-shot tests that do not exercise an interactive transport can use this safe sink. */
    data object Ignore : HostConnectionTestInteraction {
        override fun registerPromptOwner(owner: HostConnectionTestPromptOwner): Boolean {
            owner.close()
            return false
        }

        override fun showHostIdentityPrompt(prompt: HostIdentityPrompt) = Unit

        override fun showKeyboardInteractiveChallenge(challenge: KeyboardInteractiveChallenge) = Unit
    }
}

/**
 * Mutable transport control never exposed through Compose state or SavedState. A successful
 * keyboard-interactive answer transfers response ownership to the transport; a rejected answer
 * must wipe every supplied array before returning.
 */
internal interface HostConnectionTestPromptOwner : AutoCloseable {
    fun answerHostIdentity(
        promptToken: Long,
        decision: HostIdentityDecision,
    ): Boolean

    fun answerKeyboardInteractive(
        challengeToken: Long,
        responses: List<CharArray>,
    ): Boolean

    fun cancelKeyboardInteractive(challengeToken: Long): Boolean
}

/**
 * Secret-bearing input for one live Test Connection operation. The caller transfers the byte
 * array at construction; [wipeSecret] is idempotent so cancellation and completion hooks can both
 * enforce cleanup without racing a second mutable copy into existence.
 */
internal class PreparedHostConnectionTest(
    val value: ValidatedHostEditor,
    secret: ByteArray,
) {
    private val lock = Any()
    private val secretBytes = secret
    private var wiped = false

    fun borrowSecret(): ByteArray = synchronized(lock) { secretBytes }

    fun wipeSecret() {
        synchronized(lock) {
            if (wiped) return
            secretBytes.fill(0)
            wiped = true
        }
    }
}

internal fun interface HostConnectionTestOperationRunner {
    suspend fun run(
        prepared: PreparedHostConnectionTest,
        interaction: HostConnectionTestInteraction,
    ): HostConnectionTestResult
}

/**
 * Owns the exact live Test Connection operation independently of any Compose instance. A screen
 * recreation therefore observes the same token-scoped transport, while replacement and stale
 * actions are rejected against the current operation. No credential response enters [state].
 */
internal class HostConnectionTestCoordinator(
    private val scope: CoroutineScope,
    private val runner: HostConnectionTestOperationRunner,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nextOperationToken: () -> Long = {
        nextHostConnectionTestOperationToken.incrementAndGet()
    },
) : AutoCloseable {
    private class ActiveOperation(
        val operationToken: Long,
        val prepared: PreparedHostConnectionTest,
    ) {
        var promptOwner: HostConnectionTestPromptOwner? = null
        var job: Job? = null
    }

    private val lock = Any()
    private var active: ActiveOperation? = null
    private val mutableState = MutableStateFlow<HostConnectionTestUiState>(
        HostConnectionTestUiState.Idle,
    )
    val state: StateFlow<HostConnectionTestUiState> = mutableState.asStateFlow()

    fun start(prepared: PreparedHostConnectionTest): Long {
        val operation = ActiveOperation(
            operationToken = nextOperationToken(),
            prepared = prepared,
        )
        val replaced = synchronized(lock) {
            active.also {
                active = operation
                mutableState.value = HostConnectionTestUiState.Running(operation.operationToken)
            }
        }
        retire(replaced)

        val job = scope.launch(
            context = dispatcher,
            start = CoroutineStart.LAZY,
        ) {
            val result = runner.run(
                prepared = operation.prepared,
                interaction = InteractionFor(operation),
            )
            complete(operation, result)
        }
        // If the scope is already cancelled and the body never enters, this is the only cleanup
        // hook that runs. Once the runner starts its own finally may also wipe, which is harmless.
        job.invokeOnCompletion { operation.prepared.wipeSecret() }
        val shouldStart = synchronized(lock) {
            if (active === operation) {
                operation.job = job
                true
            } else {
                false
            }
        }
        if (shouldStart) {
            job.start()
        } else {
            job.cancel()
            operation.prepared.wipeSecret()
        }
        return operation.operationToken
    }

    fun answerHostIdentity(
        operationToken: Long,
        promptToken: Long,
        decision: HostIdentityDecision,
    ): Boolean {
        val owner = synchronized(lock) {
            val operation = active
            val presentation = mutableState.value
            operation?.promptOwner?.takeIf {
                operation.operationToken == operationToken &&
                    presentation is HostConnectionTestUiState.AwaitingHostIdentity &&
                    presentation.operationToken == operationToken &&
                    presentation.prompt.promptToken == promptToken
            }
        } ?: return false
        val accepted = runCatching { owner.answerHostIdentity(promptToken, decision) }
            .getOrDefault(false)
        if (accepted) markRunning(operationToken)
        return accepted
    }

    /** Takes ownership of [responses] on every path. */
    fun answerKeyboardInteractive(
        operationToken: Long,
        challengeToken: Long,
        responses: List<CharArray>,
    ): Boolean {
        val owner = synchronized(lock) {
            val operation = active
            val presentation = mutableState.value
            operation?.promptOwner?.takeIf {
                operation.operationToken == operationToken &&
                    presentation is HostConnectionTestUiState.AwaitingKeyboardInteractive &&
                    presentation.operationToken == operationToken &&
                    presentation.challenge.challengeToken == challengeToken
            }
        }
        if (owner == null) {
            responses.wipeHostTestResponses()
            return false
        }
        val accepted = try {
            owner.answerKeyboardInteractive(challengeToken, responses)
        } catch (_: Exception) {
            responses.wipeHostTestResponses()
            false
        }
        if (accepted) {
            markRunning(operationToken)
        } else {
            // Enforce the boundary even if an owner violates its rejection contract.
            responses.wipeHostTestResponses()
        }
        return accepted
    }

    fun cancelKeyboardInteractive(
        operationToken: Long,
        challengeToken: Long,
    ): Boolean {
        val owner = synchronized(lock) {
            val operation = active
            val presentation = mutableState.value
            operation?.promptOwner?.takeIf {
                operation.operationToken == operationToken &&
                    presentation is HostConnectionTestUiState.AwaitingKeyboardInteractive &&
                    presentation.operationToken == operationToken &&
                    presentation.challenge.challengeToken == challengeToken
            }
        } ?: return false
        val accepted = runCatching { owner.cancelKeyboardInteractive(challengeToken) }
            .getOrDefault(false)
        if (accepted) markRunning(operationToken)
        return accepted
    }

    /**
     * Cancels a live operation or dismisses its exact completed presentation. A stale token can
     * never clear a newer live or completed operation.
     */
    fun cancel(operationToken: Long? = null) {
        val retired = synchronized(lock) {
            val operation = active
            val current = mutableState.value
            val matches = when {
                operationToken == null -> true
                operation != null -> operation.operationToken == operationToken
                else -> current.operationToken == operationToken
            }
            if (!matches) return
            active = null
            if (operationToken == null || current.operationToken == operationToken) {
                mutableState.value = HostConnectionTestUiState.Idle
            }
            operation
        }
        retire(retired)
    }

    override fun close() = cancel()

    private inner class InteractionFor(
        private val operation: ActiveOperation,
    ) : HostConnectionTestInteraction {
        override fun registerPromptOwner(owner: HostConnectionTestPromptOwner): Boolean =
            synchronized(lock) {
                if (active === operation && operation.promptOwner == null) {
                    operation.promptOwner = owner
                    true
                } else {
                    false
                }
            }

        override fun showHostIdentityPrompt(prompt: HostIdentityPrompt) {
            synchronized(lock) {
                if (active === operation && operation.promptOwner != null) {
                    mutableState.value = HostConnectionTestUiState.AwaitingHostIdentity(
                        operationToken = operation.operationToken,
                        prompt = prompt,
                    )
                }
            }
        }

        override fun showKeyboardInteractiveChallenge(challenge: KeyboardInteractiveChallenge) {
            synchronized(lock) {
                if (active === operation && operation.promptOwner != null) {
                    mutableState.value = HostConnectionTestUiState.AwaitingKeyboardInteractive(
                        operationToken = operation.operationToken,
                        challenge = challenge,
                    )
                }
            }
        }
    }

    private fun markRunning(operationToken: Long) {
        synchronized(lock) {
            if (active?.operationToken == operationToken) {
                mutableState.value = HostConnectionTestUiState.Running(operationToken)
            }
        }
    }

    private fun complete(
        operation: ActiveOperation,
        result: HostConnectionTestResult,
    ) {
        val owner = synchronized(lock) {
            if (active !== operation) return
            active = null
            mutableState.value = HostConnectionTestUiState.Complete(
                operationToken = operation.operationToken,
                result = result,
            )
            operation.promptOwner.also { operation.promptOwner = null }
        }
        runCatching { owner?.close() }
        operation.prepared.wipeSecret()
    }

    private fun retire(operation: ActiveOperation?) {
        if (operation == null) return
        val owner = synchronized(lock) {
            operation.promptOwner.also { operation.promptOwner = null }
        }
        runCatching { owner?.close() }
        operation.job?.cancel()
        operation.prepared.wipeSecret()
    }
}

private val nextHostConnectionTestOperationToken = AtomicLong(0L)

private fun List<CharArray>.wipeHostTestResponses() {
    forEach { it.fill('\u0000') }
}

/** Injectable boundary that keeps stage attribution deterministic and independently testable. */
internal interface HostConnectionTestProbe {
    suspend fun resolve(host: String)

    suspend fun connectTcp(host: String, port: Int)

    suspend fun authenticate(
        spec: HostConnectionTestSpec,
        interaction: HostConnectionTestInteraction,
    ): HostConnectionTestLease
}

internal fun interface HostConnectionTestLease : AutoCloseable {
    suspend fun openShell()

    override fun close() = Unit
}

internal class HostConnectionStageFailure(
    val stage: ConnectionTestStage,
    val safeMessage: UiText,
) : Exception() {
    constructor(stage: ConnectionTestStage, safeMessage: String) : this(
        stage,
        UiText.Dynamic(safeMessage),
    )
}

internal class HostConnectionApprovalRequired(
    val prompt: HostIdentityPrompt,
) : Exception()

internal class HostConnectionAuthenticationRequired(
    val challenge: KeyboardInteractiveChallenge,
) : Exception()

/**
 * Runs every test through an explicit six-stage state machine. Cancellation is never translated
 * into a failure result, and caller-owned password/passphrase bytes are wiped on every exit.
 */
internal class StagedHostConnectionTester(
    private val probe: HostConnectionTestProbe,
) : HostConnectionTester {
    override suspend fun test(
        spec: HostConnectionTestSpec,
        interaction: HostConnectionTestInteraction,
    ): HostConnectionTestResult = try {
        withContext(Dispatchers.IO) {
            val completed = linkedSetOf<ConnectionTestStage>()
            var lease: HostConnectionTestLease? = null
            try {
                probe.resolve(spec.host)
                completed += ConnectionTestStage.DNS

                probe.connectTcp(spec.host, spec.port)
                completed += ConnectionTestStage.TCP

                lease = probe.authenticate(spec, interaction)
                completed += ConnectionTestStage.SSH_NEGOTIATION
                completed += ConnectionTestStage.HOST_KEY
                completed += ConnectionTestStage.AUTHENTICATION

                lease.openShell()
                completed += ConnectionTestStage.SHELL
                HostConnectionTestResult.Success(
                    completedStages = completed.toSet(),
                    startupCommandSkipped = spec.startupCommandConfigured,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (approval: HostConnectionApprovalRequired) {
                HostConnectionTestResult.HostKeyApprovalRequired(
                    prompt = approval.prompt,
                    completedStages = completed.toSet(),
                )
            } catch (authentication: HostConnectionAuthenticationRequired) {
                HostConnectionTestResult.KeyboardInteractiveRequired(
                    challenge = authentication.challenge,
                    completedStages = completed.toSet(),
                )
            } catch (failure: HostConnectionStageFailure) {
                HostConnectionTestResult.Failed(
                    stage = failure.stage,
                    message = failure.safeMessage,
                    completedStages = completed.toSet(),
                )
            } catch (_: Exception) {
                HostConnectionTestResult.Failed(
                    stage = ConnectionTestStage.SSH_NEGOTIATION,
                    message = uiText(R.string.connections_test_ssh_negotiation_failed),
                    completedStages = completed.toSet(),
                )
            } finally {
                runCatching { lease?.close() }
            }
        }
    } finally {
        // withContext may be cancelled before its block is entered.
        spec.clearSecrets()
    }
}

/**
 * A real SSH connection test. The live ViewModel-owned operation makes every trust/authentication
 * decision explicit and keeps the exact connecting session alive until the user answers or cancels.
 */
internal class JschHostConnectionTester(
    knownHostManager: KnownHostManager,
) : HostConnectionTester by StagedHostConnectionTester(JschHostConnectionTestProbe(knownHostManager))

private class JschHostConnectionTestProbe(
    knownHostManager: KnownHostManager,
) : HostConnectionTestProbe {
    private val sessions = JschAuthenticatedSessionFactory(knownHostManager)

    override suspend fun resolve(host: String) {
        try {
            InetAddress.getAllByName(host).takeIf { it.isNotEmpty() }
                ?: throw HostConnectionStageFailure(
                    ConnectionTestStage.DNS,
                    uiText(R.string.connections_test_dns_failed),
                )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: HostConnectionStageFailure) {
            throw failure
        } catch (_: Exception) {
            throw HostConnectionStageFailure(
                ConnectionTestStage.DNS,
                uiText(R.string.connections_test_dns_failed),
            )
        }
    }

    override suspend fun connectTcp(host: String, port: Int) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), TCP_TIMEOUT_MILLIS)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw HostConnectionStageFailure(
                ConnectionTestStage.TCP,
                uiText(R.string.connections_test_tcp_failed),
            )
        }
    }

    override suspend fun authenticate(
        spec: HostConnectionTestSpec,
        interaction: HostConnectionTestInteraction,
    ): HostConnectionTestLease {
        var repository: VerifyingHostKeyRepository? = null
        val opened = try {
            sessions.connect(
                config = SshConnectionConfig(
                    host = spec.host,
                    port = spec.port,
                    username = spec.username,
                    authentication = spec.authentication,
                    terminalType = spec.terminalType,
                ),
                onRepositoryReady = { repository = it },
                onPrompt = interaction::showHostIdentityPrompt,
                onKeyboardInteractiveChallenge = interaction::showKeyboardInteractiveChallenge,
                registerBeforeConnect = { candidate ->
                    val promptOwner = JschHostConnectionTestPromptOwner(
                        repository = requireNotNull(repository),
                        session = candidate,
                    )
                    interaction.registerPromptOwner(promptOwner).also { accepted ->
                        if (!accepted) promptOwner.close()
                    }
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val hostKeyFailure = repository?.failure
            if (hostKeyFailure != null) {
                throw HostConnectionStageFailure(
                    stage = ConnectionTestStage.HOST_KEY,
                    safeMessage = when (hostKeyFailure) {
                        HostKeyFailure.CHANGED ->
                            uiText(R.string.connections_test_host_key_changed)
                        HostKeyFailure.REJECTED ->
                            uiText(R.string.connections_test_host_key_rejected)
                        HostKeyFailure.STORE_FAILED ->
                            uiText(R.string.connections_test_host_key_store_failed)
                    },
                )
            }
            val message = if (error is JSchException) {
                safeJschFailureMessage(error.message)
            } else {
                null
            }
            throw HostConnectionStageFailure(
                stage = if (message?.contains("authentication", ignoreCase = true) == true) {
                    ConnectionTestStage.AUTHENTICATION
                } else {
                    ConnectionTestStage.SSH_NEGOTIATION
                },
                safeMessage = message?.let { UiText.Dynamic(it) }
                    ?: uiText(R.string.connections_test_ssh_negotiation_failed),
            )
        }
        return JschHostConnectionTestLease(
            requireNotNull(opened) { "SSH connection test was cancelled before authentication." },
            terminalType = spec.terminalType,
        )
    }

    private companion object {
        const val TCP_TIMEOUT_MILLIS = 5_000
    }
}

private class JschHostConnectionTestPromptOwner(
    private val repository: VerifyingHostKeyRepository,
    private val session: AuthenticatedJschSession,
) : HostConnectionTestPromptOwner {
    override fun answerHostIdentity(
        promptToken: Long,
        decision: HostIdentityDecision,
    ): Boolean = repository.answerPrompt(promptToken, decision)

    override fun answerKeyboardInteractive(
        challengeToken: Long,
        responses: List<CharArray>,
    ): Boolean = session.answerKeyboardInteractiveChallenge(challengeToken, responses)

    override fun cancelKeyboardInteractive(challengeToken: Long): Boolean =
        session.cancelKeyboardInteractiveChallenge(challengeToken)

    override fun close() = session.close()
}

private class JschHostConnectionTestLease(
    private val opened: AuthenticatedJschSession,
    private val terminalType: String,
) : HostConnectionTestLease {
    override suspend fun openShell() {
        try {
            val shell = opened.session.openChannel("shell") as ChannelShell
            try {
                configureSshPty(
                    target = object : SshPtyTarget {
                        override fun enablePty() = shell.setPty(true)

                        override fun setTerminalType(terminalType: String) =
                            shell.setPtyType(terminalType)

                        override fun setDimensions(columns: Int, rows: Int) =
                            shell.setPtySize(columns, rows, 0, 0)
                    },
                    terminalType = terminalType,
                    columns = 80,
                    rows = 24,
                )
                shell.connect(CHANNEL_TIMEOUT_MILLIS)
            } finally {
                runCatching(shell::disconnect)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw HostConnectionStageFailure(
                ConnectionTestStage.SHELL,
                uiText(R.string.connections_test_shell_failed),
            )
        }
    }

    override fun close() = opened.close()

    private companion object {
        const val CHANNEL_TIMEOUT_MILLIS = 10_000
    }
}

private fun HostConnectionTestSpec.clearSecrets() {
    when (val selected = authentication) {
        is SshAuthentication.Password -> selected.secret.fill(0)
        is SshAuthentication.PrivateKey -> selected.passphrase?.fill(0)
        is SshAuthentication.StoredPassword -> Unit
        is SshAuthentication.KeyboardInteractive.SessionOnly ->
            selected.initialResponse?.fill(0)
        is SshAuthentication.KeyboardInteractive.ReusableResponse -> Unit
    }
}
