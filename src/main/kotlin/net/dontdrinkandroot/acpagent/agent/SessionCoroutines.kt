package net.dontdrinkandroot.acpagent.agent

import com.agentclientprotocol.agent.client
import com.agentclientprotocol.common.ClientSessionOperations
import kotlinx.coroutines.currentCoroutineContext

/**
 * The client session operations of the enclosing session context, or null
 * outside one. `runCatching` because the SDK extension throws when the
 * context carries no session element.
 */
internal suspend fun clientOrNull(): ClientSessionOperations? =
    runCatching { currentCoroutineContext().client }.getOrNull()
