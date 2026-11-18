# Tensei Sources

The publisher-controlled source repository for Tensei. This repository contains source parsers, source API contracts, source test fixtures, and extension build tooling. It does not contain the Tensei application, its APK, screens, databases, user data, or engine models.

Tensei reads only this repository's [index.json](index.json). Users cannot configure another repository or import custom sources. The current extension supplies MangaTek (`mangatek`), Swat Manga (`mangaswat`), and Team X (`teamx`), retaining their independent source identities, URLs, pagination, chapter locks, language metadata, image headers, and unavailable-page behavior.

## Repository layout

- `sources/src/main/kotlin`: source implementations and the publisher factory.
- `sources/src/test`: trimmed public-response fixtures and source regression tests; fixtures are never used as app content.
- `sdk/src/main/kotlin`: compile-only source API version 1. These contracts are supplied by the app at runtime and are excluded from extension payloads.
- `bundle.json`: publisher bundle identity, source API version, factory, and monotonically increasing version code.
- `releases/*.dex.zip`: Android extension payloads containing `classes.dex`, without APKs or application code.
- `index.json`: the fixed publisher catalogue, with version and SHA-256 of each extension.

## Build and verify

Install JDK 17 or newer and Android SDK Build Tools `36.0.0`, plus platform `android-34` or `android-36`. Set `ANDROID_HOME`, or add `sdk.dir` to an untracked `local.properties` file.

```powershell
./gradlew.bat test publishSources
```

```sh
./gradlew test publishSources
```

The build uses the Kotlin 2.4.21 compiler with the Kotlin 2.2 language/API subset, matching Build Tools 36's supported metadata format. Source classes target JVM 17 before D8 converts them for Android API 26 and newer. Host-specific source-debug mappings are omitted, while ordinary file/line and coroutine debugging metadata remain available. The payload uses stored ZIP entries with fixed timestamps so platform compression and time zones cannot change published hashes. SDK and third-party dependency classes remain compile-only and are not bundled into the extension.

The JVM source JAR is emitted under `build/sources/` for app-side fixture tests. Android loads the verified Dex ZIP through the stable `com.tensei.app.domain.source.PublisherSourceFactory` contract. The host supplies its shared `OkHttpClient`, including cookies and verification handling. The publisher factory returns sources in catalogue order.

## Add or update a source

1. Add or edit a parser under `sources/src/main/kotlin`. Preserve an existing source's ID when updating it; changing IDs would disconnect saved reading data.
2. Add a new source instance to `com.tensei.sources.PublisherSourceFactory.create` in the order it should appear. A new implementation may use any new stable ID; the app does not require a new built-in source binding.
3. Add source-specific fixtures and tests for details, chapters, reader pages, malformed responses, pagination, and actual access limits.
4. Increase `versionCode` in `bundle.json`, then run `test publishSources`. API version changes require a compatible host app; a parser update or additional source does not.
5. Commit the source changes, generated `index.json`, and the new versioned Dex ZIP. Never overwrite an already published versioned ZIP.

The publishing workflow can be run manually after review. It builds and verifies sources, then commits only the generated source payload and index to `main`. Repository write access controls who can publish source updates; no custom repository or server URL is exposed in the app.

The current parsers were moved from Tensei without changing parsing behavior. Parser fixture entry points and `AstroProps` are public so the app's existing source regression tests can test the external JAR instead of keeping copies of implementations inside the app project.
