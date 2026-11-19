# Pyra Sources

The publisher-controlled source repository for Pyra. This repository contains source parsers, source API contracts, source test fixtures, and extension build tooling. It does not contain the Pyra application, its APK, screens, databases, user data, or engine models.

Pyra reads only this repository's [index.json](index.json). Users cannot configure another repository or import custom sources. Version 4 supplies MangaTek (`mangatek`, Arabic), Swat Manga (`mangaswat`, Arabic), Team X (`teamx`, Arabic), Asura Scans (`asurascans`, English), and Rawkuma (`rawkuma`, Japanese), retaining independent identities, URLs, pagination, access limits, language metadata, and image headers.

The index includes a `sources` metadata array generated from the actual source factory without contacting websites. Each item contains `id`, `name`, `baseUrl`, `lang`, and `extensionId`; the schema version 1 and source API version 2 and extension checksum/factory fields remain compatible. This metadata supports the host's explicit per-source installation UI. Fetching the catalogue does not itself mean that every source is installed or updated; the host exposes only source IDs the user selects.

## Repository layout

- `sources/src/main/kotlin`: source implementations and the publisher factory.
- `sources/src/test`: trimmed public-response fixtures and source regression tests; fixtures are never used as app content.
- `sdk/src/main/kotlin`: compile-only source API version 2. These contracts are supplied by the app at runtime and are excluded from extension payloads.
- `bundle.json`: publisher bundle identity, source API version, factory, and monotonically increasing version code.
- `tools/src/main/kotlin`: build-only catalogue metadata exporter and optional live source smoke check, excluded from payloads.
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

The JVM source JAR is emitted under `build/sources/` for app-side fixture tests. Android loads the verified Dex ZIP through the stable `com.pyramanga.app.domain.source.PublisherSourceFactory` contract. The host supplies its shared `OkHttpClient`, including cookies and verification handling. The publisher factory returns sources in catalogue order.

The deterministic test suite parses captured public-response fixtures without network dependencies. An optional live end-to-end check for the two new providers is available separately:

```powershell
./gradlew.bat smokeSources
```

Asura uses the public native API at `api.asurascans.com` observed in its current Astro client, including catalogue/search, details by slug, chapters, public page URLs, and explicit locked-reader responses. Rawkuma uses the current WordPress/HTMX advanced-search nonce/form, native `the_page` pagination, schema.org work data, and `section[data-image-data]` reader images. Unsupported native orders are not fabricated. Site/network/protection failures remain typed source errors; a locked chapter does not produce invented pages.

For future sources with several website/API hosts, keep a correct `Referer` for every request. Source authors may also attach `Request.Builder.tag(String::class.java, id)` to identify the source to the host's verification coordinator; this tag is private request metadata and is not sent as an HTTP header. The current parsers already supply their website origin as the referrer, including Swat's separate API host.

## Add or update a source

1. Add or edit a parser under `sources/src/main/kotlin`. Preserve an existing source's ID when updating it; changing IDs would disconnect saved reading data.
2. Add a new source instance to `com.pyramanga.sources.PublisherSourceFactory.create` in the order it should appear. A new implementation may use any new stable ID; the app does not require a new built-in source binding.
3. Add source-specific fixtures and tests for details, chapters, reader pages, malformed responses, pagination, and actual access limits.
4. Increase `versionCode` in `bundle.json`, then run `test publishSources`. API version changes require a compatible host app; a parser update or additional source does not.
5. Commit the source changes, generated `index.json`, and the new versioned Dex ZIP. Never overwrite an already published versioned ZIP.

The publishing workflow can be run manually after review. It builds and verifies sources, then commits only the generated source payload and index to `main`. Repository write access controls who can publish source updates; no custom repository or server URL is exposed in the app.

The original three parsers retain their existing parsing behavior. Asura and Rawkuma were added from live structures captured on 19 November 2026. Parser fixture entry points and `AstroProps` are public so host regression tests can use the external JAR without retaining site implementations inside the app project. Published version 1 and version 2 ZIP files remain unchanged; version 4 supplies the renamed Pyra API with its own immutable URL.

Optional engine APKs and verified metadata are distributed separately under engines/. They are never included in the source DEX payload or the main Pyra APK. The Android system confirms their Settings-initiated installation.
