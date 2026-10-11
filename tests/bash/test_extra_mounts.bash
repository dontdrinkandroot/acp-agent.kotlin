#!/bin/bash
# Pins the ACP_EXTRA_MOUNTS derivation and the extra-mount composition in
# ddr-acp-agent-docker: the agent must trust exactly the paths that were
# effectively mounted (mode suffixes stripped, skipped/duplicate entries
# excluded), and nothing when no extras are configured.
# shellcheck source=tests/bash/common.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"

new_sandbox extra-mounts >/dev/null
setup_git_identity

EXTRA_PROJECT=$TEST_TMP/extra-project
EXTRA_TRUSTED=$TEST_TMP/extra-trusted
EXTRA_RO=$TEST_TMP/extra-read-only
mkdir -p "$EXTRA_PROJECT" "$EXTRA_TRUSTED" "$EXTRA_RO" ||
    _fail "could not create the extra-mount fixtures"

test_extra_mounts_are_mounted_src_dst_with_mode_suffix() {
    run_docker_launcher "$EXTRA_PROJECT" OPENROUTER_API_KEY=sk-test \
        "ACP_DOCKER_EXTRA_MOUNTS=$EXTRA_TRUSTED:rw,$EXTRA_RO" --skip-pull
    assert_exit_status 0
    local mounts
    mounts=$(mount_values "$(last_run_line)")
    assert_contains "$mounts" "type=bind,src=$EXTRA_TRUSTED,dst=$EXTRA_TRUSTED"
    assert_contains "$mounts" "type=bind,src=$EXTRA_RO,dst=$EXTRA_RO,ro"
}

test_acp_extra_mounts_is_derived_from_effective_extras() {
    run_docker_launcher "$EXTRA_PROJECT" OPENROUTER_API_KEY=sk-test \
        "ACP_DOCKER_EXTRA_MOUNTS=$EXTRA_TRUSTED:rw,$EXTRA_RO" --skip-pull
    assert_exit_status 0
    assert_eq "$EXTRA_TRUSTED,$EXTRA_RO" "$(env_value_for "$(last_run_line)" ACP_EXTRA_MOUNTS)"
}

test_acp_extra_mounts_strips_mode_suffixes_and_dedupes() {
    run_docker_launcher "$EXTRA_PROJECT" OPENROUTER_API_KEY=sk-test \
        "ACP_DOCKER_EXTRA_MOUNTS=$EXTRA_TRUSTED:rw,$EXTRA_TRUSTED,$EXTRA_RO:ro" --skip-pull
    assert_exit_status 0
    assert_eq "$EXTRA_TRUSTED,$EXTRA_RO" "$(env_value_for "$(last_run_line)" ACP_EXTRA_MOUNTS)"
}

test_acp_extra_mounts_excludes_skipped_entries() {
    # Entries inside the project dir / container home / tmpfs are skipped by
    # the launcher and must never reach the agent's trust domain. Covered
    # entries are shadowed by the covering mount at the identical path, so    # they are skipped regardless of host existence - the /home/dev fixture is
    # therefore deliberately nonexistent, which is also what makes the test
    # portable: /home/dev only exists inside the agent container, never on a
    # host.
    local project_inner=$EXTRA_PROJECT/inner
    local tmp_entry
    tmp_entry=$(mktemp -d /tmp/acp-shelltest-skip-XXXXXX) || _fail "could not create the /tmp fixture"
    local home_entry=/home/dev/acp-shelltest-skip-home
    mkdir -p "$project_inner" || _fail "could not create $project_inner"
    rm -rf "$home_entry"
    run_docker_launcher "$EXTRA_PROJECT" OPENROUTER_API_KEY=sk-test \
        "ACP_DOCKER_EXTRA_MOUNTS=$EXTRA_PROJECT,$project_inner,$tmp_entry,$home_entry,$EXTRA_TRUSTED" --skip-pull
    assert_exit_status 0
    assert_eq "$EXTRA_TRUSTED" "$(env_value_for "$(last_run_line)" ACP_EXTRA_MOUNTS)"
    rm -rf "$tmp_entry"
}

test_acp_extra_mounts_is_absent_without_extras() {
    run_docker_launcher "$EXTRA_PROJECT" OPENROUTER_API_KEY=sk-test --skip-pull
    assert_exit_status 0
    assert_empty "$(env_value_for "$(last_run_line)" ACP_EXTRA_MOUNTS)"
}

run_tests
