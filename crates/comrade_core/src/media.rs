/*!
 * Track 3 — NIP-94/96 Encrypted Media Staging & Distributed Upload
 *
 * Nostr relays only store small JSON events, so media (photos, audio notes,
 * files) needs a separate pipeline. This module implements the client side:
 *
 *  1. **Stage**: AES-256-GCM-encrypt the file with a key derived from the
 *     recipient's DH shared secret (Couples) or a per-file random key shared
 *     over an E2E DM (Vault). The relay/host only ever sees opaque ciphertext.
 *  2. **Upload**: push the encrypted blob to decentralized storage through a
 *     pluggable [`MediaUploader`] (NIP-96 HTTP server / Blossom / mock).
 *  3. **Describe**: build a NIP-94 (kind-1063) file-metadata event referencing
 *     the returned URL plus content hashes, ready to paste into a note or DM.
 *
 * The decryption key is *never* placed in the public NIP-94 event — it is
 * returned separately as a [`MediaSecret`] for the caller to transmit over the
 * already-encrypted channel.
 *
 * The staging, metadata, and mock-upload paths are fully unit-tested. The real
 * HTTP NIP-96 uploader lives behind the `nip96-http` cargo feature.
 */

use std::collections::HashMap;
use std::sync::Arc;

use nostr_sdk::prelude::*;
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;
use tracing::info;

use crate::crypto::{aes256gcm_open, aes256gcm_seal, sha256_hex};
use crate::error::MediaError;

/// NIP-94 file metadata event kind.
pub const FILE_METADATA_KIND: u16 = 1063;

/// Symmetric algorithm label recorded in [`MediaSecret`].
pub const MEDIA_ALGORITHM: &str = "aes-256-gcm";

// ── Staged media & secret ───────────────────────────────────────────────────────

/// An encrypted blob ready to upload, plus the public hashes describing it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EncryptedMedia {
    /// `[nonce | ciphertext+tag]` — exactly what gets uploaded.
    pub ciphertext: Vec<u8>,
    /// SHA-256 of `ciphertext` (NIP-94 `x` tag — hash of the served file).
    pub sha256_hex: String,
    /// Size of `ciphertext` in bytes.
    pub size: usize,
    pub mime_type: String,
}

/// The information the recipient needs to decrypt the blob. Transmit this over
/// an already-encrypted channel — never in the public NIP-94 event.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MediaSecret {
    /// 32-byte AES-256 key, hex-encoded.
    pub key_hex: String,
    pub algorithm: String,
    /// SHA-256 of the original plaintext (NIP-94 `ox` tag).
    pub original_sha256_hex: String,
}

/// Encrypt `plaintext` for distribution. Returns the uploadable blob and the
/// out-of-band secret needed to decrypt it.
pub fn encrypt_media(
    plaintext: &[u8],
    mime_type: &str,
    key: &[u8; 32],
) -> Result<(EncryptedMedia, MediaSecret), MediaError> {
    let ciphertext =
        aes256gcm_seal(key, plaintext).map_err(|e| MediaError::Crypto(e.to_string()))?;
    let media = EncryptedMedia {
        sha256_hex: sha256_hex(&ciphertext),
        size: ciphertext.len(),
        mime_type: mime_type.to_string(),
        ciphertext,
    };
    let secret = MediaSecret {
        key_hex: hex::encode(key),
        algorithm: MEDIA_ALGORITHM.to_string(),
        original_sha256_hex: sha256_hex(plaintext),
    };
    Ok((media, secret))
}

/// Decrypt a downloaded blob using the out-of-band [`MediaSecret`], verifying
/// the recovered plaintext against the original hash.
pub fn decrypt_media(ciphertext: &[u8], secret: &MediaSecret) -> Result<Vec<u8>, MediaError> {
    let key_bytes = hex::decode(&secret.key_hex)
        .map_err(|e| MediaError::Crypto(format!("bad key hex: {e}")))?;
    let key: [u8; 32] = key_bytes
        .try_into()
        .map_err(|_| MediaError::Crypto("key must be 32 bytes".into()))?;

    let plaintext =
        aes256gcm_open(&key, ciphertext).map_err(|e| MediaError::Crypto(e.to_string()))?;

    if sha256_hex(&plaintext) != secret.original_sha256_hex {
        return Err(MediaError::Crypto("decrypted content hash mismatch".into()));
    }
    Ok(plaintext)
}

// ── NIP-94 file metadata ─────────────────────────────────────────────────────────

/// Parsed NIP-94 file metadata (kind 1063).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileMetadata {
    pub url: String,
    pub mime_type: String,
    /// SHA-256 of the served (encrypted) file — NIP-94 `x`.
    pub sha256_hex: String,
    /// SHA-256 of the original file — NIP-94 `ox`.
    pub original_sha256_hex: Option<String>,
    pub size: Option<usize>,
    /// Free-text caption (event content).
    pub caption: String,
}

/// Build a signed NIP-94 (kind-1063) file-metadata event.
pub fn build_file_metadata_event(keys: &Keys, meta: &FileMetadata) -> Result<Event, MediaError> {
    let mut tags: Vec<Tag> = Vec::new();
    let mut push = |parts: &[&str]| -> Result<(), MediaError> {
        let tag = Tag::parse(parts.iter().copied())
            .map_err(|e| MediaError::ParseFailed(e.to_string()))?;
        tags.push(tag);
        Ok(())
    };

    push(&["url", &meta.url])?;
    push(&["m", &meta.mime_type])?;
    push(&["x", &meta.sha256_hex])?;
    if let Some(ox) = &meta.original_sha256_hex {
        push(&["ox", ox])?;
    }
    if let Some(size) = meta.size {
        push(&["size", &size.to_string()])?;
    }

    EventBuilder::new(Kind::from(FILE_METADATA_KIND), meta.caption.clone())
        .tags(tags)
        .finalize(keys)
        .map_err(|e| MediaError::SigningFailed(e.to_string()))
}

/// Parse a NIP-94 event's tags into [`FileMetadata`].
pub fn parse_file_metadata(event: &Event) -> Result<FileMetadata, MediaError> {
    let val = serde_json::to_value(event)
        .map_err(|e| MediaError::ParseFailed(format!("serialise event: {e}")))?;
    let tags = val
        .get("tags")
        .and_then(|t| t.as_array())
        .ok_or_else(|| MediaError::ParseFailed("no tags array".into()))?;

    let mut map: HashMap<String, String> = HashMap::new();
    for tag in tags {
        let Some(arr) = tag.as_array() else { continue };
        let (Some(name), Some(value)) = (
            arr.first().and_then(|v| v.as_str()),
            arr.get(1).and_then(|v| v.as_str()),
        ) else {
            continue;
        };
        // First occurrence wins for each tag name.
        map.entry(name.to_string())
            .or_insert_with(|| value.to_string());
    }

    let url = map
        .get("url")
        .cloned()
        .ok_or_else(|| MediaError::ParseFailed("missing url tag".into()))?;
    let sha256_hex = map
        .get("x")
        .cloned()
        .ok_or_else(|| MediaError::ParseFailed("missing x (hash) tag".into()))?;

    Ok(FileMetadata {
        url,
        mime_type: map.get("m").cloned().unwrap_or_default(),
        sha256_hex,
        original_sha256_hex: map.get("ox").cloned(),
        size: map.get("size").and_then(|s| s.parse().ok()),
        caption: event.content.clone(),
    })
}

// ── Pluggable uploader ───────────────────────────────────────────────────────────

/// Result of a successful upload.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UploadReceipt {
    pub url: String,
}

/// A backend that stores an opaque encrypted blob and returns a fetch URL.
///
/// Implementations: [`InMemoryUploader`] (testing/local), and `Nip96Uploader`
/// (behind the `nip96-http` feature) for real decentralized HTTP storage.
#[allow(async_fn_in_trait)] // internal trait; bounds are added at call sites
pub trait MediaUploader {
    async fn upload(&self, blob: &[u8], mime_type: &str) -> Result<UploadReceipt, MediaError>;
}

/// In-memory uploader for tests and offline use. Stores blobs keyed by their
/// SHA-256 and serves them back via [`InMemoryUploader::fetch`].
#[derive(Clone, Default)]
pub struct InMemoryUploader {
    store: Arc<Mutex<HashMap<String, Vec<u8>>>>,
    base_url: String,
}

impl InMemoryUploader {
    pub fn new(base_url: impl Into<String>) -> Self {
        Self {
            store: Arc::new(Mutex::new(HashMap::new())),
            base_url: base_url.into(),
        }
    }

    /// Retrieve a previously uploaded blob by its URL.
    pub async fn fetch(&self, url: &str) -> Option<Vec<u8>> {
        let hash = url.rsplit('/').next().unwrap_or_default().to_string();
        self.store.lock().await.get(&hash).cloned()
    }
}

impl MediaUploader for InMemoryUploader {
    async fn upload(&self, blob: &[u8], _mime_type: &str) -> Result<UploadReceipt, MediaError> {
        let hash = sha256_hex(blob);
        self.store.lock().await.insert(hash.clone(), blob.to_vec());
        let base = self.base_url.trim_end_matches('/');
        Ok(UploadReceipt {
            url: format!("{base}/{hash}"),
        })
    }
}

// ── Media engine: stage → upload → describe ──────────────────────────────────────

/// Ties encryption, upload, and NIP-94 metadata into one call.
pub struct MediaEngine<U: MediaUploader> {
    uploader: U,
    keys: Keys,
}

impl<U: MediaUploader> MediaEngine<U> {
    pub fn new(uploader: U, keys: Keys) -> Self {
        Self { uploader, keys }
    }

    /// Encrypt `plaintext`, upload the ciphertext, and produce a signed NIP-94
    /// event plus the out-of-band [`MediaSecret`] for the recipient.
    pub async fn share_encrypted(
        &self,
        plaintext: &[u8],
        mime_type: &str,
        caption: &str,
        key: &[u8; 32],
    ) -> Result<(Event, MediaSecret), MediaError> {
        let (media, secret) = encrypt_media(plaintext, mime_type, key)?;
        let receipt = self.uploader.upload(&media.ciphertext, mime_type).await?;
        info!(url = %receipt.url, size = media.size, "media: encrypted blob uploaded");

        let meta = FileMetadata {
            url: receipt.url,
            mime_type: media.mime_type,
            sha256_hex: media.sha256_hex,
            original_sha256_hex: Some(secret.original_sha256_hex.clone()),
            size: Some(media.size),
            caption: caption.to_string(),
        };
        let event = build_file_metadata_event(&self.keys, &meta)?;
        Ok((event, secret))
    }
}

// ── Real NIP-96 HTTP uploader (feature-gated) ────────────────────────────────────

#[cfg(feature = "nip96-http")]
mod nip96 {
    use super::*;
    use base64::{engine::general_purpose::STANDARD as B64, Engine as _};

    /// NIP-98 HTTP Auth event kind.
    const HTTP_AUTH_KIND: u16 = 27235;

    /// Uploads encrypted blobs to a NIP-96 HTTP file-storage server.
    ///
    /// `api_url` is the server's upload endpoint (from its
    /// `/.well-known/nostr/nip96.json` `api_url` field). Each request is
    /// authenticated with a NIP-98 `Authorization: Nostr <base64-event>` header.
    pub struct Nip96Uploader {
        client: reqwest::Client,
        api_url: String,
        keys: Keys,
    }

    impl Nip96Uploader {
        pub fn new(api_url: impl Into<String>, keys: Keys) -> Self {
            Self {
                client: reqwest::Client::new(),
                api_url: api_url.into(),
                keys,
            }
        }

        /// Build the base64-encoded NIP-98 auth event for a POST to `url`.
        fn auth_header(&self, url: &str) -> Result<String, MediaError> {
            let tags = vec![
                Tag::parse(["u", url]).map_err(|e| MediaError::Http(e.to_string()))?,
                Tag::parse(["method", "POST"]).map_err(|e| MediaError::Http(e.to_string()))?,
            ];
            let event = EventBuilder::new(Kind::from(HTTP_AUTH_KIND), "")
                .tags(tags)
                .finalize(&self.keys)
                .map_err(|e| MediaError::SigningFailed(e.to_string()))?;
            let json =
                serde_json::to_string(&event).map_err(|e| MediaError::Http(e.to_string()))?;
            Ok(format!("Nostr {}", B64.encode(json)))
        }
    }

    impl MediaUploader for Nip96Uploader {
        async fn upload(&self, blob: &[u8], mime_type: &str) -> Result<UploadReceipt, MediaError> {
            let part = reqwest::multipart::Part::bytes(blob.to_vec())
                .file_name("comrade-media.bin")
                .mime_str(mime_type)
                .map_err(|e| MediaError::Http(e.to_string()))?;
            let form = reqwest::multipart::Form::new().part("file", part);

            let auth = self.auth_header(&self.api_url)?;
            let resp = self
                .client
                .post(&self.api_url)
                .header("Authorization", auth)
                .multipart(form)
                .send()
                .await
                .map_err(|e| MediaError::Http(e.to_string()))?;

            if !resp.status().is_success() {
                return Err(MediaError::UploadFailed(format!(
                    "status {}",
                    resp.status()
                )));
            }

            let body: serde_json::Value = resp
                .json()
                .await
                .map_err(|e| MediaError::Http(e.to_string()))?;

            // NIP-96 returns the download URL inside nip94_event's `url` tag.
            let url = body
                .get("nip94_event")
                .and_then(|e| e.get("tags"))
                .and_then(|t| t.as_array())
                .and_then(|tags| {
                    tags.iter().find_map(|tag| {
                        let arr = tag.as_array()?;
                        if arr.first()?.as_str()? == "url" {
                            arr.get(1)?.as_str().map(|s| s.to_string())
                        } else {
                            None
                        }
                    })
                })
                .ok_or_else(|| {
                    MediaError::UploadFailed("response missing nip94_event url tag".into())
                })?;

            Ok(UploadReceipt { url })
        }
    }
}

#[cfg(feature = "nip96-http")]
pub use nip96::Nip96Uploader;

// ── Blossom upload + fetch-and-decrypt (feature-gated) ───────────────────────────

/// Blossom hosts tried in order, first success wins. Blossom servers are
/// content-addressed (blob URL = `<server>/<sha256>`) and accept raw `PUT`s.
///
/// **A list, because a single default is a single point of failure — and it
/// failed.** This was one hard-coded host, `cdn.hackers.town`, which stopped
/// completing TCP connections; every attachment on every platform then died
/// with `error sending request for url (https://cdn.hackers.town/upload)` and
/// no way for a user to route around it. Nothing about that host was special,
/// which is the point: any of these can go the same way, so the uploader tries
/// the next one instead of reporting failure after one.
///
/// Ordering is measured, not guessed. `examples/blossom_probe.rs` uploads a
/// signed blob to each and fetches it back; on the day this list was written it
/// reported:
///
/// | host | result |
/// |---|---|
/// | `nostr.download` | **accepts** anonymous BUD-01 uploads, round trip verified |
/// | `blossom.band` | reachable, rejects us (415 opaque / 400 typed) |
/// | `cdn.satellite.earth` | reachable, 401 — wants an account |
///
/// The two that reject us stay as fallbacks anyway: they cost nothing while the
/// first host works, and being down to one host is the exact condition that
/// produced this outage. Re-run the probe before trusting the order.
pub const DEFAULT_BLOSSOM_SERVERS: &[&str] = &[
    "https://nostr.download",
    "https://blossom.band",
    "https://cdn.satellite.earth",
];

/// The first of [`DEFAULT_BLOSSOM_SERVERS`], for callers that want exactly one.
/// Prefer the list: a single host is how this broke.
pub const DEFAULT_BLOSSOM_SERVER: &str = DEFAULT_BLOSSOM_SERVERS[0];

/// Hard cap on a decrypted media payload (10 MB). Mirrors the frontend limit;
/// enforced in the core so *every* caller (desktop, JNI/Android) is protected,
/// not just the ones that happen to pre-check.
pub const MAX_MEDIA_BYTES: usize = 10 * 1024 * 1024;

/// Cap on the encrypted blob we will buffer from the network, allowing for the
/// 12-byte nonce + 16-byte GCM tag envelope on top of [`MAX_MEDIA_BYTES`].
pub const MAX_ENCRYPTED_MEDIA_BYTES: usize = MAX_MEDIA_BYTES + 64;

#[cfg(feature = "media-http")]
mod http {
    use super::*;
    use base64::{engine::general_purpose::STANDARD as B64, Engine as _};
    use std::time::{Duration, SystemTime, UNIX_EPOCH};

    /// Blossom authorization event kind (BUD-01).
    const BLOSSOM_AUTH_KIND: u16 = 24242;

    fn unix_now() -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or_default()
    }

    /// Fetch the opaque blob at `url` and AES-256-GCM-decrypt it with `key`.
    ///
    /// The inverse of the stage→upload pipeline: the nonce travels prefixed to
    /// the ciphertext, so only the 32-byte `key` (re-derived from ECDH) is
    /// needed. Authenticated decryption rejects any tampered blob.
    ///
    /// The `url` comes from a peer's DM envelope and is therefore attacker
    /// influenced, so this hardens the fetch: HTTPS only, redirects disabled
    /// (an `http://` or redirected target could leak the client IP or probe
    /// internal addresses), and the body is capped at
    /// [`MAX_ENCRYPTED_MEDIA_BYTES`] — checked against `Content-Length` up front
    /// and again while streaming, since the header may be absent or lie. When
    /// `expected_sha256` is supplied the ciphertext hash is verified before
    /// decryption for a cheap fail-fast on the wrong/tampered blob.
    pub async fn fetch_and_decrypt_media(
        url: &str,
        key: &[u8; 32],
        expected_sha256: Option<&str>,
    ) -> Result<Vec<u8>, MediaError> {
        let (bytes, _) =
            fetch_guarded_bytes(url, MAX_ENCRYPTED_MEDIA_BYTES, "media URL", &[]).await?;
        if let Some(expected) = expected_sha256 {
            let actual = sha256_hex(&bytes);
            if actual != expected {
                return Err(MediaError::Crypto(format!(
                    "ciphertext hash mismatch: expected {expected}, got {actual}"
                )));
            }
        }
        aes256gcm_open(key, &bytes).map_err(|e| MediaError::Crypto(e.to_string()))
    }

    /// Fetch a URL that somebody else chose, with every guard this module has.
    ///
    /// The one implementation of the hardening, so the encrypted-media path and
    /// the profile-picture path cannot drift apart: HTTPS only (case-insensitive,
    /// fail-closed on anything else), redirects disabled — an `http://` or
    /// redirected target could leak the client IP or probe internal addresses —
    /// and the body capped at `max_bytes`, checked against `Content-Length` up
    /// front and again while streaming, since the header may be absent or lie.
    ///
    /// `subject` only names the thing in the refusal message; `allowed_types`
    /// empty means any, and is checked against the response header rather than
    /// the bytes, because only the caller knows how to sniff its own format.
    /// Returns the bytes and the declared content type, if the host sent one.
    async fn fetch_guarded_bytes(
        url: &str,
        max_bytes: usize,
        subject: &str,
        allowed_types: &[&str],
    ) -> Result<(Vec<u8>, Option<String>), MediaError> {
        // URL schemes are case-insensitive; accept HTTPS in any case but keep
        // the fail-closed default for every other (or missing) scheme.
        let is_https = url
            .split_once("://")
            .is_some_and(|(scheme, _)| scheme.eq_ignore_ascii_case("https"));
        if !is_https {
            return Err(MediaError::Http(format!(
                "refusing to fetch a non-HTTPS {subject}"
            )));
        }
        let client = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            // Same reasoning as the upload side: a host that accepts the
            // connection and then stops sending would otherwise leave a bubble
            // spinning forever with no error to act on.
            .connect_timeout(CONNECT_TIMEOUT)
            .timeout(TRANSFER_TIMEOUT)
            .build()
            .map_err(|e| MediaError::Http(e.to_string()))?;
        let mut resp = client
            .get(url)
            .send()
            .await
            .map_err(|e| MediaError::Http(e.to_string()))?;
        if !resp.status().is_success() {
            return Err(MediaError::UploadFailed(format!(
                "fetch status {}",
                resp.status()
            )));
        }
        let content_type = resp
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .map(|v| v.to_string());
        if !allowed_types.is_empty() {
            // Judge the bare type, not the parameters — `image/png; charset=x` is
            // still `image/png`, and a host that adds one is not an attacker.
            let bare = content_type
                .as_deref()
                .and_then(|v| v.split(';').next())
                .map(|v| v.trim().to_ascii_lowercase());
            // An absent header is tolerated: plenty of hosts omit it, and the
            // caller sniffs the bytes anyway. A *present and disallowed* one is
            // refused before the body is buffered.
            if let Some(bare) = bare.filter(|v| !v.is_empty()) {
                if !allowed_types.contains(&bare.as_str()) {
                    return Err(MediaError::UploadFailed(format!(
                        "refusing a {subject} served as {bare}"
                    )));
                }
            }
        }
        if let Some(len) = resp.content_length() {
            if len > max_bytes as u64 {
                return Err(MediaError::UploadFailed(format!(
                    "blob too large: {len} bytes (limit {max_bytes})"
                )));
            }
        }
        // Bounded streaming read — never buffer more than the cap even if the
        // server omits or understates Content-Length.
        let mut bytes: Vec<u8> = Vec::new();
        while let Some(chunk) = resp
            .chunk()
            .await
            .map_err(|e| MediaError::Http(e.to_string()))?
        {
            if bytes.len() + chunk.len() > max_bytes {
                return Err(MediaError::UploadFailed(format!(
                    "blob exceeds the {max_bytes}-byte limit"
                )));
            }
            bytes.extend_from_slice(&chunk);
        }
        Ok((bytes, content_type))
    }

    /// Fetch and vet a peer-published profile picture.
    ///
    /// Every guard in [`crate::avatar`] runs — the URL policy before a socket is
    /// opened, the type and pixel checks on what comes back — plus the shared
    /// transport hardening above with the avatar's own, much smaller, cap.
    /// Returns the bytes and the *sniffed* type, never the declared one.
    ///
    /// Whether a picture should be fetched **at all** is not decided here: that
    /// depends on the user's setting and on whether the peer has been accepted,
    /// both of which live in `comrade_ui`. This is the "is it safe", not the
    /// "should we ask".
    pub async fn fetch_avatar(url: &str) -> Result<(Vec<u8>, String), MediaError> {
        let vetted =
            crate::avatar::vet_avatar_url(url).map_err(|e| MediaError::Http(e.to_string()))?;
        let (bytes, declared) = fetch_guarded_bytes(
            vetted.as_str(),
            crate::avatar::MAX_AVATAR_BYTES,
            "profile picture",
            crate::avatar::AVATAR_MIME_ALLOWLIST,
        )
        .await?;
        let sniffed = crate::avatar::vet_avatar_bytes(&bytes, declared.as_deref())
            .map_err(|e| MediaError::Http(e.to_string()))?;
        Ok((bytes, sniffed.to_string()))
    }

    /// Upload bytes that are **not encrypted** and are meant to be publicly
    /// fetchable — the one exception in this module.
    ///
    /// A Nostr Kind-0 `picture` URL has to be readable by every client on the
    /// network, so an avatar cannot ride the encrypted pipeline: there is nobody
    /// to share the key with. The cost, stated rather than buried, because a user
    /// has to be told it before they agree to it:
    ///
    /// - the image is **public**, to anyone who ever sees the profile;
    /// - it is **permanent** in practice — Blossom hosts are content-addressed
    ///   and mirror, so clearing the Kind-0 field unpublishes the *pointer*, not
    ///   the bytes;
    /// - the bytes are **correlatable** with the npub that published them, and
    ///   with every other place that image has ever appeared.
    ///
    /// Mechanically this is what [`upload_encrypted_blob`] already did — that
    /// function never encrypted anything, it uploads whatever it is handed — but
    /// calling it here would leave the one public upload in the codebase reading
    /// as an encrypted one. A name that asserts a guarantee the code does not
    /// provide is the bug, even when the bytes are identical.
    pub async fn upload_public_blob(blob: Vec<u8>, mime_type: &str) -> Result<String, MediaError> {
        upload_encrypted_blob(blob, mime_type).await
    }

    /// How long to wait for a host to *answer at all*.
    ///
    /// Short on purpose, and separate from [`TRANSFER_TIMEOUT`]: a host that has
    /// stopped completing connections (which is exactly how the old default
    /// died) must cost seconds before the next one is tried, not minutes. A slow
    /// but live host is a different case and gets the full transfer budget.
    const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

    /// Whole-request budget. Generous because the payload can be 10 MB over a
    /// phone's uplink — but finite, because `reqwest`'s default is *no timeout*,
    /// and a black-holed connection would otherwise hang a send forever with a
    /// spinner and no error.
    const TRANSFER_TIMEOUT: Duration = Duration::from_secs(120);

    fn http_client() -> Result<reqwest::Client, MediaError> {
        reqwest::Client::builder()
            .connect_timeout(CONNECT_TIMEOUT)
            .timeout(TRANSFER_TIMEOUT)
            .build()
            .map_err(|e| MediaError::Http(e.to_string()))
    }

    /// Render one host's failure for a combined error message. The host is named
    /// because "upload failed" without it sends the reader to the wrong layer —
    /// the relay, the network, their own file — when the answer is "that media
    /// host is down".
    fn attempt_error(server: &str, error: &MediaError) -> String {
        format!("{}: {error}", server.trim_end_matches('/'))
    }

    /// Anonymous Blossom upload: `PUT <server>/upload` with the raw blob body.
    /// Returns the download URL. Servers that mandate auth will reject this;
    /// use [`BlossomUploader`] (signed) for those.
    pub async fn upload_encrypted_blob_to(
        server: &str,
        blob: Vec<u8>,
        mime_type: &str,
    ) -> Result<String, MediaError> {
        let endpoint = format!("{}/upload", server.trim_end_matches('/'));
        let resp = http_client()?
            .put(&endpoint)
            .header(reqwest::header::CONTENT_TYPE, mime_type)
            .body(blob)
            .send()
            .await
            .map_err(|e| MediaError::Http(e.to_string()))?;
        parse_blob_url(resp).await
    }

    /// Upload an encrypted blob anonymously, trying each of
    /// [`DEFAULT_BLOSSOM_SERVERS`] until one accepts it.
    pub async fn upload_encrypted_blob(
        blob: Vec<u8>,
        mime_type: &str,
    ) -> Result<String, MediaError> {
        let mut failures: Vec<String> = Vec::new();
        for server in DEFAULT_BLOSSOM_SERVERS {
            match upload_encrypted_blob_to(server, blob.clone(), mime_type).await {
                Ok(url) => return Ok(url),
                Err(e) => failures.push(attempt_error(server, &e)),
            }
        }
        Err(MediaError::UploadFailed(format!(
            "no media host accepted the upload — {}",
            failures.join("; ")
        )))
    }

    /// Extract the download URL from a Blossom blob-descriptor JSON response.
    async fn parse_blob_url(resp: reqwest::Response) -> Result<String, MediaError> {
        if !resp.status().is_success() {
            return Err(MediaError::UploadFailed(format!(
                "upload status {}",
                resp.status()
            )));
        }
        let body: serde_json::Value = resp
            .json()
            .await
            .map_err(|e| MediaError::Http(e.to_string()))?;
        body.get("url")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
            .ok_or_else(|| MediaError::UploadFailed("blob descriptor missing url".into()))
    }

    /// A Blossom uploader that signs each request with a BUD-01 `kind:24242`
    /// authorization event, so it works against servers that require auth.
    ///
    /// Holds a *list* of hosts and tries them in order — see
    /// [`DEFAULT_BLOSSOM_SERVERS`] for why one is not enough.
    pub struct BlossomUploader {
        client: reqwest::Client,
        servers: Vec<String>,
        keys: Keys,
    }

    impl BlossomUploader {
        /// One host. Kept for callers that mean exactly one (a test server, a
        /// user-chosen host); [`Self::with_servers`] is the failover form.
        pub fn new(server: impl Into<String>, keys: Keys) -> Self {
            Self::with_servers([server.into()], keys)
        }

        /// Try each host in order until one accepts the blob.
        ///
        /// Falls back to [`DEFAULT_BLOSSOM_SERVERS`] if handed an empty list, so
        /// a misconfigured caller degrades to "the defaults" rather than to
        /// "media is silently impossible".
        pub fn with_servers(
            servers: impl IntoIterator<Item = impl Into<String>>,
            keys: Keys,
        ) -> Self {
            let mut servers: Vec<String> = servers
                .into_iter()
                .map(Into::into)
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty())
                .collect();
            if servers.is_empty() {
                servers = DEFAULT_BLOSSOM_SERVERS
                    .iter()
                    .map(|s| s.to_string())
                    .collect();
            }
            Self {
                // `unwrap_or_default` rather than `?`: a client that cannot be
                // built is a programming error in the builder arguments, and
                // failing here would make the constructor fallible for every
                // caller to no benefit. The default client still works; it just
                // has no timeouts, which the per-request budget below covers.
                client: http_client().unwrap_or_default(),
                servers,
                keys,
            }
        }

        /// The hosts this uploader will try, in order.
        pub fn servers(&self) -> &[String] {
            &self.servers
        }

        fn auth_header(&self, blob_sha256: &str) -> Result<String, MediaError> {
            let expiration = (unix_now() + 3600).to_string();
            let tags = vec![
                Tag::parse(["t", "upload"]).map_err(|e| MediaError::Http(e.to_string()))?,
                Tag::parse(["x", blob_sha256]).map_err(|e| MediaError::Http(e.to_string()))?,
                Tag::parse(["expiration", &expiration])
                    .map_err(|e| MediaError::Http(e.to_string()))?,
            ];
            let event = EventBuilder::new(Kind::from(BLOSSOM_AUTH_KIND), "Upload encrypted media")
                .tags(tags)
                .finalize(&self.keys)
                .map_err(|e| MediaError::SigningFailed(e.to_string()))?;
            let json =
                serde_json::to_string(&event).map_err(|e| MediaError::Http(e.to_string()))?;
            Ok(format!("Nostr {}", B64.encode(json)))
        }
    }

    impl BlossomUploader {
        /// One host, one attempt.
        async fn upload_to(
            &self,
            server: &str,
            blob: &[u8],
            sha: &str,
            mime_type: &str,
        ) -> Result<String, MediaError> {
            let endpoint = format!("{}/upload", server.trim_end_matches('/'));
            let resp = self
                .client
                .put(&endpoint)
                .header(reqwest::header::AUTHORIZATION, self.auth_header(sha)?)
                .header(reqwest::header::CONTENT_TYPE, mime_type)
                // BUD-02: servers match this against the `x` tag in the auth
                // event, and some reject an upload that omits it.
                .header("X-SHA-256", sha)
                .header(reqwest::header::CONTENT_LENGTH, blob.len())
                .body(blob.to_vec())
                .send()
                .await
                .map_err(|e| MediaError::Http(e.to_string()))?;
            parse_blob_url(resp).await
        }
    }

    impl MediaUploader for BlossomUploader {
        async fn upload(&self, blob: &[u8], mime_type: &str) -> Result<UploadReceipt, MediaError> {
            let sha = sha256_hex(blob);
            let mut failures: Vec<String> = Vec::new();
            for server in &self.servers {
                match self.upload_to(server, blob, &sha, mime_type).await {
                    Ok(url) => return Ok(UploadReceipt { url }),
                    Err(e) => failures.push(attempt_error(server, &e)),
                }
            }
            // Every host named, with its own reason. One line the user can act
            // on — or paste into a bug report that is actually diagnosable.
            Err(MediaError::UploadFailed(format!(
                "no media host accepted the upload — {}",
                failures.join("; ")
            )))
        }
    }
}

#[cfg(feature = "media-http")]
pub use http::{
    fetch_and_decrypt_media, fetch_avatar, upload_encrypted_blob, upload_encrypted_blob_to,
    upload_public_blob, BlossomUploader,
};

// Stubs so the rest of the workspace compiles (and degrades gracefully) when the
// `media-http` feature is off — the runtime calls these unconditionally.
#[cfg(not(feature = "media-http"))]
pub async fn fetch_and_decrypt_media(
    _url: &str,
    _key: &[u8; 32],
    _expected_sha256: Option<&str>,
) -> Result<Vec<u8>, MediaError> {
    Err(MediaError::Http(
        "media download requires the `media-http` cargo feature".into(),
    ))
}

#[cfg(not(feature = "media-http"))]
pub async fn upload_encrypted_blob(_blob: Vec<u8>, _mime_type: &str) -> Result<String, MediaError> {
    Err(MediaError::Http(
        "media upload requires the `media-http` cargo feature".into(),
    ))
}

#[cfg(not(feature = "media-http"))]
pub async fn upload_public_blob(_blob: Vec<u8>, _mime_type: &str) -> Result<String, MediaError> {
    Err(MediaError::Http(
        "publishing a profile picture requires the `media-http` cargo feature".into(),
    ))
}

#[cfg(not(feature = "media-http"))]
pub async fn fetch_avatar(_url: &str) -> Result<(Vec<u8>, String), MediaError> {
    Err(MediaError::Http(
        "fetching a profile picture requires the `media-http` cargo feature".into(),
    ))
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn key() -> [u8; 32] {
        [7u8; 32]
    }

    #[test]
    fn encrypt_decrypt_roundtrip() {
        let plaintext = b"fake JPEG bytes \x00\xFF\xD8\xFF";
        let (media, secret) = encrypt_media(plaintext, "image/jpeg", &key()).unwrap();
        assert_ne!(media.ciphertext, plaintext);
        assert_eq!(media.sha256_hex, sha256_hex(&media.ciphertext));
        let recovered = decrypt_media(&media.ciphertext, &secret).unwrap();
        assert_eq!(recovered, plaintext);
    }

    #[test]
    fn wrong_key_fails_to_decrypt() {
        let (media, mut secret) = encrypt_media(b"data", "image/png", &key()).unwrap();
        secret.key_hex = hex::encode([9u8; 32]);
        assert!(decrypt_media(&media.ciphertext, &secret).is_err());
    }

    #[test]
    fn tampered_hash_is_detected() {
        let (media, mut secret) = encrypt_media(b"data", "image/png", &key()).unwrap();
        secret.original_sha256_hex = sha256_hex(b"different");
        // Decryption succeeds but the integrity check against ox must fail.
        assert!(decrypt_media(&media.ciphertext, &secret).is_err());
    }

    #[test]
    fn x_and_ox_hashes_are_distinct_and_correct() {
        let plaintext = b"original content";
        let (media, secret) = encrypt_media(plaintext, "text/plain", &key()).unwrap();
        assert_eq!(secret.original_sha256_hex, sha256_hex(plaintext));
        assert_eq!(media.sha256_hex, sha256_hex(&media.ciphertext));
        assert_ne!(media.sha256_hex, secret.original_sha256_hex);
    }

    #[test]
    fn nip94_build_then_parse_roundtrip() {
        let keys = Keys::generate();
        let meta = FileMetadata {
            url: "https://host.example/abc".into(),
            mime_type: "image/jpeg".into(),
            sha256_hex: "a".repeat(64),
            original_sha256_hex: Some("b".repeat(64)),
            size: Some(2048),
            caption: "a sunset".into(),
        };
        let event = build_file_metadata_event(&keys, &meta).unwrap();
        assert_eq!(event.kind, Kind::from(FILE_METADATA_KIND));
        let parsed = parse_file_metadata(&event).unwrap();
        assert_eq!(parsed, meta);
    }

    #[test]
    fn zero_knowledge_event_leaks_no_key_or_plaintext_hash() {
        // The public NIP-94 event must carry only URL + ciphertext hash — never
        // the AES key (derived via ECDH) nor the plaintext (`ox`) hash.
        let keys = Keys::generate();
        let (media, _secret) = encrypt_media(b"secret photo", "image/jpeg", &key()).unwrap();
        let meta = FileMetadata {
            url: "https://blob.example/abc".into(),
            mime_type: "image/jpeg".into(),
            sha256_hex: media.sha256_hex.clone(),
            original_sha256_hex: None,
            size: Some(media.size),
            caption: String::new(),
        };
        let event = build_file_metadata_event(&keys, &meta).unwrap();
        let json = serde_json::to_string(&event).unwrap();
        assert!(!json.contains(&hex::encode(key())), "AES key must not leak");
        let parsed = parse_file_metadata(&event).unwrap();
        assert_eq!(parsed.original_sha256_hex, None);
        assert_eq!(parsed.sha256_hex, media.sha256_hex);
    }

    #[cfg(not(feature = "media-http"))]
    #[tokio::test]
    async fn http_paths_degrade_gracefully_without_feature() {
        // No panics, just typed errors, when the network feature is disabled.
        assert!(upload_encrypted_blob(vec![1, 2, 3], "image/png")
            .await
            .is_err());
        assert!(fetch_and_decrypt_media("https://x/y", &[0u8; 32], None)
            .await
            .is_err());
    }

    #[cfg(feature = "media-http")]
    #[tokio::test]
    async fn fetch_rejects_non_https_url_before_any_request() {
        // A peer-supplied http:// (or other-scheme) URL must be refused up
        // front — no request is issued, so this returns without touching the
        // network (SSRF/IP-leak hardening).
        for url in [
            "http://169.254.169.254/latest",
            "file:///etc/passwd",
            "ftp://x/y",
        ] {
            let err = fetch_and_decrypt_media(url, &[0u8; 32], None).await;
            assert!(matches!(err, Err(MediaError::Http(_))), "must reject {url}");
        }
    }

    #[cfg(feature = "media-http")]
    #[tokio::test]
    async fn avatar_fetch_refuses_a_hostile_url_before_any_request() {
        // The avatar path is stricter than the media path: as well as the scheme,
        // a host that is not on the public internet is refused, and so are the
        // decimal/hex/IPv4-mapped spellings of loopback that a string comparison
        // walks straight past. None of these touch the network.
        for url in [
            "http://example.com/a.png",
            "file:///etc/passwd",
            "https://127.0.0.1/a.png",
            "https://169.254.169.254/latest/meta-data/",
            "https://2130706433/a.png",
            "https://[::ffff:127.0.0.1]/a.png",
            "https://localhost/a.png",
            "https://printer.local/a.png",
            "https://intranet/a.png",
            "https://user:pass@example.com/a.png",
            "",
        ] {
            let err = fetch_avatar(url).await;
            assert!(
                matches!(err, Err(MediaError::Http(_))),
                "must reject {url:?} without opening a socket"
            );
        }
    }

    // The complement — that a *public* URL is not refused by the policy — is
    // pinned in `avatar::tests::an_ordinary_public_url_survives_all_of_it`, where
    // it costs no socket. Asserting it here would mean a real connection attempt
    // to an unroutable address and a test that waits out CONNECT_TIMEOUT.

    #[test]
    fn the_single_default_host_is_the_first_of_the_list() {
        // The scalar is kept for callers that want one host, but it must never
        // drift away from the list the uploader actually tries.
        assert_eq!(DEFAULT_BLOSSOM_SERVER, DEFAULT_BLOSSOM_SERVERS[0]);
        assert!(
            DEFAULT_BLOSSOM_SERVERS.len() > 1,
            "one media host is a single point of failure — that is what broke"
        );
        for server in DEFAULT_BLOSSOM_SERVERS {
            assert!(
                server.starts_with("https://"),
                "{server} must be HTTPS: the blob is opaque, but the request is not"
            );
            assert!(
                !server.ends_with('/'),
                "{server} must not carry a trailing /"
            );
        }
    }

    #[cfg(feature = "media-http")]
    #[test]
    fn an_uploader_given_no_usable_host_falls_back_to_the_defaults() {
        let keys = Keys::generate();
        // Blank/whitespace entries are dropped rather than turned into
        // `https:///upload`, and an empty result degrades to the defaults —
        // "media is impossible" must not be reachable by misconfiguration.
        let uploader = BlossomUploader::with_servers(["", "   "], keys.clone());
        assert_eq!(uploader.servers(), DEFAULT_BLOSSOM_SERVERS);

        let trimmed = BlossomUploader::with_servers(["  https://one.example  "], keys);
        assert_eq!(trimmed.servers(), &["https://one.example".to_string()]);
    }

    #[cfg(feature = "media-http")]
    #[tokio::test]
    async fn every_host_is_tried_and_every_failure_is_named() {
        // Closed ports on loopback: refused immediately, so this stays hermetic
        // and fast (no DNS, no external network, no timeout wait).
        let uploader = BlossomUploader::with_servers(
            ["https://127.0.0.1:1", "https://127.0.0.1:2"],
            Keys::generate(),
        );
        let err = uploader
            .upload(b"ciphertext", "application/octet-stream")
            .await
            .expect_err("no host can accept this");
        let message = err.to_string();

        // Both hosts attempted — a failover that stops at the first failure is
        // the bug this replaced.
        assert!(message.contains("127.0.0.1:1"), "{message}");
        assert!(message.contains("127.0.0.1:2"), "{message}");
        // And it says which layer failed. "upload failed" alone sends the
        // reader to their relay, their network, or their own file.
        assert!(
            message.contains("no media host accepted the upload"),
            "{message}"
        );
    }

    #[test]
    fn parse_rejects_event_without_url() {
        let keys = Keys::generate();
        let event = EventBuilder::new(Kind::from(FILE_METADATA_KIND), "no tags")
            .finalize(&keys)
            .unwrap();
        assert!(parse_file_metadata(&event).is_err());
    }

    #[tokio::test]
    async fn in_memory_uploader_stores_and_serves() {
        let uploader = InMemoryUploader::new("https://blob.example");
        let receipt = uploader
            .upload(b"opaque", "application/octet-stream")
            .await
            .unwrap();
        assert!(receipt.url.starts_with("https://blob.example/"));
        assert_eq!(uploader.fetch(&receipt.url).await, Some(b"opaque".to_vec()));
    }

    #[tokio::test]
    async fn full_pipeline_encrypt_upload_describe_recover() {
        let keys = Keys::generate();
        let uploader = InMemoryUploader::new("https://blob.example");
        let engine = MediaEngine::new(uploader.clone(), keys);

        let original = b"the secret photo bytes";
        let (event, secret) = engine
            .share_encrypted(original, "image/jpeg", "for your eyes only", &key())
            .await
            .unwrap();

        // The public event describes the upload...
        let meta = parse_file_metadata(&event).unwrap();
        assert_eq!(meta.caption, "for your eyes only");
        assert_eq!(meta.mime_type, "image/jpeg");

        // ...and the recipient can fetch + decrypt the blob back to the original.
        let blob = uploader.fetch(&meta.url).await.expect("blob present");
        assert_eq!(blob.len(), meta.size.unwrap());
        let recovered = decrypt_media(&blob, &secret).unwrap();
        assert_eq!(recovered, original);
    }
}
