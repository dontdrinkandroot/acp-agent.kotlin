#!/bin/bash
# Pins the docker-run composition of ddr-acp-agent-docker: sandbox flags,
# env forwarding, .env.local masking, git identity forwarding and the pull
# fallback behavior.
# shellcheck source=tests/bash/common.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"

new_sandbox launcher-args >/dev/null
setup_git_identity

PROJECT=$TEST_TMP/project
mkdir -p "$PROJECT"

test_project_is_mounted_at_the_identical_path_with_workdir() {
    run_docker_launcher "$PROJECT" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_contains "$line" "--workdir $PROJECT"
    assert_contains "$line" "type=bind,src=$PROJECT,dst=$PROJECT"
}

test_sandbox_hardening_flags_are_present() {
    run_docker_launcher "$PROJECT" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_contains "$line" "--cap-drop=ALL"
    assert_contains "$line" "--security-opt no-new-privileges"
    assert_contains "$line" "--read-only"
    assert_contains "$line" "--rm --init -i"
    assert_contains "$line" "--tmpfs /tmp:rw,nosuid,nodev,exec,size=1g"
    assert_contains "$line" "--user $(id -u):$(id -g)"
}

test_rw_rootfs_opt_out_relaxes_read_only() {
    run_docker_launcher "$PROJECT" DDR_ACP_AGENT_DOCKER_RW_ROOTFS=1 --skip-pull
    assert_exit_status 0
    assert_not_contains "$(last_run_line)" "--read-only"
}

# The agent container shares the host's network namespace by default: Gradle's
# cache locking only hands a lock to another process via a loopback UDP ping
# (issue #68), so a bridge-namespaced container times out after ~60s instead of
# handing over. In host mode the container also resolves host-loopback services
# (e.g. the PhpStorm MCP server at http://host.docker.internal:64442/sse)
# directly, so the mapping must point at 127.0.0.1 instead of the bridge
# gateway, which would miss 127.0.0.1-bound services.
test_host_network_is_the_default() {
    run_docker_launcher "$PROJECT" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_contains "$line" "--network host"
    assert_contains "$line" "--add-host=host.docker.internal:127.0.0.1"
}

# Opting back into a bridge/named network restores namespace isolation, so
# host.docker.internal maps to the bridge gateway again (the service names of a
# compose project, e.g. 'postgres', resolve through its embedded DNS).
test_explicit_network_keeps_the_host_gateway_mapping() {
    run_docker_launcher "$PROJECT" DDR_ACP_AGENT_DOCKER_NETWORK=development --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_contains "$line" "--network development"
    assert_contains "$line" "--add-host=host.docker.internal:host-gateway"
}

test_explicit_docker_network_is_honored() {
    run_docker_launcher "$PROJECT" DDR_ACP_AGENT_DOCKER_NETWORK=default --skip-pull
    assert_exit_status 0
    assert_contains "$(last_run_line)" "--network default"
}

test_extra_args_are_appended() {
    run_docker_launcher "$PROJECT" \
        "DDR_ACP_AGENT_DOCKER_EXTRA_ARGS=--label foo=bar --label baz=qux" --skip-pull
    assert_exit_status 0
    assert_contains "$(last_run_line)" "--label foo=bar --label baz=qux"
}

test_home_local_directories_get_uid_mapped_tmpfs_mounts() {
    # runc creates missing bind-mount mountpoints root-owned inside the
    # container; the ~/.local tmpfs mounts keep the whole XDG state/data home
    # writable for the agent's uid (the session-state bind mounts on top of
    # the state tmpfs; see the mount-ordering test below).
    run_docker_launcher "$PROJECT" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    # The home tmpfs is exec-allowed: toolchains execute native binaries
    # extracted into $HOME (Kotlin/Native under ~/.konan; see issue #33).
    assert_contains "$line" \
        "--tmpfs /home/dev:uid=$(id -u),gid=$(id -g),mode=700,size=1g,exec"
    # ~/.local/.cache keep docker's noexec default - nothing executes there.
    assert_contains "$line" \
        "--tmpfs /home/dev/.local:uid=$(id -u),gid=$(id -g),mode=700,size=1g"
    assert_contains "$line" \
        "--tmpfs /home/dev/.local/share:uid=$(id -u),gid=$(id -g),mode=700,size=1g"
    assert_contains "$line" \
        "--tmpfs /home/dev/.local/state:uid=$(id -u),gid=$(id -g),mode=700,size=1g"
}

test_state_bind_lands_on_top_of_the_local_state_tmpfs() {
    # The session-state bind (dst=/home/dev/.local/state/ddr-acp-agent) must
    # come after the .local tmpfs mounts in the docker run argv: runc mounts
    # in spec order (moby sorts shallowest-first), so a shallower tmpfs mounted
    # later would shadow the state bind and sessions would live in an
    # ephemeral tmpfs. Asserting argv order over-pins the daemon's sort - the
    # launcher keeps the argv independently correct either way.
    run_docker_launcher "$PROJECT" "DDR_ACP_AGENT_DOCKER_STATE_DIR=$TEST_TMP/ddr-acp-agent" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_contains "$line" \
        "type=bind,src=$TEST_TMP/ddr-acp-agent,dst=/home/dev/.local/state/ddr-acp-agent"
    local local_tmpfs_state_bind
    local_tmpfs_state_bind=$(
        printf '%s\n' "$line" |
            sed -n 's/.*--tmpfs \/home\/dev\/\.local:uid=.*size=1g.*\(type=bind,src=[^ ]*dst=\/home\/dev\/\.local\/state\/ddr-acp-agent\).*/\1/p'
    )
    assert_nonempty "$local_tmpfs_state_bind"
}

test_mount_caches_zero_skips_the_local_store_and_cache_mounts() {
    # DDR_ACP_AGENT_DOCKER_MOUNT_CACHES=0 disables the cache binds, but never the
    # uid-mapped tmpfs mounts: ~/.local, ~/.cache and the private Gradle
    # user-home root stay writable.
    run_docker_launcher "$PROJECT" DDR_ACP_AGENT_DOCKER_MOUNT_CACHES=0 --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_not_contains "$(mount_values "$line")" "pnpm/store"
    assert_not_contains "$line" "type=bind,src=$TEST_TMP/.gradle"
    assert_contains "$line" \
        "--tmpfs /home/dev/.local:uid=$(id -u),gid=$(id -g),mode=700,size=1g"
    assert_contains "$line" \
        "--tmpfs /home/dev/.cache:uid=$(id -u),gid=$(id -g),mode=700,size=1g"
    # The private Gradle tmpfs root + intermediate depths are unconditional:
    # no whole-GUH bind and no leaf binds under DDR_ACP_AGENT_DOCKER_MOUNT_CACHES=0, but
    # the .gradle root/caches/wrapper tmpfs mounts are still present so
    # daemon/process state stays container-private and every depth the binds
    # would otherwise leave root-owned stays writable.
    # "/.gradle" shape: matches any shared GUH bind (src or dst) but never the
    # state bind under a fixture path that merely contains "gradle" in its name.
    assert_not_contains "$(mount_values "$line")" "/.gradle"
    local gradle_tmpfs
    gradle_tmpfs=$(printf '%s\n' "$line" | tr ' ' '\n' | grep '^/home/dev/\.gradle' || true)
    assert_eq "3" "$(printf '%s\n' "$gradle_tmpfs" | grep -c . || true)"
    assert_contains "$line" \
        "--tmpfs /home/dev/.gradle:uid=$(id -u),gid=$(id -g),mode=700,size=1g,exec"
    assert_contains "$line" \
        "--tmpfs /home/dev/.gradle/caches:uid=$(id -u),gid=$(id -g),mode=700,size=1g,exec"
    assert_contains "$line" \
        "--tmpfs /home/dev/.gradle/wrapper:uid=$(id -u),gid=$(id -g),mode=700,size=1g,exec"
}

test_explicit_cache_dir_is_identity_mounted_and_forwarded_unchanged() {
    # Explicit UV_CACHE_DIR config is honored as-is (issue #51): the dir is
    # created if missing, identity-bound at the identical in-container path
    # (no relocation to the container default) and the env value is forwarded
    # verbatim - trailing slash included.
    local uv_cache=$TEST_TMP/altdata/cache/uv
    run_docker_launcher "$PROJECT" "UV_CACHE_DIR=$uv_cache/" --skip-pull
    assert_exit_status 0
    [ -d "$uv_cache" ] || _fail "expected $uv_cache to be created"
    local line
    line=$(last_run_line)
    assert_contains "$(mount_values "$line")" "type=bind,src=$uv_cache,dst=$uv_cache"
    assert_eq "$uv_cache/" "$(env_value_for "$line" UV_CACHE_DIR)"
}

test_explicit_cache_dir_inside_the_project_is_forwarded_without_a_mount() {
    # An in-project cache dir needs no separate mount (the project is mounted
    # rw at the identical path) - the env value must still be forwarded so the
    # tool honors the configured path instead of silently falling back to its
    # in-container default (issue #51).
    local uv_cache=$PROJECT/cache-uv
    run_docker_launcher "$PROJECT" "UV_CACHE_DIR=$uv_cache" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_not_contains "$(mount_values "$line")" "src=$uv_cache,dst=$uv_cache"
    assert_eq "$uv_cache" "$(env_value_for "$line" UV_CACHE_DIR)"
}

test_uv_link_mode_is_forwarded_when_set() {
    # Hosts configure UV_LINK_MODE=symlink for cross-device cache/venv layouts
    # (caches on an unencrypted disk, venvs in an encrypted home) - the same
    # layout exists in the container once the cache is identity-bound, so the
    # setting must travel (issue #51).
    run_docker_launcher "$PROJECT" UV_LINK_MODE=symlink --skip-pull
    assert_exit_status 0
    assert_eq "symlink" "$(env_value_for "$(last_run_line)" UV_LINK_MODE)"
}

test_pnpm_store_and_cache_dirs_are_identity_mounted_and_forwarded() {
    # Explicit PNPM_CONFIG_STORE_DIR/PNPM_CONFIG_CACHE_DIR config (pnpm reads
    # pnpm_config_*/PNPM_CONFIG_* env vars, pnpm.io/configuring) gets the
    # issue #51 explicit-config policy: identity-bound at the identical
    # in-container path and forwarded verbatim - so host-created global-
    # virtual-store symlinks into the shared store keep resolving.
    local pnpm_store=$TEST_TMP/altdata/cache/pnpm/store
    local pnpm_cache=$TEST_TMP/altdata/cache/pnpm/cache
    run_docker_launcher "$PROJECT" \
        "PNPM_CONFIG_STORE_DIR=$pnpm_store" "PNPM_CONFIG_CACHE_DIR=$pnpm_cache" --skip-pull
    assert_exit_status 0
    [ -d "$pnpm_store" ] || _fail "expected $pnpm_store to be created"
    [ -d "$pnpm_cache" ] || _fail "expected $pnpm_cache to be created"
    local line
    line=$(last_run_line)
    assert_contains "$(mount_values "$line")" "type=bind,src=$pnpm_store,dst=$pnpm_store"
    assert_contains "$(mount_values "$line")" "type=bind,src=$pnpm_cache,dst=$pnpm_cache"
    assert_eq "$pnpm_store" "$(env_value_for "$line" PNPM_CONFIG_STORE_DIR)"
    assert_eq "$pnpm_cache" "$(env_value_for "$line" PNPM_CONFIG_CACHE_DIR)"
}

test_pnpm_global_virtual_store_is_forwarded_when_set() {
    # pnpm's enable-global-virtual-store makes node_modules symlink into the
    # shared store - the setting must travel with the identity-bound store so
    # host and container projects share one layout (like UV_LINK_MODE).
    run_docker_launcher "$PROJECT" \
        PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE=true --skip-pull
    assert_exit_status 0
    assert_eq "true" "$(env_value_for "$(last_run_line)" PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE)"
}

test_pnpm_default_candidates_are_mounted_at_the_container_defaults() {
    # Without explicit config the existing pnpm default dirs are shared at the
    # tool's in-container defaults and the env vars are pinned to those dsts -
    # the shared fallback policy of add_tool_cache (pnpm's defaults follow
    # $XDG_DATA_HOME/pnpm/store and $XDG_CACHE_HOME/pnpm).
    local xdg_data=$TEST_TMP/xdg-data
    local xdg_cache=$TEST_TMP/xdg-cache
    mkdir -p "$xdg_data/pnpm/store" "$xdg_cache/pnpm" ||
        _fail "could not create the pnpm default-candidate fixtures"
    run_docker_launcher "$PROJECT" \
        "XDG_DATA_HOME=$xdg_data" "XDG_CACHE_HOME=$xdg_cache" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_contains "$(mount_values "$line")" \
        "type=bind,src=$xdg_data/pnpm/store,dst=/home/dev/.local/share/pnpm/store"
    assert_contains "$(mount_values "$line")" \
        "type=bind,src=$xdg_cache/pnpm,dst=/home/dev/.cache/pnpm"
    assert_eq "/home/dev/.local/share/pnpm/store" "$(env_value_for "$line" PNPM_CONFIG_STORE_DIR)"
    assert_eq "/home/dev/.cache/pnpm" "$(env_value_for "$line" PNPM_CONFIG_CACHE_DIR)"
}

test_mount_caches_zero_ignores_explicit_cache_config_entirely() {
    # Total opt-out: with no caches shared at all, explicit cache config is
    # ignored wholesale - no mounts, no env forwarding and no validation of
    # even unusable values (the tool keeps its in-container default; issue #51).
    run_docker_launcher "$PROJECT" \
        DDR_ACP_AGENT_DOCKER_MOUNT_CACHES=0 UV_CACHE_DIR=relative/cache ANDROID_HOME=relative/sdk \
        UV_LINK_MODE=symlink PNPM_CONFIG_STORE_DIR=relative/store \
        PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE=true --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_empty "$(env_value_for "$line" UV_CACHE_DIR)"
    assert_empty "$(env_value_for "$line" ANDROID_HOME)"
    assert_empty "$(env_value_for "$line" ANDROID_SDK_ROOT)"
    assert_empty "$(env_value_for "$line" UV_LINK_MODE)"
    assert_empty "$(env_value_for "$line" PNPM_CONFIG_STORE_DIR)"
    assert_empty "$(env_value_for "$line" PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE)"
    assert_not_contains "$(mount_values "$line")" "cache"
}

test_the_launcher_never_handles_the_api_key() {
    # Issue #50: the key is no longer a launcher input at all. Even with the old
    # host variables set, nothing reaches the container: no key mount, no key
    # env - the key travels inside the mounted config directory instead.
    local key_file=$TEST_TMP/host-key
    printf 'sk-hostfile\n' >"$key_file"
    chmod 600 "$key_file"
    run_docker_launcher "$PROJECT" OPENROUTER_API_KEY=sk-env "OPENROUTER_API_KEY_FILE=$key_file" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_nonempty "$line"
    assert_empty "$(env_value_for "$line" OPENROUTER_API_KEY)"
    assert_empty "$(env_value_for "$line" OPENROUTER_API_KEY_FILE)"
    assert_empty "$(env_value_for "$line" DDR_ACP_AGENT_OPEN_ROUTER_API_KEY_FILE)"
    assert_not_contains "$(mount_values "$line")" "openrouter-api-key"
    [ -f "$key_file" ] || _fail "the launcher must not touch the host key file"
}

test_optional_env_vars_are_forwarded_only_when_set() {
    # KEY=VALUE env args must precede launcher flags (run_docker_launcher
    # parses them until the first non-assignment). The agent's whole env layer
    # (issue #50) is forwarded verbatim by prefix.
    run_docker_launcher "$PROJECT" \
        DDR_ACP_AGENT_OPEN_ROUTER_DEFAULT_MODEL=test/model DDR_ACP_AGENT_TOOLS_FS_PROXY=0 \
        DDR_ACP_AGENT_TOOLS_WEB_FETCH_ALLOW_PRIVATE=1 \
        DDR_ACP_AGENT_CONFIG_FILE=work.json \
        DDR_ACP_AGENT_SCRATCH_DIR=/tmp/scratch-test \
        TZ=Europe/Berlin --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_eq "test/model" "$(env_value_for "$line" DDR_ACP_AGENT_OPEN_ROUTER_DEFAULT_MODEL)"
    assert_eq "0" "$(env_value_for "$line" DDR_ACP_AGENT_TOOLS_FS_PROXY)"
    assert_eq "1" "$(env_value_for "$line" DDR_ACP_AGENT_TOOLS_WEB_FETCH_ALLOW_PRIVATE)"
    # The config-file *name* travels verbatim and resolves inside the mounted
    # config directory (issue #50) - it is deliberately not a host path.
    assert_eq "work.json" "$(env_value_for "$line" DDR_ACP_AGENT_CONFIG_FILE)"
    # The scratch directory (issue #10) is forwarded verbatim - never mounted,
    # and never reinterpreted by the launcher.
    assert_eq "/tmp/scratch-test" "$(env_value_for "$line" DDR_ACP_AGENT_SCRATCH_DIR)"
    # TZ keeps the agent's rendered timestamps on the host zone (issue #22).
    assert_eq "Europe/Berlin" "$(env_value_for "$line" TZ)"
    assert_empty "$(env_value_for "$line" DDR_ACP_AGENT_TOOLS_MAX_TURN_REQUESTS)"
}

test_launcher_knobs_stay_out_of_the_agent_env() {
    # The launcher's own knobs live under DDR_ACP_AGENT_DOCKER_*, i.e. inside
    # the same prefix family the agent config is forwarded by. They are
    # host-side decisions (network mode, state dir, cache sharing, ...) that
    # mean nothing inside the container, so the forwarding loop must carve them
    # out - otherwise the agent would see, e.g., a network name as config.
    run_docker_launcher "$PROJECT" \
        DDR_ACP_AGENT_DOCKER_NETWORK=development \
        DDR_ACP_AGENT_DOCKER_MOUNT_CACHES=0 \
        DDR_ACP_AGENT_DOCKER_STATE_DIR="$TEST_TMP/state" \
        DDR_ACP_AGENT_DOCKER_RW_ROOTFS=1 \
        DDR_ACP_AGENT_DOCKER_CAP_ADD=SYS_PTRACE \
        DDR_ACP_AGENT_DOCKER_HOME_VOLUME=probe-volume \
        DDR_ACP_AGENT_DOCKER_EXTRA_ARGS="--label probe=1" \
        DDR_ACP_AGENT_TOOLS_FS_PROXY=0 --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    # The knobs took effect on the docker side ...
    assert_contains "$line" "--network development"
    assert_contains "$line" "--cap-add SYS_PTRACE"
    assert_contains "$line" "--label probe=1"
    assert_contains "$line" "type=volume,src=probe-volume"
    # ... while the agent env carries only its own config.
    assert_eq "0" "$(env_value_for "$line" DDR_ACP_AGENT_TOOLS_FS_PROXY)"
    local knob
    for knob in DDR_ACP_AGENT_DOCKER_NETWORK DDR_ACP_AGENT_DOCKER_MOUNT_CACHES \
        DDR_ACP_AGENT_DOCKER_STATE_DIR DDR_ACP_AGENT_DOCKER_RW_ROOTFS \
        DDR_ACP_AGENT_DOCKER_CAP_ADD DDR_ACP_AGENT_DOCKER_HOME_VOLUME \
        DDR_ACP_AGENT_DOCKER_EXTRA_ARGS; do
        assert_empty "$(env_value_for "$line" "$knob")"
    done
}

test_the_legacy_acp_docker_names_are_inert() {
    # The rename is a hard switch (like the issue #50 env renames): the old
    # ACP_DOCKER_* spellings are not read and produce no warning. A leftover
    # must not silently select a network, relax the rootfs or mount a volume.
    run_docker_launcher "$PROJECT" \
        ACP_DOCKER_NETWORK=development ACP_DOCKER_RW_ROOTFS=1 \
        ACP_DOCKER_HOME_VOLUME=legacy-volume --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_contains "$line" "--network host"
    assert_contains "$line" "--read-only"
    assert_not_contains "$line" "legacy-volume"
}

test_env_local_is_masked_with_an_empty_read_only_file() {
    : >"$PROJECT/.env.local"
    run_docker_launcher "$PROJECT" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_contains "$line" "type=bind,src=$TEST_TMP"
    assert_contains "$line" "dst=$PROJECT/.env.local,ro"
    rm -f "$PROJECT/.env.local"
}

test_env_local_is_not_mounted_when_absent() {
    # Defensive: a previously failing test aborts before its cleanup and must
    # not leak its .env.local fixture into this one.
    rm -f "$PROJECT/.env.local"
    run_docker_launcher "$PROJECT" --skip-pull
    assert_exit_status 0
    assert_not_contains "$(last_run_line)" ".env.local"
}

test_git_identity_is_forwarded_when_configured() {
    GIT_IDENTITY_SET=1 setup_git_identity
    run_docker_launcher "$PROJECT" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    # The stub log is space-joined argv, so the fixture name must not contain
    # a space (a value with one is lossy there, unlike on the real wire).
    assert_eq "TestUser" "$(env_value_for "$line" GIT_AUTHOR_NAME)"
    assert_eq "test@example.com" "$(env_value_for "$line" GIT_COMMITTER_EMAIL)"
}

test_git_identity_is_omitted_when_not_configured() {
    GIT_IDENTITY_SET=0 setup_git_identity
    run_docker_launcher "$PROJECT" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_empty "$(env_value_for "$line" GIT_AUTHOR_NAME)"
    assert_empty "$(env_value_for "$line" GIT_COMMITTER_EMAIL)"
    GIT_IDENTITY_SET=1 setup_git_identity
}

ANDROID_SDK=$TEST_TMP/android-sdk
ANDROID_SDK_ROOT_DIR=$TEST_TMP/android-sdk-root
FAKE_HOME=$TEST_TMP/fakehome
mkdir -p "$ANDROID_SDK" "$ANDROID_SDK_ROOT_DIR" "$FAKE_HOME/Android/Sdk"

test_explicit_android_home_is_identity_mounted_and_forwarded() {
    # Explicit ANDROID_HOME config is identity-bound at the identical
    # in-container path (no relocation to the container default); both
    # ANDROID_* variables carry the configured value (the companion is
    # synthesized to the same path).
    run_docker_launcher "$PROJECT" "ANDROID_HOME=$ANDROID_SDK" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_contains "$(mount_values "$line")" \
        "type=bind,src=$ANDROID_SDK,dst=$ANDROID_SDK"
    assert_eq "$ANDROID_SDK" "$(env_value_for "$line" ANDROID_HOME)"
    assert_eq "$ANDROID_SDK" "$(env_value_for "$line" ANDROID_SDK_ROOT)"
}

test_explicit_android_sdk_root_is_forwarded_with_a_synthesized_home() {
    # A lone ANDROID_SDK_ROOT is the authoritative value: identity-mounted and
    # forwarded verbatim, with ANDROID_HOME synthesized to the same path.
    run_docker_launcher "$PROJECT" "ANDROID_SDK_ROOT=$ANDROID_SDK" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_contains "$(mount_values "$line")" \
        "type=bind,src=$ANDROID_SDK,dst=$ANDROID_SDK"
    assert_eq "$ANDROID_SDK" "$(env_value_for "$line" ANDROID_SDK_ROOT)"
    assert_eq "$ANDROID_SDK" "$(env_value_for "$line" ANDROID_HOME)"
}

test_android_home_wins_over_android_sdk_root() {
    # Both set to different paths: ANDROID_HOME is authoritative - its dir is
    # identity-mounted and both variables carry its value (the losing
    # ANDROID_SDK_ROOT value is never mounted).
    run_docker_launcher "$PROJECT" \
        "ANDROID_HOME=$ANDROID_SDK" "ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT_DIR" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_contains "$(mount_values "$line")" \
        "type=bind,src=$ANDROID_SDK,dst=$ANDROID_SDK"
    assert_not_contains "$(mount_values "$line")" "$ANDROID_SDK_ROOT_DIR"
    assert_eq "$ANDROID_SDK" "$(env_value_for "$line" ANDROID_HOME)"
    assert_eq "$ANDROID_SDK" "$(env_value_for "$line" ANDROID_SDK_ROOT)"
}

test_default_android_sdk_dir_is_picked_up_without_env() {
    # No ANDROID_HOME/ANDROID_SDK_ROOT (common.sh strips them): an existing
    # $HOME/Android/Sdk is shared, never created. HOME is pinned to a fixture
    # so the test does not depend on the runner's real home.
    run_docker_launcher "$PROJECT" "HOME=$FAKE_HOME" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_contains "$(mount_values "$line")" \
        "type=bind,src=$FAKE_HOME/Android/Sdk,dst=/home/dev/Android/Sdk"
    assert_eq "/home/dev/Android/Sdk" "$(env_value_for "$line" ANDROID_HOME)"
}

test_default_android_sdk_dir_inside_the_project_is_skipped() {
    # A default-candidate SDK inside the project dir needs no mount (the
    # project is mounted rw at the identical path) and must not be relocated
    # or pinned either - the tool-cache skip policy applies to it unchanged
    # (issue #51).
    local home_in_project=$PROJECT/home
    mkdir -p "$home_in_project/Android/Sdk"
    run_docker_launcher "$PROJECT" "HOME=$home_in_project" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_not_contains "$(mount_values "$line")" "Android/Sdk"
    assert_empty "$(env_value_for "$line" ANDROID_HOME)"
    assert_empty "$(env_value_for "$line" ANDROID_SDK_ROOT)"
}

test_no_android_mount_without_an_sdk() {
    # HOME without an Android/Sdk dir: nothing is mounted and no ANDROID_*
    # env is injected.
    run_docker_launcher "$PROJECT" "HOME=$TEST_TMP/empty-home" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_not_contains "$(mount_values "$line")" "Android/Sdk"
    assert_empty "$(env_value_for "$line" ANDROID_HOME)"
    assert_empty "$(env_value_for "$line" ANDROID_SDK_ROOT)"
}

test_android_mount_honors_acp_docker_mount_caches_zero() {
    run_docker_launcher "$PROJECT" \
        DDR_ACP_AGENT_DOCKER_MOUNT_CACHES=0 "ANDROID_HOME=$ANDROID_SDK" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_not_contains "$(mount_values "$line")" "Android/Sdk"
    assert_empty "$(env_value_for "$line" ANDROID_HOME)"
}

test_android_sdk_inside_the_project_dir_pins_the_host_path() {
    # A project-internal SDK needs no mount (the project is mounted rw at the
    # identical path) and the pins must keep the original path - pointing at
    # the skipped container default would reference a nonexistent directory.
    local sdk_in_project=$PROJECT/android-sdk
    mkdir -p "$sdk_in_project"
    run_docker_launcher "$PROJECT" "ANDROID_HOME=$sdk_in_project" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_not_contains "$(mount_values "$line")" "Android/Sdk"
    assert_eq "$sdk_in_project" "$(env_value_for "$line" ANDROID_HOME)"
    assert_eq "$sdk_in_project" "$(env_value_for "$line" ANDROID_SDK_ROOT)"
}

GRADLE_HOME_FIXTURE=$TEST_TMP/gradle-home
mkdir -p "$GRADLE_HOME_FIXTURE/.gradle/caches/modules-2" \
    "$GRADLE_HOME_FIXTURE/.gradle/caches/jars-9" \
    "$GRADLE_HOME_FIXTURE/.gradle/caches/jars-8" \
    "$GRADLE_HOME_FIXTURE/.gradle/caches/build-cache-1" \
    "$GRADLE_HOME_FIXTURE/.gradle/caches/transforms-3" \
    "$GRADLE_HOME_FIXTURE/.gradle/caches/9.6.0" \
    "$GRADLE_HOME_FIXTURE/.gradle/wrapper/dists" \
    "$GRADLE_HOME_FIXTURE/.gradle/jdks" \
    "$GRADLE_HOME_FIXTURE/.gradle/daemon" ||
    _fail "could not create the gradle-home fixture"

test_gradle_content_addressed_leaves_are_shared_rw() {
    # Only the content-addressed, machine-independent leaves of the Gradle
    # user home are bind-mounted (modules-2, jars-*, wrapper/dists, jdks) at
    # their identical in-container path; daemon/, per-version caches and the
    # private root are left to the .gradle tmpfs. The GUH root itself is not
    # mounted whole (no daemon-registry merge across PID namespaces).
    run_docker_launcher "$PROJECT" "HOME=$GRADLE_HOME_FIXTURE" --skip-pull
    assert_exit_status 0
    local line mounts
    line=$(last_run_line)
    mounts=$(mount_values "$line")
    assert_contains "$mounts" \
        "type=bind,src=$GRADLE_HOME_FIXTURE/.gradle/caches/modules-2,dst=/home/dev/.gradle/caches/modules-2"
    assert_contains "$mounts" \
        "type=bind,src=$GRADLE_HOME_FIXTURE/.gradle/caches/jars-9,dst=/home/dev/.gradle/caches/jars-9"
    assert_contains "$mounts" \
        "type=bind,src=$GRADLE_HOME_FIXTURE/.gradle/caches/jars-8,dst=/home/dev/.gradle/caches/jars-8"
    assert_contains "$mounts" \
        "type=bind,src=$GRADLE_HOME_FIXTURE/.gradle/caches/build-cache-1,dst=/home/dev/.gradle/caches/build-cache-1"
    assert_contains "$mounts" \
        "type=bind,src=$GRADLE_HOME_FIXTURE/.gradle/caches/transforms-3,dst=/home/dev/.gradle/caches/transforms-3"
    assert_contains "$mounts" \
        "type=bind,src=$GRADLE_HOME_FIXTURE/.gradle/wrapper/dists,dst=/home/dev/.gradle/wrapper/dists"
    assert_contains "$mounts" \
        "type=bind,src=$GRADLE_HOME_FIXTURE/.gradle/jdks,dst=/home/dev/.gradle/jdks"
    # The private root stays a tmpfs; daemon state and per-version caches are
    # never shared. The caches/ and wrapper/ intermediate depths get their own
    # per-depth uid tmpfs (like the ~/.local depths) so the depths the binds
    # leave as root-owned tmpfs mountpoints stay writable for the versioned
    # caches/<ver>/ metadata Gradle creates.
    assert_contains "$line" \
        "--tmpfs /home/dev/.gradle:uid=$(id -u),gid=$(id -g),mode=700,size=1g,exec"
    assert_contains "$line" \
        "--tmpfs /home/dev/.gradle/caches:uid=$(id -u),gid=$(id -g),mode=700,size=1g,exec"
    assert_contains "$line" \
        "--tmpfs /home/dev/.gradle/wrapper:uid=$(id -u),gid=$(id -g),mode=700,size=1g,exec"
    # Version-bound content-addressed caches are shared too (issue #67):
    # transforms (AGP dex/resource transform output) and generated-gradle-jars
    # (deterministic per distribution). The version dir itself gets a uid
    # tmpfs so its private siblings (fileHashes, kotlin-dsl, cc-keystore)
    # stay writable and private; the two shared children land on top as binds.
    assert_contains "$line" \
        "--tmpfs /home/dev/.gradle/caches/9.6.0:uid=$(id -u),gid=$(id -g),mode=700,size=1g,exec"
    assert_contains "$mounts" \
        "type=bind,src=$GRADLE_HOME_FIXTURE/.gradle/caches/9.6.0/transforms,dst=/home/dev/.gradle/caches/9.6.0/transforms"
    assert_contains "$mounts" \
        "type=bind,src=$GRADLE_HOME_FIXTURE/.gradle/caches/9.6.0/generated-gradle-jars,dst=/home/dev/.gradle/caches/9.6.0/generated-gradle-jars"
    # Missing host-side leaf dirs are created like the shared leaves above.
    [ -d "$GRADLE_HOME_FIXTURE/.gradle/caches/9.6.0/transforms" ] ||
        _fail "expected the launcher to create the version-bound transforms leaf on the host"
    [ -d "$GRADLE_HOME_FIXTURE/.gradle/caches/9.6.0/generated-gradle-jars" ] ||
        _fail "expected the launcher to create the version-bound generated-gradle-jars leaf on the host"
    assert_not_contains "$mounts" "dst=/home/dev/.gradle/daemon"
    # The version dir itself is never shared wholesale - its private state
    # (absolute-path-keyed fileHashes, JVM-sensitive kotlin-dsl, the
    # configuration-cache keystore) must stay on the tmpfs. Exact-match: the
    # shared children carry the version dir as a path prefix.
    assert_eq "0" "$(printf '%s\n' "$mounts" | grep -c 'dst=/home/dev/.gradle/caches/9.6.0$' || true)"
    assert_not_contains "$mounts" "dst=/home/dev/.gradle,"
    # GRADLE_USER_HOME is pinned to the container-private root.
    assert_eq "/home/dev/.gradle" "$(env_value_for "$line" GRADLE_USER_HOME)"
}

test_gradle_leaves_are_not_shared_when_mount_caches_zero() {
    # DDR_ACP_AGENT_DOCKER_MOUNT_CACHES=0 skips even the content-addressed leaves, but
    # the private .gradle tmpfs (root + intermediate depths) and the
    # GRADLE_USER_HOME pin remain.
    run_docker_launcher "$PROJECT" \
        DDR_ACP_AGENT_DOCKER_MOUNT_CACHES=0 "HOME=$GRADLE_HOME_FIXTURE" --skip-pull
    assert_exit_status 0
    local line mounts
    line=$(last_run_line)
    mounts=$(mount_values "$line")
    # "/.gradle" shape: a bare "gradle" would also match the state bind under
    # the gradle-home fixture (test-design smell, broke host runs).
    assert_not_contains "$mounts" "/.gradle"
    assert_contains "$line" \
        "--tmpfs /home/dev/.gradle:uid=$(id -u),gid=$(id -g),mode=700,size=1g,exec"
    assert_contains "$line" \
        "--tmpfs /home/dev/.gradle/caches:uid=$(id -u),gid=$(id -g),mode=700,size=1g,exec"
    assert_contains "$line" \
        "--tmpfs /home/dev/.gradle/wrapper:uid=$(id -u),gid=$(id -g),mode=700,size=1g,exec"
    assert_eq "/home/dev/.gradle" "$(env_value_for "$line" GRADLE_USER_HOME)"
}

test_gradle_no_mount_without_a_host_gradle_home() {
    # No GRADLE_USER_HOME and no existing $HOME/.gradle: nothing is shared,
    # but the private root + intermediate depth tmpfs mounts and the pin stay
    # (the container Gradle uses its ephemeral private GUH).
    run_docker_launcher "$PROJECT" "HOME=$TEST_TMP/empty-gradle-home" --skip-pull
    assert_exit_status 0
    local line mounts
    line=$(last_run_line)
    mounts=$(mount_values "$line")
    # "/.gradle" shape: a bare "gradle" would also match the state bind under
    # the empty-gradle-home fixture (test-design smell, broke host runs).
    assert_not_contains "$mounts" "/.gradle"
    assert_contains "$line" \
        "--tmpfs /home/dev/.gradle:uid=$(id -u),gid=$(id -g),mode=700,size=1g,exec"
    assert_contains "$line" \
        "--tmpfs /home/dev/.gradle/caches:uid=$(id -u),gid=$(id -g),mode=700,size=1g,exec"
    assert_contains "$line" \
        "--tmpfs /home/dev/.gradle/wrapper:uid=$(id -u),gid=$(id -g),mode=700,size=1g,exec"
    assert_eq "/home/dev/.gradle" "$(env_value_for "$line" GRADLE_USER_HOME)"
}

test_gradle_version_dirs_derived_from_wrapper_dists_are_shared() {
    # A Gradle version whose distribution is shared (wrapper/dists) but whose
    # caches/<ver> dir the host has not created yet (e.g. right after a
    # wrapper upgrade) must still get its version-bound leaves, or every
    # session re-derives 100s of MB (generated-gradle-jars, transforms) into
    # the tmpfs. Versions are derived from the wrapper dist names.
    local guh=$TEST_TMP/dists-home/.gradle
    mkdir -p "$guh/caches/modules-2" "$guh/wrapper/dists/gradle-9.7.0-bin" \
        "$guh/wrapper/dists/gradle-8.14-all" ||
        _fail "could not create the dists-home fixture"
    run_docker_launcher "$PROJECT" "HOME=$TEST_TMP/dists-home" --skip-pull
    assert_exit_status 0
    local line mounts
    line=$(last_run_line)
    mounts=$(mount_values "$line")
    assert_contains "$line" \
        "--tmpfs /home/dev/.gradle/caches/9.7.0:uid=$(id -u),gid=$(id -g),mode=700,size=1g,exec"
    assert_contains "$line" \
        "--tmpfs /home/dev/.gradle/caches/8.14:uid=$(id -u),gid=$(id -g),mode=700,size=1g,exec"
    assert_contains "$mounts" \
        "type=bind,src=$guh/caches/9.7.0/transforms,dst=/home/dev/.gradle/caches/9.7.0/transforms"
    assert_contains "$mounts" \
        "type=bind,src=$guh/caches/9.7.0/generated-gradle-jars,dst=/home/dev/.gradle/caches/9.7.0/generated-gradle-jars"
    assert_contains "$mounts" \
        "type=bind,src=$guh/caches/8.14/generated-gradle-jars,dst=/home/dev/.gradle/caches/8.14/generated-gradle-jars"
    # The host-side leaf dirs are created so the binds always have a source.
    [ -d "$guh/caches/9.7.0/transforms" ] ||
        _fail "expected the launcher to create the version-bound transforms leaf for a dist-derived version"
    [ -d "$guh/caches/8.14/generated-gradle-jars" ] ||
        _fail "expected the launcher to create the version-bound generated-gradle-jars leaf for a dist-derived version"
}

test_gradle_user_home_env_overrides_home() {
    # An explicit absolute GRADLE_USER_HOME wins over $HOME/.gradle.
    run_docker_launcher "$PROJECT" \
        "GRADLE_USER_HOME=$GRADLE_HOME_FIXTURE/.gradle" "HOME=$TEST_TMP/other-home" --skip-pull
    assert_exit_status 0
    local line mounts
    line=$(last_run_line)
    mounts=$(mount_values "$line")
    assert_contains "$mounts" \
        "type=bind,src=$GRADLE_HOME_FIXTURE/.gradle/caches/modules-2,dst=/home/dev/.gradle/caches/modules-2"
    assert_eq "/home/dev/.gradle" "$(env_value_for "$line" GRADLE_USER_HOME)"
}

test_pull_failure_falls_back_to_a_local_image() {
    run_docker_launcher "$PROJECT" FAKE_DOCKER_PULL_EXIT=1 FAKE_DOCKER_INSPECT_EXIT=0
    assert_exit_status 0
    assert_contains "$OUT" "WARNING: docker pull"
    assert_contains "$OUT" "continuing with the locally available image"
}

test_pull_failure_without_a_local_image_fails_loudly() {
    run_docker_launcher "$PROJECT" FAKE_DOCKER_PULL_EXIT=1 FAKE_DOCKER_INSPECT_EXIT=1
    assert_exit_status 1
    assert_contains "$OUT" "WARNING: docker pull"
}

test_skip_pull_uses_the_local_image_without_pulling() {
    run_docker_launcher "$PROJECT" --skip-pull
    assert_exit_status 0
    assert_contains "$(cat "$LOG")" "image inspect"
    local pulls
    pulls=$(grep -c '^pull ' "$LOG" || true)
    assert_eq "0" "$pulls"
}

run_tests
