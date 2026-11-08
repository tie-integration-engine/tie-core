# tie-core

[![CI](https://github.com/tie-integration-engine/tie-core/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/tie-integration-engine/tie-core/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.tie-integration-engine/tie-core)](https://central.sonatype.com/artifact/io.github.tie-integration-engine/tie-core)

`tie-core` is a module of [TIE](https://github.com/tie-integration-engine/tie), a stateless Java library for file
integrations: the core library: model, field engine, formats, channels and declarative loading. No Spring.

This repository is a git submodule of [tie-integration-engine/tie](https://github.com/tie-integration-engine/tie),
which holds the parent pom, the examples and the documentation. The module builds from there:

```shell
git clone --recurse-submodules https://github.com/tie-integration-engine/tie.git
cd tie
./mvnw -pl tie-core -am verify
```

See the [wiki](https://github.com/tie-integration-engine/tie/wiki) for the documentation. Licensed under the MIT License.
