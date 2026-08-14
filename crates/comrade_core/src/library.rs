/*!
 * library — one shelf for everything a person already saved somewhere else.
 *
 * The reader in `attention` was built to be *fed by hand*: paste an article,
 * read it a chapter at a time. That is the part of the attention pillar nobody
 * used, and the reason is not subtle — the articles people mean to read
 * properly are already saved, in Instagram's bookmarks, in X's, in Facebook's
 * saves, in a browser's reading list, scattered across the apps that took the
 * attention in the first place. Asking someone to copy them out one at a time
 * is asking them to do the collecting twice.
 *
 * So this module is the collector: URL normalisation and de-duplication, the
 * share-sheet payload parser, and format-detecting importers for the archives
 * those platforms already hand out. It holds no state and does no I/O.
 *
 * ## Why the imports read *export archives* and not APIs
 *
 * Every one of those platforms can be read programmatically — with an OAuth
 * app, a client secret, a per-request round trip to their servers, and their
 * terms of service. Comrade would then be an app that promises not to phone
 * home and ships four API clients. That trade is not available here (see
 * `docs/ATTENTION.md` gate 2: on-device or not at all), so the path taken is
 * the one that needs no account and no network at all: **the user's own data
 * export.** Instagram, X, Facebook and Reddit are all obliged to hand over the
 * saved/bookmarked list as a file, and a file is something a pure function can
 * read on a plane.
 *
 * The cost is honest and has to be said in the UI rather than buried here:
 * an import is a **snapshot, not a sync**. Save something on Instagram
 * tomorrow and Comrade will not know until the next export — or until it is
 * shared into the app, which is the other half of this module and the one that
 * works in the moment.
 *
 * ## Why the JSON importer walks the shape instead of matching it
 *
 * The obvious implementation is one struct per platform with `serde` derives.
 * It is also the one that breaks silently: these schemas are not a contract
 * anyone owes us, they are internal formats that change between export
 * versions, and the failure mode of a mismatched `Deserialize` is *"0 items
 * imported"* — indistinguishable, to the person holding the file, from Comrade
 * being broken. So [`import_saves`] walks arbitrary JSON looking for the shape
 * of a save (a URL-valued field, with a title and a timestamp near it) and
 * takes whatever it finds. One tested code path covers Instagram's
 * `saved_posts.json`, X's `bookmarks.js`, Facebook's `saved_items.json` and
 * whatever those files look like next year, and an unrecognised file degrades
 * to "we found the links in it" rather than to zero.
 *
 * **Nothing here fetches anything.** An imported bookmark is a title and a
 * link; the reader can only show text the user actually brought (pasted, or
 * shared as a text selection). A library that quietly fetched every URL it was
 * given would be a crawler wearing a reading app's clothes, and it would do it
 * on the one screen that exists to be calm.
 */

use serde_json::Value;

/// Where a save came from, as far as its URL can say.
///
/// Used for the shelf's grouping and for the one-line "from Instagram" label.
/// It is deliberately coarse: this is a provenance hint for a human, not a
/// routing decision, and a host it has never seen is `Web` rather than an
/// error.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SaveSource {
    Instagram,
    /// X, Twitter, and `t.co` short links.
    Twitter,
    Facebook,
    Reddit,
    YouTube,
    /// A read-it-later service's own export (Pocket, Instapaper).
    ReadLater,
    /// Any other web address.
    Web,
    /// Text the user brought themselves, with no link at all.
    Pasted,
}

impl SaveSource {
    /// The stored form. `as_str`/`from_key` rather than `Display`/`FromStr`
    /// for the same reason [`crate::attention::FocusOutcome`] does it: these
    /// strings are a storage and FFI contract, not user-facing text, so they
    /// must not drift with a formatting change. The words a person reads are
    /// the frontend's (`R.string.library_source_*`, `sourceLabel` on desktop).
    pub fn as_str(&self) -> &'static str {
        match self {
            SaveSource::Instagram => "instagram",
            SaveSource::Twitter => "twitter",
            SaveSource::Facebook => "facebook",
            SaveSource::Reddit => "reddit",
            SaveSource::YouTube => "youtube",
            SaveSource::ReadLater => "readlater",
            SaveSource::Web => "web",
            SaveSource::Pasted => "pasted",
        }
    }

    /// Parse a stored source. Anything unrecognised reads as [`SaveSource::Web`]
    /// rather than `None`: the value comes off disk, possibly written by a newer
    /// build, and losing a whole saved article because its provenance label is
    /// from the future would be the worse failure by far.
    pub fn from_key(s: &str) -> Self {
        match s {
            "instagram" => SaveSource::Instagram,
            "twitter" => SaveSource::Twitter,
            "facebook" => SaveSource::Facebook,
            "reddit" => SaveSource::Reddit,
            "youtube" => SaveSource::YouTube,
            "readlater" => SaveSource::ReadLater,
            "pasted" => SaveSource::Pasted,
            _ => SaveSource::Web,
        }
    }

    /// Guess the source from a URL's host.
    ///
    /// Suffix-matched against the registrable domain, so `www.instagram.com`
    /// and `l.instagram.com` both read as Instagram while `instagram.com.evil`
    /// does not — the dot in the pattern is what makes that hold.
    pub fn from_url(url: &str) -> Self {
        let Some(host) = host_of(url) else {
            return SaveSource::Web;
        };
        const HOSTS: &[(&str, SaveSource)] = &[
            ("instagram.com", SaveSource::Instagram),
            ("cdninstagram.com", SaveSource::Instagram),
            ("threads.net", SaveSource::Instagram),
            ("twitter.com", SaveSource::Twitter),
            ("x.com", SaveSource::Twitter),
            ("t.co", SaveSource::Twitter),
            ("facebook.com", SaveSource::Facebook),
            ("fb.watch", SaveSource::Facebook),
            ("fb.me", SaveSource::Facebook),
            ("reddit.com", SaveSource::Reddit),
            ("redd.it", SaveSource::Reddit),
            ("youtube.com", SaveSource::YouTube),
            ("youtu.be", SaveSource::YouTube),
            ("getpocket.com", SaveSource::ReadLater),
            ("instapaper.com", SaveSource::ReadLater),
        ];
        for (pattern, source) in HOSTS {
            if host == *pattern || host.ends_with(&format!(".{pattern}")) {
                return *source;
            }
        }
        SaveSource::Web
    }
}

/// Longest URL this module will carry. Matches the bound `together`'s stream
/// URLs use, for the same reason: a link is a thing a person will look at, and
/// past a couple of thousand characters it is a payload wearing a link's shape.
pub const URL_MAX_LEN: usize = 2048;

/// Longest title kept. Beyond this the shelf row is a paragraph, and the point
/// of the row is to be scannable.
pub const TITLE_MAX_LEN: usize = 160;

/// How many items one import may add. Instagram accounts with a decade of saves
/// exist; the cap is what stops one file from turning the shelf into a feed —
/// which would make this module a reimplementation of the thing it exists to
/// escape. The caller is told when it bites ([`ImportReport::truncated`]) so the
/// UI can say so rather than quietly keeping the first N.
pub const IMPORT_MAX_ITEMS: usize = 2000;

/// How many raw rows an extractor will read before it stops.
///
/// Deliberately above [`IMPORT_MAX_ITEMS`] rather than equal to it, so that
/// [`collect`] can tell "this file had exactly the cap in it" from "this file
/// had more and we stopped". An extractor that stopped *at* the cap made
/// [`ImportReport::truncated`] permanently `false`, which is the silent
/// truncation the flag exists to prevent. Twice the cap is slack for a file
/// that is mostly duplicates; past that the count of what was dropped stops
/// being exact, and the flag says "there was more" rather than how much.
const EXTRACT_MAX_ROWS: usize = IMPORT_MAX_ITEMS * 2;

/// How deep the JSON walk goes. Export archives nest three or four levels; this
/// is slack, and it exists because the input is a file whose shape nobody
/// promised — a pathological one must cost a bounded walk, not the stack.
const JSON_MAX_DEPTH: usize = 16;

/// Query parameters stripped from every URL, whatever the host.
///
/// These are unambiguous campaign/click identifiers: dropping them is what
/// makes "the same article, shared twice from two different apps" one row on
/// the shelf instead of two. Ambiguous short names (`t`, `s`, `ref`) are **not**
/// here — see [`X_TRACKING_PARAMS`].
const TRACKING_PARAMS: &[&str] = &[
    "fbclid",
    "gclid",
    "dclid",
    "msclkid",
    "igshid",
    "igsh",
    "mc_cid",
    "mc_eid",
    "twclid",
    "__twitter_impression",
    "_branch_match_id",
    "share_id",
    "ref_src",
    "ref_url",
];

/// Parameters stripped **only** on X/Twitter hosts, where they are that site's
/// share-tracking pair and nothing else.
///
/// They are host-scoped because the same names carry real meaning elsewhere:
/// `?t=90` is a YouTube timestamp and `?s=…` is a search term on half the web.
/// Stripping them globally would silently rewrite a link to "watch from 1:30"
/// into a link to the top of the video, which is the kind of quiet corruption a
/// user would blame on the platform rather than on us.
const X_TRACKING_PARAMS: &[&str] = &["s", "t"];

/// Prefixes stripped wherever they appear: the whole `utm_*` family.
const TRACKING_PREFIXES: &[&str] = &["utm_"];

/// The host of an `http(s)` URL, lowercased, with no port and no `www.`
///
/// `None` for anything that is not an absolute http(s) URL — this is the same
/// "refuse the shape rather than parse it" line `together::valid_stream_url`
/// draws, and for the same reason: a credentials-in-authority URL
/// (`https://a@b/`) is not a host we want to reason about.
fn host_of(url: &str) -> Option<String> {
    let rest = url
        .strip_prefix("https://")
        .or_else(|| url.strip_prefix("http://"))
        .or_else(|| {
            let lower = url.to_ascii_lowercase();
            if lower.starts_with("https://") {
                Some(&url[8..])
            } else if lower.starts_with("http://") {
                Some(&url[7..])
            } else {
                None
            }
        })?;
    let authority = rest.split(['/', '?', '#']).next().unwrap_or_default();
    if authority.contains('@') || authority.is_empty() {
        return None;
    }
    let host = authority.rsplit_once(':').map_or(authority, |(h, _)| h);
    let host = host.trim_start_matches("www.").to_ascii_lowercase();
    (!host.is_empty() && host.contains('.')).then_some(host)
}

/// Whether `s` is an absolute http(s) URL with a plausible host.
///
/// The bar is deliberately low — a saved link is not handed to a media element,
/// it is shown to the person who saved it — but it is not nothing: a bare word,
/// a `javascript:` URL or a path fragment must not become a row on the shelf.
pub fn looks_like_url(s: &str) -> bool {
    s.len() <= URL_MAX_LEN
        && !s.bytes().any(|b| b.is_ascii_control() || b == b' ')
        && host_of(s).is_some()
}

/// A URL with its tracking parameters removed, otherwise byte-for-byte as given.
///
/// Deliberately conservative about *rewriting*: the scheme, host case and path
/// are left exactly as the user's own app produced them, because a link that
/// stopped working after Comrade "tidied" it is a bug the user cannot diagnose.
/// Aggressive canonicalisation happens only in [`dedupe_key`], which is a
/// comparison and never a stored value.
pub fn strip_tracking(url: &str) -> String {
    let Some((head, tail)) = url.split_once('?') else {
        return url.to_string();
    };
    // The fragment rides along with the last parameter; keep it attached to the
    // URL rather than to whichever parameter happened to be final.
    let (query, fragment) = match tail.split_once('#') {
        Some((q, f)) => (q, Some(f)),
        None => (tail, None),
    };
    let x_host = matches!(SaveSource::from_url(url), SaveSource::Twitter);
    let kept: Vec<&str> = query
        .split('&')
        .filter(|pair| !pair.is_empty())
        .filter(|pair| {
            let name = pair.split_once('=').map_or(*pair, |(n, _)| n);
            let lower = name.to_ascii_lowercase();
            let generic = TRACKING_PARAMS.iter().any(|p| *p == lower)
                || TRACKING_PREFIXES.iter().any(|p| lower.starts_with(p));
            let x_only = x_host && X_TRACKING_PARAMS.iter().any(|p| *p == lower);
            !generic && !x_only
        })
        .collect();
    let mut out = String::with_capacity(url.len());
    out.push_str(head);
    if !kept.is_empty() {
        out.push('?');
        out.push_str(&kept.join("&"));
    }
    if let Some(f) = fragment {
        out.push('#');
        out.push_str(f);
    }
    out
}

/// The key two saves are the *same* save under.
///
/// Scheme and host lowercased, `www.` and the fragment dropped, a trailing
/// slash dropped, and the surviving query parameters sorted — so the same
/// article arriving from a browser, from a share sheet and from an export
/// archive collapses to one row. The **path keeps its case**, because a
/// case-sensitive path is common enough (and cheap enough to get wrong) that
/// folding it would merge two genuinely different pages.
pub fn dedupe_key(url: &str) -> String {
    let stripped = strip_tracking(url.trim());
    let body = stripped
        .split('#')
        .next()
        .unwrap_or(&stripped)
        .trim_end_matches('/');
    let rest = body
        .strip_prefix("https://")
        .or_else(|| body.strip_prefix("http://"))
        .unwrap_or(body);
    let (authority, path) = match rest.find(['/', '?']) {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, ""),
    };
    let authority = authority
        .trim_start_matches("www.")
        .to_ascii_lowercase()
        // A default port is the same address as no port; anything else is not.
        .replace(":443", "")
        .replace(":80", "");
    let (path, query) = match path.split_once('?') {
        Some((p, q)) => (p, Some(q)),
        None => (path, None),
    };
    let mut key = format!("{authority}{}", path.trim_end_matches('/'));
    if let Some(q) = query {
        let mut params: Vec<&str> = q.split('&').filter(|p| !p.is_empty()).collect();
        params.sort_unstable();
        if !params.is_empty() {
            key.push('?');
            key.push_str(&params.join("&"));
        }
    }
    key
}

/// The first http(s) URL in `text`, tracking-stripped, or `None`.
///
/// Trailing sentence punctuation is trimmed, because a URL at the end of a
/// shared sentence arrives with the full stop attached, and a link that 404s on
/// a trailing `.` is the most annoying possible way for this to fail. A closing
/// bracket is only trimmed when it is unbalanced — Wikipedia's
/// `…_(disambiguation)` is a real path, and cutting it makes a working link
/// broken in the one direction users notice.
pub fn first_url(text: &str) -> Option<String> {
    let start = find_url_start(text)?;
    let rest = &text[start..];
    let end = rest
        .find(|c: char| c.is_whitespace() || matches!(c, '"' | '\'' | '<' | '>' | '\\'))
        .unwrap_or(rest.len());
    let mut candidate = &rest[..end];
    loop {
        let trimmed = candidate.trim_end_matches(['.', ',', ';', ':', '!', '?']);
        let trimmed = if trimmed.ends_with(')')
            && trimmed.matches('(').count() < trimmed.matches(')').count()
        {
            &trimmed[..trimmed.len() - 1]
        } else {
            trimmed
        };
        if trimmed.len() == candidate.len() {
            break;
        }
        candidate = trimmed;
    }
    looks_like_url(candidate).then(|| strip_tracking(candidate))
}

/// Byte offset of the first `http://` / `https://` in `text`, case-insensitively.
fn find_url_start(text: &str) -> Option<usize> {
    let lower = text.to_ascii_lowercase();
    let http = lower.find("http://");
    let https = lower.find("https://");
    match (http, https) {
        (Some(a), Some(b)) => Some(a.min(b)),
        (Some(a), None) => Some(a),
        (None, Some(b)) => Some(b),
        (None, None) => None,
    }
}

/// A readable title guessed from a URL's own path.
///
/// `…/2026/07/the-attention-economy-is-a-lie` becomes *"The attention economy
/// is a lie"*. This is the fallback when a share carries no subject and an
/// export carries no title, and it is worth doing because the alternative row
/// on the shelf is a naked URL — which tells the reader nothing about whether
/// they want to read it, which is the one job the row has.
///
/// Returns an empty string rather than guessing when the last segment is an
/// opaque id (`/p/CxYz12/`, `/status/1234567890`): a made-up title is worse
/// than none, and the frontend has a real answer for the empty case (it shows
/// the host).
pub fn title_from_url(url: &str) -> String {
    let body = url.split(['?', '#']).next().unwrap_or(url);
    let last = body
        .trim_end_matches('/')
        .rsplit('/')
        .find(|s| !s.is_empty() && !s.contains('.'))
        .unwrap_or_default();
    let words: Vec<String> = last
        .split(['-', '_', '+'])
        .filter(|w| !w.is_empty())
        .map(|w| w.to_string())
        .collect();
    // Two or more words is the signal that this is a slug and not an id. A
    // single token, however long, could equally be `CxYz12` — and "Cxyz12" as a
    // title is a lie with a capital letter on it.
    if words.len() < 2 {
        return String::new();
    }
    let joined = words.join(" ");
    let mut chars = joined.chars();
    let title = match chars.next() {
        Some(first) => first.to_uppercase().collect::<String>() + chars.as_str(),
        None => String::new(),
    };
    bound_title(&title)
}

/// A title trimmed, collapsed to single spaces, and cut to [`TITLE_MAX_LEN`] at
/// a character boundary. Cutting mid-word is accepted; cutting mid-`char` is a
/// panic, which is why this exists rather than a slice.
pub fn bound_title(title: &str) -> String {
    let collapsed: String = title.split_whitespace().collect::<Vec<_>>().join(" ");
    if collapsed.chars().count() <= TITLE_MAX_LEN {
        return collapsed;
    }
    collapsed.chars().take(TITLE_MAX_LEN).collect::<String>() + "…"
}

/// Roughly how long a text takes to read, in minutes, never less than 1.
///
/// 220 words a minute — mid-range for adult prose on a screen. It is an
/// estimate and the UI says so ("~7 min"); the reason it is here at all is that
/// "is this a 4-minute read or a 40-minute one" is the single most useful thing
/// a shelf row can say to someone deciding what to open.
pub fn estimated_minutes(text: &str) -> u32 {
    const WORDS_PER_MINUTE: usize = 220;
    let words = text.split_whitespace().count();
    if words == 0 {
        return 0;
    }
    (words.div_ceil(WORDS_PER_MINUTE) as u32).max(1)
}

// ── The share sheet ───────────────────────────────────────────────────────────

/// Below this many characters of non-URL text, a share is treated as a *link*
/// rather than as an article.
///
/// Both shapes arrive through the same Android `ACTION_SEND`: "share this page"
/// sends a title and a URL, while "share this selection" sends the passage
/// itself. The threshold is what tells them apart, and it is set at a couple of
/// paragraphs deliberately — a 200-character share is a pull-quote, and saving
/// it as *the article* would put a fragment on the shelf under a title
/// promising the whole thing.
pub const SHARED_TEXT_MIN_CHARS: usize = 400;

/// What a share sheet handed us, resolved into something the shelf can hold.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SharedCapture {
    /// The link, tracking-stripped. `None` for a pure text selection.
    pub url: Option<String>,
    /// May be empty; the frontend falls back to the host or to "Saved text".
    pub title: String,
    /// The article body, **verbatim**, or empty for a link-only save. Never
    /// trimmed of interior whitespace: the reader shows exactly what was
    /// saved, and `attention::chunk_reading` is lossless by test on the
    /// strength of that.
    pub text: String,
}

/// Resolve an Android/desktop share payload.
///
/// `subject` is `Intent.EXTRA_SUBJECT` (the page title, when the sharing app
/// bothers) and `body` is `EXTRA_TEXT`. Returns `None` when there is neither a
/// link nor enough text to be worth a row — an empty share is a no-op, not an
/// error dialog.
///
/// The rules, in order:
///
/// 1. A body with [`SHARED_TEXT_MIN_CHARS`] or more of text *besides* the URL
///    is an article: the text is kept whole and the URL, if any, rides along as
///    provenance.
/// 2. Otherwise the first URL is the save, and the title is the subject, or the
///    leftover text, or the URL's own slug.
/// 3. A body with no URL and less than the threshold of text is still kept if
///    it is not blank — a scrap someone deliberately sent is theirs to keep,
///    and the alternative is silently dropping it.
pub fn parse_shared(subject: Option<&str>, body: &str) -> Option<SharedCapture> {
    let url = first_url(body);
    let subject = subject.map(bound_title).unwrap_or_default();
    // What is left once the link is out of the way — the measure of "is there
    // an article here", since a share of a bare URL is mostly URL.
    let without_url = match &url {
        Some(u) => body.replace(u.as_str(), " "),
        None => body.to_string(),
    };
    let prose: String = without_url.split_whitespace().collect::<Vec<_>>().join(" ");

    if prose.chars().count() >= SHARED_TEXT_MIN_CHARS {
        let title = if subject.is_empty() {
            first_line_title(&prose)
        } else {
            subject
        };
        return Some(SharedCapture {
            url,
            title,
            // Verbatim body, not `prose`: the whitespace is the paragraphing.
            text: body.to_string(),
        });
    }

    if let Some(u) = url {
        let title = if !subject.is_empty() {
            subject
        } else if !prose.is_empty() {
            bound_title(&prose)
        } else {
            title_from_url(&u)
        };
        return Some(SharedCapture {
            url: Some(u),
            title,
            text: String::new(),
        });
    }

    let trimmed = body.trim();
    (!trimmed.is_empty()).then(|| SharedCapture {
        url: None,
        title: if subject.is_empty() {
            first_line_title(&prose)
        } else {
            subject
        },
        text: body.to_string(),
    })
}

/// The first sentence-or-so of a text, as a stand-in title.
fn first_line_title(prose: &str) -> String {
    let head: String = prose.chars().take(TITLE_MAX_LEN).collect();
    bound_title(head.split_once(". ").map_or(head.as_str(), |(s, _)| s))
}

// ── Importing an export archive ───────────────────────────────────────────────

/// How an import file was read. Reported back so the UI can say *what* it
/// understood — "read as a bookmark export" versus "we could not read the
/// format, so we took the links out of it" are very different sentences to the
/// person who chose the file, and only one of them means their titles survived.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ImportFormat {
    /// A JSON archive (Instagram `saved_posts.json`, Facebook `saved_items.json`).
    Json,
    /// X's `bookmarks.js` / `like.js`: JSON behind a `window.YTD… =` assignment.
    JsonAssignment,
    /// Comma- or tab-separated, with or without a header (Reddit, Pocket, Raindrop).
    Csv,
    /// Pocket's `ril_export.html`, or any page of links.
    Html,
    /// One URL per line — and the fallback for a file nothing else recognised.
    UrlList,
}

impl ImportFormat {
    /// The stored/FFI form; the frontend owns the sentence.
    pub fn as_str(&self) -> &'static str {
        match self {
            ImportFormat::Json => "json",
            ImportFormat::JsonAssignment => "json_assignment",
            ImportFormat::Csv => "csv",
            ImportFormat::Html => "html",
            ImportFormat::UrlList => "url_list",
        }
    }
}

/// One save found in an archive.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImportedSave {
    /// Tracking-stripped, and known to satisfy [`looks_like_url`].
    pub url: String,
    /// May be empty — plenty of archives carry only the link.
    pub title: String,
    pub source: SaveSource,
    /// When the platform says it was saved, in seconds. `None` when the archive
    /// does not say, which is common; the caller stamps its own time then and
    /// the UI must not pretend that is the original save date.
    pub saved_at: Option<u64>,
}

/// What an import found, including what it threw away.
///
/// The counts are here because "imported 412" alone is not an honest answer to
/// a file with 900 lines in it. A UI that cannot say *"412 added, 480 already
/// on your shelf, 8 we could not read"* invites the user to assume the missing
/// ones are lost.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImportReport {
    pub format: ImportFormat,
    pub items: Vec<ImportedSave>,
    /// Rows naming a URL already named by an earlier row in the same file.
    pub duplicates: u32,
    /// Rows that carried nothing usable as a link.
    pub skipped: u32,
    /// [`IMPORT_MAX_ITEMS`] was hit and the rest of the file was not read.
    pub truncated: bool,
    /// The declared format failed to parse and the links were scavenged out of
    /// the raw text instead. Titles are lost in that path, so the UI says so.
    pub fell_back: bool,
}

/// Read an export archive and return every save in it.
///
/// Deliberately infallible: the worst outcome is an empty [`ImportReport`], and
/// the caller has something honest to show for every input. Size limiting is
/// the caller's job, not this function's — the cap belongs where the file is
/// read off disk (`ComradeRuntime::import_saves`), not in a pure parser that
/// has already been handed the whole string.
pub fn import_saves(payload: &str) -> ImportReport {
    let trimmed = payload.trim_start_matches(|c: char| c.is_whitespace() || c == '\u{feff}');
    let (format, found) = match detect_format(trimmed) {
        ImportFormat::JsonAssignment => (
            ImportFormat::JsonAssignment,
            json_saves(strip_assignment(trimmed)),
        ),
        ImportFormat::Json => (ImportFormat::Json, json_saves(trimmed)),
        ImportFormat::Html => (ImportFormat::Html, html_saves(trimmed)),
        ImportFormat::Csv => (ImportFormat::Csv, csv_saves(trimmed)),
        ImportFormat::UrlList => (ImportFormat::UrlList, None),
    };
    // A file that declared itself JSON and then would not parse is the case
    // this fallback exists for: the links are still in there as plain text, and
    // handing back 340 untitled saves beats handing back a parse error nobody
    // outside this file can act on.
    let (rows, fell_back) = match found {
        Some(rows) => (rows, false),
        None => (
            text_saves(trimmed),
            !matches!(format, ImportFormat::UrlList),
        ),
    };
    collect(format, rows, fell_back)
}

/// One row as an extractor found it, before de-duplication.
struct RawSave {
    url: String,
    title: String,
    saved_at: Option<u64>,
}

/// De-duplicate, bound and count. The single place the caps and the
/// bookkeeping are applied, so no extractor can forget one.
fn collect(format: ImportFormat, rows: Vec<RawSave>, fell_back: bool) -> ImportReport {
    let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();
    let mut items: Vec<ImportedSave> = Vec::new();
    let mut duplicates = 0u32;
    let mut skipped = 0u32;
    let mut truncated = false;
    for row in rows {
        let url = strip_tracking(row.url.trim());
        if !looks_like_url(&url) {
            skipped = skipped.saturating_add(1);
            continue;
        }
        if !seen.insert(dedupe_key(&url)) {
            duplicates = duplicates.saturating_add(1);
            continue;
        }
        // The cap is checked *after* the row has been judged, so that a file of
        // exactly the cap plus a tail of duplicates does not report as
        // truncated. `truncated` means "there were saves we did not take", and
        // a duplicate is not one of them.
        if items.len() >= IMPORT_MAX_ITEMS {
            truncated = true;
            break;
        }
        let title = bound_title(&row.title);
        let title = if title.is_empty() {
            title_from_url(&url)
        } else {
            title
        };
        items.push(ImportedSave {
            source: SaveSource::from_url(&url),
            url,
            title,
            saved_at: row.saved_at,
        });
    }
    ImportReport {
        format,
        items,
        duplicates,
        skipped,
        truncated,
        fell_back,
    }
}

/// Which reader to try, from the first characters of the file.
///
/// Order matters: the JSON checks come first because X's `bookmarks.js` is both
/// an assignment *and* full of `https://` lines, so a content sniff would call
/// it a URL list and silently drop every title.
fn detect_format(payload: &str) -> ImportFormat {
    let head: String = payload.chars().take(4096).collect();
    let first = head.trim_start().chars().next().unwrap_or(' ');
    if first == '{' || first == '[' {
        return ImportFormat::Json;
    }
    if is_assignment(&head) {
        return ImportFormat::JsonAssignment;
    }
    let lower = head.to_ascii_lowercase();
    if first == '<' || lower.contains("<!doctype html") || lower.contains("<a href") {
        return ImportFormat::Html;
    }
    // A header naming a URL column, or simply more than one comma/tab-separated
    // field on the first line, is a table. A bare list of URLs has neither.
    let first_line = head.lines().next().unwrap_or_default();
    let delimiter = if first_line.contains('\t') { '\t' } else { ',' };
    if first_line.matches(delimiter).count() >= 1 && !looks_like_url(first_line.trim()) {
        return ImportFormat::Csv;
    }
    ImportFormat::UrlList
}

/// Whether `head` looks like `name.path = [` — the shape X's archive uses.
fn is_assignment(head: &str) -> bool {
    let Some((lhs, rhs)) = head.split_once('=') else {
        return false;
    };
    let lhs = lhs.trim();
    !lhs.is_empty()
        && lhs
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '$' | '[' | ']'))
        && rhs.trim_start().starts_with(['[', '{'])
}

/// Everything from the first `[`/`{` to the last `]`/`}`, so a `window.YTD… =`
/// prefix and a trailing `;` both fall away.
fn strip_assignment(payload: &str) -> &str {
    let start = payload.find(['[', '{']).unwrap_or(0);
    let end = payload
        .rfind([']', '}'])
        .map_or(payload.len(), |i| i + 1)
        .max(start);
    &payload[start..end]
}

/// Field names that hold the link, most specific first. Order is what stops
/// Facebook's `{"url": …, "external_context": {"url": …}}` from preferring the
/// wrapper's own permalink over the article it points at — the inner object is
/// walked first because the walk is depth-first.
const URL_KEYS: &[&str] = &[
    "expandedurl",
    "expanded_url",
    "external_url",
    "permalink",
    "href",
    "url",
    "uri",
    "link",
    "resolved_url",
    "given_url",
];

/// Field names that hold a human title.
const TITLE_KEYS: &[&str] = &[
    "title",
    "name",
    "headline",
    "resolved_title",
    "given_title",
    "excerpt",
    "description",
    "full_text",
    "text",
    "caption",
];

/// Field names that hold a save timestamp.
const TIME_KEYS: &[&str] = &[
    "timestamp",
    "time_added",
    "saved_at",
    "created_at",
    "created_utc",
    "date",
    "added",
];

/// Walk arbitrary JSON for saves. `None` when the payload is not JSON at all,
/// which is the signal for [`import_saves`] to scavenge the raw text instead.
fn json_saves(payload: &str) -> Option<Vec<RawSave>> {
    let value: Value = serde_json::from_str(payload).ok()?;
    let mut out = Vec::new();
    walk_json(&value, 0, None, None, &mut out);
    Some(out)
}

/// Depth-first walk collecting one save per object that names a URL.
///
/// `inherited_title` / `inherited_time` carry the nearest ancestor's answers
/// down the tree, which is the whole reason this handles Instagram: its
/// `saved_posts.json` puts the account name in the outer object's `title` and
/// the link two levels down inside `string_map_data → "Saved on" → href`. A
/// walk that only looked at one object at a time would find the link and lose
/// the only label there was.
fn walk_json(
    value: &Value,
    depth: usize,
    inherited_title: Option<&str>,
    inherited_time: Option<u64>,
    out: &mut Vec<RawSave>,
) {
    if depth > JSON_MAX_DEPTH || out.len() >= EXTRACT_MAX_ROWS {
        return;
    }
    match value {
        Value::Array(items) => {
            for item in items {
                walk_json(item, depth + 1, inherited_title, inherited_time, out);
                if out.len() >= EXTRACT_MAX_ROWS {
                    return;
                }
            }
        }
        Value::Object(map) => {
            let title = first_matching_string(map, TITLE_KEYS)
                .map(|s| s.to_string())
                .or_else(|| inherited_title.map(|s| s.to_string()));
            let time = first_matching_epoch(map, TIME_KEYS).or(inherited_time);
            // Children first: a nested `external_context.url` is the article,
            // and the wrapper's own `url` is the platform's permalink for the
            // save. Whichever the file has, the deeper one is the better guess.
            let before = out.len();
            for child in map.values() {
                if matches!(child, Value::Array(_) | Value::Object(_)) {
                    walk_json(child, depth + 1, title.as_deref(), time, out);
                }
            }
            if out.len() > before {
                return;
            }
            if let Some(url) = first_matching_url(map) {
                out.push(RawSave {
                    url: url.to_string(),
                    title: title.unwrap_or_default(),
                    saved_at: time,
                });
            }
        }
        // A bare array of URL strings — the simplest export there is.
        Value::String(s) if looks_like_url(s.trim()) => {
            out.push(RawSave {
                url: s.trim().to_string(),
                title: inherited_title.unwrap_or_default().to_string(),
                saved_at: inherited_time,
            });
        }
        _ => {}
    }
}

/// The URL-valued field of one object: a preferred key if present, else any
/// string field that is a URL. The second half is what catches an export whose
/// key is called something nobody guessed.
fn first_matching_url(map: &serde_json::Map<String, Value>) -> Option<&str> {
    for key in URL_KEYS {
        if let Some(s) = map.iter().find_map(|(k, v)| {
            (k.to_ascii_lowercase() == *key)
                .then(|| v.as_str())
                .flatten()
        }) {
            if looks_like_url(s.trim()) {
                return Some(s.trim());
            }
        }
    }
    map.values()
        .filter_map(|v| v.as_str())
        .map(str::trim)
        .find(|s| looks_like_url(s))
}

fn first_matching_string<'a>(
    map: &'a serde_json::Map<String, Value>,
    keys: &[&str],
) -> Option<&'a str> {
    for key in keys {
        for (k, v) in map {
            if k.to_ascii_lowercase() == *key {
                if let Some(s) = v.as_str() {
                    let s = s.trim();
                    // A title that is itself the link tells the reader nothing
                    // the URL does not; leave it empty and let the slug or the
                    // host answer instead.
                    if !s.is_empty() && !looks_like_url(s) {
                        return Some(s);
                    }
                }
            }
        }
    }
    None
}

/// A save timestamp in seconds, from a number or a numeric string.
///
/// Milliseconds are divided down: several archives quote epoch millis, and a
/// save dated 55000 AD sorts to the top of the shelf for ever. The cutoff is
/// "past the year 5000", which no real save date reaches and every millisecond
/// timestamp since 1970 exceeds. ISO date strings are deliberately *not*
/// parsed — that needs a calendar, and this crate does not carry one (the same
/// call `record_attention_day` makes).
fn first_matching_epoch(map: &serde_json::Map<String, Value>, keys: &[&str]) -> Option<u64> {
    /// Seconds at roughly the year 5000.
    const SECONDS_CEILING: u64 = 95_617_584_000;
    for key in keys {
        for (k, v) in map {
            if k.to_ascii_lowercase() != *key {
                continue;
            }
            let raw = v
                .as_u64()
                .or_else(|| v.as_f64().filter(|f| *f >= 0.0).map(|f| f as u64))
                .or_else(|| v.as_str().and_then(|s| s.trim().parse::<u64>().ok()));
            if let Some(n) = raw.filter(|n| *n > 0) {
                return Some(if n > SECONDS_CEILING { n / 1000 } else { n });
            }
        }
    }
    None
}

/// Links out of an HTML page: Pocket's export, a browser's bookmarks file, or
/// anything else shaped like a list of anchors.
///
/// Hand-scanned rather than parsed. A real HTML parser is a dependency, and the
/// only thing wanted here is `href` plus the anchor text — the rest of the
/// document is not being rendered, so there is nothing for a parser to get
/// right that this gets wrong.
fn html_saves(payload: &str) -> Option<Vec<RawSave>> {
    let lower = payload.to_ascii_lowercase();
    let mut out = Vec::new();
    let mut at = 0usize;
    while let Some(rel) = lower[at..].find("href=") {
        let start = at + rel + "href=".len();
        let rest = &payload[start..];
        let quote = rest.chars().next().unwrap_or(' ');
        let (url, after) = if quote == '"' || quote == '\'' {
            match rest[1..].find(quote) {
                Some(end) => (&rest[1..1 + end], start + 2 + end),
                None => break,
            }
        } else {
            let end = rest
                .find(|c: char| c.is_whitespace() || c == '>')
                .unwrap_or(rest.len());
            (&rest[..end], start + end)
        };
        // The rest of the opening tag, then the anchor text up to `</a`.
        //
        // Read *forward* from the href, because that is where the attribute
        // holding the save date actually is: Pocket writes
        // `time_added="1690000000"` after `href` on every row of a real
        // `ril_export.html`. Scanning backwards for it — which is what this did
        // first — finds nothing on every row of every export, and the symptom is
        // a whole shelf of saves dated "just now".
        let tag_end = payload[after..].find('>').map(|i| after + i);
        let saved_at = tag_end.and_then(|gt| attr_epoch(&payload[after..gt]));
        let title = tag_end
            .and_then(|gt| {
                payload[gt + 1..]
                    .find("</a")
                    .map(|end| &payload[gt + 1..gt + 1 + end])
            })
            .map(strip_tags)
            .unwrap_or_default();
        out.push(RawSave {
            url: url.to_string(),
            title,
            saved_at,
        });
        at = after;
        if out.len() >= EXTRACT_MAX_ROWS {
            break;
        }
    }
    (!out.is_empty()).then_some(out)
}

/// `time_added="1690000000"` out of an attribute run, if it is there.
fn attr_epoch(attrs: &str) -> Option<u64> {
    let at = attrs.find("time_added=")? + "time_added=".len();
    attrs[at..]
        .trim_start_matches(['"', '\''])
        .chars()
        .take_while(char::is_ascii_digit)
        .collect::<String>()
        .parse()
        .ok()
}

/// Anchor text with any nested tags removed and whitespace collapsed.
fn strip_tags(html: &str) -> String {
    let mut out = String::with_capacity(html.len());
    let mut inside = false;
    for c in html.chars() {
        match c {
            '<' => inside = true,
            '>' => inside = false,
            _ if !inside => out.push(c),
            _ => {}
        }
    }
    out.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Rows out of a comma- or tab-separated export (Reddit's `saved_posts.csv`,
/// Pocket's CSV, Raindrop's).
///
/// The header, when there is one, names the columns; when there is not, the
/// field that looks like a URL is the URL and the longest other field is the
/// title. Both paths matter: Reddit ships a header and Reddit's older exports
/// do not.
fn csv_saves(payload: &str) -> Option<Vec<RawSave>> {
    let mut lines = payload.lines().filter(|l| !l.trim().is_empty());
    let header_line = lines.next()?;
    let delimiter = if header_line.contains('\t') {
        '\t'
    } else {
        ','
    };
    let header: Vec<String> = split_csv(header_line, delimiter)
        .into_iter()
        .map(|h| h.trim().to_ascii_lowercase())
        .collect();
    let column = |names: &[&str]| -> Option<usize> {
        names
            .iter()
            .find_map(|n| header.iter().position(|h| h == n))
    };
    let url_col = column(&["url", "permalink", "link", "href", "uri"]);
    let title_col = column(&["title", "name", "headline"]);
    let time_col = column(&["time_added", "timestamp", "date", "created", "saved_at"]);
    // A first line that is itself a row (no header) must not be eaten.
    let header_is_data = url_col.is_none()
        && split_csv(header_line, delimiter)
            .iter()
            .any(|f| looks_like_url(f.trim()));
    let mut out = Vec::new();
    let rows = std::iter::once(header_line)
        .filter(|_| header_is_data)
        .chain(lines);
    for line in rows {
        let fields = split_csv(line, delimiter);
        let url = url_col
            .and_then(|i| fields.get(i))
            .map(|s| s.trim().to_string())
            .filter(|s| looks_like_url(s))
            .or_else(|| {
                fields
                    .iter()
                    .map(|f| f.trim())
                    .find(|f| looks_like_url(f))
                    .map(str::to_string)
            });
        let Some(url) = url else {
            continue;
        };
        let title = title_col
            .and_then(|i| fields.get(i))
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty() && !looks_like_url(s))
            .or_else(|| {
                fields
                    .iter()
                    .map(|f| f.trim())
                    .filter(|f| !f.is_empty() && !looks_like_url(f) && f.parse::<u64>().is_err())
                    .max_by_key(|f| f.len())
                    .map(str::to_string)
            })
            .unwrap_or_default();
        let saved_at = time_col
            .and_then(|i| fields.get(i))
            .and_then(|s| s.trim().parse::<u64>().ok());
        out.push(RawSave {
            url,
            title,
            saved_at,
        });
        if out.len() >= EXTRACT_MAX_ROWS {
            break;
        }
    }
    (!out.is_empty()).then_some(out)
}

/// Split one CSV/TSV line, honouring `"…"` quoting and `""` escapes. Enough of
/// RFC 4180 for a bookmark export; not a general CSV reader, and it does not
/// pretend to handle a newline inside a quoted field (no export here has one,
/// and the failure is one dropped row rather than a corrupted file).
fn split_csv(line: &str, delimiter: char) -> Vec<String> {
    let mut fields = Vec::new();
    let mut current = String::new();
    let mut quoted = false;
    let mut chars = line.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '"' if quoted && chars.peek() == Some(&'"') => {
                current.push('"');
                chars.next();
            }
            '"' => quoted = !quoted,
            c if c == delimiter && !quoted => fields.push(std::mem::take(&mut current)),
            c => current.push(c),
        }
    }
    fields.push(current);
    fields
}

/// Every URL in a blob of text, one row each. The URL-list reader, and the
/// fallback for a file whose declared format would not parse.
fn text_saves(payload: &str) -> Vec<RawSave> {
    let mut out = Vec::new();
    for line in payload.lines() {
        let mut rest = line;
        while let Some(url) = first_url(rest) {
            // Advance past this URL before looking again, or a line with two
            // links loops on the first one for ever.
            let at = rest.find(&url).map_or(rest.len(), |i| i + url.len());
            let title = line
                .split(url.as_str())
                .next()
                .map(bound_title)
                .unwrap_or_default();
            out.push(RawSave {
                url,
                title,
                saved_at: None,
            });
            if out.len() >= EXTRACT_MAX_ROWS {
                return out;
            }
            rest = &rest[at.min(rest.len())..];
        }
    }
    out
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn source_matches_the_registrable_domain_and_not_a_lookalike() {
        assert_eq!(
            SaveSource::from_url("https://www.instagram.com/p/Cx1/"),
            SaveSource::Instagram
        );
        assert_eq!(
            SaveSource::from_url("https://x.com/someone/status/1"),
            SaveSource::Twitter
        );
        assert_eq!(
            SaveSource::from_url("https://youtu.be/abc"),
            SaveSource::YouTube
        );
        // The dot in the pattern is what makes this hold.
        assert_eq!(
            SaveSource::from_url("https://instagram.com.phish.example/p/1"),
            SaveSource::Web
        );
        assert_eq!(SaveSource::from_url("not a url"), SaveSource::Web);
    }

    #[test]
    fn source_keys_round_trip_and_the_unknown_one_is_web() {
        for s in [
            SaveSource::Instagram,
            SaveSource::Twitter,
            SaveSource::Facebook,
            SaveSource::Reddit,
            SaveSource::YouTube,
            SaveSource::ReadLater,
            SaveSource::Web,
            SaveSource::Pasted,
        ] {
            assert_eq!(SaveSource::from_key(s.as_str()), s);
        }
        // A row written by a newer build must not lose the article.
        assert_eq!(SaveSource::from_key("mastodon"), SaveSource::Web);
    }

    #[test]
    fn tracking_params_go_but_meaningful_short_ones_stay() {
        assert_eq!(
            strip_tracking("https://example.com/a?utm_source=ig&utm_medium=x&id=7"),
            "https://example.com/a?id=7"
        );
        assert_eq!(
            strip_tracking("https://example.com/a?fbclid=123"),
            "https://example.com/a"
        );
        // Host-scoped: X's share pair goes...
        assert_eq!(
            strip_tracking("https://x.com/u/status/1?s=20&t=abc"),
            "https://x.com/u/status/1"
        );
        // ...and the identically named YouTube timestamp stays, because
        // rewriting "start at 1:30" to "start at 0:00" is silent corruption.
        assert_eq!(
            strip_tracking("https://youtu.be/abc?t=90"),
            "https://youtu.be/abc?t=90"
        );
        // The fragment belongs to the URL, not to the last parameter.
        assert_eq!(
            strip_tracking("https://example.com/a?utm_source=x#section"),
            "https://example.com/a#section"
        );
    }

    #[test]
    fn the_same_article_from_three_apps_is_one_row() {
        let key = dedupe_key("https://example.com/post/");
        assert_eq!(dedupe_key("http://www.example.com/post"), key);
        assert_eq!(dedupe_key("https://EXAMPLE.com/post/#intro"), key);
        assert_eq!(dedupe_key("https://example.com/post?fbclid=9"), key);
        assert_eq!(dedupe_key("https://example.com:443/post"), key);
        // Query order is not identity...
        assert_eq!(
            dedupe_key("https://example.com/p?b=2&a=1"),
            dedupe_key("https://example.com/p?a=1&b=2")
        );
        // ...but path case is: two different pages must not merge.
        assert_ne!(
            dedupe_key("https://example.com/Post"),
            dedupe_key("https://example.com/post")
        );
    }

    #[test]
    fn first_url_survives_the_punctuation_a_share_wraps_it_in() {
        assert_eq!(
            first_url("read this: https://example.com/a-good-read."),
            Some("https://example.com/a-good-read".to_string())
        );
        assert_eq!(
            first_url("(see https://example.com/x)"),
            Some("https://example.com/x".to_string())
        );
        // A balanced bracket is part of the path.
        assert_eq!(
            first_url("https://en.wikipedia.org/wiki/Foo_(bar)"),
            Some("https://en.wikipedia.org/wiki/Foo_(bar)".to_string())
        );
        assert_eq!(first_url("no link here"), None);
        assert_eq!(first_url("javascript:alert(1)"), None);
    }

    #[test]
    fn a_slug_becomes_a_title_and_an_opaque_id_does_not() {
        assert_eq!(
            title_from_url("https://example.com/2026/07/the-attention-economy-is-a-lie"),
            "The attention economy is a lie"
        );
        // One token could equally be an id, so no title is offered rather than
        // a capitalised guess.
        assert_eq!(title_from_url("https://www.instagram.com/p/CxYz12/"), "");
        assert_eq!(title_from_url("https://x.com/u/status/1789"), "");
    }

    #[test]
    fn shared_link_and_shared_selection_are_told_apart() {
        // "Share this page": subject + URL.
        let link = parse_shared(
            Some("A good read"),
            "A good read https://example.com/x?igshid=99",
        )
        .expect("a link is a save");
        assert_eq!(link.url.as_deref(), Some("https://example.com/x"));
        assert_eq!(link.title, "A good read");
        assert!(link.text.is_empty(), "a link save carries no body");

        // "Share this selection": a passage, kept verbatim.
        let body = format!("{}\n\nsecond paragraph", "word ".repeat(200));
        let sel = parse_shared(None, &body).expect("text is a save");
        assert_eq!(sel.text, body, "the body is stored byte-for-byte");
        assert!(sel.url.is_none());
        assert!(!sel.title.is_empty(), "a title is guessed from the opening");

        // A passage that also names its source keeps both.
        let with_url = format!("https://example.com/essay {}", "word ".repeat(200));
        let both = parse_shared(Some("Essay"), &with_url).expect("save");
        assert_eq!(both.url.as_deref(), Some("https://example.com/essay"));
        assert_eq!(both.title, "Essay");
        assert!(both.text.contains("word word"));

        // Nothing usable is a no-op, not an error.
        assert_eq!(parse_shared(None, "   "), None);
    }

    #[test]
    fn a_short_share_with_no_link_is_still_kept() {
        let scrap = parse_shared(None, "remember to read the Bergson chapter").expect("kept");
        assert!(scrap.url.is_none());
        assert_eq!(scrap.text, "remember to read the Bergson chapter");
    }

    #[test]
    fn instagram_saved_posts_json_imports_with_its_labels() {
        // The real shape: the account name is two levels above the link.
        let payload = r#"{
          "saved_saved_media": [
            {
              "title": "brianeno",
              "string_map_data": {
                "Saved on": {
                  "href": "https://www.instagram.com/p/CxAbc123/",
                  "timestamp": 1690000000
                }
              }
            },
            {
              "title": "someone_else",
              "string_map_data": {
                "Saved on": {
                  "href": "https://www.instagram.com/reel/CyDef456/",
                  "timestamp": 1700000000
                }
              }
            }
          ]
        }"#;
        let report = import_saves(payload);
        assert_eq!(report.format, ImportFormat::Json);
        assert!(!report.fell_back);
        assert_eq!(report.items.len(), 2);
        assert_eq!(report.items[0].url, "https://www.instagram.com/p/CxAbc123/");
        assert_eq!(report.items[0].title, "brianeno");
        assert_eq!(report.items[0].source, SaveSource::Instagram);
        assert_eq!(report.items[0].saved_at, Some(1690000000));
    }

    #[test]
    fn x_bookmarks_js_imports_through_its_assignment_prefix() {
        let payload = r#"window.YTD.bookmark.part0 = [
          {
            "bookmark": {
              "tweetId": "1789",
              "expandedUrl": "https://twitter.com/i/web/status/1789?s=20"
            }
          }
        ];"#;
        let report = import_saves(payload);
        assert_eq!(report.format, ImportFormat::JsonAssignment);
        assert_eq!(report.items.len(), 1);
        // The `?s=20` is X's own share tracking and goes.
        assert_eq!(report.items[0].url, "https://twitter.com/i/web/status/1789");
        assert_eq!(report.items[0].source, SaveSource::Twitter);
    }

    #[test]
    fn facebook_saved_items_prefers_the_article_over_the_wrapper() {
        let payload = r#"{
          "saves_v2": [
            {
              "title": "Cory Doctorow shared a link",
              "timestamp": 1680000000,
              "attachments": [
                {"data": [{"external_context": {
                  "url": "https://pluralistic.net/2026/01/enshittification"
                }}]}
              ]
            }
          ]
        }"#;
        let report = import_saves(payload);
        assert_eq!(report.items.len(), 1, "one save, not one per nesting level");
        assert_eq!(
            report.items[0].url,
            "https://pluralistic.net/2026/01/enshittification"
        );
        // The outer object's label is inherited down to the link.
        assert_eq!(report.items[0].title, "Cory Doctorow shared a link");
        assert_eq!(report.items[0].saved_at, Some(1680000000));
    }

    #[test]
    fn milliseconds_do_not_become_the_year_55000() {
        let payload = r#"[{"url": "https://example.com/a", "timestamp": 1690000000000}]"#;
        let report = import_saves(payload);
        assert_eq!(report.items[0].saved_at, Some(1690000000));
    }

    #[test]
    fn reddit_csv_imports_with_and_without_a_header() {
        let with_header =
            "id,permalink,date\nabc123,https://www.reddit.com/r/rust/comments/x/y/,2026-01-02";
        let report = import_saves(with_header);
        assert_eq!(report.format, ImportFormat::Csv);
        assert_eq!(report.items.len(), 1);
        assert_eq!(report.items[0].source, SaveSource::Reddit);

        // No header: the first line is data and must not be eaten.
        let headerless =
            "abc,https://www.reddit.com/r/rust/comments/x/y/\ndef,https://example.com/z";
        let report = import_saves(headerless);
        assert_eq!(report.items.len(), 2, "the first row is a row");
    }

    #[test]
    fn a_quoted_csv_title_with_a_comma_stays_one_field() {
        let csv = "title,url\n\"Attention, and how to keep it\",https://example.com/a";
        let report = import_saves(csv);
        assert_eq!(report.items.len(), 1);
        assert_eq!(report.items[0].title, "Attention, and how to keep it");
    }

    #[test]
    fn pocket_html_export_imports_titles_and_dates() {
        let html = r#"<!DOCTYPE html><html><body><ul>
          <li><a href="https://example.com/one" time_added="1690000000">The first essay</a></li>
          <li><a href="https://example.com/two" time_added="1700000000">The <b>second</b> essay</a></li>
        </ul></body></html>"#;
        let report = import_saves(html);
        assert_eq!(report.format, ImportFormat::Html);
        assert_eq!(report.items.len(), 2);
        assert_eq!(report.items[0].title, "The first essay");
        assert_eq!(report.items[0].saved_at, Some(1690000000));
        // Nested markup in the anchor text is stripped, not shown.
        assert_eq!(report.items[1].title, "The second essay");
    }

    #[test]
    fn a_plain_url_list_imports_and_counts_its_duplicates() {
        let list = "https://example.com/a\nhttps://example.com/a?utm_source=x\nnot a link\nhttps://example.com/b";
        let report = import_saves(list);
        assert_eq!(report.format, ImportFormat::UrlList);
        assert_eq!(report.items.len(), 2);
        assert_eq!(report.duplicates, 1, "the tracked twin is the same save");
        assert!(!report.fell_back, "a URL list is not a fallback");
    }

    #[test]
    fn an_unparseable_json_file_degrades_to_its_links_and_says_so() {
        // A truncated export — the shape a half-finished download has.
        let broken =
            r#"{"saved": [{"href": "https://example.com/a"}, {"href": "https://example.com/b""#;
        let report = import_saves(broken);
        assert_eq!(report.format, ImportFormat::Json);
        assert!(
            report.fell_back,
            "the UI must be able to say titles were lost"
        );
        assert_eq!(report.items.len(), 2, "the links are still in there");
    }

    #[test]
    fn an_empty_or_useless_file_reports_nothing_rather_than_failing() {
        let report = import_saves("");
        assert!(report.items.is_empty());
        let report = import_saves("hello, world\nnothing here either");
        assert!(report.items.is_empty());
        assert_eq!(report.duplicates, 0);
    }

    #[test]
    fn an_import_is_capped_and_says_when_the_cap_bit() {
        let list: String = (0..IMPORT_MAX_ITEMS + 50)
            .map(|i| format!("https://example.com/{i}\n"))
            .collect();
        let report = import_saves(&list);
        assert_eq!(report.items.len(), IMPORT_MAX_ITEMS);
        assert!(report.truncated, "silent truncation reads as completeness");
    }

    #[test]
    fn deeply_nested_json_costs_a_bounded_walk() {
        // Pathological input: a file nobody promised the shape of. Two guards
        // sit in front of it and the test pins both, because they fail
        // differently and only one of them is ours.
        let nest = |depth: usize| {
            let mut payload = String::new();
            for _ in 0..depth {
                payload.push_str("{\"a\":");
            }
            payload.push_str("\"https://example.com/deep\"");
            for _ in 0..depth {
                payload.push('}');
            }
            payload
        };

        // Past JSON_MAX_DEPTH but well within what serde_json will parse: the
        // walk stops itself, and a link buried 40 levels down is not a save.
        let report = import_saves(&nest(40));
        assert_eq!(report.format, ImportFormat::Json);
        assert!(report.items.is_empty(), "past the depth guard, nothing");

        // Past serde_json's own recursion limit: the parse refuses, and the
        // fallback scavenges the link out of the raw text rather than throwing
        // the file away. The point is that this returns at all.
        let report = import_saves(&nest(500));
        assert!(report.fell_back);
        assert_eq!(report.items.len(), 1);
    }

    #[test]
    fn titles_are_bounded_and_never_split_a_char() {
        let long = "🦀".repeat(500);
        let bounded = bound_title(&long);
        assert_eq!(
            bounded.chars().count(),
            TITLE_MAX_LEN + 1,
            "plus the ellipsis"
        );
        assert_eq!(bound_title("  spaced   out  "), "spaced out");
    }

    #[test]
    fn read_time_is_an_estimate_that_never_reads_as_zero_minutes() {
        assert_eq!(estimated_minutes(""), 0, "no text is not a one-minute read");
        assert_eq!(estimated_minutes("a few words here"), 1);
        assert_eq!(estimated_minutes(&"word ".repeat(220)), 1);
        assert_eq!(estimated_minutes(&"word ".repeat(221)), 2);
    }
}
