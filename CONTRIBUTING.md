# Contributing to DisplayKit

DisplayKit accepts focused changes that preserve the contracts in
[ARCHITECTURE.md](ARCHITECTURE.md). Open an issue before a broad API redesign so
the use case and compatibility cost can be discussed first.

## Development setup

Install Java 21, clone the repository, and run. The checked-in Gradle daemon
criteria selects Java 21 even when a newer JDK launches the wrapper:

```text
./gradlew test
```

Use the `showcase` module for changes whose correctness depends on Minecraft
rendering, input, or resource-pack behavior. Start its server and client run
configurations, exercise the affected scene head-on and obliquely, and include
the visual cases you checked in the pull request.

## Pull-request gate

- Add or update focused tests for behavior and invalid input.
- Preserve stable keys and entity identity during retained updates.
- Keep application-specific names, state, and branding out of public modules.
- Avoid raw world offsets in widgets; measured rectangles own geometry.
- Close every timer, subscription, presentation, and virtual entity layer.
- Run the complete build command from `.github/workflows/build.yml`.
- Update public documentation when behavior or compatibility changes.

By contributing, you agree that your contribution is licensed under the MIT
License for source code. Bundled third-party assets retain the licenses and
ownership described in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
