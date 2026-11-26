package net.dontdrinkandroot.acpagent.sandbox

/**
 * Renders the `docker run` argv from a resolved [SandboxPlan]: pure, no concatenation of whole
 * commands and no word splitting (`extraArgs`/`capAdd` are typed lists, mounts are typed
 * parts). The argv **includes** the `run` subcommand, so it is exactly what is handed to the
 * docker CLI.
 */
fun composeDockerInvocation(plan: SandboxPlan): List<String> = buildList {
    addAll(listOf("run", "--rm", "--init", "-i"))
    addAll(listOf("--network", plan.network))
    add("--add-host=host.docker.internal:${plan.hostDockerInternalTarget}")
    addAll(listOf("--user", "${plan.uid}:${plan.gid}"))

    plan.permissionMounts.forEach { addMountPart(it) }

    addAll(listOf("--workdir", plan.cwd))
    addMountPart(plan.projectMount)
    plan.envLocalMask?.let { addMountPart(it) }
    addMountPart(plan.homeMount)
    plan.gradleTmpfs.forEach { addTmpfs(it) }
    plan.gradleVersionTmpfs.forEach { addTmpfs(it) }
    addTmpfs(plan.cacheTmpfs)
    plan.localTmpfs.forEach { addTmpfs(it) }
    plan.cacheMounts.forEach { addMountPart(it) }
    plan.stateMount?.let { addMountPart(it) }
    plan.configTmpfs?.let { addTmpfs(it) }
    plan.configMount?.let { addMountPart(it) }

    if (plan.readOnlyRootfs) add("--read-only")
    add("--cap-drop=ALL")
    plan.capAdd.forEach { addAll(listOf("--cap-add", it)) }
    addAll(listOf("--security-opt", "no-new-privileges"))
    addAll(listOf("--tmpfs", "/tmp:rw,nosuid,nodev,exec,size=1g"))

    fun addEnv(entry: EnvEntry) = addAll(listOf("--env", "${entry.key}=${entry.value}"))
    plan.gitIdentityEnv.forEach(::addEnv)
    plan.forwardedEnv.forEach(::addEnv)
    plan.cacheEnv.forEach(::addEnv)
    plan.baseEnv.forEach(::addEnv)

    addAll(plan.extraArgs)
    add(plan.image)
    addAll(plan.agentArgs)
}

/**
 * Renders a sandbox mount under its docker flag: tmpfs mounts go through `--tmpfs`
 * (an anonymous, uid-mapped filesystem), bind and volume mounts through `--mount`.
 */
private fun MutableList<String>.addMountPart(mount: SandboxMount) = when (mount) {
    is TmpfsMount -> addTmpfs(mount)
    is BindMount, is VolumeMount -> addAll(listOf("--mount", mount.render()))
}

private fun MutableList<String>.addTmpfs(mount: TmpfsMount) = addAll(listOf("--tmpfs", mount.render()))
