//! Print a sample Tara conversation, so copy changes can be *read* rather
//! than imagined: `cargo run -p comrade_core --example tara_demo`.
//!
//! The engine is deterministic, so this output is stable for a given build —
//! diffing two runs of this example is diffing the conversational feel.

use comrade_core::tara::{CompanionEngine, JournalSignal, ReflectiveCompanion};

fn main() {
    let tara = ReflectiveCompanion;

    println!("── opener (fresh install) ──");
    println!("tara> {}\n", tara.opener(&[]));

    println!("── opener (two recent low journal days) ──");
    let low = |age| JournalSignal {
        mood: Some("😞".into()),
        age_days: age,
    };
    println!("tara> {}\n", tara.opener(&[low(1), low(3)]));

    let script: &[&str] = &[
        "hey",
        "i've been really anxious about the review tomorrow",
        "yeah",
        "i'm exhausted and scared it will go badly",
        "should i just ask to postpone it",
        "i don't know",
        "why is everything so hard?",
        "thanks tara",
        "are you a real person?",
        "i'm not happy about how i handled today",
        "goodnight",
    ];
    for (turn, message) in script.iter().enumerate() {
        let reply = tara.reply(message, turn as u64);
        println!("  me> {message}");
        println!(
            "tara> {}{}\n",
            reply.text,
            if reply.crisis { "  [crisis]" } else { "" }
        );
    }
}
