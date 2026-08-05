//! Profile pictures: what may be fetched, what may be believed, what may be
//! published.
//!
//! A Nostr Kind-0 `picture` is a URL chosen by whoever published the event. For
//! a stranger that is simply an attacker-supplied string, and fetching it makes
//! this device open a connection to a host they picked — which discloses the
//! user's IP and, if the host is on the local network, reaches somewhere the
//! network thought was private. So every guard lives here, and every guard is a
//! *pure function*: the URL policy, the type sniff and the dimension read all run
//! under a plain `cargo test`, and only the socket call in
//! [`crate::media`] is behind the `media-http` feature. Guards that only run
//! under a feature flag are guards nobody runs.
//!
//! The stated policy, in one place:
//!
//! | Guard | Value |
//! | --- | --- |
//! | Scheme | HTTPS only, case-insensitive |
//! | Host | no IP literal that is loopback / private / link-local / unique-local / unspecified / multicast; no `.local` or `.onion`; no bare hostname without a dot |
//! | Userinfo | refused outright — `https://good.example@evil.example/` is a lie about where the bytes come from |
//! | Redirects | disabled (in [`crate::media`], where the client is built) |
//! | Size | [`MAX_AVATAR_BYTES`], checked against `Content-Length` *and* while streaming |
//! | Type | [`AVATAR_MIME_ALLOWLIST`], and the bytes must agree with the header |
//! | Pixels | [`MAX_AVATAR_DIMENSION`] per side, read from the header without decoding |
//!
//! **What this does not close, stated plainly:** a hostname that *resolves* to a
//! private address defeats a host-string check, and so does a DNS rebind between
//! the check and the connection. Closing that means resolving the name here and
//! pinning the socket address (`reqwest::ClientBuilder::resolve`). Until then this
//! narrows the attack to someone who controls a public DNS record, which is a
//! smaller set than "anyone who can publish a Kind-0 event" but is not nobody.
//! Recorded in `AUDIT.md` rather than implied away.

use std::net::IpAddr;

use nostr_sdk::prelude::Url;

/// The largest profile picture that will be fetched or published.
///
/// Two orders of magnitude below [`crate::media::MAX_MEDIA_BYTES`] on purpose. An
/// avatar is fetched on sight — for every peer whose profile is opened, and for
/// every contact a refresh sweep touches — and the whole blob is buffered before
/// anything can be drawn. The media cap is what one deliberate send may cost,
/// which is a different question with a different answer.
pub const MAX_AVATAR_BYTES: usize = 256 * 1024;

/// The largest side length an avatar may claim, read from the image header.
///
/// A 64 KB PNG can legitimately declare 65535×65535, which is four billion
/// pixels — sixteen gigabytes once a platform decoder allocates it. The byte cap
/// does not catch that at all, so the pixel count is its own guard, checked
/// *before* any decoder is handed the bytes.
pub const MAX_AVATAR_DIMENSION: u32 = 2048;

/// The image types an avatar may be.
///
/// An allowlist, not `starts_with("image/")`: the set of formats a platform
/// decoder will attempt is far wider than the set anyone needs for an avatar, and
/// every extra one is another decoder reachable from a URL a peer chose.
///
/// SVG is absent deliberately, and permanently — it is a document that can carry
/// script and fetch remote resources, not a picture. GIF is absent because the
/// only thing it adds over PNG here is an animation nobody asked a contact list
/// to run.
pub const AVATAR_MIME_ALLOWLIST: &[&str] = &["image/jpeg", "image/png", "image/webp"];

/// Why an avatar was refused.
///
/// Its own domain enum rather than a variant on `MediaError`, per the crate's
/// error convention: these are decisions about a picture, and a caller that wants
/// to say *which* guard fired should not have to match on a string.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum AvatarError {
    #[error("refusing to fetch a non-HTTPS profile picture")]
    NotHttps,
    #[error("profile picture URL is not a URL: {0}")]
    Unparseable(String),
    #[error("refusing to fetch a profile picture from a non-public host: {0}")]
    PrivateHost(String),
    #[error("refusing a profile picture URL that carries a username or password")]
    Userinfo,
    #[error("profile picture is {actual} bytes (limit {limit})")]
    TooLarge { actual: usize, limit: usize },
    #[error("profile pictures must be JPEG, PNG or WebP, not {0}")]
    DisallowedType(String),
    #[error("profile picture claims to be {declared} but its bytes are {sniffed}")]
    TypeMismatch { declared: String, sniffed: String },
    #[error("profile picture is {width}x{height}, over the {limit}px limit")]
    TooManyPixels { width: u32, height: u32, limit: u32 },
    #[error("profile picture is not a readable image")]
    Undecodable,
}

/// Vet a peer-published `picture` URL before anything opens a socket to it.
///
/// Fail-closed: anything not positively recognised as a public HTTPS URL is
/// refused. Host parsing is delegated to `url`, which is the load-bearing part —
/// `https://2130706433/`, `https://0x7f.0.0.1/` and `https://[::ffff:127.0.0.1]/`
/// are all spellings of 127.0.0.1 that a string comparison waves straight
/// through, and `Url::host()` normalises every one of them to an `IpAddr` this
/// can then reject.
pub fn vet_avatar_url(raw: &str) -> Result<Url, AvatarError> {
    let url = Url::parse(raw.trim()).map_err(|e| AvatarError::Unparseable(e.to_string()))?;
    if !url.scheme().eq_ignore_ascii_case("https") {
        return Err(AvatarError::NotHttps);
    }
    // Credentials in a picture URL have no legitimate use and are a way to make
    // the prominent part of a rendered URL disagree with the host it resolves to.
    if !url.username().is_empty() || url.password().is_some() {
        return Err(AvatarError::Userinfo);
    }
    match url.host() {
        Some(nostr_sdk::prelude::url::Host::Ipv4(ip)) => reject_private(IpAddr::V4(ip))?,
        Some(nostr_sdk::prelude::url::Host::Ipv6(ip)) => reject_private(IpAddr::V6(ip))?,
        Some(nostr_sdk::prelude::url::Host::Domain(name)) => reject_private_domain(name)?,
        None => return Err(AvatarError::PrivateHost("no host".into())),
    }
    Ok(url)
}

/// Refuse an IP literal that is not a public address.
fn reject_private(ip: IpAddr) -> Result<(), AvatarError> {
    let private = match ip {
        IpAddr::V4(v4) => {
            v4.is_loopback()
                || v4.is_private()
                || v4.is_link_local()
                || v4.is_unspecified()
                || v4.is_multicast()
                || v4.is_broadcast()
                || v4.is_documentation()
                // 100.64.0.0/10, carrier-grade NAT — `is_shared` is unstable, so
                // spell it out rather than wait for it.
                || (v4.octets()[0] == 100 && (v4.octets()[1] & 0b1100_0000) == 64)
                // 0.0.0.0/8 "this network" — is_unspecified only covers 0.0.0.0.
                || v4.octets()[0] == 0
        }
        IpAddr::V6(v6) => {
            v6.is_loopback()
                || v6.is_unspecified()
                || v6.is_multicast()
                // Unique-local fc00::/7 and link-local fe80::/10. `is_unique_local`
                // and `is_unicast_link_local` are unstable, so test the prefixes.
                || (v6.segments()[0] & 0xfe00) == 0xfc00
                || (v6.segments()[0] & 0xffc0) == 0xfe80
                // An IPv4-mapped or -compatible address is an IPv4 address wearing
                // a hat; judge it as one, or ::ffff:127.0.0.1 walks through.
                || v6
                    .to_ipv4_mapped()
                    .or_else(|| v6.to_ipv4())
                    .is_some_and(|v4| reject_private(IpAddr::V4(v4)).is_err())
        }
    };
    if private {
        return Err(AvatarError::PrivateHost(ip.to_string()));
    }
    Ok(())
}

/// Refuse a hostname that names something other than the public internet.
///
/// A bare label with no dot (`http://intranet/`) resolves through the local
/// search domain, which is the same reachability problem as a private IP with
/// none of the syntax that would reveal it.
fn reject_private_domain(name: &str) -> Result<(), AvatarError> {
    let host = name.trim_end_matches('.').to_ascii_lowercase();
    if host.is_empty() {
        return Err(AvatarError::PrivateHost("empty host".into()));
    }
    let refused = host == "localhost"
        || host.ends_with(".localhost")
        || host.ends_with(".local")
        || host.ends_with(".onion")
        || host.ends_with(".internal")
        || host.ends_with(".home.arpa")
        || !host.contains('.');
    if refused {
        return Err(AvatarError::PrivateHost(host));
    }
    Ok(())
}

/// The image type the *bytes* actually are, by magic number, or `None`.
///
/// Never the server's `Content-Type` alone: that header is chosen by the same
/// person who chose the URL, and a decoder handed bytes of a format it was not
/// told about is exactly the shape of the image-parsing bugs that get CVEs.
pub fn sniff_image_mime(bytes: &[u8]) -> Option<&'static str> {
    if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        return Some("image/jpeg");
    }
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        return Some("image/png");
    }
    // RIFF....WEBP — the four size bytes in the middle are not ours to check.
    if bytes.len() >= 12 && bytes.starts_with(b"RIFF") && &bytes[8..12] == b"WEBP" {
        return Some("image/webp");
    }
    None
}

/// Intrinsic pixel dimensions, read from the header only.
///
/// Total on hostile input: every read is length-checked, there is no indexing
/// that can panic, and a truncated or malformed header is an error rather than a
/// slice out of bounds. It decodes nothing and allocates nothing — the point is
/// to decide whether a decoder should ever see these bytes.
pub fn image_dimensions(bytes: &[u8], mime: &str) -> Result<(u32, u32), AvatarError> {
    match mime {
        "image/png" => png_dimensions(bytes),
        "image/jpeg" => jpeg_dimensions(bytes),
        "image/webp" => webp_dimensions(bytes),
        other => Err(AvatarError::DisallowedType(other.to_string())),
    }
}

/// PNG: width and height are the first two big-endian u32s of the IHDR chunk,
/// which the spec requires to be first, at a fixed offset.
fn png_dimensions(bytes: &[u8]) -> Result<(u32, u32), AvatarError> {
    // 8 signature + 4 length + 4 "IHDR" + 4 width + 4 height
    if bytes.len() < 24 || &bytes[12..16] != b"IHDR" {
        return Err(AvatarError::Undecodable);
    }
    Ok((be_u32(bytes, 16)?, be_u32(bytes, 20)?))
}

/// JPEG: walk the marker segments to the first start-of-frame, whose payload
/// carries height then width. Bounded by the buffer, so a segment length that
/// runs off the end stops the walk instead of indexing past it.
fn jpeg_dimensions(bytes: &[u8]) -> Result<(u32, u32), AvatarError> {
    let mut i = 2; // past SOI
    while i + 3 < bytes.len() {
        if bytes[i] != 0xFF {
            // Not at a marker: a fill byte or a malformed stream. Skip one byte
            // rather than trusting a length we have not validated.
            i += 1;
            continue;
        }
        let marker = bytes[i + 1];
        // Standalone markers carry no length.
        if marker == 0xD8 || marker == 0x01 || (0xD0..=0xD7).contains(&marker) || marker == 0xFF {
            i += 2;
            continue;
        }
        let len = be_u16(bytes, i + 2)? as usize;
        if len < 2 {
            return Err(AvatarError::Undecodable);
        }
        // SOF0..SOF15, excluding the two that are not frame headers.
        let is_sof =
            (0xC0..=0xCF).contains(&marker) && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
        if is_sof {
            // payload: precision(1) height(2) width(2)
            let h = be_u16(bytes, i + 5)? as u32;
            let w = be_u16(bytes, i + 7)? as u32;
            return Ok((w, h));
        }
        // Start of scan: pixel data begins, no frame header will follow.
        if marker == 0xDA {
            break;
        }
        i = i
            .checked_add(2)
            .and_then(|v| v.checked_add(len))
            .ok_or(AvatarError::Undecodable)?;
    }
    Err(AvatarError::Undecodable)
}

/// WebP: three container shapes (lossy VP8, lossless VP8L, extended VP8X), each
/// storing the size differently and none of them the same as the others.
fn webp_dimensions(bytes: &[u8]) -> Result<(u32, u32), AvatarError> {
    if bytes.len() < 16 {
        return Err(AvatarError::Undecodable);
    }
    match &bytes[12..16] {
        b"VP8X" => {
            // 24-bit little-endian canvas size minus one, at offset 24.
            if bytes.len() < 30 {
                return Err(AvatarError::Undecodable);
            }
            let w = le_u24(bytes, 24)? + 1;
            let h = le_u24(bytes, 27)? + 1;
            Ok((w, h))
        }
        b"VP8L" => {
            // 14 bits each, packed into the 32 bits at offset 21.
            if bytes.len() < 25 {
                return Err(AvatarError::Undecodable);
            }
            let bits = u32::from_le_bytes([bytes[21], bytes[22], bytes[23], bytes[24]]);
            Ok(((bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1))
        }
        b"VP8 " => {
            // 16-bit little-endian, 14 significant bits, after the 3-byte start
            // code at offset 23.
            if bytes.len() < 30 {
                return Err(AvatarError::Undecodable);
            }
            Ok((
                (le_u16(bytes, 26)? & 0x3FFF) as u32,
                (le_u16(bytes, 28)? & 0x3FFF) as u32,
            ))
        }
        _ => Err(AvatarError::Undecodable),
    }
}

fn be_u32(b: &[u8], at: usize) -> Result<u32, AvatarError> {
    let s = b.get(at..at + 4).ok_or(AvatarError::Undecodable)?;
    Ok(u32::from_be_bytes([s[0], s[1], s[2], s[3]]))
}

fn be_u16(b: &[u8], at: usize) -> Result<u16, AvatarError> {
    let s = b.get(at..at + 2).ok_or(AvatarError::Undecodable)?;
    Ok(u16::from_be_bytes([s[0], s[1]]))
}

fn le_u16(b: &[u8], at: usize) -> Result<u16, AvatarError> {
    let s = b.get(at..at + 2).ok_or(AvatarError::Undecodable)?;
    Ok(u16::from_le_bytes([s[0], s[1]]))
}

fn le_u24(b: &[u8], at: usize) -> Result<u32, AvatarError> {
    let s = b.get(at..at + 3).ok_or(AvatarError::Undecodable)?;
    Ok(u32::from(s[0]) | (u32::from(s[1]) << 8) | (u32::from(s[2]) << 16))
}

/// The whole acceptance decision for a downloaded (or picked) avatar, in one
/// place: size, declared type, actual type, and pixel count.
///
/// Returns the *sniffed* type, which is the one a caller should record — the
/// declared one is a claim, and the two having matched is what makes the claim
/// worth anything.
pub fn vet_avatar_bytes(bytes: &[u8], declared: Option<&str>) -> Result<&'static str, AvatarError> {
    if bytes.len() > MAX_AVATAR_BYTES {
        return Err(AvatarError::TooLarge {
            actual: bytes.len(),
            limit: MAX_AVATAR_BYTES,
        });
    }
    let sniffed = sniff_image_mime(bytes).ok_or(AvatarError::Undecodable)?;
    if !AVATAR_MIME_ALLOWLIST.contains(&sniffed) {
        return Err(AvatarError::DisallowedType(sniffed.to_string()));
    }
    if let Some(declared) = declared {
        let declared = declared
            .split(';')
            .next()
            .unwrap_or_default()
            .trim()
            .to_ascii_lowercase();
        // An absent or empty header is not a mismatch — plenty of hosts omit it.
        // A *present and different* one is: the bytes and the label disagree, and
        // only one of them is going to a decoder.
        if !declared.is_empty() && declared != sniffed {
            return Err(AvatarError::TypeMismatch {
                declared,
                sniffed: sniffed.to_string(),
            });
        }
    }
    let (width, height) = image_dimensions(bytes, sniffed)?;
    if width == 0 || height == 0 {
        return Err(AvatarError::Undecodable);
    }
    if width > MAX_AVATAR_DIMENSION || height > MAX_AVATAR_DIMENSION {
        return Err(AvatarError::TooManyPixels {
            width,
            height,
            limit: MAX_AVATAR_DIMENSION,
        });
    }
    Ok(sniffed)
}

/// Strip every ancillary chunk from a PNG, keeping only what is needed to render
/// it: `IHDR`, `PLTE`, `tRNS`, `IDAT`, `IEND`.
///
/// This is why publishing is PNG-only. An avatar goes out **unencrypted and
/// public** (see `upload_public_blob`), and a JPEG straight off a camera roll
/// carries EXIF that routinely includes GPS coordinates — so "set your picture"
/// would quietly publish where the photo was taken, permanently, to anyone who
/// ever opens the profile. Dropping ancillary chunks from a PNG is small, total
/// and testable; an equivalent JPEG APPn stripper is a bigger piece of
/// hostile-input parsing and is deliberately out of scope rather than half-done.
///
/// Incoming pictures are unaffected: a peer's JPEG or WebP still renders.
pub fn strip_png_ancillary(bytes: &[u8]) -> Result<Vec<u8>, AvatarError> {
    const SIGNATURE: &[u8] = b"\x89PNG\r\n\x1a\n";
    const KEEP: [&[u8; 4]; 5] = [b"IHDR", b"PLTE", b"tRNS", b"IDAT", b"IEND"];
    if !bytes.starts_with(SIGNATURE) {
        return Err(AvatarError::Undecodable);
    }
    let mut out = Vec::with_capacity(bytes.len());
    out.extend_from_slice(SIGNATURE);
    let mut i = SIGNATURE.len();
    let mut saw_ihdr = false;
    let mut saw_iend = false;
    while i < bytes.len() {
        let len = be_u32(bytes, i)? as usize;
        let kind = bytes.get(i + 4..i + 8).ok_or(AvatarError::Undecodable)?;
        // length + type + data + crc, and the whole of it must be present.
        let end = i
            .checked_add(12)
            .and_then(|v| v.checked_add(len))
            .ok_or(AvatarError::Undecodable)?;
        if end > bytes.len() {
            return Err(AvatarError::Undecodable);
        }
        let keep = KEEP.iter().any(|k| k.as_slice() == kind);
        if kind == b"IHDR" {
            saw_ihdr = true;
        }
        if keep {
            out.extend_from_slice(&bytes[i..end]);
        }
        if kind == b"IEND" {
            saw_iend = true;
            break;
        }
        i = end;
    }
    if !saw_ihdr || !saw_iend {
        return Err(AvatarError::Undecodable);
    }
    Ok(out)
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    // ── URL policy ───────────────────────────────────────────────────────────

    #[test]
    fn only_https_is_fetched() {
        assert!(vet_avatar_url("https://example.com/a.png").is_ok());
        assert_eq!(
            vet_avatar_url("http://example.com/a.png"),
            Err(AvatarError::NotHttps)
        );
        // Schemes are case-insensitive; the guard must not be.
        assert!(vet_avatar_url("HTTPS://example.com/a.png").is_ok());
        for bad in [
            "file:///etc/passwd",
            "ftp://example.com/a.png",
            "javascript:alert(1)",
            "data:image/png;base64,AAAA",
        ] {
            assert!(
                matches!(
                    vet_avatar_url(bad),
                    Err(AvatarError::NotHttps) | Err(AvatarError::Unparseable(_))
                ),
                "{bad} was not refused"
            );
        }
    }

    #[test]
    fn nonsense_is_refused_rather_than_guessed_at() {
        for bad in ["", "   ", "not a url", "//example.com/a.png", "https://"] {
            assert!(vet_avatar_url(bad).is_err(), "{bad:?} was accepted");
        }
    }

    #[test]
    fn a_url_carrying_credentials_is_refused() {
        // `https://good.example@evil.example/` renders with the wrong part
        // prominent in more UIs than not.
        assert_eq!(
            vet_avatar_url("https://user:pass@example.com/a.png"),
            Err(AvatarError::Userinfo)
        );
        assert_eq!(
            vet_avatar_url("https://user@example.com/a.png"),
            Err(AvatarError::Userinfo)
        );
    }

    #[test]
    fn loopback_and_private_addresses_are_refused() {
        for bad in [
            "https://127.0.0.1/a.png",
            "https://10.0.0.1/a.png",
            "https://192.168.1.1/a.png",
            "https://172.16.0.1/a.png",
            "https://169.254.169.254/latest/meta-data/", // the cloud-metadata one
            "https://0.0.0.0/a.png",
            "https://100.64.0.1/a.png",
            "https://[::1]/a.png",
            "https://[fd00::1]/a.png",
            "https://[fe80::1]/a.png",
        ] {
            assert!(
                matches!(vet_avatar_url(bad), Err(AvatarError::PrivateHost(_))),
                "{bad} was not refused"
            );
        }
    }

    #[test]
    fn the_alternate_spellings_of_localhost_are_refused_too() {
        // These are the bypasses a string comparison against "127.0.0.1" misses,
        // and the reason host parsing is delegated to `url` rather than hand-rolled.
        for bad in [
            "https://2130706433/a.png",         // decimal
            "https://0x7f.0.0.1/a.png",         // hex octet
            "https://127.1/a.png",              // short form
            "https://[::ffff:127.0.0.1]/a.png", // IPv4-mapped IPv6
        ] {
            assert!(
                matches!(vet_avatar_url(bad), Err(AvatarError::PrivateHost(_))),
                "{bad} was not refused — a string check would have missed it too"
            );
        }
    }

    #[test]
    fn hostnames_that_do_not_name_the_public_internet_are_refused() {
        for bad in [
            "https://localhost/a.png",
            "https://intranet/a.png", // no dot: resolves through the search domain
            "https://printer.local/a.png",
            "https://x.onion/a.png",
            "https://db.internal/a.png",
            "https://thing.home.arpa/a.png",
            "https://EXAMPLE.LOCAL/a.png",  // casing is not a bypass
            "https://example.local./a.png", // nor is a trailing root dot
        ] {
            assert!(
                matches!(vet_avatar_url(bad), Err(AvatarError::PrivateHost(_))),
                "{bad} was not refused"
            );
        }
    }

    #[test]
    fn an_ordinary_public_url_survives_all_of_it() {
        for good in [
            "https://example.com/a.png",
            "https://cdn.example.co.uk/avatars/1.jpg",
            "https://example.com:8443/a.png",
            "https://8.8.8.8/a.png", // a public IP literal is fine
        ] {
            assert!(vet_avatar_url(good).is_ok(), "{good} was refused");
        }
    }

    // ── Type sniffing ────────────────────────────────────────────────────────

    fn png(width: u32, height: u32) -> Vec<u8> {
        let mut v = b"\x89PNG\r\n\x1a\n".to_vec();
        v.extend_from_slice(&13u32.to_be_bytes());
        v.extend_from_slice(b"IHDR");
        v.extend_from_slice(&width.to_be_bytes());
        v.extend_from_slice(&height.to_be_bytes());
        v.extend_from_slice(&[8, 6, 0, 0, 0]); // bit depth, colour type, …
        v.extend_from_slice(&[0, 0, 0, 0]); // crc, unchecked here
        v
    }

    /// A PNG with the chunks a real encoder emits, including two we must drop.
    fn png_with_chunks() -> Vec<u8> {
        let mut v = png(4, 4);
        for (kind, data) in [
            (b"eXIf".as_slice(), b"GPS-here".as_slice()),
            (b"tEXt".as_slice(), b"Comment\0hi".as_slice()),
            (b"IDAT".as_slice(), b"pixels".as_slice()),
        ] {
            v.extend_from_slice(&(data.len() as u32).to_be_bytes());
            v.extend_from_slice(kind);
            v.extend_from_slice(data);
            v.extend_from_slice(&[0, 0, 0, 0]);
        }
        v.extend_from_slice(&0u32.to_be_bytes());
        v.extend_from_slice(b"IEND");
        v.extend_from_slice(&[0, 0, 0, 0]);
        v
    }

    fn jpeg(width: u16, height: u16) -> Vec<u8> {
        let mut v = vec![0xFF, 0xD8]; // SOI
        v.extend_from_slice(&[0xFF, 0xC0]); // SOF0
        v.extend_from_slice(&11u16.to_be_bytes());
        v.push(8);
        v.extend_from_slice(&height.to_be_bytes());
        v.extend_from_slice(&width.to_be_bytes());
        v.extend_from_slice(&[1, 1, 0x11, 0]);
        v
    }

    fn webp_vp8x(width: u32, height: u32) -> Vec<u8> {
        let mut v = b"RIFF".to_vec();
        v.extend_from_slice(&0u32.to_le_bytes());
        v.extend_from_slice(b"WEBP");
        v.extend_from_slice(b"VP8X");
        v.extend_from_slice(&10u32.to_le_bytes());
        v.extend_from_slice(&[0, 0, 0, 0]); // flags + reserved
        let w = width - 1;
        let h = height - 1;
        v.extend_from_slice(&w.to_le_bytes()[..3]);
        v.extend_from_slice(&h.to_le_bytes()[..3]);
        v
    }

    #[test]
    fn the_three_allowed_formats_are_recognised_by_their_bytes() {
        assert_eq!(sniff_image_mime(&png(1, 1)), Some("image/png"));
        assert_eq!(sniff_image_mime(&jpeg(1, 1)), Some("image/jpeg"));
        assert_eq!(sniff_image_mime(&webp_vp8x(1, 1)), Some("image/webp"));
    }

    #[test]
    fn things_that_are_not_images_are_not_mistaken_for_images() {
        for bytes in [
            b"<svg xmlns='http://www.w3.org/2000/svg'></svg>".as_slice(),
            b"<!DOCTYPE html><html></html>".as_slice(),
            b"PK\x03\x04".as_slice(), // zip
            b"\x7fELF".as_slice(),    // executable
            b"GIF89a".as_slice(),     // a real image, but not an allowed one
            b"".as_slice(),
            b"\xFF".as_slice(),
            b"RIFF____NOPE".as_slice(), // right container, wrong payload
        ] {
            assert_eq!(
                sniff_image_mime(bytes),
                None,
                "{bytes:?} sniffed as an image"
            );
        }
    }

    #[test]
    fn a_declared_type_that_disagrees_with_the_bytes_is_refused() {
        // The header is chosen by whoever chose the URL. A decoder handed bytes of
        // a format it was not told about is the shape of the image CVEs.
        assert_eq!(
            vet_avatar_bytes(&jpeg(8, 8), Some("image/png")),
            Err(AvatarError::TypeMismatch {
                declared: "image/png".into(),
                sniffed: "image/jpeg".into()
            })
        );
    }

    #[test]
    fn a_missing_or_parameterised_content_type_is_tolerated() {
        assert_eq!(vet_avatar_bytes(&png(8, 8), None), Ok("image/png"));
        assert_eq!(vet_avatar_bytes(&png(8, 8), Some("")), Ok("image/png"));
        assert_eq!(
            vet_avatar_bytes(&png(8, 8), Some("image/png; charset=binary")),
            Ok("image/png"),
            "a parameter is not a different type"
        );
        assert_eq!(
            vet_avatar_bytes(&png(8, 8), Some("IMAGE/PNG")),
            Ok("image/png")
        );
    }

    #[test]
    fn dimensions_are_read_from_each_container_shape() {
        assert_eq!(
            image_dimensions(&png(320, 240), "image/png"),
            Ok((320, 240))
        );
        assert_eq!(
            image_dimensions(&jpeg(320, 240), "image/jpeg"),
            Ok((320, 240))
        );
        assert_eq!(
            image_dimensions(&webp_vp8x(320, 240), "image/webp"),
            Ok((320, 240))
        );
    }

    #[test]
    fn an_image_bomb_is_refused_on_its_header_alone() {
        // 65535x65535 is four billion pixels — sixteen gigabytes decoded — inside a
        // file small enough to pass the byte cap without noticing.
        let bomb = png(65535, 65535);
        assert!(
            bomb.len() < MAX_AVATAR_BYTES,
            "the byte cap would not have caught this"
        );
        assert_eq!(
            vet_avatar_bytes(&bomb, Some("image/png")),
            Err(AvatarError::TooManyPixels {
                width: 65535,
                height: 65535,
                limit: MAX_AVATAR_DIMENSION
            })
        );
    }

    #[test]
    fn a_zero_dimension_image_is_refused() {
        assert_eq!(
            vet_avatar_bytes(&png(0, 8), None),
            Err(AvatarError::Undecodable)
        );
        assert_eq!(
            vet_avatar_bytes(&png(8, 0), None),
            Err(AvatarError::Undecodable)
        );
    }

    #[test]
    fn an_oversize_blob_is_refused_before_it_is_parsed() {
        let big = vec![0u8; MAX_AVATAR_BYTES + 1];
        assert_eq!(
            vet_avatar_bytes(&big, None),
            Err(AvatarError::TooLarge {
                actual: MAX_AVATAR_BYTES + 1,
                limit: MAX_AVATAR_BYTES
            })
        );
    }

    #[test]
    fn an_image_exactly_at_the_cap_is_accepted() {
        let mut exact = png(8, 8);
        exact.resize(MAX_AVATAR_BYTES, 0);
        assert_eq!(vet_avatar_bytes(&exact, None), Ok("image/png"));
    }

    // ── Totality on hostile input ────────────────────────────────────────────

    #[test]
    fn truncating_a_valid_image_at_every_length_never_panics() {
        for full in [png(64, 64), jpeg(64, 64), webp_vp8x(64, 64)] {
            let mime = sniff_image_mime(&full).unwrap();
            for cut in 0..full.len() {
                // The only requirement is that it returns. A truncated header may
                // legitimately still yield dimensions if the size bytes survived.
                let _ = image_dimensions(&full[..cut], mime);
                let _ = vet_avatar_bytes(&full[..cut], None);
            }
        }
    }

    #[test]
    fn pseudo_random_buffers_are_refused_without_panicking() {
        // A cheap deterministic LCG — no dependency, and the same bytes every run,
        // so a failure is reproducible rather than a flake.
        let mut state: u64 = 0x2545_F491_4F6C_DD1D;
        for _ in 0..512 {
            let len = (state % 96) as usize;
            let mut buf = Vec::with_capacity(len);
            for _ in 0..len {
                state = state
                    .wrapping_mul(6364136223846793005)
                    .wrapping_add(1442695040888963407);
                buf.push((state >> 33) as u8);
            }
            for mime in ["image/png", "image/jpeg", "image/webp"] {
                let _ = image_dimensions(&buf, mime);
            }
            let _ = vet_avatar_bytes(&buf, None);
            let _ = strip_png_ancillary(&buf);
        }
    }

    #[test]
    fn a_jpeg_whose_segment_length_runs_off_the_end_is_an_error_not_a_panic() {
        let mut bad = vec![0xFF, 0xD8, 0xFF, 0xE0];
        bad.extend_from_slice(&60000u16.to_be_bytes()); // length far past the buffer
        bad.extend_from_slice(&[0u8; 8]);
        assert_eq!(jpeg_dimensions(&bad), Err(AvatarError::Undecodable));
    }

    #[test]
    fn a_jpeg_declaring_a_zero_length_segment_terminates() {
        // len < 2 is malformed; treating it as a stride would loop forever.
        let bad = vec![0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x00, 0x00, 0x00];
        assert_eq!(jpeg_dimensions(&bad), Err(AvatarError::Undecodable));
    }

    #[test]
    fn an_unknown_mime_is_not_dispatched_to_a_parser() {
        assert_eq!(
            image_dimensions(&png(8, 8), "image/gif"),
            Err(AvatarError::DisallowedType("image/gif".into()))
        );
    }

    // ── PNG stripping ────────────────────────────────────────────────────────

    #[test]
    fn stripping_a_png_drops_exif_and_text_but_keeps_the_picture() {
        let original = png_with_chunks();
        assert!(
            original.windows(4).any(|w| w == b"eXIf"),
            "the fixture has to contain what we are testing the removal of"
        );
        let stripped = strip_png_ancillary(&original).unwrap();
        assert!(
            !stripped.windows(4).any(|w| w == b"eXIf"),
            "an avatar is published unencrypted and public; EXIF routinely carries GPS"
        );
        assert!(!stripped.windows(4).any(|w| w == b"tEXt"));
        assert!(stripped.windows(4).any(|w| w == b"IHDR"));
        assert!(stripped.windows(4).any(|w| w == b"IDAT"));
        assert!(stripped.windows(4).any(|w| w == b"IEND"));
        assert!(stripped.len() < original.len());
    }

    #[test]
    fn a_stripped_png_still_reads_as_the_same_picture() {
        let original = png_with_chunks();
        let stripped = strip_png_ancillary(&original).unwrap();
        assert_eq!(sniff_image_mime(&stripped), Some("image/png"));
        assert_eq!(
            image_dimensions(&stripped, "image/png"),
            image_dimensions(&original, "image/png"),
            "stripping must not change the image, only what travels beside it"
        );
    }

    #[test]
    fn stripping_is_idempotent() {
        let once = strip_png_ancillary(&png_with_chunks()).unwrap();
        let twice = strip_png_ancillary(&once).unwrap();
        assert_eq!(once, twice);
    }

    #[test]
    fn stripping_refuses_anything_that_is_not_a_png() {
        assert_eq!(
            strip_png_ancillary(&jpeg(8, 8)),
            Err(AvatarError::Undecodable)
        );
        assert_eq!(strip_png_ancillary(b""), Err(AvatarError::Undecodable));
        // A signature with no IEND is a truncated file, not a picture.
        assert_eq!(
            strip_png_ancillary(&png(8, 8)),
            Err(AvatarError::Undecodable)
        );
    }

    #[test]
    fn a_png_chunk_length_that_overflows_is_an_error_not_a_panic() {
        let mut bad = png(8, 8);
        bad.extend_from_slice(&u32::MAX.to_be_bytes());
        bad.extend_from_slice(b"IDAT");
        assert_eq!(strip_png_ancillary(&bad), Err(AvatarError::Undecodable));
    }
}
