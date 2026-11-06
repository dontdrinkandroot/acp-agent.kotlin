#!/bin/bash
# Pins the fail-loudly error paths of ddr-acp-agent-docker: missing docker CLI,
# invalid permission-directory entries and unusable tool-cache env config.
# None of these may compose a `docker run`.
# shellcheck source=tests/bash/common.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"

new_sandbox error-paths >/dev/null
setup_git_identity

PROJECT=$TEST_TMP/project
EXTRA_DIR=$TEST_TMP/extra
mkdir -p "$PROJECT" "$EXTRA_DIR"

test_missing_docker_binary_fails_with_127() {
    # The launcher exits 127 when it cannot resolve a docker CLI at all: unset
    # DOCKER_BIN and strip PATH so command -v docker finds nothing (the
    # absolute /usr/bin|/usr/local/bin probes also miss on dockerless hosts).
    run_exit_status env -u DOCKER_BIN PATH=/usr/bin-empty "FAKE_DOCKER_LOG=$TEST_TMP/never.log" \
        "$DOCKER_LAUNCHER" --skip-pull
    assert_exit_status 127
}

test_relative_permission_path_fails_loudly_without_composing_a_run() {
    run_docker_launcher "$PROJECT" \
        "DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES=relative/path" --skip-pull
    assert_exit_status 1
    assert_contains "$OUT" "READABLE_DIRECTORIES: relative/path is not an absolute host path"
    assert_empty "$(last_run_line || true)"
}

test_root_permission_path_fails_loudly_without_composing_a_run() {
    run_docker_launcher "$PROJECT" \
        "DDR_ACP_AGENT_PERMISSIONS_WRITABLE_DIRECTORIES=/" --skip-pull
    assert_exit_status 1
    assert_contains "$OUT" "WRITABLE_DIRECTORIES: / cannot be mounted"
    assert_empty "$(last_run_line || true)"
}

test_missing_permission_path_fails_loudly_without_composing_a_run() {
    run_docker_launcher "$PROJECT" \
        "DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES=$TEST_TMP/does-not-exist" --skip-pull
    assert_exit_status 1
    assert_contains "$OUT" "does not exist on the host"
    assert_empty "$(last_run_line || true)"
}

test_relative_cache_dir_fails_loudly_without_composing_a_run() {
    # Explicit cache config that cannot be honored (relative path) is user
    # intent - it must fail loudly, never silently fall back to the default
    # candidate (issue #51).
    run_docker_launcher "$PROJECT" UV_CACHE_DIR=relative/cache --skip-pull
    assert_exit_status 1
    assert_contains "$OUT" "UV_CACHE_DIR: relative/cache is not an absolute host path"
    assert_empty "$(last_run_line || true)"
}

test_root_cache_dir_fails_loudly_without_composing_a_run() {
    run_docker_launcher "$PROJECT" UV_CACHE_DIR=/ --skip-pull
    assert_exit_status 1
    assert_contains "$OUT" "UV_CACHE_DIR: / cannot be used as a cache directory"
    assert_empty "$(last_run_line || true)"
}

test_uncreatable_cache_dir_fails_loudly_without_composing_a_run() {
    # A regular file where the cache dir should go makes mkdir -p fail - the
    # config cannot be honored and must not silently fall back.
    printf 'x\n' >"$TEST_TMP/not-a-dir"
    run_docker_launcher "$PROJECT" "UV_CACHE_DIR=$TEST_TMP/not-a-dir/cache" --skip-pull
    assert_exit_status 1
    assert_contains "$OUT" "UV_CACHE_DIR: $TEST_TMP/not-a-dir/cache could not be created on the host"
    assert_empty "$(last_run_line || true)"
}

test_relative_pnpm_cache_dir_fails_loudly_without_composing_a_run() {
    # The pnpm config vars go through the same explicit-config validation as
    # every other tool cache - never a silent fallback.
    run_docker_launcher "$PROJECT" PNPM_CONFIG_STORE_DIR=relative/store --skip-pull
    assert_exit_status 1
    assert_contains "$OUT" "PNPM_CONFIG_STORE_DIR: relative/store is not an absolute host path"
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
