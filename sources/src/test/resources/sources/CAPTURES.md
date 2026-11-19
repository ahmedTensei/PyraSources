# Public source fixtures

The Asura and Rawkuma fixtures were captured on 19 November 2026. Responses are trimmed to the structures used by parser regression tests. They are test inputs and are never served as application content.

Asura fixtures come from its current public API, identified in the website's native BrowseFilters client:

- `https://api.asurascans.com/api/series?sort=latest&order=desc&limit=20&offset=0`
- `https://api.asurascans.com/api/series/immortals-way-of-life`
- `https://api.asurascans.com/api/series/immortals-way-of-life/chapters`
- `https://api.asurascans.com/api/series/immortals-way-of-life/chapters/37`
- `https://api.asurascans.com/api/series/immortals-way-of-life/chapters/38`

The last response was captured while the chapter was still premium/locked. Its unlock time is preserved as source metadata; the regression test checks the captured locked response without depending on the current clock.

Rawkuma fixtures come from native latest updates, an advanced-search POST with a freshly fetched public search nonce, the One Piece work page, and chapter 1197's HTML reader:

- `https://rawkuma.net/latest-update/`
- `https://rawkuma.net/wp-admin/admin-ajax.php?type=search_form&action=get_nonce`
- `https://rawkuma.net/wp-admin/admin-ajax.php?action=advanced_search`
- `https://rawkuma.net/manga/one-piece/`
- `https://rawkuma.net/manga/one-piece/chapter-1197.430413/`

Public work metadata, source ordering/pagination, chapter identities/timestamps, and image URLs/headers are retained. Tracking scripts, reader media bytes, site comments, and unrelated page decoration are omitted. The optional `smokeSources` task fetches current native responses separately from these deterministic fixtures.
