# Puppet Runtime Compatibility and Maintainability Audit

Date: 2026-07-18

## Scope

This audit covers code that executes on the PHP or Java puppet runtime:

- PHP bootstrap: `phpcore/src/main/resources/templates/php-puppet.php.txt`
- PHP core protocol: `phpcore/src/main/resources/templates/php-core.php.txt`
- PHP components: 25 files under `phpcore/src/main/resources/components`
- Java components: 26 files under `javacore/src/main/java/org/leo/core/component`
- Java component build/audit pipeline: `javacore/compile-components.sh`

Platform-side adapters such as `PhpPuppetNode`, `PhpRpcClient`, `JavaPuppetNode`, and
the Java service classes were inspected only where they define a wire or lifecycle
contract used by the runtime code.

## Current compatibility baseline

### Java

- The component audit compiles all 26 components with Java 8 `javac` using
  `-source 1.6 -target 1.6`.
- Every generated payload has class-file major version 50.
- The audit confirms one output class per component and rejects anonymous/inner
  classes that would make a payload incomplete.
- Animal Sniffer reports that all generated payloads stay within the Java 6 API.
- Components are deliberately self-contained. Moving repeated entry, parameter,
  close, or error helpers into a shared runtime class would break loading on a
  puppet that receives only one component class.

### PHP

- All 25 component sources and the core template pass PHP 8.3 syntax lint.
- Sources use the declared PHP 5.6-compatible language subset: short arrays,
  closures, and `finally`, without scalar declarations, return declarations,
  arrow functions, null coalescing, typed properties, or modern-only collection
  syntax.
- Components are deliberately self-contained. Moving their local closures into
  the bootstrap would couple new components to a new bootstrap and break mixed
  version deployments.
- The existing PHP 5.6 source guard is lexical rather than an actual PHP 5.6
  parser run. A CI job with a real PHP 5.6 binary remains the strongest regression
  check.

## Size baseline

| Artifact group | Count | Total bytes |
|---|---:|---:|
| Java component source | 26 | 429,616 |
| Java component payload | 26 | 272,580 |
| PHP component source | 14 | 96,880 |
| PHP bootstrap/core templates | 2 | 6,511 |

The largest generated Java payloads are `ExecCommandComponent`,
`FingerprintComponent`, `ReconScanComponent`, and `BasicInfoComponent`. The
largest PHP components are `ExecCommandComponent`, `ReverseTunnelComponent`,
`BasicInfoComponent`, and `HttpRequestComponent`.

Source comment removal would reduce repository text but not Java payload size.
Likewise, extracting helpers into shared runtime classes would reduce source
duplication at the cost of the current single-artifact compatibility guarantee.

## Findings

### P0: Preserve the independent-artifact contract

The Java loader sends a single renamed class, while PHP loads digest-addressed,
self-contained source files. Global helper extraction is therefore not a safe
mechanical refactor. Repetition that is part of the artifact boundary should be
generated at build time rather than replaced by a runtime dependency.

Recommended direction:

1. Keep emitted Java classes and PHP components self-contained.
2. If source duplication becomes costly, introduce build-time templates that
   emit the same standalone source.
3. Compare public result keys and component digests before and after generation.

### P1: Runtime state cleanup is request-driven

Terminal, scan, forward, and tunnel components keep process, thread, channel, or
filesystem state across invocations. Normal stop paths clean most state, but an
abrupt process/request termination leaves cleanup until a later invocation or
the operating system reclaims it.

Recommended compatibility-preserving checks:

- Add lifecycle tests for repeated start/stop, duplicate IDs, partial startup,
  timeout, and stale-state cleanup.
- Assert that failure paths close all streams/channels and delete only state
  owned by the current component instance.
- Keep cleanup idempotent so old management clients can retry stop operations.

### P1: Error schemas are inconsistent

The common shape is `code` plus `msg`, but components differ in whether they
return an exception class, exception message, absolute path, operation-specific
message, or no message. This complicates callers and can expose more local detail
than needed.

Recommended direction:

- Define stable machine-readable error categories at the platform boundary.
- Preserve existing `code` and `msg` fields for old callers.
- Add optional structured fields rather than changing or removing existing keys.
- Test malformed parameters separately from runtime failures.

### P1: PHP worker exception handling is asymmetric

The HTTP wrapper handles both `Exception` and `Throwable`, while long-running PHP
worker closures primarily handle `Exception`. On PHP 7+, engine errors therefore
have a different cleanup path from ordinary exceptions.

Recommended direction:

- Verify cleanup with both exception categories on PHP 7/8.
- Keep PHP 5.6 parsing as a hard gate when changing catch structure.
- Put socket/file closure in `finally` where the same resource exists on both
  success and failure paths.

### P1: PHP host identity includes the deployment path

`phpcore_id()` hashes `__FILE__` and `php_uname()`. Moving the same bootstrap to a
new path changes `hostId`, invalidating the management-side loaded-component view
even though the host is otherwise unchanged. This is compatible with the current
protocol but should be documented as a session identity rather than a permanent
machine identity.

### P2: Coverage is uneven

The build pipeline strongly checks Java bytecode compatibility, but runtime
behavior coverage is concentrated in a small subset of components. High-state
components have fewer failure-path tests than their lifecycle complexity warrants.

Recommended additions:

- Contract tests for every component ID, supported action, required parameter,
  success schema, and missing-resource behavior.
- Java 6 bytecode/API audit on every CI run.
- PHP 5.6, 7.4, and 8.x matrix tests with representative extension sets.
- Artifact reproducibility checks: source digest, emitted payload count, class
  major version, and absence of extra classes.

## Verification performed

- `bash javacore/compile-components.sh --check`: passed for all 26 Java components.
- PHP syntax lint: passed for all 14 PHP components and the PHP core template.
- Maven tests for `core`, `phpcore`, and `javacore`: passed when run with JDK 17,
  PHP 8.3 on `PATH`, localhost socket access, and macOS `sysctl` access.

## Change decision

No puppet runtime source was modified in this audit. The current independent
artifact boundary is a compatibility feature, and broad deduplication without a
larger contract-test matrix would create more regression risk than verified size
or maintenance benefit.
