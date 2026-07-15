# phpcore

`phpcore` is the PHP runtime implementation of the shared Puppet contracts and
an equal-status sibling of `javacore`.

The shared runtime, capability, plugin and generator contracts live in `core`.
This module provides the platform-side `PhpPuppetNode`, the Java-compatible
`M=0/1/2/3` core client, PHP disguise validation, single-file endpoint generation
and target-side PHP components. Neither runtime module depends on or adapts the
other one.

## Delivered surface

- PHP 5.6+ minimal single-file HTTP bootstrap generation
- Minimal test/load/invoke/forward target core
- Basic info and one-shot command execution
- File management, chunked upload/download, ZIP compression/decompression
- PHP script execution and PDO database queries
- Platform-managed PHP source plugins
- Runtime-aware request/response disguise validation

The generator exposes three output modes. `compact` is the default and emits
whitespace-minified PHP without an `eval`/zlib bootstrap. `packed` retains the
smallest DEFLATE + Base64 representation, while `portable` emits line-oriented
plain PHP for inspection and debugging. The outer wrapper only decodes the
request, calls the generated core entry point, and encodes the result. The inner
core mirrors Java's operation model: `M=0` tests the endpoint,
`M=1` forwards an encoded inner request, `M=2` loads a component, and `M=3`
invokes a component.

Business and runtime inspection logic lives in independently delivered PHP
components. An invocation carries an opaque platform-assigned `componentKey`, so
the target loads only that exact version-addressed cached file without exposing
component names in cache filenames. A cache miss returns
code 424; the platform then sends the matching PHP artifact, the endpoint stores
it atomically under an endpoint-scoped directory in `sys_get_temp_dir()`, and
retries the invocation. The target omits content re-hashing while keeping
platform-assigned cache version selection. Generated core function and local
variable names vary per generation, error display is disabled, and target errors
use numeric status codes. No startup-time component preload, runtime
profile, fixed target allowlist, or remote unload operation is required. ZIP operations
need `ZipArchive`; database connections need the matching PDO driver.

Only `packed` requires PHP's standard `base64_decode` and `gzinflate` functions.
Component source remains outside the generated endpoint and is never part of the
bootstrap payload.

## Verification

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw -pl phpcore,service,web -am test
```

Frontend runtime selection, generation, disguise editing, plugin management and
node information live in the sibling `LeoVueAi` project. Its production `dist/`
is packaged under `web/src/main/resources/static/`.
