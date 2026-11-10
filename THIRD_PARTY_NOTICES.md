# Third-party notices

The Orafit JDBC JAR bundles no third-party classes. It declares the following
runtime dependency, which build tools resolve from Maven Central.

## JSqlParser 5.4 (runtime dependency, not bundled)

- Maven coordinate: `com.github.jsqlparser:jsqlparser:5.4`
- License: dual licensed under LGPL 2.1 or Apache License 2.0
- Orafit license choice: Apache License 2.0
- Project: <https://github.com/JSQLParser/JSqlParser>

Orafit uses JSqlParser under the Apache License 2.0 option. The LGPL 2.1 option
remains the upstream project's alternative license; Orafit does not rely on
that option. The upstream dual-license statement is documented in the
JSqlParser project and its Maven metadata.

Orafit's own source code is distributed under the MIT License in `LICENSE`.

## Zonky Embedded PostgreSQL (test runtime only)

- Maven coordinates: `io.zonky.test:embedded-postgres:2.2.2`
- PostgreSQL binary coordinate: `io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64:17.10.0`
- Runtime target: glibc-based Linux amd64; Windows, macOS, and Alpine binaries are not bundled
- License: Apache License 2.0
- Project: <https://github.com/zonkyio/embedded-postgres>

The embedded PostgreSQL library and native binaries are used only by the Maven
test suite and are not included in the Orafit product JAR.
