#!/bin/bash
# Shared setup for the shell test suite: sandbox fixture dirs and launcher
# invocation with the fake docker stub. Sources harness.sh itself.

SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/../.." && pwd)
DOCKER_LAUNCHER=$REPO_ROOT/ddr-acp-agent-docker
FAKE_DOCKER=$SCRIPT_DIR/stubs/fake-docker

# shellcheck source=tests/bash/harness.sh
source "$SCRIPT_DIR/harness.sh"

# Deterministic git identity for the launcher's git-config forwarding probe:
# tests call `GIT_IDENTITY_SET=0/1 setup_git_identity` (prefix assignment, so
# the function itself is exported-visible to git via the env vars it sets).
setup_git_identity() {
    export GIT_CONFIG_SYSTEM=/dev/null
    if [ "${GIT_IDENTITY_SET:-1}" = 1 ]; then
        export GIT_CONFIG_GLOBAL=$TEST_TMP/git-identity.config
        printf '[user]\n\tname = Test User\n\temail = test@example.com\n' >"$GIT_CONFIG_GLOBAL"
    else
        export GIT_CONFIG_GLOBAL=/dev/null
    fi
}

# The launcher invocation strips both API key variants (plus
# ACP_DOCKER_EXTRA_MOUNTS) so ambient values can never leak into a test; tests
# opt into a fake key via leading KEY=VALUE arguments.

# Creates a fresh sandbox dir for one test file: $TEST_TMP/<name>/...
# The sandbox lives under build/ (never /tmp or $HOME): the launcher skips
# extra mounts inside /tmp (tmpfs) and the container home, which would defeat
# every fixture placed there.
new_sandbox() {
    local name=$1
    mkdir -p "$REPO_ROOT/build"
    TEST_TMP=$(mktemp -d "$REPO_ROOT/build/acp-shelltest-$name-XXXXXX")
    trap 'rm -rf "$TEST_TMP"' EXIT
    printf '%s\n' "$TEST_TMP"
}

# Runs the docker launcher with the stub docker, capturing stdout+stderr into
# $OUT and the stub invocations into $LOG. Extra args are forwarded to the
# launcher (e.g. --skip-pull). Env overrides come via the caller's environment.
# Runs the docker launcher with the stub docker, capturing stdout+stderr into
# $OUT and the stub invocations into $LOG. Leading KEY=VALUE args are applied
# to the launcher's environment (unexported VAR=x func does not propagate, so
# this is the supported way to set ACP_DOCKER_EXTRA_MOUNTS & friends; they
# must precede any launcher flags); remaining args are forwarded to the
# launcher (e.g. --skip-pull). ACP_DOCKER_EXTRA_MOUNTS is always cleared first
# so a stale value from a previous test cannot leak.
run_docker_launcher() {
    local project_dir=$1
    shift
    local env_args=()
    while [ $# -gt 0 ]; do
        case $1 in
            [A-Za-z_]*=*) env_args+=("$1"); shift ;;
            *) break ;;
        esac
    done
    LOG=$TEST_TMP/docker.log
    : >"$LOG"
    # The launcher execs docker with stdin kept open (-i); feed /dev/null so
    # the stub's stdin loop sees EOF instead of blocking forever. Both API key
    # variants are stripped so a real key from the ambient environment can
    # never leak into test output or the stub log; tests opt into a fake one.
    # shellcheck disable=SC2034  # OUT is asserted on by the tests
    OUT=$(cd "$project_dir" && env -u OPENROUTER_API_KEY -u OPENROUTER_API_KEY_FILE \
        -u ACP_DOCKER_EXTRA_MOUNTS "${env_args[@]}" \
        "FAKE_DOCKER_LOG=$LOG" \
        "DOCKER_BIN=$FAKE_DOCKER" "$DOCKER_LAUNCHER" "$@" </dev/null 2>&1)
    # The launcher execs docker, so the exit status is the stub's.
    LAST_EXIT_STATUS=$?
}

# Last `docker run` argv line from the stub log.
last_run_line() {
    grep '^run ' "$LOG" | tail -1
}

# Extracts the value of `--env K=V` for a given key from a `docker run` line.
env_value_for() {
    local line=$1 key=$2
    printf '%s\n' "$line" | tr ' ' '\n' | grep -A1 '^--env$' |
        grep "^${key}=" | head -1 | cut -d= -f2-
}

# All values of `--mount` from a `docker run` line (the argument follows the
# flag on the next space-separated token).
mount_values() {
    printf '%s\n' "$1" | tr ' ' '\n' | grep -A1 '^--mount$' | grep -v '^--mount$' | grep -v '^--$' || true
}
