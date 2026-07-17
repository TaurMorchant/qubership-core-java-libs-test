# GIB Incremental Build Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce incremental Maven builds into the `qubership-core-java-libs-test` monorepo via gitflow-incremental-builder (GIB), proven locally and wired into an experimental incremental-deploy workflow on push to `main`.

**Architecture:** GIB is added as a Maven core extension (`.mvn/extensions.xml`), disabled by default (`gib.disable=true` in the root pom), and enabled only via a CLI/CI `-Dgib.disable=false` flag. On push to `main`, a new experimental workflow computes a Maven command that points GIB's `referenceBranch` at `github.event.before` (the pre-push SHA) with `compareToMergeBase=false`, falling back to a full build when that SHA is empty/zero.

**Tech Stack:** Maven (multi-module, 26 modules), gitflow-incremental-builder 4.6.0, GitHub Actions (reusable workflow `Netcracker/qubership-core-infra/.github/workflows/generic-maven-build.yaml`).

## Global Constraints

- GIB version: **4.6.0** (`io.github.gitflow-incremental-builder:gitflow-incremental-builder`).
- Minimum Maven: 3.6.3 (repo already builds on Java 21 / current Maven).
- GIB **disabled by default**; local developer builds must stay full builds.
- Disable property is **`gib.disable`** (NOT `gib.enabled`). Default `false`; we set `true` in pom, override with `-Dgib.disable=false`.
- `buildDownstream`/`buildUpstream` take `always`/`derived`/`never` (not booleans).
- Deploy target repo: `github::https://maven.pkg.github.com/TaurMorchant/qubership-core-java-libs-test`.
- Existing `maven-deploy.yml` (push→main full deploy) must remain **untouched**.
- Working branch: `feat/gib-incremental-build`.
- All new Maven/YAML comments in English only; no comments unless needed for clarity.

---

## File Structure

- Create: `.mvn/extensions.xml` — registers GIB as a core extension.
- Modify: `pom.xml` (root) — add default GIB properties under `<properties>`.
- Create: `.github/workflows/maven-incremental-deploy.yml` — experimental incremental-deploy workflow on push to `main`.

---

## Task 1: Install GIB extension, disabled by default

**Files:**
- Create: `.mvn/extensions.xml`
- Modify: `pom.xml` (root `<properties>` block)

**Interfaces:**
- Produces: a repo where `mvn <goal>` behaves as a normal full build (GIB disabled), and `mvn -Dgib.disable=false <goal>` activates GIB.

- [ ] **Step 1: Create the core extension descriptor**

Create `.mvn/extensions.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<extensions xmlns="http://maven.apache.org/EXTENSIONS/1.0.0"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://maven.apache.org/EXTENSIONS/1.0.0 http://maven.apache.org/xsd/core-extensions-1.0.0.xsd">
  <extension>
    <groupId>io.github.gitflow-incremental-builder</groupId>
    <artifactId>gitflow-incremental-builder</artifactId>
    <version>4.6.0</version>
    <classLoadingStrategy>plugin</classLoadingStrategy>
  </extension>
</extensions>
```

- [ ] **Step 2: Add GIB defaults to the root pom**

In `pom.xml`, locate the `<properties>` element and add these entries (keep existing properties intact):

```xml
<!-- gitflow-incremental-builder: disabled by default; enabled in CI via -Dgib.disable=false -->
<gib.disable>true</gib.disable>
<gib.buildDownstream>always</gib.buildDownstream>
<gib.buildUpstream>never</gib.buildUpstream>
```

If the root pom has no `<properties>` element yet, add one directly after the `<modules>` block.

- [ ] **Step 3: Verify GIB is present but inert on a normal build**

Run (from the repo root):

```
mvn -B -ntp -N validate 2>&1 | grep -iE "gitflow-incremental-builder|gib" | head
```

Expected: GIB logs that it is **disabled** (a line such as `gitflow-incremental-builder ... is disabled` / `[INFO] ... gib ... disabled`). The build succeeds. This confirms the extension loaded but does not alter normal builds.

- [ ] **Step 4: Verify GIB activates when explicitly enabled**

Run:

```
mvn -B -ntp -Dgib.disable=false validate 2>&1 | grep -iE "gitflow-incremental-builder|Building explicitly|Selected .* project" | head
```

Expected: GIB's banner appears and it reports project selection (e.g. a `gitflow-incremental-builder ... starting` banner). Build succeeds. This confirms the override flag works.

- [ ] **Step 5: Commit**

```
git add .mvn/extensions.xml pom.xml
git commit -m "feat: add gitflow-incremental-builder extension (disabled by default)"
```

---

## Task 2: Local POC — prove module narrowing (exploratory, no commit)

**Files:** none committed. This task validates behavior only; each throwaway change is reverted.

**Interfaces:**
- Consumes: the extension + defaults from Task 1.
- Produces: a captured comparison table showing that changing a **high-level**
  module yields a tiny reactor, while a **low-level** module cascades — proving
  GIB narrows correctly across the dependency spectrum.

> **Note on module choice:** the real reactor is large (~250 Maven projects across
> the 26 top-level aggregators). `core-utils` sits near the bottom of the graph, so
> almost everything depends on it transitively — a poor demo of narrowing. Pick
> **high-level consumer** modules (few/no downstream dependents) as the primary
> examples, and try several to observe the spectrum.

- [ ] **Step 1: Establish a full baseline**

Run a full build once so all artifacts are installed locally (avoids
missing-artifact noise during narrowed builds) and record the full reactor size:

```
mvn -B -ntp -Dgib.disable=true install -DskipTests -T 1C 2>&1 | tee /tmp/gib-baseline.log | tail -5
grep -c "Building " /tmp/gib-baseline.log   # approx full reactor module count
```

Expected: `BUILD SUCCESS`; the reactor lists ~250 modules.

- [ ] **Step 2: POC-A — change a HIGH-LEVEL module (expect tiny reactor)**

Pick a top-of-graph consumer such as `maas-client-spring/maas-client-spring-rabbit`.
Make a trivial uncommitted change (GIB detects uncommitted changes by default):

```
echo "" >> maas-client-spring/maas-client-spring-rabbit/pom.xml
mvn -B -ntp -Dgib.disable=false -Dgib.referenceBranch=refs/heads/main verify -DskipTests 2>&1 | tee /tmp/gib-A.log | grep -iE "gitflow-incremental-builder|Building |Reactor Summary" | head -40
git checkout -- maas-client-spring/maas-client-spring-rabbit/pom.xml
```

Expected (success criterion): the reactor contains only `maas-client-spring-rabbit`
plus a handful (or zero) downstream modules — far fewer than the ~250 baseline.
Record the count from `grep -c "Building " /tmp/gib-A.log`.

- [ ] **Step 3: POC-B — change a DIFFERENT high/mid-level module**

Repeat with another module to confirm it is not a fluke, e.g. `dbaas-client` or
`maas-declarative-client-spring`:

```
MOD=dbaas-client
POM=$(find $MOD -maxdepth 2 -name pom.xml -not -path '*/target/*' | head -1)
echo "" >> "$POM"
mvn -B -ntp -Dgib.disable=false -Dgib.referenceBranch=refs/heads/main verify -DskipTests 2>&1 | tee /tmp/gib-B.log | grep -iE "Building |Reactor Summary" | head -40
git checkout -- "$POM"
grep -c "Building " /tmp/gib-B.log
```

Expected: again a narrowed subset; a mid-level module may pull in more downstream
than POC-A but still far fewer than the full reactor.

- [ ] **Step 4: POC-C (contrast) — change a LOW-LEVEL module**

Change `core-utils` to show the worst case (wide cascade), confirming GIB reacts to
graph position rather than narrowing blindly:

```
POM=$(find core-utils -maxdepth 2 -name pom.xml -not -path '*/target/*' | head -1)
echo "" >> "$POM"
mvn -B -ntp -Dgib.disable=false -Dgib.referenceBranch=refs/heads/main verify -DskipTests 2>&1 | tee /tmp/gib-C.log | grep -c "Building "
git checkout -- "$POM"
```

Expected: a much larger reactor than POC-A/B (core-utils is depended on widely).
This contrast is the proof that narrowing tracks the dependency graph.

- [ ] **Step 5: Record the comparison**

Summarize the three runs, e.g.:

```
echo "baseline: $(grep -c 'Building ' /tmp/gib-baseline.log)"
echo "POC-A maas-client-spring-rabbit: $(grep -c 'Building ' /tmp/gib-A.log)"
echo "POC-B dbaas-client: $(grep -c 'Building ' /tmp/gib-B.log)"
echo "POC-C core-utils: $(grep -c 'Building ' /tmp/gib-C.log)"
```

Expected ordering: `POC-A` ≤ `POC-B` ≪ `POC-C` < `baseline`. If a high-level module
builds ~everything, stop and debug (check the `-Dgib.disable=false` override, that
the edited pom is really the intended module, and `buildUpstream=never`).

- [ ] **Step 6: Control run — no changes selects nothing**

With a clean tree, run incrementally:

```
git status --short   # must be clean
mvn -B -ntp -Dgib.disable=false -Dgib.referenceBranch=refs/heads/main verify -DskipTests 2>&1 | grep -iE "No changed artifacts|Building |validate" | head
```

Expected: GIB reports no changed artifacts and runs only `validate` on the root
(no submodule builds). This confirms the no-op path.

- [ ] **Step 7: Clean up**

```
git status --short
```

Expected: clean working tree (no leftover POC edits). No commit for this task.

---

## Task 3: Experimental incremental-deploy workflow on push to main

**Files:**
- Create: `.github/workflows/maven-incremental-deploy.yml`

**Interfaces:**
- Consumes: the reusable workflow `Netcracker/qubership-core-infra/.github/workflows/generic-maven-build.yaml@8668020927f2a84d8d096a359c719c3c3a403520` (pinned, v2.5.0) which checks out with `fetch-depth: 0` and runs `maven-command`.
- Produces: on push to `main`, a deploy that builds/deploys only changed modules + downstream, or a full deploy fallback when the pre-push SHA is unavailable.

- [ ] **Step 1: Create the workflow file**

Create `.github/workflows/maven-incremental-deploy.yml`:

```yaml
# Maven Incremental Deploy Workflow (experimental)
#
# Builds and deploys ONLY the modules changed since the previous push (and their
# downstream dependents) using gitflow-incremental-builder. Compares HEAD against
# github.event.before. Falls back to a full deploy when that SHA is empty/zero
# (first push, force-push, or manual dispatch).
#
# Runs alongside maven-deploy.yml (which performs a full deploy) for comparison.
#
# Triggers:
#   - Push to main
#   - Manual dispatch (always a full deploy — no pre-push SHA available)

name: Maven incremental deploy (experimental)

run-name: >
  Incremental deploy —
  ${{ github.event_name == 'workflow_dispatch' && 'manual [full]' || format('push [{0}]', github.event.head_commit.message) }}

on:
  push:
    branches:
      - main
  workflow_dispatch:

permissions:
  contents: read
  packages: write
  pull-requests: write

jobs:
  prepare:
    name: Compute maven command
    runs-on: ubuntu-latest
    outputs:
      mvn: ${{ steps.cmd.outputs.mvn }}
    steps:
      - name: Determine incremental vs full command
        id: cmd
        env:
          BEFORE: ${{ github.event.before }}
        run: |
          BASE="deploy -T 1C -DskipTests -Dorg.slf4j.simpleLogger.showThreadId=true -DaltDeploymentRepository=github::https://maven.pkg.github.com/TaurMorchant/qubership-core-java-libs-test"
          ZERO="0000000000000000000000000000000000000000"
          if [ -z "$BEFORE" ] || [ "$BEFORE" = "$ZERO" ]; then
            echo "No usable before-SHA; running FULL deploy."
            echo "mvn=$BASE" >> "$GITHUB_OUTPUT"
          else
            echo "Incremental deploy against before-SHA $BEFORE."
            echo "mvn=$BASE -Dgib.disable=false -Dgib.referenceBranch=$BEFORE -Dgib.compareToMergeBase=false -Dgib.baseBranch=HEAD" >> "$GITHUB_OUTPUT"
          fi

  deploy:
    name: Maven incremental deploy
    needs: prepare
    uses: Netcracker/qubership-core-infra/.github/workflows/generic-maven-build.yaml@8668020927f2a84d8d096a359c719c3c3a403520 # v2.5.0
    with:
      maven-command: ${{ needs.prepare.outputs.mvn }}
      maven-username: 'TaurMorchant'
      sonar-project-key: ${{ vars.SONAR_PROJECT_KEY }}
      ref: ${{ github.ref }}
    secrets:
      MAVEN_TOKEN: ${{ secrets.GH_RWD_PACKAGE_TOKEN }}
      MAVEN_GPG_PRIVATE_KEY: ${{ secrets.MAVEN_GPG_PRIVATE_KEY }}
      MAVEN_GPG_PASSPHRASE: ${{ secrets.MAVEN_GPG_PASSPHRASE }}
      SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
```

- [ ] **Step 2: Validate workflow YAML syntax**

If `actionlint` is available:

```
actionlint .github/workflows/maven-incremental-deploy.yml
```

Otherwise validate YAML parses:

```
python -c "import yaml,sys; yaml.safe_load(open('.github/workflows/maven-incremental-deploy.yml')); print('YAML OK')"
```

Expected: no errors / `YAML OK`.

- [ ] **Step 3: Sanity-check the fallback logic locally**

Simulate the `prepare` step's branch selection for both cases:

```
BASE="deploy -T 1C -DskipTests -DaltDeploymentRepository=github::..."
for BEFORE in "" "0000000000000000000000000000000000000000" "abc1234def"; do
  ZERO="0000000000000000000000000000000000000000"
  if [ -z "$BEFORE" ] || [ "$BEFORE" = "$ZERO" ]; then echo "[$BEFORE] -> FULL"; else echo "[$BEFORE] -> INCREMENTAL ref=$BEFORE"; fi
done
```

Expected: empty and zero SHA → `FULL`; a real SHA → `INCREMENTAL ref=<sha>`.

- [ ] **Step 4: Commit**

```
git add .github/workflows/maven-incremental-deploy.yml
git commit -m "feat: add experimental incremental-deploy workflow on push to main"
```

- [ ] **Step 5: End-to-end validation on GitHub (requires user)**

This step runs on the user's fork and requires pushing. Coordinate with the user before pushing.

1. Push the branch and open/merge to `main` (or push a test commit to `main`) so the workflow triggers.
2. In the Actions run for **Maven incremental deploy (experimental)**, open the `prepare` job logs and confirm it printed `Incremental deploy against before-SHA <sha>`.
3. In the `deploy` job (reusable workflow) Maven logs, confirm the GIB banner and that the reactor is a **subset** of modules (only changed + downstream), not all 26.
4. Confirm only those modules were deployed to GitHub Packages.
5. Compare against the concurrent full `maven-deploy.yml` run to confirm the experiment behaves as expected.

Expected: incremental run selects fewer modules than the full run; a first push / dispatch falls back to a full deploy.

---

## Notes / follow-ups (out of scope this iteration)

- **PR incremental verify:** make `maven-verify.yml` incremental (`referenceBranch=refs/remotes/origin/main`, `compareToMergeBase=true`) — separate iteration.
- **Force-push / orphaned before-SHA:** if `github.event.before` is unreachable after gc, GIB errors. Mitigation if it ever bites: add `-Dgib.fetchReferenceBranch=true` with a `refs/remotes/origin/...` ref, or extend the `prepare` job to verify reachability and fall back to full. Not implemented now (test repo, low risk).
- **Downstream deploy semantics:** a change to `core-utils` redeploys its downstream SNAPSHOTs; unrelated modules are not redeployed. Intended, but noted for comparison with the full deploy.
```
