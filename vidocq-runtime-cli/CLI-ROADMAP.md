# Vidocq CLI — Roadmap

The Vidocq CLI is the command-line front of the Vidocq Maven plugin: `create`, `dev`, `start`, `test`, `build`,
`extension add|remove|list`, `doctor`, `config`, `idea`, `completion`, `update`, inspired by the vocabulary of the
[Quarkus CLI](https://quarkus.io/guides/cli-tooling).

This file no longer describes the commands one by one: its milestones described a CLI that booted the runtime in
its own JVM and scanned its own class path, which `vidocq dev`, `vidocq start` and `vidocq extension list` stopped
doing with Vidocq/vidocq#47 and #314, and the description drifted from the code (BUG-20261024-12). There is one
description to keep up to date, the reference page.

## Where the CLI is described

- **Reference**: [`vidocq-runtime-cli.adoc`](../docs/en/modules/ROOT/pages/modules/vidocq-runtime-cli.adoc) — every
  command, its options, and what it runs.
- **The CLI and the Maven plugin, side by side**:
  [`cli-plugin-correspondence.adoc`](../docs/en/modules/ROOT/partials/cli-plugin-correspondence.adoc) — the goal of
  each command.
- **What changed**: [`whats-new.adoc`](../docs/en/modules/ROOT/pages/whats-new.adoc).

## How it is built

- **The project's own Maven runs the project.** `dev`, `start`, `test`, `idea` and `build` run the matching
  `vidocq:` goal through the project's `./mvnw`, else the `mvn` of the `PATH`; the CLI never boots the application
  in its own JVM.
- **One implementation for both tools.** The commands that edit or check a project (`create`, `extension`,
  `doctor`, `config`) and their goals run the same `vidocq-runtime-tooling` code, so the CLI and the plugin cannot
  disagree.
- **The extensions describe themselves.** The catalog comes from each extension's
  `META-INF/vidocq/extension.properties`, its family included, by which `extension list --available` groups it; a
  reactor test fails when the two drift. The CLI reads no remote registry: one belongs with Vidocq/vidocq#17.
- **Quiet unless useful.** `info` reads the project's files instead of running Maven; the update notice shows at
  most once a day, on a terminal, on the error stream.
- **No external dependency.** A hand-rolled argument parser, Java 25, a named module.

## What is next

The developer-experience work of 0.4.0-RC1 is tracked in the umbrella issue
[Vidocq/vidocq#339](https://codefloe.com/Vidocq/vidocq/issues/339): an application BOM and parent separate from the
reactor parent, codegen wiring that follows the dependency, `vidocq doctor --fix`, a complete `vidocq create`, and a
build without a warning.
