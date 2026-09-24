package net.dontdrinkandroot.acpagent.agent

import ai.koog.prompt.executor.clients.openai.base.models.Content
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

/**
 * Shared by the session facade and the session state: the text of a Koog
 * `Content`, or null when absent.
 */
internal fun Content?.textOrNull(): String? = this?.text()
