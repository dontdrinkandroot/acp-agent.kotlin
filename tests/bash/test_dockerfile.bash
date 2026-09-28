#!/bin/bash
# Pins the builder stage's build-input allowlist in the Dockerfile: the image
# builds from an explicitly COPY'd subset of the repo, so every file the
# Gradle build needs must appear as a COPY source - a missed file (e.g. the
# version catalog, missed when 0a49ec8 introduced it) fails the image build
# only, not the local Gradle build.
# shellcheck source=tests/bash/common.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"

DOCKERFILE=$REPO_ROOT/Dockerfile

# Every COPY source token of the builder stage (lines may carry several
# space-separated sources before the destination; runtime-stage COPY --from
# lines are not build inputs and are excluded). The grep-based parse keeps it
# a composition test: it pins that the sources are listed, not what the image
# build does with them.
copy_sources() {
    grep -E '^COPY ' "$DOCKERFILE" | grep -v -- '--from=' | awk '{for (i = 2; i < NF; i++) print $i}'
}

test_version_catalog_is_copied_into_the_builder_stage() {
    assert_contains "$(copy_sources)" "gradle/libs.versions.toml"
}

test_wrapper_and_launcher_scripts_are_copied() {
    local sources
    sources=$(copy_sources)
    assert_contains "$sources" "gradlew"
    assert_contains "$sources" "gradle/wrapper/"
}

test_build_scripts_and_properties_are_copied() {
    local sources
    sources=$(copy_sources)
    assert_contains "$sources" "settings.gradle.kts"
    assert_contains "$sources" "build.gradle.kts"
    assert_contains "$sources" "gradle.properties"
}

test_sources_tree_is_copied() {
    assert_contains "$(copy_sources)" "src/"
}

run_tests
