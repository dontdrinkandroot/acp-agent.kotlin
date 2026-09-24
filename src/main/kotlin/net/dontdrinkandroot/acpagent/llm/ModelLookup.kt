package net.dontdrinkandroot.acpagent.llm

/**
 * The model with the given id, or null. The single lookup shared by the
 * session facade, the config options and the usage indicator.
 */
internal fun List<OpenRouterModel>.forModel(id: String): OpenRouterModel? = firstOrNull { it.id == id }
