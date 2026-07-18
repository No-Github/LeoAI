# javacore

`javacore` is the Java runtime implementation of the shared Puppet contracts.

It owns `JavaPuppetNode`, Java component sources and payloads, component-call
services, proxy/tunnel engines, and Java-specific component build auditing.
It is a sibling of `phpcore`; neither runtime module is part of shared `core`.

The platform-side Java RPC client derives a stable HTTP transport profile from
the endpoint URL and host identifier. Generated routes, optional headers,
User-Agent, language and same-origin Referer remain coherent within a session,
while explicitly configured headers retain precedence. Enabled padding uses
bounded length buckets with request-derived field names. Retries retain the
original RPC request identity and use capped exponential backoff with
deterministic jitter.

Java runtime and Component class names now use one application-style package
family per host session instead of mixing reserved JDK namespaces with lambda
markers. Component aliases and member-name variants are derived from the host,
endpoint and component identity, so retries and restarts reproduce the same
artifact while different hosts receive distinct variants. Runtime transformation
preserves the audited Java 6 major version and the ASM pass rebuilds the constant
pool after debug metadata and annotations are removed.

Loaded-component state is bounded by host count, per-host component count and
idle TTL; node shutdown clears every service cache even when another close step
fails. Component worker pools implement `ThreadFactory` directly, avoiding extra
class files while naming workers from the runtime class profile instead of fixed
feature names or default pool counters. Retry warnings are concise at normal log
levels and retain stack details at debug level. The component compiler now builds
single sources sequentially with a bounded heap and can use ECJ when Java 8
`javac` is unavailable.

All `ComponentService` instances owned by one `JavaPuppetNode` now share a
node-level load registry. Concurrent requests for the same host/component are
coalesced into one class-definition request, and later calls reuse the shared
loaded state. Consecutive load failures enter a bounded cooldown before another
artifact transfer, reducing repeated large requests and duplicate-class races.
Cache clear operations use a generation boundary so an older in-flight load
does not repopulate state after node shutdown.
