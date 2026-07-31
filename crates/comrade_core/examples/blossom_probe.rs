//! Probe the configured media hosts with a real, signed upload.
//!
//! Not a test: it needs the network, so it must never gate CI. It exists
//! because the alternative is guessing — the single hard-coded Blossom host
//! this replaced had stopped completing connections, and no unit test could
//! have told anyone that.
//!
//! ```sh
//! cargo run -p comrade_core --features media-http --example blossom_probe
//! ```
//!
//! It uploads a few bytes of random-looking data under a throwaway key (the
//! same shape of key the real upload path signs with), then fetches the URL back
//! and checks the bytes match.

#[cfg(not(feature = "media-http"))]
fn main() {
    eprintln!("run with --features media-http");
}

#[cfg(feature = "media-http")]
#[tokio::main]
async fn main() {
    use comrade_core::media::{
        BlossomUploader, MediaUploader, DEFAULT_BLOSSOM_SERVERS, MAX_ENCRYPTED_MEDIA_BYTES,
    };

    let blob: Vec<u8> = (0..512u32).map(|i| (i % 251) as u8).collect();
    println!("probing {} host(s)\n", DEFAULT_BLOSSOM_SERVERS.len());

    // Does a host care what we *call* the bytes? It matters: the payload is
    // AES-GCM ciphertext, and claiming it is a JPEG both lies to the host and
    // tells it what kind of thing the user just sent.
    for server in DEFAULT_BLOSSOM_SERVERS {
        for mime in ["application/octet-stream", "image/jpeg"] {
            let uploader = BlossomUploader::new(*server, nostr_sdk::Keys::generate());
            let outcome = match uploader.upload(&blob, mime).await {
                Ok(r) => format!("OK   {}", r.url),
                Err(e) => format!("FAIL {e}"),
            };
            println!("  {server:<30} as {mime:<26} {outcome}");
        }
    }
    println!();

    let mut any = false;
    for server in DEFAULT_BLOSSOM_SERVERS {
        let uploader = BlossomUploader::new(*server, nostr_sdk::Keys::generate());
        match uploader.upload(&blob, "application/octet-stream").await {
            Ok(receipt) => {
                any = true;
                print!("  {server:<32} UPLOAD OK  {}", receipt.url);
                match reqwest::get(&receipt.url).await {
                    Ok(r) if r.status().is_success() => {
                        let back = r.bytes().await.unwrap_or_default();
                        let same = back.as_ref() == blob.as_slice();
                        println!(
                            "   fetch {} ({} bytes)",
                            if same { "OK" } else { "MISMATCH" },
                            back.len()
                        );
                    }
                    Ok(r) => println!("   fetch status {}", r.status()),
                    Err(e) => println!("   fetch failed: {e}"),
                }
            }
            Err(e) => println!("  {server:<32} FAILED     {e}"),
        }
    }

    // The failover path itself: what a caller actually gets.
    let all = BlossomUploader::with_servers(
        DEFAULT_BLOSSOM_SERVERS.iter().copied(),
        nostr_sdk::Keys::generate(),
    );
    match all.upload(&blob, "application/octet-stream").await {
        Ok(r) => println!("\nfailover upload -> {}", r.url),
        Err(e) => println!("\nfailover upload FAILED: {e}"),
    }

    println!(
        "\n{} (encrypted-blob cap {} bytes)",
        if any {
            "at least one host works"
        } else {
            "NO HOST WORKS"
        },
        MAX_ENCRYPTED_MEDIA_BYTES
    );
}
