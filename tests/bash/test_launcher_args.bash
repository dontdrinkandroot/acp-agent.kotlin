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
    run_docker_launcher "$PROJECT" OPENROUTER_API_KEY=sk-test --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_contains "$line" "--workdir $PROJECT"
    assert_contains "$line" "type=bind,src=$PROJECT,dst=$PROJECT"
}

test_sandbox_hardening_flags_are_present() {
    run_docker_launcher "$PROJECT" OPENROUTER_API_KEY=sk-test --skip-pull
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
    run_docker_launcher "$PROJECT" ACP_DOCKER_RW_ROOTFS=1 --skip-pull
    assert_exit_status 0
    assert_not_contains "$(last_run_line)" "--read-only"
}

test_api_key_never_travels_through_the_env() {
    run_docker_launcher "$PROJECT" OPENROUTER_API_KEY=sk-test --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    # The container env carries only the file path, never the key itself.
    assert_empty "$(env_value_for "$line" OPENROUTER_API_KEY)"
    assert_eq "/tmp/openrouter-api-key" "$(env_value_for "$line" OPENROUTER_API_KEY_FILE)"
    # The key is staged as a 0600 file (tmpfs; /tmp fallback where /dev/shm is
    # unavailable) and mounted ro at the in-container path.
    local mount
    mount=$(mount_values "$line" | grep 'dst=/tmp/openrouter-api-key')
    assert_nonempty "$mount"
    assert_contains "$mount" "type=bind,src="
    assert_contains "$mount" "dst=/tmp/openrouter-api-key,ro"
    local staged
    staged=$(printf '%s\n' "$mount" | sed -n 's/.*src=\([^,]*\),.*/\1/p')
    assert_nonempty "$staged"
    assert_eq "600" "$(stat -c '%a' "$staged")"
    assert_eq "sk-test" "$(cat "$staged")"
    # The EXIT trap removed the staged file after docker returned.
    [ ! -e "$staged" ]
}

test_relative_api_key_file_path_is_absolutized_against_the_launcher_cwd() {
    # Relative OPENROUTER_API_KEY_FILE (a plain filename, cwd == the project
    # dir): the launcher must absolutize it against its own cwd, since docker
    # would otherwise resolve it inside the container. The launcher cds into
    # the project dir first, so the file must exist there.
    local key_file=$PROJECT/keys/host-key
    mkdir -p "$PROJECT/keys"
    printf 'sk-hostfile\n' >"$key_file"
    chmod 600 "$key_file"
    run_docker_launcher "$PROJECT" "OPENROUTER_API_KEY_FILE=keys/host-key" --skip-pull
    assert_exit_status 0
    local mount
    mount=$(mount_values "$(last_run_line)" | grep 'dst=/tmp/openrouter-api-key')
    assert_contains "$mount" "type=bind,src=$key_file,dst=/tmp/openrouter-api-key,ro"
    rm -rf "$PROJECT/keys"
}

test_api_key_file_is_mounted_directly_without_a_staged_copy() {
    local key_file=$TEST_TMP/host-key
    printf 'sk-hostfile\n' >"$key_file"
    chmod 600 "$key_file"
    run_docker_launcher "$PROJECT" "OPENROUTER_API_KEY_FILE=$key_file" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_eq "/tmp/openrouter-api-key" "$(env_value_for "$line" OPENROUTER_API_KEY_FILE)"
    assert_empty "$(env_value_for "$line" OPENROUTER_API_KEY)"
    local mount
    mount=$(mount_values "$line" | grep 'dst=/tmp/openrouter-api-key')
    assert_contains "$mount" "type=bind,src=$key_file,dst=/tmp/openrouter-api-key,ro"
    # The host file is mounted, not staged: it still exists afterwards, and
    # exactly one key mount exists with the host file as its source.
    [ -f "$key_file" ]
    local key_mounts
    key_mounts=$(mount_values "$line" | grep -c 'dst=/tmp/openrouter-api-key' || true)
    assert_eq "1" "$key_mounts"
}

test_optional_env_vars_are_forwarded_only_when_set() {
    run_docker_launcher "$PROJECT" --skip-pull \
        OPENROUTER_MODEL=test/model FS_PROXY_ENABLED=0 ACP_WEB_FETCH_ALLOW_PRIVATE=1
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_eq "test/model" "$(env_value_for "$line" OPENROUTER_MODEL)"
    assert_eq "0" "$(env_value_for "$line" FS_PROXY_ENABLED)"
    assert_eq "1" "$(env_value_for "$line" ACP_WEB_FETCH_ALLOW_PRIVATE)"
    assert_empty "$(env_value_for "$line" ACP_MAX_TURN_REQUESTS)"
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
    run_docker_launcher "$PROJECT" OPENROUTER_API_KEY=sk-test --skip-pull
    assert_exit_status 0
    assert_not_contains "$(last_run_line)" ".env.local"
}

test_git_identity_is_forwarded_when_configured() {
    GIT_IDENTITY_SET=1 setup_git_identity
    run_docker_launcher "$PROJECT" OPENROUTER_API_KEY=sk-test --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_eq "Test User" "$(env_value_for "$line" GIT_AUTHOR_NAME)"
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

test_android_home_is_mounted_and_pinned_to_the_container_default() {
    run_docker_launcher "$PROJECT" OPENROUTER_API_KEY=sk-test \
        "ANDROID_HOME=$ANDROID_SDK" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_contains "$(mount_values "$line")" \
        "type=bind,src=$ANDROID_SDK,dst=/home/dev/Android/Sdk"
    assert_eq "/home/dev/Android/Sdk" "$(env_value_for "$line" ANDROID_HOME)"
    assert_eq "/home/dev/Android/Sdk" "$(env_value_for "$line" ANDROID_SDK_ROOT)"
}

test_android_sdk_root_falls_back_to_the_same_mount() {
    run_docker_launcher "$PROJECT" OPENROUTER_API_KEY=sk-test \
        "ANDROID_SDK_ROOT=$ANDROID_SDK" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_contains "$(mount_values "$line")" \
        "type=bind,src=$ANDROID_SDK,dst=/home/dev/Android/Sdk"
    assert_eq "/home/dev/Android/Sdk" "$(env_value_for "$line" ANDROID_HOME)"
}

test_android_home_wins_over_android_sdk_root() {
    run_docker_launcher "$PROJECT" OPENROUTER_API_KEY=sk-test \
        "ANDROID_HOME=$ANDROID_SDK" "ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT_DIR" --skip-pull
    assert_exit_status 0
    assert_contains "$(mount_values "$(last_run_line)")" \
        "type=bind,src=$ANDROID_SDK,dst=/home/dev/Android/Sdk"
}

test_default_android_sdk_dir_is_picked_up_without_env() {
    # No ANDROID_HOME/ANDROID_SDK_ROOT (common.sh strips them): an existing
    # $HOME/Android/Sdk is shared, never created. HOME is pinned to a fixture
    # so the test does not depend on the runner's real home.
    run_docker_launcher "$PROJECT" OPENROUTER_API_KEY=sk-test \
        "HOME=$FAKE_HOME" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_contains "$(mount_values "$line")" \
        "type=bind,src=$FAKE_HOME/Android/Sdk,dst=/home/dev/Android/Sdk"
    assert_eq "/home/dev/Android/Sdk" "$(env_value_for "$line" ANDROID_HOME)"
}

test_no_android_mount_without_an_sdk() {
    # HOME without an Android/Sdk dir: nothing is mounted and no ANDROID_*
    # env is injected.
    run_docker_launcher "$PROJECT" OPENROUTER_API_KEY=sk-test \
        "HOME=$TEST_TMP/empty-home" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_not_contains "$(mount_values "$line")" "Android/Sdk"
    assert_empty "$(env_value_for "$line" ANDROID_HOME)"
    assert_empty "$(env_value_for "$line" ANDROID_SDK_ROOT)"
}

test_android_mount_honors_acp_docker_mount_caches_zero() {
    run_docker_launcher "$PROJECT" OPENROUTER_API_KEY=sk-test \
        ACP_DOCKER_MOUNT_CACHES=0 "ANDROID_HOME=$ANDROID_SDK" --skip-pull
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
    run_docker_launcher "$PROJECT" OPENROUTER_API_KEY=sk-test \
        "ANDROID_HOME=$sdk_in_project" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_not_contains "$(mount_values "$line")" "Android/Sdk"
    assert_eq "$sdk_in_project" "$(env_value_for "$line" ANDROID_HOME)"
    assert_eq "$sdk_in_project" "$(env_value_for "$line" ANDROID_SDK_ROOT)"
}

test_pull_failure_falls_back_to_a_local_image() {
    run_docker_launcher "$PROJECT" FAKE_DOCKER_PULL_EXIT=1 FAKE_DOCKER_INSPECT_EXIT=0
    run_docker_launcher "$PROJECT" OPENROUTER_API_KEY=sk-test \
        FAKE_DOCKER_PULL_EXIT=1 FAKE_DOCKER_INSPECT_EXIT=0
    assert_exit_status 0
    assert_contains "$OUT" "WARNING: docker pull"
    assert_contains "$OUT" "continuing with the locally available image"
}

test_pull_failure_without_a_local_image_fails_loudly() {
    run_docker_launcher "$PROJECT" FAKE_DOCKER_PULL_EXIT=1 FAKE_DOCKER_INSPECT_EXIT=1
    run_docker_launcher "$PROJECT" OPENROUTER_API_KEY=sk-test \
        FAKE_DOCKER_PULL_EXIT=1 FAKE_DOCKER_INSPECT_EXIT=1
    assert_exit_status 1
    assert_contains "$OUT" "WARNING: docker pull"
}

test_skip_pull_uses_the_local_image_without_pulling() {
    run_docker_launcher "$PROJECT" OPENROUTER_API_KEY=sk-test --skip-pull
    assert_exit_status 0
    assert_contains "$(cat "$LOG")" "image inspect"
    local pulls
    pulls=$(grep -c '^pull ' "$LOG" || true)
    assert_eq "0" "$pulls"
}

run_tests
