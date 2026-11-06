#!/bin/bash
# Pins the agent config-directory mount composition of ddr-acp-agent-docker
# (issue #50): the host config directory ($XDG_CONFIG_HOME/ddr-acp-agent,
# holding config.json and the default openrouter.key) is mounted read-only at
# the container's own config directory, on top of a uid-mapped ~/.config tmpfs
# (the same runc-parent treatment as ~/.local). A missing host directory mounts
# nothing (the agent then runs on built-in defaults), and a config directory
# inside the project directory is skipped (the project bind already covers it
# rw at the identical path).
# shellcheck source=tests/bash/common.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"

new_sandbox config-mount >/dev/null
setup_git_identity

PROJECT=$TEST_TMP/project
CONFIG_HOME=$TEST_TMP/config-home
CONFIG_HOST_DIR=$CONFIG_HOME/ddr-acp-agent
mkdir -p "$PROJECT" "$CONFIG_HOST_DIR" ||
    _fail "could not create the config fixture dirs"
printf '%s\n' '{}' >"$CONFIG_HOST_DIR/config.json"

test_host_config_dir_is_mounted_read_only_on_a_config_tmpfs() {
    run_docker_launcher "$PROJECT" "XDG_CONFIG_HOME=$CONFIG_HOME" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_contains "$(mount_values "$line")" \
        "type=bind,src=$CONFIG_HOST_DIR,dst=/home/dev/.config/ddr-acp-agent,ro"
    assert_contains "$line" \
        "--tmpfs /home/dev/.config:uid=$(id -u),gid=$(id -g),mode=700,size=64m"
    assert_contains "$OUT" "Mounting agent config: $CONFIG_HOST_DIR"
}

test_the_config_bind_lands_on_top_of_the_config_tmpfs() {
    # Same argv-order pin as the session-state bind: runc mounts in spec order
    # (moby sorts shallowest-first), so a shallower tmpfs mounted later would
    # shadow the config bind and the agent would see an empty config directory.
    run_docker_launcher "$PROJECT" "XDG_CONFIG_HOME=$CONFIG_HOME" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    local config_tmpfs_bind
    config_tmpfs_bind=$(
        printf '%s\n' "$line" |
            sed -n 's/.*--tmpfs \/home\/dev\/\.config:uid=.*size=64m.*\(type=bind,src=[^ ]*dst=\/home\/dev\/\.config\/ddr-acp-agent\).*/\1/p'
    )
    assert_nonempty "$config_tmpfs_bind"
}

test_a_missing_host_config_dir_mounts_nothing() {
    # A missing host directory is fine: the agent then runs on built-in
    # defaults - and neither the bind nor the ~/.config tmpfs is composed.
    local empty_config_home=$TEST_TMP/empty-config-home
    mkdir -p "$empty_config_home" || _fail "could not create $empty_config_home"
    run_docker_launcher "$PROJECT" "XDG_CONFIG_HOME=$empty_config_home" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_not_contains "$(mount_values "$line")" "dst=/home/dev/.config"
    assert_not_contains "$line" "--tmpfs /home/dev/.config"
    assert_not_contains "$OUT" "Mounting agent config"
}

test_a_config_dir_inside_the_project_directory_is_skipped() {
    # A config dir under the project dir is covered by the project bind
    # (mounted rw at the identical path), so neither the config bind nor the
    # ~/.config tmpfs is composed. The fixture directory exists, so the skip
    # is really caused by the project-dir condition.
    local project_config_home=$PROJECT/.config
    mkdir -p "$project_config_home/ddr-acp-agent" ||
        _fail "could not create $project_config_home/ddr-acp-agent"
    run_docker_launcher "$PROJECT" "XDG_CONFIG_HOME=$project_config_home" --skip-pull
    assert_exit_status 0
    assert_not_contains "$(mount_values "$(last_run_line)")" "dst=/home/dev/.config"
    assert_not_contains "$OUT" "Mounting agent config"
}

run_tests
