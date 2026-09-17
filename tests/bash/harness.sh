#!/bin/bash
# Minimal assert library for the shell test suite (TAP-ish output).
# A test file sources this (directly or via common.sh), defines test_<name>
# functions and calls run_tests at the end. run_tests executes each test in a
# set -e subshell, so the FIRST failing assertion aborts the test; its output
# (including the FAIL line with file:line) is printed below the not-ok line.
# run-all and the Gradle wiring rely on the nonzero exit when any test fails.

TESTS_PASSED=0
TESTS_FAILED=0

# Prints the failure with the caller's location and returns 1 (aborting the
# set -e test subshell).
_fail() {
    printf 'FAIL: %s at %s\n' "$1" "$(caller 0)" >&2
    return 1
}

assert_eq() {
    [ "$1" = "$2" ] || _fail "expected [$1] but was [$2]"
}

assert_contains() {
    case "$1" in
        *"$2"*) return 0 ;;
        *) _fail "expected to contain [$2] but was [$1]" ;;
    esac
}

assert_not_contains() {
    case "$1" in
        *"$2"*) _fail "expected NOT to contain [$2] but was [$1]" ;;
        *) return 0 ;;
    esac
}

assert_empty() {
    [ -z "$1" ] || _fail "expected empty but was [$1]"
}

assert_nonempty() {
    [ -n "$1" ] || _fail "expected non-empty"
}

# Asserts the exit status captured by run_exit_status / run_docker_launcher.
assert_exit_status() {
    [ "$1" = "${LAST_EXIT_STATUS-}" ] ||
        _fail "expected exit status [$1] but was [${LAST_EXIT_STATUS-}]"
}

# Runs a command, captures its exit status without set -e killing the caller.
run_exit_status() {
    "$@" >/dev/null 2>&1
    LAST_EXIT_STATUS=$?
}

# Discovers test_<name> functions in the sourcing file and runs them. Returns
# nonzero when any test failed.
run_tests() {
    local name out status
    for name in $(declare -F | awk '{print $3}' | grep '^test_' || true); do
        status=0
        out=$(set -e; "$name" 2>&1) || status=$?
        if [ "$status" -eq 0 ]; then
            TESTS_PASSED=$((TESTS_PASSED + 1))
            printf 'ok - %s\n' "$name"
        else
            TESTS_FAILED=$((TESTS_FAILED + 1))
            printf 'not ok - %s\n' "$name" >&2
            [ -n "$out" ] && printf '%s\n' "$out" >&2
        fi
    done
    if [ "$TESTS_FAILED" -gt 0 ]; then
        printf 'not ok - %s failed, %s passed\n' "$TESTS_FAILED" "$TESTS_PASSED" >&2
        return 1
    fi
    printf 'ok - %s passed\n' "$TESTS_PASSED"
}
