#!/bin/bash
# Pins the fail-loudly error paths of ddr-acp-agent-docker: missing API key,
# missing docker CLI and invalid ACP_DOCKER_EXTRA_MOUNTS entries. None of these
# may compose a `docker run`.
# shellcheck source=tests/bash/common.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"

new_sandbox error-paths >/dev/null
setup_git_identity

PROJECT=$TEST_TMP/project
EXTRA_DIR=$TEST_TMP/extra
mkdir -p "$PROJECT" "$EXTRA_DIR"

test_missing_api_key_fails_before_anything_else() {
    run_exit_status env -u OPENROUTER_API_KEY -u OPENROUTER_API_KEY_FILE \
        "FAKE_DOCKER_LOG=$TEST_TMP/never.log" \
        "DOCKER_BIN=$FAKE_DOCKER" "$DOCKER_LAUNCHER" --skip-pull
    assert_exit_status 1
    assert_empty "$(cat "$TEST_TMP/never.log" 2>/dev/null || true)"
}

test_unreadable_api_key_file_fails_before_composing_a_run() {
    run_docker_launcher "$PROJECT" "OPENROUTER_API_KEY_FILE=$TEST_TMP/does-not-exist" --skip-pull
    assert_exit_status 1
    assert_contains "$OUT" "is not a readable file"
    assert_empty "$(last_run_line || true)"
}

test_both_api_key_variants_fail_before_composing_a_run() {
    local key_file=$TEST_TMP/key.txt
    printf 'sk-x\n' >"$key_file"
    run_docker_launcher "$PROJECT" OPENROUTER_API_KEY=sk-env "OPENROUTER_API_KEY_FILE=$key_file" --skip-pull
    assert_exit_status 1
    assert_contains "$OUT" "not both"
    assert_empty "$(last_run_line || true)"
}

test_missing_docker_binary_fails_with_127() {
    # The launcher exits 127 when it cannot resolve a docker CLI at all: unset
    # DOCKER_BIN and strip PATH so command -v docker finds nothing (the
    # absolute /usr/bin|/usr/local/bin probes also miss on dockerless hosts).
    run_exit_status env -u DOCKER_BIN PATH=/usr/bin-empty "FAKE_DOCKER_LOG=$TEST_TMP/never.log" \
        OPENROUTER_API_KEY=sk-test "$DOCKER_LAUNCHER" --skip-pull
    assert_exit_status 127
}

test_invalid_extra_entry_fails_loudly_without_composing_a_run() {
    run_docker_launcher "$PROJECT" "ACP_DOCKER_EXTRA_MOUNTS=$EXTRA_DIR,broken-entry" --skip-pull
    assert_exit_status 1
    assert_contains "$OUT" "invalid entry broken-entry"
    assert_empty "$(last_run_line || true)"
}

test_relative_extra_path_fails_loudly_without_composing_a_run() {
    run_docker_launcher "$PROJECT" "ACP_DOCKER_EXTRA_MOUNTS=relative/path" --skip-pull
    assert_exit_status 1
    assert_contains "$OUT" "is not an absolute host path"
    assert_empty "$(last_run_line || true)"
}

test_root_extra_path_fails_loudly_without_composing_a_run() {
    run_docker_launcher "$PROJECT" "ACP_DOCKER_EXTRA_MOUNTS=/" --skip-pull
    assert_exit_status 1
    assert_contains "$OUT" "/ cannot be mounted"
    assert_empty "$(last_run_line || true)"
}

test_missing_extra_path_fails_loudly_without_composing_a_run() {
    run_docker_launcher "$PROJECT" "ACP_DOCKER_EXTRA_MOUNTS=$TEST_TMP/does-not-exist" --skip-pull
    assert_exit_status 1
    assert_contains "$OUT" "does not exist on the host"
    assert_empty "$(last_run_line || true)"
}

test_relative_android_home_fails_loudly_without_composing_a_run() {
    run_docker_launcher "$PROJECT" ANDROID_HOME=relative/sdk --skip-pull
    assert_exit_status 1
    assert_contains "$OUT" "ANDROID_HOME: relative/sdk is not an absolute host path"
    assert_empty "$(last_run_line || true)"
}

test_missing_android_sdk_dir_fails_loudly_without_composing_a_run() {
    run_docker_launcher "$PROJECT" "ANDROID_HOME=$TEST_TMP/does-not-exist" --skip-pull
    assert_exit_status 1
    assert_contains "$OUT" "ANDROID_HOME: $TEST_TMP/does-not-exist is not a directory on the host"
    assert_empty "$(last_run_line || true)"
}

test_root_android_home_fails_loudly_without_composing_a_run() {
    run_docker_launcher "$PROJECT" ANDROID_HOME=/ --skip-pull
    assert_exit_status 1
    assert_contains "$OUT" "ANDROID_HOME: / cannot be the Android SDK directory"
    assert_empty "$(last_run_line || true)"
}

test_android_sdk_root_errors_name_the_set_variable() {
    run_docker_launcher "$PROJECT" "ANDROID_SDK_ROOT=$TEST_TMP/does-not-exist" --skip-pull
    assert_exit_status 1
    assert_contains "$OUT" "ANDROID_SDK_ROOT: $TEST_TMP/does-not-exist is not a directory on the host"
    assert_empty "$(last_run_line || true)"
}

run_tests
