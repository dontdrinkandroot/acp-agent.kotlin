#!/bin/bash
# Regression pins for the harness itself (issue #52): _fail must abort the
# test on the FIRST failing assertion, even inside run_tests' capture context
# (bash ignores errexit in the `$( ... ) || status=$?` context), so a later
# passing command can never false-green a test.
#
# Each test therefore ends in ONE decisive assertion covering all properties
# (kept LAST): with a non-aborting _fail none of the earlier asserts can stop
# the test, so the last assert alone decides the test status and must fail
# whenever any property is violated. The earlier granular asserts only give
# precise failure messages while the harness is healthy.
# shellcheck source=tests/bash/common.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"

HARNESS=$SCRIPT_DIR/harness.sh

test_first_failing_assertion_aborts_the_test() {
    local out status=0
    # Inner program keeps its own positional params (harness path as $1).
    # shellcheck disable=SC2016
    out=$(bash -c '
        source "$1"
        test_demo() {
            assert_eq "expected" "actual"
            printf "AFTER-FAIL\n"
        }
        run_tests
    ' bash "$HARNESS" 2>&1) || status=$?
    assert_eq "1" "$status"
    assert_contains "$out" "not ok - test_demo"
    assert_contains "$out" "FAIL: expected [expected] but was [actual]"
    assert_not_contains "$out" "AFTER-FAIL"
    local verdict=broken
    if [ "$status" = 1 ] &&
        printf '%s' "$out" | grep -qxF 'not ok - test_demo' &&
        printf '%s' "$out" | grep -qF 'FAIL: expected [expected] but was [actual]' &&
        ! printf '%s' "$out" | grep -qF 'AFTER-FAIL'; then
        verdict=aborting
    fi
    assert_eq "aborting" "$verdict"
}

test_a_failing_test_does_not_abort_the_remaining_tests() {
    local out status=0
    # Each test runs in its own subshell: one failing test must not stop the
    # others, but the overall run must still exit nonzero.
    # shellcheck disable=SC2016
    out=$(bash -c '
        source "$1"
        test_a() { assert_eq "x" "y"; }
        test_b() { assert_eq "same" "same"; }
        run_tests
    ' bash "$HARNESS" 2>&1) || status=$?
    assert_eq "1" "$status"
    assert_contains "$out" "not ok - test_a"
    assert_contains "$out" "ok - test_b"
    local verdict=broken
    if [ "$status" = 1 ] &&
        printf '%s' "$out" | grep -qxF 'not ok - test_a' &&
        printf '%s' "$out" | grep -qxF 'ok - test_b'; then
        verdict=isolated
    fi
    assert_eq "isolated" "$verdict"
}

run_tests
