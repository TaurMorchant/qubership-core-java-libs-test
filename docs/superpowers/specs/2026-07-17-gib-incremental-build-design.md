# Incremental build with gitflow-incremental-builder (GIB)

- **Date:** 2026-07-17
- **Repo:** `qubership-core-java-libs-test` (fork `TaurMorchant/qubership-core-java-libs-test`)
- **Status:** Approved design, pending implementation

## Goal

Introduce incremental Maven builds into the test monorepo using
[gitflow-incremental-builder (GIB)](https://github.com/gitflow-incremental-builder/gitflow-incremental-builder),
so that only changed modules (and their downstream dependents) are built instead
of all 26 modules.

Scope of **this** iteration:

1. A **local POC** proving GIB narrows the reactor to the impacted modules.
2. A **new, experimental CI workflow** that runs an **incremental deploy** on
   `push` to `main`.

Out of scope for this iteration (planned as a follow-up): making the PR
`maven-verify.yml` workflow incremental (`verify` for PRs).

## Decisions

| Topic | Decision |
|-------|----------|
| Goal on `main` | Incremental **deploy** — build & deploy only changed modules + downstream |
| Workflow shape | **Separate experimental** workflow, alongside existing `maven-deploy.yml` (which is left untouched) |
| GIB configuration | **Core extension** (`.mvn/extensions.xml`), **disabled by default**, enabled only via CLI/CI flag |
| Diff base on push | `github.event.before` (SHA `main` pointed to before the push), with **fallback to full build** when it is empty/zero |
| PR-verify incremental | Deferred to a later iteration |

## Component 1 — GIB installation & configuration

### `.mvn/extensions.xml`
Register GIB as a Maven core extension:

```xml
<extensions xmlns="http://maven.apache.org/EXTENSIONS/1.0.0">
  <extension>
    <groupId>io.github.gitflow-incremental-builder</groupId>
    <artifactId>gitflow-incremental-builder</artifactId>
    <version>4.6.0</version>
    <classLoadingStrategy>plugin</classLoadingStrategy>
  </extension>
</extensions>
```

### Disabled by default
Add to the root `pom.xml` `<properties>`:

```xml
<gib.disable>true</gib.disable>
```

The GIB disable property is `gib.disable` (default `false`). A system property
`-Dgib.disable=false` (CLI/CI) overrides the pom property.
Result: developers' local `mvn ...` invocations remain normal **full** builds;
incremental mode is opt-in and used only by the local POC and CI.

### Default GIB properties (root `pom.xml` `<properties>`)
- `gib.buildDownstream=always` — rebuild modules depending on changed ones
  (correctness for deploy; `always` is also the GIB default).
- `gib.buildUpstream=never` — do not walk up the dependency graph
  (GIB default is `derived`).
- `gib.compareToMergeBase` / `gib.referenceBranch` — **not** fixed in the pom;
  set per-context in CI (for `main`: `compareToMergeBase=false`,
  `referenceBranch=<before-SHA>`).

## Component 2 — Local POC (first stage)

Goal: confirm GIB actually limits the module set. This is exploratory; the throwaway
change is **not** committed.

The real reactor is large (~250 Maven projects across the 26 top-level aggregators).
`core-utils` sits near the bottom of the graph — almost everything depends on it
transitively, so it is a poor demo of narrowing. Use **high-level consumer** modules
(few/no downstream dependents) as the primary examples and try several to see the
spectrum.

1. Install the extension + defaults (Component 1). Run a full baseline once:
   `mvn -Dgib.disable=true install -DskipTests`.
2. Change a **high-level** module (e.g. `maas-client-spring/maas-client-spring-rabbit`).
3. Run incrementally against `main`:
   ```
   mvn -Dgib.disable=false -Dgib.referenceBranch=refs/heads/main verify -DskipTests
   ```
4. **Success criterion:** the reactor contains only that module + a small set of
   downstream dependents — far fewer than the ~250 baseline.
5. Repeat for a couple more modules (e.g. `dbaas-client`, and `core-utils` as the
   low-level contrast) to confirm narrowing tracks the dependency graph.
6. Control run with no changes → GIB selects nothing (root `validate` only, no-op).

## Component 3 — CI workflow on push to `main`

New workflow `maven-incremental-deploy.yml` in the test repo, **alongside**
`maven-deploy.yml` (existing one untouched).

**Constraint:** the reusable `generic-maven-build.yaml` (in `qubership-core-infra`)
accepts only a `maven-command` string input — no place for conditional fallback
logic. So the new workflow uses two jobs:

### Job `prepare`
Computes the `maven-command` string and exposes it as an output.
- If `github.event.before` is empty or the zero SHA
  (`0000000000000000000000000000000000000000` — first push, force-push losing the
  ancestor) → emit a **full** deploy command **without** GIB flags (fallback to
  full build).
- Otherwise → emit an incremental deploy command:
  ```
  deploy -T 1C -DskipTests
    -Dgib.disable=false
    -Dgib.referenceBranch=${{ github.event.before }}
    -Dgib.compareToMergeBase=false
    -Dgib.baseBranch=HEAD
    -DaltDeploymentRepository=github::https://maven.pkg.github.com/TaurMorchant/qubership-core-java-libs-test
  ```

### Job `deploy`
`needs: prepare`. Calls
`Netcracker/qubership-core-infra/.github/workflows/generic-maven-build.yaml@<pin>`
with:
- `maven-command: ${{ needs.prepare.outputs.mvn }}`
- `maven-username: TaurMorchant`
- `sonar-project-key`, secrets — same as existing `maven-deploy.yml`.

`fetch-depth: 0` is already set in the reusable workflow's checkout, so the
`before`-SHA commit is present locally for GIB's diff.

**Triggers:** `push: branches: [main]` + `workflow_dispatch` (manual verification).

## Notes / trade-offs

- Incremental deploy changes semantics vs the full deploy: a push touching only
  `core-utils` rebuilds & redeploys `core-utils` + its downstream (SNAPSHOT),
  while unrelated modules are **not** redeployed. This is the intended saving but
  should be kept in mind when comparing against the full `maven-deploy.yml`.
- GIB version pinned to `4.6.0` (current release as of 2026-07-17).
