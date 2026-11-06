#!/bin/bash
# Pins the permission-directory mount composition of ddr-acp-agent-docker
# (issue #50): the permissions lists ARE the mount list. Readable directories
# mount read-only, writable ones read-write, skipped/duplicate entries are
# dropped, and the variables are forwarded unchanged so the agent trusts
# exactly what is mounted.
# shellcheck source=tests/bash/common.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"

new_sandbox permission-mounts >/dev/null
setup_git_identity

EXTRA_PROJECT=$TEST_TMP/extra-project
READABLE=$TEST_TMP/readable
WRITABLE=$TEST_TMP/writable
mkdir -p "$EXTRA_PROJECT" "$READABLE" "$WRITABLE" ||
    _fail "could not create the permission fixture dirs"

test_tiers_mount_with_the_matching_mode() {
    run_docker_launcher "$EXTRA_PROJECT" \
        "DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES=$READABLE" \
        "DDR_ACP_AGENT_PERMISSIONS_WRITABLE_DIRECTORIES=$WRITABLE" --skip-pull
    assert_exit_status 0
    local mounts
    mounts=$(mount_values "$(last_run_line)")
    assert_contains "$mounts" "type=bind,src=$READABLE,dst=$READABLE,ro"
    # writable = read-write: no `,ro` suffix.
    assert_contains "$mounts" "type=bind,src=$WRITABLE,dst=$WRITABLE"
    assert_not_contains "$mounts" "src=$WRITABLE,dst=$WRITABLE,ro"
}

test_the_lists_are_forwarded_unchanged_and_once() {
    # The agent must trust exactly what is mounted, so both lists travel
    # verbatim - and exactly once (a duplicate `--env` would be a launcher bug
    # the last-one-wins semantics hides).
    run_docker_launcher "$EXTRA_PROJECT" \
        "DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES=$READABLE" \
        "DDR_ACP_AGENT_PERMISSIONS_WRITABLE_DIRECTORIES=$WRITABLE" --skip-pull
    assert_exit_status 0
    local line
    line=$(last_run_line)
    assert_eq "$READABLE" "$(env_value_for "$line" DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES)"
    assert_eq "$WRITABLE" "$(env_value_for "$line" DDR_ACP_AGENT_PERMISSIONS_WRITABLE_DIRECTORIES)"
    assert_eq "1" "$(env_count_for "$line" DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES)"
    assert_eq "1" "$(env_count_for "$line" DDR_ACP_AGENT_PERMISSIONS_WRITABLE_DIRECTORIES)"
}

test_a_writable_path_also_listed_readable_mounts_rw_once() {
    run_docker_launcher "$EXTRA_PROJECT" \
        "DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES=$WRITABLE" \
        "DDR_ACP_AGENT_PERMISSIONS_WRITABLE_DIRECTORIES=$WRITABLE" --skip-pull
    assert_exit_status 0
    local mounts
    mounts=$(mount_values "$(last_run_line)")
    # Exactly one mount for the path, and it is the read-write form.
    assert_eq "1" "$(printf '%s\n' "$mounts" | grep -c "dst=$WRITABLE\(,\|$\)" || true)"
    assert_contains "$mounts" "type=bind,src=$WRITABLE,dst=$WRITABLE"
    assert_not_contains "$mounts" "src=$WRITABLE,dst=$WRITABLE,ro"
}

test_covered_paths_are_skipped() {
    # Paths inside the project dir / container home / tmpfs are shadowed by the
    # covering mount at the identical path, so they are skipped with a warning -
    # and are never mounted. The /home/dev fixture is deliberately nonexistent,
    # which is what makes the test portable: it only exists inside the agent
    # container, never on a host.
    local project_inner=$EXTRA_PROJECT/inner
    local tmp_entry
    tmp_entry=$(mktemp -d /tmp/acp-shelltest-skip-XXXXXX) || _fail "could not create the /tmp fixture"
    local home_entry=/home/dev/acp-shelltest-skip-home
    mkdir -p "$project_inner" || _fail "could not create $project_inner"
    rm -rf "$home_entry"
    run_docker_launcher "$EXTRA_PROJECT" \
        "DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES=$EXTRA_PROJECT,$project_inner,$tmp_entry,$home_entry,$READABLE" \
        --skip-pull
    assert_exit_status 0
    local mounts
    mounts=$(mount_values "$(last_run_line)")
    assert_contains "$mounts" "type=bind,src=$READABLE,dst=$READABLE,ro"
    assert_not_contains "$mounts" "src=$project_inner"
    assert_not_contains "$mounts" "src=$tmp_entry"
    assert_not_contains "$mounts" "acp-shelltest-skip-home"
    rm -rf "$tmp_entry"
}

test_no_permission_lists_mount_nothing() {
    run_docker_launcher "$EXTRA_PROJECT" --skip-pull
    assert_exit_status 0
    assert_empty "$(env_value_for "$(last_run_line)" DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES)"
    assert_empty "$(env_value_for "$(last_run_line)" DDR_ACP_AGENT_PERMISSIONS_WRITABLE_DIRECTORIES)"
}

test_duplicate_entries_are_mounted_once() {
    run_docker_launcher "$EXTRA_PROJECT" \
        "DDR_ACP_AGENT_PERMISSIONS_READABLE_DIRECTORIES=$READABLE,$READABLE" --skip-pull
    assert_exit_status 0
    assert_eq "1" "$(mount_values "$(last_run_line)" | grep -c "src=$READABLE,dst=$READABLE,ro" || true)"
}

run_tests
