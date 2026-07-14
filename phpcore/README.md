# phpcore

`phpcore` is the PHP runtime implementation of the shared Puppet contracts and
an equal-status sibling of `javacore`.

The shared RPC, runtime profile, capability and component artifact contracts
live in `core`. This module will provide the platform-side `PhpPuppetNode`, PHP
component deployment strategies, payload generation and target-side PHP
templates. Neither runtime module depends on or adapts the other one.
