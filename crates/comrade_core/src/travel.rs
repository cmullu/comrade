/*!
 * travel — "I am standing here; where do the locals eat, and what is this
 * place?"
 *
 * The Travel surface answers two questions from one coordinate, and they are
 * genuinely different questions with different sources:
 *
 * 1. **Where do I eat?** — the legendary places, the ones thousands of people
 *    have already reviewed. Ratings at that volume exist in exactly one place
 *    the public can query, and it is Google Maps ([`GooglePlaces`]). There is no
 *    open dataset with a million reviews in it, and pretending otherwise would
 *    be the polite lie this module refuses to tell.
 * 2. **What is here?** — attractions, landmarks, and the sentence that makes a
 *    street worth walking down. That half is answered by OpenStreetMap
 *    ([`overpass_query`]) and Wikipedia ([`wikipedia_nearby_url`]), both of
 *    which need no key and no account.
 *
 * ## "Legendary" is a definition, not a vibe
 * A 5.0 from three friends of the owner is not a legend; 4.6 across twelve
 * thousand strangers is. [`legend_score`] therefore never ranks on the raw star
 * average — it is a Bayesian rating pulled toward [`PRIOR_RATING`] by
 * [`PRIOR_VOTES`] imaginary middling reviews, multiplied by a crowd weight that
 * reaches 1.0 at [`LEGEND_MIN_REVIEWS`]. [`is_legendary`] is the stricter,
 * *labelling* question — both a rating floor and a review-count floor — and it
 * is deliberately separate from the ranking, because a list has to be ordered
 * even when nothing in it earns the badge.
 *
 * ## The coordinate that leaves the device is not the coordinate of the phone
 * Every outbound query is built from [`coarse_origin`], the centre of the
 * [`QUERY_PRECISION`] geohash cell the user is standing in — about 150 m across.
 * A search radius is kilometres wide, so this costs nothing in result quality,
 * and it means a third party learns "somewhere in this block" rather than "this
 * doorway". Distances shown to the user are computed against the *real* fix,
 * locally, so the UI stays accurate while the wire stays blurred. See
 * [`crate::geo`], whose privacy argument this is a second application of.
 *
 * ## The API key is the user's, never ours
 * Google Places needs a key. There is no key in this repository and there will
 * not be one: an app binary is not a secret store, and a shipped key is a key
 * that gets scraped, billed, and revoked out from under every install. The key
 * is supplied by the person using the app and lives in the encrypted vault
 * (`comrade_ui`), and a missing one is [`TravelError::MissingApiKey`] — a loud
 * "ratings are not configured", never a silent fall back to an unrated list
 * that looks like the feature working badly.
 *
 * ## The socket is behind a feature; the shapes are not
 * `travel-http` guards every byte that touches the network, following
 * `catalogue.rs` and `media.rs`: HTTPS only, redirects disabled, explicit
 * timeouts, bounded read. Unlike those two, the request builders **and the
 * response parsers** are compiled and tested unconditionally — the response
 * format is the part that breaks when an upstream changes, and a fixture test
 * that only runs under a feature nobody enables in CI is a test that does not
 * run.
 */

use std::collections::HashMap;

use crate::geo::{decode_center, Geohash, Precision};

// ── Thresholds ───────────────────────────────────────────────────────────────

/// How many reviews "reviewed by thousands of people" means, taken literally.
/// The crowd weight in [`legend_score`] reaches 1.0 here, and [`is_legendary`]
/// refuses the badge below it.
pub const LEGEND_MIN_REVIEWS: u32 = 1_000;

/// The star floor for the badge. Below this a place is popular, which is a
/// different claim from legendary — a chain outlet by a station clears the
/// review count and should not clear this.
pub const LEGEND_MIN_RATING: f64 = 4.3;

/// Imaginary middling reviews every place is credited with before its own count
/// starts to matter. This is what stops a 5.0-from-4 outranking a 4.6-from-9000.
const PRIOR_VOTES: f64 = 250.0;

/// The rating those imaginary reviews carry — a little under the global mean of
/// rated restaurants, so the prior is a mild drag rather than a thumb on the
/// scale.
const PRIOR_RATING: f64 = 3.9;

/// Default search radius when a caller does not choose one: a walk, not a drive.
pub const DEFAULT_RADIUS_M: u32 = 2_000;

/// Tightest radius worth asking for. Below this the coarse origin (~150 m) is a
/// meaningful fraction of the circle and the results stop being trustworthy.
pub const MIN_RADIUS_M: u32 = 500;

/// Widest radius. Google Places caps `searchNearby` at 50 km; this is far
/// tighter because "local" stops meaning anything past a city.
pub const MAX_RADIUS_M: u32 = 20_000;

/// Rows per section. A guide is read standing up.
pub const MAX_RESULTS: usize = 24;

/// How many nearby articles the facts panel may carry.
pub const MAX_FACTS: usize = 6;

/// The geohash precision every outbound query is rounded to (~150 m cell).
///
/// Not [`Precision::Neighborhood`] (~1.2 km), which was the first instinct: at
/// that size the blur is comparable to the whole search radius and the answer
/// stops being about where the user actually is. Not [`Precision::Building`]
/// (~40 m) either, which is a doorway. This is the level that hides the
/// building without moving the neighbourhood.
pub const QUERY_PRECISION: Precision = Precision::Block;

/// Two records this close together, with names that normalise the same, are one
/// place seen by two providers. Generous because OSM pins a building centroid
/// and Google pins an entrance.
const SAME_PLACE_M: f64 = 80.0;

/// How long a fetched guide stays fresh. Restaurants do not open and close on a
/// timescale that justifies re-querying a paid API every time a tab is opened.
pub const GUIDE_TTL_SECS: u64 = 6 * 60 * 60;

/// Earth's mean radius, for [`haversine_m`].
const EARTH_RADIUS_M: f64 = 6_371_008.8;

// ── The model ────────────────────────────────────────────────────────────────

/// The two halves of the screen. Which one a place belongs to is a property of
/// its kind, not of the query that found it — a bakery turned up by an
/// attractions sweep is still somewhere you eat.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Section {
    /// Legendary local food.
    Eat,
    /// Things to do: attractions, landmarks, parks, viewpoints, markets.
    Do,
}

impl Section {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Eat => "eat",
            Self::Do => "do",
        }
    }

    /// Wire name → section. Unknown input is [`Section::Eat`]'s opposite only
    /// when it says so; anything unrecognised is `None` so a caller decides.
    pub fn from_str_opt(raw: &str) -> Option<Self> {
        match raw {
            "eat" => Some(Self::Eat),
            "do" => Some(Self::Do),
            _ => None,
        }
    }
}

/// What kind of place this is.
///
/// Flat and small on purpose: this is the vocabulary a card renders a line of
/// text and an icon from, not a taxonomy. Both providers' richer type lists are
/// mapped down to it ([`kind_from_osm_tags`], [`kind_from_google_types`]) so a
/// frontend never switches on a provider's own strings.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum PlaceKind {
    Restaurant,
    /// Counter service, a stall, a hole in the wall — where the legends usually
    /// are, and a category Google flattens into `restaurant`.
    StreetFood,
    Cafe,
    Bakery,
    Bar,
    Sweets,
    Museum,
    Gallery,
    Landmark,
    WorshipSite,
    Park,
    Viewpoint,
    Beach,
    Market,
    /// Recognised as a place, not as any of the above.
    Other,
}

impl PlaceKind {
    /// Wire name — what crosses the FFI and what a frontend keys its icon table
    /// on. Snake case, like every other wire name in this crate.
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Restaurant => "restaurant",
            Self::StreetFood => "street_food",
            Self::Cafe => "cafe",
            Self::Bakery => "bakery",
            Self::Bar => "bar",
            Self::Sweets => "sweets",
            Self::Museum => "museum",
            Self::Gallery => "gallery",
            Self::Landmark => "landmark",
            Self::WorshipSite => "worship_site",
            Self::Park => "park",
            Self::Viewpoint => "viewpoint",
            Self::Beach => "beach",
            Self::Market => "market",
            Self::Other => "other",
        }
    }

    /// Which half of the screen this kind belongs to.
    pub const fn section(self) -> Section {
        match self {
            Self::Restaurant
            | Self::StreetFood
            | Self::Cafe
            | Self::Bakery
            | Self::Bar
            | Self::Sweets => Section::Eat,
            _ => Section::Do,
        }
    }
}

/// Who said so. Carried on every place because the answer to "why is this
/// rated and that one is not" is always this field, and a UI that cannot say
/// where a number came from is a UI that invites people to trust it more than
/// they should.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum PlaceSource {
    /// Google Maps / Places — the only source here with review counts in the
    /// thousands.
    GoogleMaps,
    /// OpenStreetMap via Overpass. Names, kinds and coordinates; no ratings,
    /// ever, because OSM does not hold opinions.
    OpenStreetMap,
    /// Wikipedia — used for the facts panel and for naming an attraction that
    /// OSM knows only as a polygon.
    Wikipedia,
}

impl PlaceSource {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::GoogleMaps => "google_maps",
            Self::OpenStreetMap => "openstreetmap",
            Self::Wikipedia => "wikipedia",
        }
    }

    /// Whether this source can supply a rating and a review count at all.
    pub const fn carries_ratings(self) -> bool {
        matches!(self, Self::GoogleMaps)
    }
}

/// One place on the guide.
#[derive(Debug, Clone, PartialEq)]
pub struct Place {
    /// Provider-scoped identifier, prefixed with the source (`gm:…`, `osm:…`)
    /// so two providers can never collide on a bare numeric id.
    pub id: String,
    pub name: String,
    pub kind: PlaceKind,
    pub lat: f64,
    pub lon: f64,
    /// Star average, 0–5, when the source carries one.
    pub rating: Option<f64>,
    /// How many people left that rating. The load-bearing half of "legendary".
    pub review_count: Option<u32>,
    pub address: Option<String>,
    /// Free text from the provider — cuisine, or the editorial one-liner.
    pub cuisine: Option<String>,
    /// A sentence worth reading: an editorial summary, or a Wikipedia opening.
    pub note: Option<String>,
    pub source: PlaceSource,
    /// Somewhere to open this in a map app. Always the provider's own canonical
    /// link when it gives one — never a URL assembled here from a name, which
    /// is how a tap ends up at the wrong branch of a chain.
    pub open_url: Option<String>,
}

impl Place {
    /// The ranking score — see the module header. `0.0` for anything unrated,
    /// which sorts it below every rated place and leaves distance to break the
    /// tie.
    pub fn legend_score(&self) -> f64 {
        legend_score(self.rating, self.review_count)
    }

    /// Whether this place earns the badge: loved *and* by thousands.
    pub fn is_legendary(&self) -> bool {
        is_legendary(self.rating, self.review_count)
    }

    /// Metres from `origin`, computed against the caller's real fix.
    pub fn distance_m(&self, origin: (f64, f64)) -> f64 {
        haversine_m(origin, (self.lat, self.lon))
    }
}

/// One thing worth knowing about where you are standing.
#[derive(Debug, Clone, PartialEq)]
pub struct TravelFact {
    pub title: String,
    /// Trimmed to card length by [`first_sentences`] — a Wikipedia lead section
    /// is several paragraphs and nobody reads those on a street corner.
    pub text: String,
    pub url: Option<String>,
    /// Where the article's subject is, when it has coordinates.
    pub coord: Option<(f64, f64)>,
}

/// Everything the Travel tab draws, from one coordinate.
#[derive(Debug, Clone, PartialEq)]
pub struct TravelGuide {
    /// A human name for where this is ("Fort, Mumbai"), when a source offered
    /// one. `None` rather than a fabricated label from the coordinates.
    pub area: Option<String>,
    pub eat: Vec<Place>,
    pub things_to_do: Vec<Place>,
    pub facts: Vec<TravelFact>,
    /// Which source supplied the ratings, or `None` when nothing did — the
    /// difference between "no legendary places nearby" and "no ratings
    /// configured", which a UI must not blur.
    pub ratings_from: Option<PlaceSource>,
    /// The cell this was fetched for, so a cache can tell whether the user has
    /// moved somewhere the answer would differ.
    pub cell: String,
    pub fetched_at: u64,
}

impl TravelGuide {
    /// Has this guide aged past [`GUIDE_TTL_SECS`]?
    pub fn is_stale(&self, now_secs: u64) -> bool {
        now_secs.saturating_sub(self.fetched_at) >= GUIDE_TTL_SECS
    }
}

/// What to ask a provider for.
#[derive(Debug, Clone, PartialEq)]
pub struct TravelQuery {
    /// The **coarse** origin — always [`coarse_origin`]'s output, never a raw
    /// fix. Built that way by [`TravelQuery::around`], which is the only
    /// constructor, so a provider cannot be handed a doorway by accident.
    pub lat: f64,
    pub lon: f64,
    pub radius_m: u32,
    pub section: Section,
    /// The cell the origin was rounded to, carried so a cache key and the
    /// query can never disagree.
    pub cell: String,
}

impl TravelQuery {
    /// Build a query around a real GPS fix, blurring it to [`QUERY_PRECISION`]
    /// and clamping the radius to [`MIN_RADIUS_M`]..=[`MAX_RADIUS_M`].
    pub fn around(lat: f64, lon: f64, radius_m: u32, section: Section) -> Self {
        let cell = Geohash::at(lat, lon, QUERY_PRECISION);
        let (clat, clon) = decode_center(cell.as_str()).unwrap_or((lat, lon));
        Self {
            lat: clat,
            lon: clon,
            radius_m: radius_m.clamp(MIN_RADIUS_M, MAX_RADIUS_M),
            section,
            cell: cell.as_str().to_string(),
        }
    }
}

/// What can go wrong on the way to a guide.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum TravelError {
    #[error("travel lookups only speak https")]
    InsecureUrl,
    #[error("travel lookup failed: {0}")]
    Http(String),
    #[error("travel provider returned something unreadable: {0}")]
    Malformed(String),
    #[error("travel provider response exceeded the read cap")]
    TooLarge,
    #[error("no ratings provider is configured — add a Google Places API key")]
    MissingApiKey,
}

/// A user-supplied Google Places key.
///
/// A newtype with no `Debug` derive and no `Display`, so it cannot be printed
/// into a log by a `{:?}` on a struct that happens to hold one. [`Self::parse`]
/// refuses blank input rather than building a key that fails at the far end
/// with an opaque 403 — the "fail fast on missing config" rule in `CLAUDE.md`.
#[derive(Clone, PartialEq, Eq)]
pub struct ApiKey(String);

impl ApiKey {
    pub fn parse(raw: &str) -> Result<Self, TravelError> {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            return Err(TravelError::MissingApiKey);
        }
        Ok(Self(trimmed.to_string()))
    }

    /// The header value. Deliberately named so that reaching for it reads like
    /// what it is at the call site.
    pub fn header_value(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Debug for ApiKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Never the value. A key in a crash report is a key on the internet.
        f.write_str("ApiKey(<redacted>)")
    }
}

// ── Scoring ──────────────────────────────────────────────────────────────────

/// The star average pulled toward [`PRIOR_RATING`] by [`PRIOR_VOTES`] imaginary
/// reviews — the standard "true Bayesian estimate", and the reason a perfect
/// score from four people does not top this list.
pub fn bayesian_rating(rating: f64, votes: u32) -> f64 {
    let v = f64::from(votes);
    (v * rating.clamp(0.0, 5.0) + PRIOR_VOTES * PRIOR_RATING) / (v + PRIOR_VOTES)
}

/// How much the crowd itself counts. Logarithmic, reaching 1.0 at
/// [`LEGEND_MIN_REVIEWS`] and continuing to grow slowly past it, capped so a
/// tourist trap with 400 000 reviews cannot buy its way over a genuinely better
/// place with 8 000.
pub fn crowd_weight(votes: u32) -> f64 {
    let v = f64::from(votes) + 1.0;
    let reference = f64::from(LEGEND_MIN_REVIEWS) + 1.0;
    (v.ln() / reference.ln()).min(1.4)
}

/// The ranking score. `0.0` when either half is missing: an unrated place is
/// not a badly rated one, but it also cannot be ordered against rated ones on
/// merit, so it falls to the distance tie-break in [`rank`].
pub fn legend_score(rating: Option<f64>, review_count: Option<u32>) -> f64 {
    match (rating, review_count) {
        (Some(r), Some(v)) if v > 0 => bayesian_rating(r, v) * crowd_weight(v),
        _ => 0.0,
    }
}

/// The badge. Both floors, deliberately — [`LEGEND_MIN_RATING`] alone would
/// decorate every new café with nine glowing reviews, and
/// [`LEGEND_MIN_REVIEWS`] alone would decorate the airport food court.
pub fn is_legendary(rating: Option<f64>, review_count: Option<u32>) -> bool {
    matches!((rating, review_count), (Some(r), Some(v))
        if r >= LEGEND_MIN_RATING && v >= LEGEND_MIN_REVIEWS)
}

/// Great-circle distance in metres.
pub fn haversine_m(from: (f64, f64), to: (f64, f64)) -> f64 {
    let (lat1, lon1) = (from.0.to_radians(), from.1.to_radians());
    let (lat2, lon2) = (to.0.to_radians(), to.1.to_radians());
    let dlat = lat2 - lat1;
    let dlon = lon2 - lon1;
    let a = (dlat / 2.0).sin().powi(2) + lat1.cos() * lat2.cos() * (dlon / 2.0).sin().powi(2);
    2.0 * EARTH_RADIUS_M * a.sqrt().clamp(0.0, 1.0).asin()
}

/// The coordinate that may leave the device: the centre of the
/// [`QUERY_PRECISION`] cell containing the real fix. See the module header.
pub fn coarse_origin(lat: f64, lon: f64) -> (f64, f64) {
    let cell = Geohash::at(lat, lon, QUERY_PRECISION);
    decode_center(cell.as_str()).unwrap_or((lat, lon))
}

/// Order a section: legendary first, then whatever is closest.
///
/// Truncated to [`MAX_RESULTS`] *after* sorting, so the cap drops the least
/// interesting rows rather than whichever ones a provider happened to return
/// last.
pub fn rank(mut places: Vec<Place>, origin: (f64, f64)) -> Vec<Place> {
    places.sort_by(|a, b| {
        b.legend_score()
            .total_cmp(&a.legend_score())
            .then_with(|| a.distance_m(origin).total_cmp(&b.distance_m(origin)))
            .then_with(|| a.name.cmp(&b.name))
    });
    places.truncate(MAX_RESULTS);
    places
}

// ── Merging what two providers say about one restaurant ──────────────────────

/// Fold a name down to the part two providers agree on: lowercase, accents left
/// alone (they match each other), punctuation and the common decorations
/// dropped.
///
/// Not a fuzzy matcher. It exists to make `"Leopold Cafe & Bar"` and
/// `"Leopold Café and Bar"` collide, and it is paired with a
/// [`SAME_PLACE_M`] proximity check precisely because normalising harder would
/// start merging two branches of the same chain on the same street.
pub fn normalise_name(raw: &str) -> String {
    let lowered = raw.to_lowercase();
    let mut out = String::with_capacity(lowered.len());
    let mut last_space = true;
    for ch in lowered.chars() {
        let mapped = match ch {
            'á' | 'à' | 'â' | 'ä' | 'ã' | 'å' => 'a',
            'é' | 'è' | 'ê' | 'ë' => 'e',
            'í' | 'ì' | 'î' | 'ï' => 'i',
            'ó' | 'ò' | 'ô' | 'ö' | 'õ' => 'o',
            'ú' | 'ù' | 'û' | 'ü' => 'u',
            'ç' => 'c',
            'ñ' => 'n',
            c if c.is_alphanumeric() => c,
            _ => ' ',
        };
        if mapped == ' ' {
            if !last_space {
                out.push(' ');
            }
            last_space = true;
        } else {
            out.push(mapped);
            last_space = false;
        }
    }
    out.split_whitespace()
        .filter(|word| !matches!(*word, "the" | "and" | "restaurant" | "cafe" | "hotel"))
        .collect::<Vec<_>>()
        .join(" ")
        .trim()
        .to_string()
}

/// Are these two records the same physical place?
pub fn same_place(a: &Place, b: &Place) -> bool {
    let a_key = normalise_name(&a.name);
    let b_key = normalise_name(&b.name);
    if a_key.is_empty() || b_key.is_empty() {
        return false;
    }
    a_key == b_key && haversine_m((a.lat, a.lon), (b.lat, b.lon)) <= SAME_PLACE_M
}

/// Merge `enrichment` into `base`, keeping one row per physical place.
///
/// The rating always wins from whichever record has one — that is the whole
/// reason both providers are queried. Everything else prefers the record that
/// already has a value, so an OSM cuisine tag survives a Google row that has
/// none, and a Google `googleMapsUri` survives an OSM row that has none.
///
/// `base` order is preserved and unmatched `enrichment` rows are appended;
/// [`rank`] re-orders afterwards, so this only has to be stable, not sorted.
pub fn merge_places(base: Vec<Place>, enrichment: Vec<Place>) -> Vec<Place> {
    let mut out = base;
    for candidate in enrichment {
        match out
            .iter_mut()
            .find(|existing| same_place(existing, &candidate))
        {
            Some(existing) => {
                if existing.rating.is_none() {
                    existing.rating = candidate.rating;
                    existing.review_count = candidate.review_count;
                    // The rating and the badge have to come from one source, so
                    // whoever supplied the number supplies the attribution too.
                    if candidate.source.carries_ratings() {
                        existing.source = candidate.source;
                        existing.id = candidate.id.clone();
                    }
                }
                if existing.kind == PlaceKind::Other {
                    existing.kind = candidate.kind;
                }
                existing.address = existing.address.take().or(candidate.address);
                existing.cuisine = existing.cuisine.take().or(candidate.cuisine);
                existing.note = existing.note.take().or(candidate.note);
                existing.open_url = existing.open_url.take().or(candidate.open_url);
            }
            None => out.push(candidate),
        }
    }
    out
}

/// Assemble the guide a frontend renders.
///
/// Pure: every argument is already-fetched data, so the whole shape of the
/// screen — which section a place lands in, what order it is in, what the
/// ratings attribution says — is testable without a socket.
///
/// `origin` is the caller's **real** fix (distances are local); `cell` is the
/// coarse cell the queries actually went out for.
pub fn build_guide(
    origin: (f64, f64),
    cell: &str,
    places: Vec<Place>,
    facts: Vec<TravelFact>,
    area: Option<String>,
    now_secs: u64,
) -> TravelGuide {
    let ratings_from = places
        .iter()
        .find(|p| p.rating.is_some() && p.source.carries_ratings())
        .map(|p| p.source);

    let (eat, todo): (Vec<Place>, Vec<Place>) = places
        .into_iter()
        .partition(|p| p.kind.section() == Section::Eat);

    let mut facts = facts;
    facts.truncate(MAX_FACTS);

    TravelGuide {
        area,
        eat: rank(eat, origin),
        things_to_do: rank(todo, origin),
        facts,
        ratings_from,
        cell: cell.to_string(),
        fetched_at: now_secs,
    }
}

/// Words that end in a full stop without ending a sentence. Short and
/// hand-picked rather than exhaustive: the cost of a miss is one fact card that
/// stops a few words early, so a long list would be paying for a rounding error.
const SENTENCE_ABBREVIATIONS: &[&str] = &[
    "st", "mt", "ft", "dr", "mr", "mrs", "ms", "jr", "sr", "prof", "rev", "gen", "col", "capt",
    "no", "vs", "etc", "approx", "est", "ca", "cf", "fig", "vol",
];

/// Keep the first `count` sentences of a Wikipedia lead, so a fact fits on a
/// card — a lead section is several paragraphs, and nobody reads those standing
/// on a street corner.
///
/// A heuristic, and the honest description of it is: a `.`/`!`/`?` ends a
/// sentence when it is followed by whitespace and a capital, unless the word
/// before it is a known abbreviation ([`SENTENCE_ABBREVIATIONS`]) or a single
/// lowercase letter (which covers `e.g.` and `i.e.`). It gets "St. Xavier's"
/// right and it will split "George W. Bush" — a middle initial reads exactly
/// like the end of a sentence, and no rule this size distinguishes them.
/// Truncating a card one clause early is the failure mode worth having.
pub fn first_sentences(text: &str, count: usize) -> String {
    let text = text.trim();
    if count == 0 || text.is_empty() {
        return String::new();
    }
    let chars: Vec<char> = text.chars().collect();
    let mut found = 0usize;
    for (i, ch) in chars.iter().enumerate() {
        if !matches!(ch, '.' | '!' | '?') {
            continue;
        }
        // End of string, or whitespace then a capital: the shape of a break.
        let next_starts_a_sentence = match chars.get(i + 1) {
            None => true,
            Some(c) if c.is_whitespace() => chars[i + 2..]
                .iter()
                .find(|c| !c.is_whitespace())
                .is_none_or(|c| c.is_uppercase() || c.is_numeric() || *c == '"'),
            Some(_) => false,
        };
        if !next_starts_a_sentence || is_abbreviation(&chars[..i]) {
            continue;
        }
        found += 1;
        if found == count {
            return chars[..=i].iter().collect::<String>();
        }
    }
    text.to_string()
}

/// Is the last word of `before` something that carries a full stop of its own?
fn is_abbreviation(before: &[char]) -> bool {
    let word: String = before
        .iter()
        .rev()
        .take_while(|c| c.is_alphanumeric())
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect();
    if word.is_empty() {
        return false;
    }
    // `e.g.` / `i.e.` — a lone lowercase letter is never a word that ends a
    // sentence. A lone *capital* is left alone, because "King George V." is one.
    if word.chars().count() == 1 && word.chars().all(char::is_lowercase) {
        return true;
    }
    SENTENCE_ABBREVIATIONS.contains(&word.to_lowercase().as_str())
}

// ── A cache, so opening the tab twice is not two paid queries ────────────────

/// Last guide per coarse cell.
///
/// Keyed by [`TravelQuery::cell`] rather than by a rounded coordinate: the cell
/// is what the query actually used, so a hit here is a guarantee that the cached
/// answer was fetched for the same circle. Bounded, because a long trip visits a
/// lot of cells and none of them are worth remembering for a day.
#[derive(Debug, Default)]
pub struct GuideCache {
    entries: HashMap<String, TravelGuide>,
    /// Insert order, for the bounded eviction. Small enough that a `Vec` beats
    /// anything cleverer.
    order: Vec<String>,
}

impl GuideCache {
    /// How many cells are remembered before the oldest is dropped.
    pub const CAPACITY: usize = 16;

    pub fn new() -> Self {
        Self::default()
    }

    /// The cached guide for `cell`, if there is one and it has not aged past
    /// [`GUIDE_TTL_SECS`]. A stale entry is *returned as `None`* but left in
    /// place, so a caller whose network is down can still ask for it
    /// explicitly via [`Self::stale`] and show something with a "last checked"
    /// line rather than an empty screen.
    pub fn fresh(&self, cell: &str, now_secs: u64) -> Option<&TravelGuide> {
        self.entries.get(cell).filter(|g| !g.is_stale(now_secs))
    }

    /// Whatever is cached for `cell`, fresh or not.
    pub fn stale(&self, cell: &str) -> Option<&TravelGuide> {
        self.entries.get(cell)
    }

    pub fn put(&mut self, guide: TravelGuide) {
        let cell = guide.cell.clone();
        if self.entries.insert(cell.clone(), guide).is_none() {
            self.order.push(cell);
        }
        while self.order.len() > Self::CAPACITY {
            let oldest = self.order.remove(0);
            self.entries.remove(&oldest);
        }
    }

    /// Forget everything — a vault lock, a panic wipe, or a user who has just
    /// turned the tab off.
    pub fn clear(&mut self) {
        self.entries.clear();
        self.order.clear();
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
}

// ── OpenStreetMap / Overpass ─────────────────────────────────────────────────

/// The public Overpass instance. No key, no account, no per-call cost — which
/// is why it carries the half of this feature that can work for everyone.
pub const OVERPASS_BASE: &str = "https://overpass-api.de/api/interpreter";

/// Build the Overpass QL for one section.
///
/// `node` **and** `way` because a restaurant is often a building polygon rather
/// than a point, and `out center` so a polygon still yields a coordinate. The
/// element cap is on the query rather than applied after parsing: an
/// unbounded Overpass answer in a dense city is megabytes, and the read cap
/// would reject it after paying for the whole download.
pub fn overpass_query(query: &TravelQuery) -> String {
    let filter = match query.section {
        Section::Eat => {
            r#"["amenity"~"^(restaurant|cafe|fast_food|bar|pub|ice_cream|food_court)$"]["name"]"#
        }
        Section::Do => {
            r#"["tourism"~"^(attraction|museum|artwork|viewpoint|gallery|zoo|theme_park)$"]["name"]"#
        }
    };
    // The Do section is two sweeps: `tourism=*` misses parks, beaches, markets
    // and monuments, which are exactly the things somebody means by "what is
    // there to do here".
    let extra = match query.section {
        Section::Eat => String::new(),
        Section::Do => format!(
            concat!(
                r#"  node["historic"]["name"](around:{r},{lat},{lon});"#,
                "\n",
                r#"  way["historic"]["name"](around:{r},{lat},{lon});"#,
                "\n",
                r#"  node["leisure"~"^(park|garden|nature_reserve)$"]["name"](around:{r},{lat},{lon});"#,
                "\n",
                r#"  way["leisure"~"^(park|garden|nature_reserve)$"]["name"](around:{r},{lat},{lon});"#,
                "\n",
                r#"  way["natural"="beach"]["name"](around:{r},{lat},{lon});"#,
                "\n",
                r#"  node["amenity"~"^(marketplace|place_of_worship)$"]["name"](around:{r},{lat},{lon});"#,
                "\n",
            ),
            r = query.radius_m,
            lat = fmt_coord(query.lat),
            lon = fmt_coord(query.lon),
        ),
    };
    format!(
        concat!(
            "[out:json][timeout:25];\n(\n",
            "  node{filter}(around:{r},{lat},{lon});\n",
            "  way{filter}(around:{r},{lat},{lon});\n",
            "{extra}",
            ");\nout center tags {limit};\n"
        ),
        filter = filter,
        r = query.radius_m,
        lat = fmt_coord(query.lat),
        lon = fmt_coord(query.lon),
        extra = extra,
        limit = MAX_RESULTS * 4,
    )
}

/// Six decimal places — about 11 cm, far finer than anything here needs, and
/// short enough that a query is readable in a log.
fn fmt_coord(v: f64) -> String {
    format!("{v:.6}")
}

/// Map OSM tags to a [`PlaceKind`].
pub fn kind_from_osm_tags(tags: &serde_json::Value) -> PlaceKind {
    let tag = |k: &str| tags.get(k).and_then(|v| v.as_str()).unwrap_or_default();
    match tag("amenity") {
        "restaurant" | "food_court" => return PlaceKind::Restaurant,
        // Overwhelmingly a stall or a counter, which is where the legends are.
        "fast_food" => return PlaceKind::StreetFood,
        "cafe" => return PlaceKind::Cafe,
        "bar" | "pub" => return PlaceKind::Bar,
        "ice_cream" => return PlaceKind::Sweets,
        "marketplace" => return PlaceKind::Market,
        "place_of_worship" => return PlaceKind::WorshipSite,
        _ => {}
    }
    if tag("shop") == "bakery" || tag("shop") == "pastry" {
        return PlaceKind::Bakery;
    }
    match tag("tourism") {
        "museum" => return PlaceKind::Museum,
        "gallery" | "artwork" => return PlaceKind::Gallery,
        "viewpoint" => return PlaceKind::Viewpoint,
        "attraction" | "zoo" | "theme_park" => return PlaceKind::Landmark,
        _ => {}
    }
    if !tag("historic").is_empty() {
        return PlaceKind::Landmark;
    }
    if matches!(tag("leisure"), "park" | "garden" | "nature_reserve") {
        return PlaceKind::Park;
    }
    if tag("natural") == "beach" {
        return PlaceKind::Beach;
    }
    PlaceKind::Other
}

/// Read an Overpass JSON answer.
///
/// Elements without a name or without a resolvable coordinate are dropped
/// rather than rendered as a blank row: an unnamed polygon is a data point, not
/// a recommendation.
pub fn parse_overpass(body: &str) -> Result<Vec<Place>, TravelError> {
    let doc: serde_json::Value =
        serde_json::from_str(body).map_err(|e| TravelError::Malformed(e.to_string()))?;
    let Some(elements) = doc.get("elements").and_then(|e| e.as_array()) else {
        // No `elements` key is an empty answer, not a broken one — Overpass
        // omits it for a query that matched nothing.
        return Ok(Vec::new());
    };
    Ok(elements
        .iter()
        .filter_map(|el| {
            let tags = el.get("tags")?;
            let name = tags.get("name")?.as_str()?.trim();
            if name.is_empty() {
                return None;
            }
            // A `way` carries its coordinate under `center` (that is what
            // `out center` is for); a `node` carries it inline.
            let lat = el
                .get("lat")
                .or_else(|| el.get("center").and_then(|c| c.get("lat")))?
                .as_f64()?;
            let lon = el
                .get("lon")
                .or_else(|| el.get("center").and_then(|c| c.get("lon")))?
                .as_f64()?;
            let el_type = el.get("type").and_then(|t| t.as_str()).unwrap_or("node");
            let id = el.get("id").and_then(|i| i.as_i64()).unwrap_or_default();
            let str_tag = |k: &str| {
                tags.get(k)
                    .and_then(|v| v.as_str())
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                    .map(str::to_string)
            };
            Some(Place {
                id: format!("osm:{el_type}/{id}"),
                name: name.to_string(),
                kind: kind_from_osm_tags(tags),
                lat,
                lon,
                rating: None,
                review_count: None,
                address: osm_address(tags),
                cuisine: str_tag("cuisine"),
                note: str_tag("description"),
                source: PlaceSource::OpenStreetMap,
                // OSM's own object page, which is a real canonical link rather
                // than a map search assembled from the name.
                open_url: Some(format!("https://www.openstreetmap.org/{el_type}/{id}")),
            })
        })
        .collect())
}

/// Assemble a one-line address from the `addr:*` tags, when there are enough of
/// them to be useful. A bare house number is not an address.
fn osm_address(tags: &serde_json::Value) -> Option<String> {
    let part = |k: &str| {
        tags.get(k)
            .and_then(|v| v.as_str())
            .map(str::trim)
            .filter(|s| !s.is_empty())
    };
    let street = part("addr:street")?;
    let line = match part("addr:housenumber") {
        Some(number) => format!("{number} {street}"),
        None => street.to_string(),
    };
    Some(match part("addr:city") {
        Some(city) => format!("{line}, {city}"),
        None => line,
    })
}

// ── Google Places (New) ──────────────────────────────────────────────────────

/// `places:searchNearby` on the Places API (New).
pub const GOOGLE_PLACES_BASE: &str = "https://places.googleapis.com/v1/places:searchNearby";

/// The fields the response may contain.
///
/// Places (New) bills by field mask, and an unbounded mask is both expensive
/// and a request for data this feature has no use for. Every field here is
/// rendered on a card; nothing is fetched "in case".
pub const GOOGLE_FIELD_MASK: &str = concat!(
    "places.id,places.displayName,places.formattedAddress,places.location,",
    "places.rating,places.userRatingCount,places.primaryTypeDisplayName,",
    "places.types,places.googleMapsUri,places.editorialSummary"
);

/// The `includedTypes` for a section.
fn google_types(section: Section) -> &'static [&'static str] {
    match section {
        Section::Eat => &["restaurant", "cafe", "bakery", "bar", "meal_takeaway"],
        Section::Do => &[
            "tourist_attraction",
            "museum",
            "art_gallery",
            "park",
            "historical_landmark",
            "market",
        ],
    }
}

/// Build the `searchNearby` request body.
///
/// `rankPreference: POPULARITY` rather than `DISTANCE`, and that is the whole
/// point of the feature: the nearest restaurant is not the question anybody is
/// asking. Distance still decides ties, but locally, in [`rank`].
pub fn google_nearby_body(query: &TravelQuery) -> String {
    let types = google_types(query.section)
        .iter()
        .map(|t| serde_json::Value::String((*t).to_string()))
        .collect::<Vec<_>>();
    let body = serde_json::json!({
        "includedTypes": types,
        // 20 is the API's own maximum for this endpoint.
        "maxResultCount": 20,
        "rankPreference": "POPULARITY",
        "locationRestriction": {
            "circle": {
                "center": { "latitude": query.lat, "longitude": query.lon },
                "radius": f64::from(query.radius_m),
            }
        }
    });
    body.to_string()
}

/// Map Google's `types` array to a [`PlaceKind`], first match wins.
pub fn kind_from_google_types(types: &[&str]) -> PlaceKind {
    for t in types {
        let kind = match *t {
            "restaurant" | "meal_delivery" => PlaceKind::Restaurant,
            "meal_takeaway" | "fast_food_restaurant" => PlaceKind::StreetFood,
            "cafe" | "coffee_shop" => PlaceKind::Cafe,
            "bakery" => PlaceKind::Bakery,
            "bar" | "pub" | "night_club" => PlaceKind::Bar,
            "ice_cream_shop" | "dessert_shop" | "candy_store" => PlaceKind::Sweets,
            "museum" => PlaceKind::Museum,
            "art_gallery" => PlaceKind::Gallery,
            "park" | "national_park" | "garden" => PlaceKind::Park,
            "historical_landmark" | "tourist_attraction" | "monument" => PlaceKind::Landmark,
            "hindu_temple" | "church" | "mosque" | "synagogue" | "place_of_worship" => {
                PlaceKind::WorshipSite
            }
            "market" | "shopping_mall" => PlaceKind::Market,
            _ => continue,
        };
        return kind;
    }
    PlaceKind::Other
}

/// Read a `places:searchNearby` answer.
///
/// A place with no `userRatingCount` keeps `rating: None` as well, even when a
/// star average is present. One without the other cannot be scored — and a
/// star average with an unknown crowd behind it is exactly the number this
/// module exists to stop showing.
pub fn parse_google_places(body: &str) -> Result<Vec<Place>, TravelError> {
    let doc: serde_json::Value =
        serde_json::from_str(body).map_err(|e| TravelError::Malformed(e.to_string()))?;
    // Places (New) reports failures as a JSON `error` object with a 200-shaped
    // body in some proxy configurations, so name it rather than reading the
    // absence of `places` as "nothing nearby".
    if let Some(message) = doc
        .get("error")
        .and_then(|e| e.get("message"))
        .and_then(|m| m.as_str())
    {
        return Err(TravelError::Http(message.to_string()));
    }
    let Some(items) = doc.get("places").and_then(|p| p.as_array()) else {
        return Ok(Vec::new());
    };
    Ok(items
        .iter()
        .filter_map(|item| {
            let name = item
                .get("displayName")
                .and_then(|d| d.get("text"))
                .and_then(|t| t.as_str())
                .map(str::trim)
                .filter(|s| !s.is_empty())?;
            let location = item.get("location")?;
            let lat = location.get("latitude")?.as_f64()?;
            let lon = location.get("longitude")?.as_f64()?;
            let id = item
                .get("id")
                .and_then(|i| i.as_str())
                .unwrap_or(name)
                .to_string();
            let types: Vec<&str> = item
                .get("types")
                .and_then(|t| t.as_array())
                .map(|arr| arr.iter().filter_map(|t| t.as_str()).collect())
                .unwrap_or_default();
            let review_count = item
                .get("userRatingCount")
                .and_then(|c| c.as_u64())
                .and_then(|c| u32::try_from(c).ok())
                .filter(|c| *c > 0);
            let rating = review_count.and_then(|_| {
                item.get("rating")
                    .and_then(|r| r.as_f64())
                    .filter(|r| (0.0..=5.0).contains(r))
            });
            Some(Place {
                id: format!("gm:{id}"),
                name: name.to_string(),
                kind: kind_from_google_types(&types),
                lat,
                lon,
                rating,
                review_count: rating.and(review_count),
                address: item
                    .get("formattedAddress")
                    .and_then(|a| a.as_str())
                    .map(str::to_string),
                cuisine: item
                    .get("primaryTypeDisplayName")
                    .and_then(|p| p.get("text"))
                    .and_then(|t| t.as_str())
                    .map(str::to_string),
                note: item
                    .get("editorialSummary")
                    .and_then(|e| e.get("text"))
                    .and_then(|t| t.as_str())
                    .map(str::to_string),
                source: PlaceSource::GoogleMaps,
                open_url: item
                    .get("googleMapsUri")
                    .and_then(|u| u.as_str())
                    .map(str::to_string),
            })
        })
        .collect())
}

// ── Wikipedia — the "what is this place" half ────────────────────────────────

/// English Wikipedia's action API.
pub const WIKIPEDIA_BASE: &str = "https://en.wikipedia.org/w/api.php";

/// One request for "articles about things near here, with their opening
/// paragraphs".
///
/// `generator=geosearch` + `prop=extracts` in a single call rather than a
/// geosearch followed by N summary fetches: one round trip instead of twenty,
/// and twenty fewer chances to leak the same coordinate again.
pub fn wikipedia_nearby_url(base: &str, query: &TravelQuery) -> String {
    format!(
        "{base}?action=query&format=json&formatversion=2\
&generator=geosearch&ggscoord={lat}%7C{lon}&ggsradius={radius}&ggslimit={limit}\
&prop=extracts%7Ccoordinates&exintro=1&explaintext=1&exlimit={limit}",
        base = base,
        lat = fmt_coord(query.lat),
        lon = fmt_coord(query.lon),
        // Geosearch caps its radius at 10 km regardless of what we ask for.
        radius = query.radius_m.min(10_000),
        limit = MAX_FACTS * 2,
    )
}

/// Read the combined geosearch + extracts answer into facts.
///
/// Pages with an empty extract are dropped: a title with no sentence behind it
/// is a link, and this panel promises a fact.
pub fn parse_wikipedia_nearby(body: &str) -> Result<Vec<TravelFact>, TravelError> {
    let doc: serde_json::Value =
        serde_json::from_str(body).map_err(|e| TravelError::Malformed(e.to_string()))?;
    let Some(pages) = doc
        .get("query")
        .and_then(|q| q.get("pages"))
        .and_then(|p| p.as_array())
    else {
        return Ok(Vec::new());
    };
    Ok(pages
        .iter()
        .filter_map(|page| {
            let title = page.get("title")?.as_str()?.trim();
            let extract = page.get("extract").and_then(|e| e.as_str())?.trim();
            if title.is_empty() || extract.is_empty() {
                return None;
            }
            let coord = page
                .get("coordinates")
                .and_then(|c| c.as_array())
                .and_then(|c| c.first())
                .and_then(|c| Some((c.get("lat")?.as_f64()?, c.get("lon")?.as_f64()?)));
            Some(TravelFact {
                title: title.to_string(),
                text: first_sentences(extract, 2),
                url: Some(format!(
                    "https://en.wikipedia.org/wiki/{}",
                    urlencode(&title.replace(' ', "_"))
                )),
                coord,
            })
        })
        .collect())
}

/// Order facts by how close their subject is, putting the thing the user can
/// see from where they are standing first. Articles with no coordinates keep
/// their upstream order at the end — geosearch already returned them by
/// distance, so this is a refinement, not the only ordering.
pub fn rank_facts(mut facts: Vec<TravelFact>, origin: (f64, f64)) -> Vec<TravelFact> {
    facts.sort_by(|a, b| match (a.coord, b.coord) {
        (Some(a_c), Some(b_c)) => haversine_m(origin, a_c).total_cmp(&haversine_m(origin, b_c)),
        (Some(_), None) => std::cmp::Ordering::Less,
        (None, Some(_)) => std::cmp::Ordering::Greater,
        (None, None) => std::cmp::Ordering::Equal,
    });
    facts.truncate(MAX_FACTS);
    facts
}

/// Percent-encode a URL path/query component. Local for the same reason
/// `catalogue`'s copy is: the only characters that matter are the ones a place
/// name actually contains.
pub fn urlencode(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 3);
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

// ── The socket ───────────────────────────────────────────────────────────────

/// How long any one travel lookup may take.
///
/// Overpass is the slow one and it is the free one; six seconds is roughly its
/// p95 for a query this size. A guide that arrives late is worse than a guide
/// that says "couldn't reach it, pull to retry", because the user is standing
/// on a street.
#[cfg(feature = "travel-http")]
const LOOKUP_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(8);

/// Longest response we will read. An Overpass answer for a dense city block at
/// the element cap is a few hundred kilobytes; this bounds a host that answers
/// with a stream that never ends.
#[cfg(feature = "travel-http")]
const MAX_RESPONSE_BYTES: usize = 2 * 1024 * 1024;

/// The read cap cannot reject a real answer: even at the query's element cap,
/// with generous per-element tags, an Overpass document is an order of
/// magnitude under it. A compile-time invariant rather than a test assertion,
/// matching `catalogue.rs` — raising the cap past the budget fails the build.
#[cfg(feature = "travel-http")]
const _: () = assert!(MAX_RESULTS * 4 * 4096 < MAX_RESPONSE_BYTES);

/// Somewhere places can be looked up.
///
/// A trait rather than three hard-wired calls because the *sources are the
/// policy* here: which provider is asked decides whether ratings exist at all,
/// and a caller that wants an OSM-only, no-third-party-account guide should get
/// one by passing a different provider, not by editing this module.
#[cfg(feature = "travel-http")]
#[allow(async_fn_in_trait)]
pub trait PlaceProvider {
    /// Human name, for attribution and for an error message that says which
    /// provider failed.
    fn name(&self) -> &'static str;

    /// Places near `query`. An empty vec is a real answer.
    async fn nearby(&self, query: &TravelQuery) -> Result<Vec<Place>, TravelError>;
}

/// OpenStreetMap over the public Overpass API. No key, no account.
#[cfg(feature = "travel-http")]
#[derive(Debug, Clone)]
pub struct Overpass {
    base: String,
    user_agent: String,
}

#[cfg(feature = "travel-http")]
impl Overpass {
    /// The public instance.
    pub fn new(user_agent: impl Into<String>) -> Self {
        Self {
            base: OVERPASS_BASE.to_string(),
            user_agent: user_agent.into(),
        }
    }

    /// Point at a mirror, or a test server.
    pub fn with_base(base: impl Into<String>, user_agent: impl Into<String>) -> Self {
        Self {
            base: base.into(),
            user_agent: user_agent.into(),
        }
    }
}

#[cfg(feature = "travel-http")]
impl PlaceProvider for Overpass {
    fn name(&self) -> &'static str {
        "OpenStreetMap"
    }

    async fn nearby(&self, query: &TravelQuery) -> Result<Vec<Place>, TravelError> {
        let body = post(
            &self.base,
            overpass_query(query),
            "text/plain",
            &self.user_agent,
            &[],
        )
        .await?;
        parse_overpass(&body)
    }
}

/// Google Places (New). The only source here with review counts in the
/// thousands, and the only one that needs the user's own API key.
#[cfg(feature = "travel-http")]
#[derive(Debug, Clone)]
pub struct GooglePlaces {
    base: String,
    key: ApiKey,
}

#[cfg(feature = "travel-http")]
impl GooglePlaces {
    pub fn new(key: ApiKey) -> Self {
        Self {
            base: GOOGLE_PLACES_BASE.to_string(),
            key,
        }
    }

    pub fn with_base(base: impl Into<String>, key: ApiKey) -> Self {
        Self {
            base: base.into(),
            key,
        }
    }
}

#[cfg(feature = "travel-http")]
impl PlaceProvider for GooglePlaces {
    fn name(&self) -> &'static str {
        "Google Maps"
    }

    async fn nearby(&self, query: &TravelQuery) -> Result<Vec<Place>, TravelError> {
        let body = post(
            &self.base,
            google_nearby_body(query),
            "application/json",
            "comrade",
            &[
                ("X-Goog-Api-Key", self.key.header_value()),
                ("X-Goog-FieldMask", GOOGLE_FIELD_MASK),
            ],
        )
        .await?;
        parse_google_places(&body)
    }
}

/// Wikipedia articles about things near here.
#[cfg(feature = "travel-http")]
#[derive(Debug, Clone)]
pub struct WikipediaNearby {
    base: String,
    user_agent: String,
}

#[cfg(feature = "travel-http")]
impl WikipediaNearby {
    pub fn new(user_agent: impl Into<String>) -> Self {
        Self {
            base: WIKIPEDIA_BASE.to_string(),
            user_agent: user_agent.into(),
        }
    }

    pub fn with_base(base: impl Into<String>, user_agent: impl Into<String>) -> Self {
        Self {
            base: base.into(),
            user_agent: user_agent.into(),
        }
    }

    /// Facts near `query`, closest first.
    pub async fn facts(&self, query: &TravelQuery) -> Result<Vec<TravelFact>, TravelError> {
        let url = wikipedia_nearby_url(&self.base, query);
        let body = get(&url, &self.user_agent).await?;
        parse_wikipedia_nearby(&body)
    }
}

/// GET a document under the guards `media::fetch_and_decrypt_media` uses:
/// HTTPS only, no redirects, explicit timeout, bounded read.
#[cfg(feature = "travel-http")]
async fn get(url: &str, user_agent: &str) -> Result<String, TravelError> {
    require_https(url)?;
    let client = http_client()?;
    let resp = client
        .get(url)
        .header(reqwest::header::USER_AGENT, user_agent)
        .header(reqwest::header::ACCEPT, "application/json")
        .send()
        .await
        .map_err(|e| TravelError::Http(e.to_string()))?;
    read_bounded(resp).await
}

/// POST under the same guards.
///
/// `headers` carries the Google key, so it is never in the URL: a query-string
/// credential ends up in every proxy log and every crash report between here
/// and the far end.
#[cfg(feature = "travel-http")]
async fn post(
    url: &str,
    body: String,
    content_type: &str,
    user_agent: &str,
    headers: &[(&str, &str)],
) -> Result<String, TravelError> {
    require_https(url)?;
    let client = http_client()?;
    let mut req = client
        .post(url)
        .header(reqwest::header::USER_AGENT, user_agent)
        .header(reqwest::header::CONTENT_TYPE, content_type)
        .header(reqwest::header::ACCEPT, "application/json")
        .body(body);
    for (name, value) in headers {
        req = req.header(*name, *value);
    }
    let resp = req
        .send()
        .await
        .map_err(|e| TravelError::Http(e.to_string()))?;
    read_bounded(resp).await
}

/// Refuse anything that is not `https://` *before* a socket is opened.
#[cfg(feature = "travel-http")]
fn require_https(url: &str) -> Result<(), TravelError> {
    let is_https = url
        .split_once("://")
        .is_some_and(|(scheme, _)| scheme.eq_ignore_ascii_case("https"));
    if is_https {
        Ok(())
    } else {
        Err(TravelError::InsecureUrl)
    }
}

/// Redirects disabled, for the reason the media path disables them: a provider
/// that 302s to `http://` or to a host we never chose would slip past the
/// scheme check that ran on the original URL.
#[cfg(feature = "travel-http")]
fn http_client() -> Result<reqwest::Client, TravelError> {
    reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(LOOKUP_TIMEOUT)
        .build()
        .map_err(|e| TravelError::Http(e.to_string()))
}

/// Bounded streaming read. A missing or lying `Content-Length` must not let a
/// host stream us out of memory.
#[cfg(feature = "travel-http")]
async fn read_bounded(mut resp: reqwest::Response) -> Result<String, TravelError> {
    let status = resp.status();
    if let Some(len) = resp.content_length() {
        if len > MAX_RESPONSE_BYTES as u64 {
            return Err(TravelError::TooLarge);
        }
    }
    let mut buf: Vec<u8> = Vec::new();
    while let Some(chunk) = resp
        .chunk()
        .await
        .map_err(|e| TravelError::Http(e.to_string()))?
    {
        if buf.len() + chunk.len() > MAX_RESPONSE_BYTES {
            return Err(TravelError::TooLarge);
        }
        buf.extend_from_slice(&chunk);
    }
    let text = String::from_utf8(buf).map_err(|e| TravelError::Malformed(e.to_string()))?;
    if !status.is_success() {
        // Google reports a bad or unbilled key here, and that message is the
        // single most useful thing a settings screen can show — so it is
        // carried through rather than flattened to "status 403".
        let detail = serde_json::from_str::<serde_json::Value>(&text)
            .ok()
            .and_then(|v| {
                v.get("error")
                    .and_then(|e| e.get("message"))
                    .and_then(|m| m.as_str())
                    .map(str::to_string)
            })
            .unwrap_or_else(|| format!("status {status}"));
        return Err(TravelError::Http(detail));
    }
    Ok(text)
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    /// Colaba, Mumbai — the fixtures below are shaped around it.
    const ORIGIN: (f64, f64) = (18.9220, 72.8347);

    fn place(name: &str, kind: PlaceKind, rating: Option<f64>, votes: Option<u32>) -> Place {
        Place {
            id: format!("gm:{name}"),
            name: name.to_string(),
            kind,
            lat: ORIGIN.0,
            lon: ORIGIN.1,
            rating,
            review_count: votes,
            address: None,
            cuisine: None,
            note: None,
            source: if rating.is_some() {
                PlaceSource::GoogleMaps
            } else {
                PlaceSource::OpenStreetMap
            },
            open_url: None,
        }
    }

    fn at(mut p: Place, lat: f64, lon: f64) -> Place {
        p.lat = lat;
        p.lon = lon;
        p
    }

    // ── Scoring ──────────────────────────────────────────────────────────

    #[test]
    fn a_perfect_score_from_four_people_loses_to_a_loved_place_with_thousands() {
        // The whole reason this module does not sort on the star average.
        let boutique = legend_score(Some(5.0), Some(4));
        let institution = legend_score(Some(4.6), Some(9_000));
        assert!(
            institution > boutique,
            "5.0/4 scored {boutique}, 4.6/9000 scored {institution}"
        );
    }

    #[test]
    fn among_places_with_a_real_crowd_the_better_rated_one_wins() {
        let good = legend_score(Some(4.7), Some(5_000));
        let ordinary = legend_score(Some(4.1), Some(5_000));
        assert!(good > ordinary);
    }

    #[test]
    fn a_huge_crowd_cannot_buy_its_way_past_a_much_better_place() {
        // The crowd weight is capped, so a mediocre place with 400k reviews (an
        // airport, a chain by a station) does not top a genuinely loved one.
        let tourist_trap = legend_score(Some(3.9), Some(400_000));
        let institution = legend_score(Some(4.7), Some(8_000));
        assert!(
            institution > tourist_trap,
            "trap {tourist_trap} must not beat {institution}"
        );
    }

    #[test]
    fn crowd_weight_reaches_one_at_the_threshold_and_is_capped_above_it() {
        let at_threshold = crowd_weight(LEGEND_MIN_REVIEWS);
        assert!(
            (at_threshold - 1.0).abs() < 0.001,
            "expected ~1.0 at the threshold, got {at_threshold}"
        );
        assert!(crowd_weight(10) < at_threshold);
        assert!(crowd_weight(50_000_000) <= 1.4);
    }

    #[test]
    fn an_unrated_place_scores_zero_rather_than_being_guessed_at() {
        assert_eq!(legend_score(None, Some(9_000)), 0.0);
        assert_eq!(legend_score(Some(4.9), None), 0.0);
        assert_eq!(legend_score(Some(4.9), Some(0)), 0.0);
    }

    #[test]
    fn the_badge_needs_both_a_crowd_and_a_rating() {
        assert!(is_legendary(Some(4.6), Some(12_000)));
        assert!(
            !is_legendary(Some(5.0), Some(9)),
            "nine reviews is not thousands"
        );
        assert!(
            !is_legendary(Some(3.8), Some(40_000)),
            "busy is not the same as loved"
        );
        assert!(!is_legendary(None, Some(40_000)));
    }

    #[test]
    fn a_rating_outside_the_star_range_cannot_inflate_the_score() {
        // A provider that sends 50 instead of 5.0 must not produce a place that
        // outranks everything forever.
        let absurd = bayesian_rating(50.0, 10_000);
        assert!(absurd <= 5.0, "clamped to the star range, got {absurd}");
    }

    // ── Distance and the coarse origin ───────────────────────────────────

    #[test]
    fn haversine_matches_a_known_distance() {
        // Gateway of India → Chhatrapati Shivaji Terminus, ~2.2 km.
        let d = haversine_m((18.9220, 72.8347), (18.9398, 72.8355));
        assert!((1_900.0..2_400.0).contains(&d), "got {d} m");
        assert_eq!(haversine_m(ORIGIN, ORIGIN), 0.0);
    }

    #[test]
    fn the_coordinate_that_leaves_the_device_is_not_the_one_the_phone_has() {
        let (clat, clon) = coarse_origin(ORIGIN.0, ORIGIN.1);
        assert!(
            (clat, clon) != ORIGIN,
            "the coarse origin must not be the raw fix"
        );
        // Still the same block: a ~150 m cell, so never more than its diagonal
        // away, and far inside any legal search radius.
        let moved = haversine_m(ORIGIN, (clat, clon));
        assert!(moved < 200.0, "blurred by {moved} m, which is a new place");
    }

    #[test]
    fn two_fixes_in_one_block_produce_the_same_query() {
        // The privacy claim only holds if the blur actually collapses nearby
        // fixes onto one point — otherwise the cell centre still tracks the
        // user metre by metre.
        let a = TravelQuery::around(ORIGIN.0, ORIGIN.1, 2_000, Section::Eat);
        let b = TravelQuery::around(ORIGIN.0 + 0.00002, ORIGIN.1 + 0.00002, 2_000, Section::Eat);
        assert_eq!(a.cell, b.cell);
        assert_eq!((a.lat, a.lon), (b.lat, b.lon));
    }

    #[test]
    fn a_query_clamps_an_absurd_radius_instead_of_sending_it() {
        assert_eq!(
            TravelQuery::around(ORIGIN.0, ORIGIN.1, 5_000_000, Section::Do).radius_m,
            MAX_RADIUS_M
        );
        assert_eq!(
            TravelQuery::around(ORIGIN.0, ORIGIN.1, 1, Section::Do).radius_m,
            MIN_RADIUS_M
        );
    }

    // ── Ranking and sections ─────────────────────────────────────────────

    #[test]
    fn legendary_places_lead_and_unrated_ones_fall_back_to_distance() {
        let far_unrated = at(
            place("Far Stall", PlaceKind::StreetFood, None, None),
            18.9500,
            72.8400,
        );
        let near_unrated = at(
            place("Near Stall", PlaceKind::StreetFood, None, None),
            18.9222,
            72.8349,
        );
        let legend = place("Britannia", PlaceKind::Restaurant, Some(4.6), Some(9_000));

        let ordered = rank(
            vec![far_unrated, near_unrated.clone(), legend.clone()],
            ORIGIN,
        );
        assert_eq!(ordered[0].name, legend.name, "the legend leads");
        assert_eq!(
            ordered[1].name, near_unrated.name,
            "unrated rows fall back to distance"
        );
        assert_eq!(ordered[2].name, "Far Stall");
    }

    #[test]
    fn ranking_caps_after_ordering_so_the_cap_drops_the_dullest_rows() {
        let mut many: Vec<Place> = (0..MAX_RESULTS + 5)
            .map(|i| place(&format!("Stall {i}"), PlaceKind::StreetFood, None, None))
            .collect();
        // Push the interesting one to the very end of the input.
        many.push(place(
            "Legend",
            PlaceKind::Restaurant,
            Some(4.8),
            Some(20_000),
        ));

        let ordered = rank(many, ORIGIN);
        assert_eq!(ordered.len(), MAX_RESULTS);
        assert_eq!(
            ordered[0].name, "Legend",
            "a cap applied before sorting would have dropped this"
        );
    }

    #[test]
    fn a_bakery_found_by_an_attractions_sweep_still_lands_in_the_eat_section() {
        let guide = build_guide(
            ORIGIN,
            "te7ub5t",
            vec![
                place("Kayani", PlaceKind::Bakery, Some(4.5), Some(6_000)),
                place(
                    "Prince of Wales",
                    PlaceKind::Museum,
                    Some(4.5),
                    Some(30_000),
                ),
            ],
            Vec::new(),
            None,
            1_000,
        );
        assert_eq!(guide.eat.len(), 1);
        assert_eq!(guide.eat[0].name, "Kayani");
        assert_eq!(guide.things_to_do.len(), 1);
        assert_eq!(guide.things_to_do[0].name, "Prince of Wales");
    }

    #[test]
    fn a_guide_with_no_rated_places_says_so_rather_than_looking_empty() {
        // "No ratings configured" and "no legendary places nearby" must not
        // render the same, which is what `ratings_from` exists for.
        let unrated = build_guide(
            ORIGIN,
            "te7ub5t",
            vec![place("Corner Stall", PlaceKind::StreetFood, None, None)],
            Vec::new(),
            None,
            1_000,
        );
        assert_eq!(unrated.ratings_from, None);
        assert_eq!(unrated.eat.len(), 1, "the row is still shown");

        let rated = build_guide(
            ORIGIN,
            "te7ub5t",
            vec![place(
                "Britannia",
                PlaceKind::Restaurant,
                Some(4.6),
                Some(9_000),
            )],
            Vec::new(),
            None,
            1_000,
        );
        assert_eq!(rated.ratings_from, Some(PlaceSource::GoogleMaps));
    }

    #[test]
    fn only_google_is_allowed_to_be_the_ratings_attribution() {
        // A future provider that starts reporting a rating without a crowd
        // behind it must not be able to claim the attribution line.
        assert!(PlaceSource::GoogleMaps.carries_ratings());
        assert!(!PlaceSource::OpenStreetMap.carries_ratings());
        assert!(!PlaceSource::Wikipedia.carries_ratings());
    }

    // ── Merging ──────────────────────────────────────────────────────────

    #[test]
    fn the_same_restaurant_from_two_providers_becomes_one_row_with_the_rating() {
        let osm = Place {
            cuisine: Some("parsi".into()),
            ..at(
                place("Leopold Café and Bar", PlaceKind::Restaurant, None, None),
                18.9220,
                72.8320,
            )
        };
        let google = Place {
            open_url: Some("https://maps.google.com/?cid=1".into()),
            ..at(
                place(
                    "Leopold Cafe & Bar",
                    PlaceKind::Restaurant,
                    Some(4.2),
                    Some(31_000),
                ),
                18.9221,
                72.8321,
            )
        };

        let merged = merge_places(vec![osm], vec![google]);
        assert_eq!(merged.len(), 1, "one restaurant, not two");
        assert_eq!(merged[0].rating, Some(4.2));
        assert_eq!(merged[0].review_count, Some(31_000));
        assert_eq!(
            merged[0].cuisine.as_deref(),
            Some("parsi"),
            "the OSM tag survives a Google row that has none"
        );
        assert_eq!(
            merged[0].source,
            PlaceSource::GoogleMaps,
            "attribution follows the number"
        );
        assert!(merged[0]
            .open_url
            .as_deref()
            .unwrap()
            .contains("maps.google"));
    }

    #[test]
    fn two_branches_of_one_chain_on_the_same_street_stay_two_rows() {
        let a = at(
            place("Cafe Madras", PlaceKind::Restaurant, Some(4.4), Some(9_000)),
            18.9220,
            72.8347,
        );
        // ~900 m away — same name, different restaurant.
        let b = at(
            place("Cafe Madras", PlaceKind::Restaurant, Some(4.1), Some(2_000)),
            18.9300,
            72.8347,
        );
        assert!(!same_place(&a, &b));
        assert_eq!(merge_places(vec![a], vec![b]).len(), 2);
    }

    #[test]
    fn an_unnamed_record_never_merges_with_anything() {
        let blank = place("", PlaceKind::Other, None, None);
        let other = place("", PlaceKind::Other, Some(4.5), Some(5_000));
        assert!(!same_place(&blank, &other), "empty names are not a match");
    }

    #[test]
    fn name_normalisation_collides_the_decorations_and_nothing_else() {
        assert_eq!(
            normalise_name("Leopold Café & Bar"),
            normalise_name("The Leopold Cafe and Bar")
        );
        assert_ne!(normalise_name("Cafe Madras"), normalise_name("Cafe Mysore"));
    }

    // ── Overpass ─────────────────────────────────────────────────────────

    #[test]
    fn the_overpass_query_asks_around_the_coarse_origin_only() {
        let q = TravelQuery::around(ORIGIN.0, ORIGIN.1, 2_000, Section::Eat);
        let ql = overpass_query(&q);
        assert!(ql.contains("[out:json]"));
        assert!(ql.contains("around:2000"));
        assert!(ql.contains("out center tags"));
        assert!(
            !ql.contains(&fmt_coord(ORIGIN.0)),
            "the raw fix must never appear in an outbound query"
        );
        assert!(ql.contains(&fmt_coord(q.lat)));
    }

    #[test]
    fn the_attractions_query_reaches_past_tourism_tags() {
        let ql = overpass_query(&TravelQuery::around(ORIGIN.0, ORIGIN.1, 2_000, Section::Do));
        for tag in ["tourism", "historic", "leisure", "natural", "marketplace"] {
            assert!(ql.contains(tag), "attractions query is missing {tag}");
        }
        let eat = overpass_query(&TravelQuery::around(
            ORIGIN.0,
            ORIGIN.1,
            2_000,
            Section::Eat,
        ));
        assert!(
            !eat.contains("historic"),
            "the food query stays a food query"
        );
    }

    #[test]
    fn an_overpass_answer_is_read_into_places() {
        let body = r#"{
          "elements": [
            {"type":"node","id":1,"lat":18.9221,"lon":72.8348,
             "tags":{"amenity":"restaurant","name":"Britannia & Co.","cuisine":"parsi",
                     "addr:housenumber":"11","addr:street":"Sport Road","addr:city":"Mumbai"}},
            {"type":"way","id":2,"center":{"lat":18.9300,"lon":72.8300},
             "tags":{"tourism":"museum","name":"CSMVS"}},
            {"type":"node","id":3,"lat":18.9,"lon":72.8,"tags":{"amenity":"restaurant"}}
          ]}"#;
        let places = parse_overpass(body).expect("valid document");
        assert_eq!(places.len(), 2, "the unnamed node is dropped");

        let britannia = &places[0];
        assert_eq!(britannia.name, "Britannia & Co.");
        assert_eq!(britannia.kind, PlaceKind::Restaurant);
        assert_eq!(britannia.cuisine.as_deref(), Some("parsi"));
        assert_eq!(britannia.address.as_deref(), Some("11 Sport Road, Mumbai"));
        assert_eq!(britannia.id, "osm:node/1");
        assert_eq!(
            britannia.rating, None,
            "OpenStreetMap does not hold opinions"
        );

        let museum = &places[1];
        assert_eq!(museum.kind, PlaceKind::Museum);
        assert_eq!(
            (museum.lat, museum.lon),
            (18.93, 72.83),
            "a way resolves through `center`"
        );
    }

    #[test]
    fn an_empty_or_odd_overpass_answer_is_no_results_rather_than_an_error() {
        assert!(parse_overpass(r#"{"elements":[]}"#).unwrap().is_empty());
        assert!(parse_overpass(r#"{"version":0.6}"#).unwrap().is_empty());
        assert!(parse_overpass("not json").is_err());
    }

    #[test]
    fn a_fast_food_stall_is_street_food_not_a_restaurant() {
        // The category the legends are actually in — worth pinning, because
        // Google flattens it and a naive mapping would too.
        let tags = serde_json::json!({"amenity": "fast_food", "name": "Bademiya"});
        assert_eq!(kind_from_osm_tags(&tags), PlaceKind::StreetFood);
    }

    // ── Google Places ────────────────────────────────────────────────────

    #[test]
    fn the_google_request_ranks_by_popularity_and_carries_the_coarse_origin() {
        let q = TravelQuery::around(ORIGIN.0, ORIGIN.1, 2_000, Section::Eat);
        let body: serde_json::Value =
            serde_json::from_str(&google_nearby_body(&q)).expect("valid json");
        assert_eq!(body["rankPreference"], "POPULARITY");
        assert_eq!(body["maxResultCount"], 20);
        assert_eq!(body["locationRestriction"]["circle"]["radius"], 2000.0);
        assert_eq!(
            body["locationRestriction"]["circle"]["center"]["latitude"],
            q.lat
        );
        assert_ne!(
            body["locationRestriction"]["circle"]["center"]["latitude"], ORIGIN.0,
            "the raw fix must never be in the request body"
        );
    }

    #[test]
    fn the_field_mask_asks_for_the_review_count_the_ranking_depends_on() {
        assert!(GOOGLE_FIELD_MASK.contains("places.userRatingCount"));
        assert!(GOOGLE_FIELD_MASK.contains("places.rating"));
        assert!(
            !GOOGLE_FIELD_MASK.contains('*'),
            "an unbounded mask is billed and unnecessary"
        );
    }

    #[test]
    fn a_google_response_is_read_into_rated_places() {
        let body = r#"{
          "places": [
            {"id":"ChIJx","displayName":{"text":"Britannia & Co."},
             "formattedAddress":"Ballard Estate, Mumbai","location":{"latitude":18.9337,"longitude":72.8434},
             "rating":4.4,"userRatingCount":11500,"types":["restaurant","food"],
             "primaryTypeDisplayName":{"text":"Parsi restaurant"},
             "editorialSummary":{"text":"Long-running Parsi institution."},
             "googleMapsUri":"https://maps.google.com/?cid=9"},
            {"id":"ChIJy","displayName":{"text":"New Place"},
             "location":{"latitude":18.92,"longitude":72.83},
             "rating":5.0,"types":["cafe"]}
          ]}"#;
        let places = parse_google_places(body).expect("valid document");
        assert_eq!(places.len(), 2);

        let britannia = &places[0];
        assert_eq!(britannia.id, "gm:ChIJx");
        assert_eq!(britannia.rating, Some(4.4));
        assert_eq!(britannia.review_count, Some(11_500));
        assert!(britannia.is_legendary());
        assert_eq!(britannia.kind, PlaceKind::Restaurant);
        assert_eq!(
            britannia.note.as_deref(),
            Some("Long-running Parsi institution.")
        );

        assert_eq!(
            places[1].rating, None,
            "a star average with no crowd behind it is the number this module refuses to show"
        );
    }

    #[test]
    fn a_google_error_payload_is_an_error_not_an_empty_neighbourhood() {
        let body = r#"{"error":{"code":403,"message":"API key not valid"}}"#;
        assert_eq!(
            parse_google_places(body),
            Err(TravelError::Http("API key not valid".into())),
            "a bad key must not read as 'nothing nearby'"
        );
    }

    #[test]
    fn a_blank_api_key_is_refused_before_any_request_is_built() {
        assert_eq!(ApiKey::parse("   "), Err(TravelError::MissingApiKey));
        assert_eq!(ApiKey::parse(" abc ").unwrap().header_value(), "abc");
    }

    #[test]
    fn an_api_key_never_prints_itself() {
        let key = ApiKey::parse("AIzaSy-super-secret").unwrap();
        let shown = format!("{key:?}");
        assert!(
            !shown.contains("super-secret"),
            "a key in a crash report is a key on the internet: {shown}"
        );
    }

    // ── Wikipedia ────────────────────────────────────────────────────────

    #[test]
    fn the_wikipedia_request_is_one_round_trip_for_titles_and_text() {
        let q = TravelQuery::around(ORIGIN.0, ORIGIN.1, 2_000, Section::Do);
        let url = wikipedia_nearby_url(WIKIPEDIA_BASE, &q);
        assert!(url.starts_with("https://"));
        assert!(url.contains("generator=geosearch"));
        assert!(
            url.contains("prop=extracts"),
            "otherwise it is twenty calls"
        );
        assert!(url.contains(&fmt_coord(q.lat)));
        assert!(!url.contains(&fmt_coord(ORIGIN.0)));
    }

    #[test]
    fn the_wikipedia_radius_is_capped_at_what_geosearch_accepts() {
        let q = TravelQuery::around(ORIGIN.0, ORIGIN.1, MAX_RADIUS_M, Section::Do);
        assert!(wikipedia_nearby_url(WIKIPEDIA_BASE, &q).contains("ggsradius=10000"));
    }

    #[test]
    fn a_wikipedia_answer_becomes_card_sized_facts() {
        let body = r#"{"query":{"pages":[
          {"title":"Gateway of India","extract":"The Gateway of India is an arch-monument built in the early 20th century in Mumbai. It was erected to commemorate the landing of King George V. A third sentence nobody reads on a street corner.",
           "coordinates":[{"lat":18.9220,"lon":72.8347}]},
          {"title":"Empty Article","extract":"   "}
        ]}}"#;
        let facts = parse_wikipedia_nearby(body).expect("valid document");
        assert_eq!(facts.len(), 1, "an article with no sentence is not a fact");
        assert!(facts[0].text.ends_with("King George V."));
        assert!(!facts[0].text.contains("third sentence"));
        assert_eq!(
            facts[0].url.as_deref(),
            Some("https://en.wikipedia.org/wiki/Gateway_of_India")
        );
    }

    #[test]
    fn facts_are_ordered_by_what_you_can_see_from_where_you_stand() {
        let near = TravelFact {
            title: "Near".into(),
            text: "x".into(),
            url: None,
            coord: Some((18.9221, 72.8348)),
        };
        let far = TravelFact {
            title: "Far".into(),
            text: "x".into(),
            url: None,
            coord: Some((18.9600, 72.8600)),
        };
        let placeless = TravelFact {
            title: "Nowhere".into(),
            text: "x".into(),
            url: None,
            coord: None,
        };
        let ordered = rank_facts(vec![far, placeless, near], ORIGIN);
        assert_eq!(
            ordered.iter().map(|f| f.title.as_str()).collect::<Vec<_>>(),
            vec!["Near", "Far", "Nowhere"]
        );
    }

    #[test]
    fn an_abbreviation_does_not_end_a_sentence() {
        let text = "St. Xavier's College was founded in 1869. It is in Fort. And more.";
        assert_eq!(
            first_sentences(text, 1),
            "St. Xavier's College was founded in 1869."
        );
        assert_eq!(first_sentences("", 2), "");
        assert_eq!(
            first_sentences("No terminator here", 2),
            "No terminator here"
        );
    }

    // ── Cache ────────────────────────────────────────────────────────────

    #[test]
    fn a_cached_guide_expires_but_is_still_reachable_for_an_offline_screen() {
        let mut cache = GuideCache::new();
        let guide = build_guide(ORIGIN, "te7ub5t", Vec::new(), Vec::new(), None, 1_000);
        cache.put(guide);

        assert!(cache.fresh("te7ub5t", 1_000 + GUIDE_TTL_SECS - 1).is_some());
        assert!(
            cache.fresh("te7ub5t", 1_000 + GUIDE_TTL_SECS).is_none(),
            "past the TTL it is not fresh"
        );
        assert!(
            cache.stale("te7ub5t").is_some(),
            "but a screen with no network can still show it with a 'last checked' line"
        );
        assert!(cache.fresh("somewhere_else", 1_000).is_none());
    }

    #[test]
    fn the_cache_is_bounded_so_a_long_trip_does_not_accumulate_every_block() {
        let mut cache = GuideCache::new();
        for i in 0..GuideCache::CAPACITY + 4 {
            cache.put(build_guide(
                ORIGIN,
                &format!("cell{i}"),
                Vec::new(),
                Vec::new(),
                None,
                1_000,
            ));
        }
        assert_eq!(cache.len(), GuideCache::CAPACITY);
        assert!(
            cache.stale("cell0").is_none(),
            "the oldest cell was evicted"
        );
        assert!(cache
            .stale(&format!("cell{}", GuideCache::CAPACITY + 3))
            .is_some());

        cache.clear();
        assert!(cache.is_empty());
    }

    #[test]
    fn re_fetching_the_same_cell_replaces_rather_than_grows() {
        let mut cache = GuideCache::new();
        cache.put(build_guide(
            ORIGIN,
            "te7ub5t",
            Vec::new(),
            Vec::new(),
            None,
            1_000,
        ));
        cache.put(build_guide(
            ORIGIN,
            "te7ub5t",
            Vec::new(),
            Vec::new(),
            None,
            2_000,
        ));
        assert_eq!(cache.len(), 1);
        assert_eq!(cache.stale("te7ub5t").unwrap().fetched_at, 2_000);
    }

    // ── The socket guards ────────────────────────────────────────────────

    #[cfg(feature = "travel-http")]
    #[tokio::test]
    async fn a_plain_http_provider_is_refused_before_any_request() {
        let overpass = Overpass::with_base("http://overpass.example/api", "comrade-test");
        let err = overpass
            .nearby(&TravelQuery::around(
                ORIGIN.0,
                ORIGIN.1,
                2_000,
                Section::Eat,
            ))
            .await
            .expect_err("http must be refused");
        assert_eq!(err, TravelError::InsecureUrl);

        let wiki = WikipediaNearby::with_base("http://wiki.example/w/api.php", "comrade-test");
        assert_eq!(
            wiki.facts(&TravelQuery::around(ORIGIN.0, ORIGIN.1, 2_000, Section::Do))
                .await
                .expect_err("http must be refused"),
            TravelError::InsecureUrl
        );
    }

    // There is deliberately no test that the read cap exceeds the query's own
    // element budget: the `const _: () = assert!(..)` above `PlaceProvider`
    // does it at compile time, which is strictly stronger, and clippy's
    // `assertions_on_constants` is right that the runtime copy would just be
    // constant-folded.
}
