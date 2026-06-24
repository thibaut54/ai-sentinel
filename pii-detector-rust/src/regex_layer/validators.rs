//! Named validators for the regex layer.
//!
//! A validator is a deterministic post-filter on a regex match. Used to
//! cut the false-positive rate of broad patterns:
//!
//! - `luhn`              — credit card mod-10 check
//! - `iban_checksum`     — IBAN ISO 13616 mod-97 check
//! - `swiss_avs`         — Swiss AVS NAVS13 EAN-13 mod-10 check
//! - `entropy:<float>`   — Shannon entropy (bits/char) ≥ threshold
//!
//! New validators are added by extending [`Validator`] and its `validate`
//! and `parse` methods. Names are resolved at [`super::RegexLayer::from_taxonomy`]
//! build time — an unknown name fails loudly there, not at detection time.

use std::collections::HashMap;

use crate::error::{Error, Result};

#[derive(Debug, Clone, PartialEq)]
pub enum Validator {
    Luhn,
    IbanChecksum,
    SwissAvs,
    Entropy { min_bits_per_char: f64 },
    /// At least 2 whitespace-separated words, each starting with an uppercase letter.
    /// Drops `"utilisateur"`, `"demandeur"`, `"personnes"`; keeps `"Sandra Favre"`, `"Bob Martin"`.
    TitleCaseMultiWord,
    /// At least one alphabetic character. Drops pure-numeric matches.
    HasAlpha,
    /// Reject snake_case-ish identifiers (`responsable_etablissement`, `code_etablissement`).
    /// Heuristic: presence of an underscore between two lowercase letters.
    NoSnakeCase,
    /// At least one word starts with uppercase, OR the value is fully ALL CAPS.
    /// Cuts generic French common nouns (`groupe`, `système`, `console support`)
    /// while keeping real entity names (`BCV`, `Sandra Favre`, `DGCS`).
    TitleCaseOrCaps,
    /// Looks like a postal address: contains a street keyword (rue, av, chemin, ...)
    /// OR a CH-style postal code pattern (4 digits followed by a Title-Case word).
    /// Drops `"MAC address"`, `"vd.ch"`, `"adresse saisie"` etc.
    LooksLikeAddress,
    /// Minimum character length (inclusive). Drops short noise like `"Rue"`, `"ES"`.
    MinLength { n: usize },
    /// Case-insensitive exact-match against a curated stoplist of generic terms.
    /// Used on broad NER labels (e.g., LOCATION) to drop recurring common nouns.
    NotInStoplist { words: Vec<String> },
    /// SWIFT/BIC (ISO 9362) sanity check: candidate must be 8 or 11 chars and
    /// positions 5-6 must be a valid ISO 3166-1 alpha-2 country code. Cuts
    /// matches like `VARCHAR2`, `INTERNAL`, `LONGTEXT` (random 8-letter words).
    BicCountry,
    /// At least one lowercase letter required. Used on USERNAME to cut
    /// all-uppercase noise (JIRA tickets `EVAUDTAX-1979`, config names
    /// `GESDEMMAIL1`, system codes `NAVS13`) which the model otherwise tags as
    /// account identifiers but are not real user handles in narrative text.
    NotAllUppercase,
    /// ISO 3779 / 4030 VIN checksum: 17 chars (no I/O/Q), position 9 is the
    /// weighted mod-11 check digit. Kills 17-digit Excel cell values that
    /// passed the bare regex but aren't real VINs.
    VinChecksum,
    /// Reject candidates containing any whitespace (space, tab, newline).
    /// Used on USERNAME / ACCOUNT_ID where real handles are single tokens —
    /// kills tabular-cell leaks like `Lausanne 14` or `ServiceAccount\ttemplate-...`.
    SingleToken,
}

impl Validator {
    /// Parse a validator name as it appears in `validators = [...]` in the taxonomy.
    pub fn parse(name: &str) -> Result<Self> {
        match name {
            "luhn" => Ok(Self::Luhn),
            "iban_checksum" => Ok(Self::IbanChecksum),
            "swiss_avs" => Ok(Self::SwissAvs),
            "title_case_multi_word" => Ok(Self::TitleCaseMultiWord),
            "has_alpha" => Ok(Self::HasAlpha),
            "no_snake_case" => Ok(Self::NoSnakeCase),
            "title_case_or_caps" => Ok(Self::TitleCaseOrCaps),
            "looks_like_address" => Ok(Self::LooksLikeAddress),
            "bic_country" => Ok(Self::BicCountry),
            "not_all_uppercase" => Ok(Self::NotAllUppercase),
            "vin_checksum" => Ok(Self::VinChecksum),
            "single_token" => Ok(Self::SingleToken),
            other => {
                if let Some(rest) = other.strip_prefix("min_length:") {
                    let n: usize = rest.parse().map_err(|_| {
                        Error::Taxonomy(format!("invalid min_length {rest:?}"))
                    })?;
                    return Ok(Self::MinLength { n });
                }
                if let Some(rest) = other.strip_prefix("not_in_stoplist:") {
                    let words: Vec<String> = rest
                        .split(',')
                        .map(|w| w.trim().to_lowercase())
                        .filter(|w| !w.is_empty())
                        .collect();
                    if words.is_empty() {
                        return Err(Error::Taxonomy(
                            "not_in_stoplist requires a non-empty comma-separated word list"
                                .into(),
                        ));
                    }
                    return Ok(Self::NotInStoplist { words });
                }
                if let Some(rest) = other.strip_prefix("entropy:") {
                    let bits: f64 = rest.parse().map_err(|_| {
                        Error::Taxonomy(format!("invalid entropy threshold {rest:?}"))
                    })?;
                    if !bits.is_finite() || bits < 0.0 {
                        return Err(Error::Taxonomy(format!(
                            "entropy threshold must be finite and non-negative, got {bits}"
                        )));
                    }
                    Ok(Self::Entropy {
                        min_bits_per_char: bits,
                    })
                } else {
                    Err(Error::Taxonomy(format!("unknown validator {other:?}")))
                }
            }
        }
    }

    /// Apply the validator. `true` means the candidate passes.
    pub fn validate(&self, candidate: &str) -> bool {
        match self {
            Self::Luhn => luhn_valid(candidate),
            Self::IbanChecksum => iban_valid(candidate),
            Self::SwissAvs => swiss_avs_valid(candidate),
            Self::Entropy { min_bits_per_char } => {
                shannon_entropy(candidate) >= *min_bits_per_char
            }
            Self::TitleCaseMultiWord => title_case_multi_word(candidate),
            Self::HasAlpha => candidate.chars().any(|c| c.is_alphabetic()),
            Self::NoSnakeCase => no_snake_case(candidate),
            Self::TitleCaseOrCaps => title_case_or_caps(candidate),
            Self::LooksLikeAddress => looks_like_address(candidate),
            Self::MinLength { n } => candidate.chars().count() >= *n,
            Self::NotInStoplist { words } => {
                let lower = candidate.trim().to_lowercase();
                !words.iter().any(|w| w == &lower)
            }
            Self::BicCountry => bic_country_valid(candidate),
            Self::NotAllUppercase => candidate.chars().any(|c| c.is_lowercase()),
            Self::VinChecksum => vin_checksum_valid(candidate),
            Self::SingleToken => !candidate.chars().any(char::is_whitespace),
        }
    }
}

/// ISO 3779 VIN check-digit at position 9 (1-indexed = byte 8 0-indexed).
/// Letters map per the standard transliteration table (I, O, Q are not
/// allowed in VINs at all). Sum each char's value × weight; mod 11.
/// A remainder of 10 is represented as 'X' in position 9.
fn vin_checksum_valid(s: &str) -> bool {
    if s.len() != 17 {
        return false;
    }
    let bytes = s.as_bytes();
    // VINs are ASCII; if not, reject.
    if !s.is_ascii() {
        return false;
    }
    // Position 9 (0-indexed 8) is the check digit.
    let check_char = (bytes[8] as char).to_ascii_uppercase();
    let weights: [u32; 17] = [8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2];
    let mut sum: u32 = 0;
    for (i, &b) in bytes.iter().enumerate() {
        let c = (b as char).to_ascii_uppercase();
        let v = match c {
            '0'..='9' => (c as u32) - ('0' as u32),
            'A' => 1, 'B' => 2, 'C' => 3, 'D' => 4, 'E' => 5,
            'F' => 6, 'G' => 7, 'H' => 8,
            'J' => 1, 'K' => 2, 'L' => 3, 'M' => 4, 'N' => 5,
            'P' => 7, 'R' => 9,
            'S' => 2, 'T' => 3, 'U' => 4, 'V' => 5, 'W' => 6,
            'X' => 7, 'Y' => 8, 'Z' => 9,
            // I, O, Q forbidden — reject the whole candidate.
            _ => return false,
        };
        sum += v * weights[i];
    }
    let expected = sum % 11;
    if expected == 10 {
        check_char == 'X'
    } else {
        check_char.to_digit(10) == Some(expected)
    }
}

/// ISO 3166-1 alpha-2 country codes used by BIC position 5-6.
/// Source: ISO 3166-1 (officially assigned codes).
const ISO_3166_ALPHA2: &[&str] = &[
    "AD","AE","AF","AG","AI","AL","AM","AO","AQ","AR","AS","AT","AU","AW","AX","AZ",
    "BA","BB","BD","BE","BF","BG","BH","BI","BJ","BL","BM","BN","BO","BQ","BR","BS","BT","BV","BW","BY","BZ",
    "CA","CC","CD","CF","CG","CH","CI","CK","CL","CM","CN","CO","CR","CU","CV","CW","CX","CY","CZ",
    "DE","DJ","DK","DM","DO","DZ",
    "EC","EE","EG","EH","ER","ES","ET",
    "FI","FJ","FK","FM","FO","FR",
    "GA","GB","GD","GE","GF","GG","GH","GI","GL","GM","GN","GP","GQ","GR","GS","GT","GU","GW","GY",
    "HK","HM","HN","HR","HT","HU",
    "ID","IE","IL","IM","IN","IO","IQ","IR","IS","IT",
    "JE","JM","JO","JP",
    "KE","KG","KH","KI","KM","KN","KP","KR","KW","KY","KZ",
    "LA","LB","LC","LI","LK","LR","LS","LT","LU","LV","LY",
    "MA","MC","MD","ME","MF","MG","MH","MK","ML","MM","MN","MO","MP","MQ","MR","MS","MT","MU","MV","MW","MX","MY","MZ",
    "NA","NC","NE","NF","NG","NI","NL","NO","NP","NR","NU","NZ",
    "OM",
    "PA","PE","PF","PG","PH","PK","PL","PM","PN","PR","PS","PT","PW","PY",
    "QA",
    "RE","RO","RS","RU","RW",
    "SA","SB","SC","SD","SE","SG","SH","SI","SJ","SK","SL","SM","SN","SO","SR","SS","ST","SV","SX","SY","SZ",
    "TC","TD","TF","TG","TH","TJ","TK","TL","TM","TN","TO","TR","TT","TV","TW","TZ",
    "UA","UG","UM","US","UY","UZ",
    "VA","VC","VE","VG","VI","VN","VU",
    "WF","WS",
    "YE","YT",
    "ZA","ZM","ZW",
];

/// Scan the candidate for a valid 8- or 11-char BIC window. We accept a
/// longer candidate (e.g. `BIC: UBSWCHZH80A` when the regex matched the
/// keyword + value form) and look for any window inside that matches the
/// BIC structural rules: 4 letters / 2 letters (ISO country code) / 2
/// alphanums, optionally followed by 3 alphanums.
fn bic_country_valid(s: &str) -> bool {
    let bytes = s.as_bytes();
    for window_len in [11usize, 8usize] {
        if bytes.len() < window_len {
            continue;
        }
        for start in 0..=bytes.len() - window_len {
            let win = &bytes[start..start + window_len];
            if is_valid_bic_window(win) {
                return true;
            }
        }
    }
    false
}

fn is_valid_bic_window(bytes: &[u8]) -> bool {
    if bytes.len() != 8 && bytes.len() != 11 {
        return false;
    }
    // Word-boundary check: surrounding context (caller-side) should be
    // non-alphanumeric. We accept the window if its first 4 chars are letters,
    // chars 5-6 are letters forming a valid ISO 3166 code, char 7 is a
    // non-zero letter/digit, char 8 is alphanumeric, and (if 11 chars) the
    // last 3 are alphanumeric.
    for &b in &bytes[0..4] {
        if !b.is_ascii_uppercase() {
            return false;
        }
    }
    let cc_bytes = &bytes[4..6];
    if !cc_bytes.iter().all(|b| b.is_ascii_uppercase()) {
        return false;
    }
    let cc = match std::str::from_utf8(cc_bytes) {
        Ok(s) => s,
        Err(_) => return false,
    };
    if !ISO_3166_ALPHA2.contains(&cc) {
        return false;
    }
    // Position 7 (0-indexed 6): not '0'.
    let c7 = bytes[6];
    if c7 == b'0' || !c7.is_ascii_alphanumeric() {
        return false;
    }
    // Position 8 (0-indexed 7): alphanumeric.
    if !bytes[7].is_ascii_alphanumeric() {
        return false;
    }
    // Optional branch (chars 9-11): alphanumeric.
    if bytes.len() == 11 {
        for &b in &bytes[8..11] {
            if !b.is_ascii_alphanumeric() {
                return false;
            }
        }
    }
    true
}

fn no_snake_case(s: &str) -> bool {
    let bytes = s.as_bytes();
    for i in 0..bytes.len() {
        if bytes[i] == b'_' && i > 0 && i + 1 < bytes.len() {
            let prev = bytes[i - 1] as char;
            let next = bytes[i + 1] as char;
            if prev.is_ascii_lowercase() && next.is_ascii_lowercase() {
                return false;
            }
        }
    }
    true
}

fn title_case_or_caps(s: &str) -> bool {
    // Treat as ALL-CAPS only if there's at least one ASCII alphabetic char AND
    // every alphabetic char is uppercase (allows digits, punctuation).
    let alpha_chars: Vec<char> = s.chars().filter(|c| c.is_alphabetic()).collect();
    if alpha_chars.is_empty() {
        return false;
    }
    if alpha_chars.iter().all(|c| c.is_uppercase()) {
        return true;
    }
    // Otherwise: at least one whitespace-separated word starts with uppercase.
    s.split_whitespace()
        .any(|w| w.chars().next().map_or(false, |c| c.is_uppercase()))
}

fn looks_like_address(s: &str) -> bool {
    let lower = s.to_lowercase();
    // Common Swiss / French street keywords
    const STREET_WORDS: &[&str] = &[
        "rue", "av.", "avenue", "chemin", "ch.", "route", "rte",
        "place", "pl.", "boulevard", "bd", "bd.", "impasse",
        "strasse", "str.", "via", "ruelle", "promenade", "quai",
    ];
    for kw in STREET_WORDS {
        // Match as whole-word or with surrounding punctuation
        if lower.contains(kw) {
            // Quick boundary check: must have a non-letter or start before, and
            // a non-letter or end after (we accept anything that's not in [a-z]).
            if let Some(idx) = lower.find(kw) {
                let before_ok = idx == 0
                    || !lower.as_bytes()[idx - 1].is_ascii_alphabetic();
                let after_idx = idx + kw.len();
                let after_ok = after_idx >= lower.len()
                    || !lower.as_bytes()[after_idx].is_ascii_alphabetic();
                if before_ok && after_ok {
                    return true;
                }
            }
        }
    }
    // CH-style postal code pattern: 4 digits followed by a Title-Case word
    // (e.g., "1004 Lausanne", "1204 Genève").
    let bytes = s.as_bytes();
    let len = bytes.len();
    for i in 0..len.saturating_sub(5) {
        // 4 digits
        if bytes[i].is_ascii_digit()
            && bytes[i + 1].is_ascii_digit()
            && bytes[i + 2].is_ascii_digit()
            && bytes[i + 3].is_ascii_digit()
        {
            // Word boundary before
            let before_ok = i == 0 || !bytes[i - 1].is_ascii_alphanumeric();
            if !before_ok {
                continue;
            }
            // Followed by whitespace + uppercase letter
            let mut j = i + 4;
            while j < len && bytes[j].is_ascii_whitespace() {
                j += 1;
            }
            if j < len && (bytes[j] as char).is_uppercase() {
                return true;
            }
        }
    }
    false
}

fn title_case_multi_word(s: &str) -> bool {
    let words: Vec<&str> = s.split_whitespace().collect();
    if words.len() < 2 {
        return false;
    }
    words
        .iter()
        .all(|w| w.chars().next().map_or(false, |c| c.is_uppercase()))
}

fn luhn_valid(s: &str) -> bool {
    let digits: Vec<u32> = s.chars().filter_map(|c| c.to_digit(10)).collect();
    if !(13..=19).contains(&digits.len()) {
        return false;
    }
    let mut sum: u32 = 0;
    let mut alt = false;
    for &d in digits.iter().rev() {
        let mut x = d;
        if alt {
            x *= 2;
            if x > 9 {
                x -= 9;
            }
        }
        sum += x;
        alt = !alt;
    }
    sum % 10 == 0
}

fn iban_valid(s: &str) -> bool {
    let cleaned: String = s.chars().filter(|c| !c.is_whitespace()).collect();
    if !(15..=34).contains(&cleaned.len()) {
        return false;
    }
    if !cleaned.is_ascii() {
        return false;
    }
    // Move first 4 characters to the end.
    let (head, tail) = cleaned.split_at(4);
    let rearranged: String = tail.chars().chain(head.chars()).collect();

    // Compute mod-97 on the streamed numeric representation
    // (letters → A=10, B=11, ..., Z=35).
    let mut remainder: u64 = 0;
    for c in rearranged.chars() {
        let value: u32 = if c.is_ascii_digit() {
            c.to_digit(10).unwrap()
        } else if c.is_ascii_uppercase() {
            (c as u32) - ('A' as u32) + 10
        } else {
            return false;
        };
        // Letters expand to two-digit numbers; process digit-by-digit.
        for digit_char in value.to_string().chars() {
            let d = digit_char.to_digit(10).unwrap();
            remainder = (remainder * 10 + d as u64) % 97;
        }
    }
    remainder == 1
}

fn swiss_avs_valid(s: &str) -> bool {
    // NAVS13: 13 digits, country prefix 756, last digit is EAN-13 mod-10 check.
    let digits: Vec<u32> = s.chars().filter_map(|c| c.to_digit(10)).collect();
    if digits.len() != 13 {
        return false;
    }
    if digits[0] != 7 || digits[1] != 5 || digits[2] != 6 {
        return false;
    }
    // EAN-13 check: positions 0..12 weighted 1,3,1,3,...; mod 10; check = (10 - sum%10) % 10.
    let sum: u32 = digits[..12]
        .iter()
        .enumerate()
        .map(|(i, &d)| if i % 2 == 0 { d } else { d * 3 })
        .sum();
    let check = (10 - (sum % 10)) % 10;
    check == digits[12]
}

fn shannon_entropy(s: &str) -> f64 {
    let total = s.chars().count();
    if total == 0 {
        return 0.0;
    }
    let mut counts: HashMap<char, u64> = HashMap::new();
    for c in s.chars() {
        *counts.entry(c).or_insert(0) += 1;
    }
    let len = total as f64;
    let mut h = 0.0;
    for &n in counts.values() {
        let p = n as f64 / len;
        h -= p * p.log2();
    }
    h
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_simple_validators() {
        assert_eq!(Validator::parse("luhn").unwrap(), Validator::Luhn);
        assert_eq!(
            Validator::parse("iban_checksum").unwrap(),
            Validator::IbanChecksum
        );
        assert_eq!(Validator::parse("swiss_avs").unwrap(), Validator::SwissAvs);
    }

    #[test]
    fn parse_entropy_with_threshold() {
        match Validator::parse("entropy:3.5").unwrap() {
            Validator::Entropy { min_bits_per_char } => {
                assert!((min_bits_per_char - 3.5).abs() < 1e-9)
            }
            _ => panic!("expected Entropy"),
        }
    }

    #[test]
    fn parse_unknown_validator_errors() {
        let err = Validator::parse("flux_capacitor").unwrap_err();
        assert!(format!("{err}").contains("unknown validator"));
    }

    #[test]
    fn parse_bad_entropy_threshold_errors() {
        assert!(Validator::parse("entropy:abc").is_err());
        assert!(Validator::parse("entropy:-1").is_err());
    }

    #[test]
    fn luhn_known_good() {
        // Public test cards (Visa, MC, Amex test numbers — all genuinely Luhn-valid).
        assert!(luhn_valid("4111 1111 1111 1111"));
        assert!(luhn_valid("5555555555554444"));
        assert!(luhn_valid("371449635398431"));
    }

    #[test]
    fn luhn_known_bad() {
        assert!(!luhn_valid("4111 1111 1111 1112")); // last digit off by one
        assert!(!luhn_valid("1234567890123"));
        assert!(!luhn_valid("not a card"));
    }

    #[test]
    fn iban_known_good() {
        // Real test IBAN structures (no live accounts).
        assert!(iban_valid("CH9300762011623852957")); // PostFinance test IBAN
        assert!(iban_valid("DE89370400440532013000"));
        assert!(iban_valid("GB82WEST12345698765432"));
        // Whitespace-tolerant
        assert!(iban_valid("CH93 0076 2011 6238 5295 7"));
    }

    #[test]
    fn iban_known_bad() {
        assert!(!iban_valid("CH9300762011623852958")); // last digit flipped
        assert!(!iban_valid("CH"));
        assert!(!iban_valid("not an iban at all"));
    }

    #[test]
    fn swiss_avs_known_good() {
        assert!(swiss_avs_valid("756.1234.5678.97"));
        assert!(swiss_avs_valid("7561234567897"));
    }

    #[test]
    fn swiss_avs_known_bad() {
        assert!(!swiss_avs_valid("756.1234.5678.98")); // bad check digit
        assert!(!swiss_avs_valid("123.4567.8901.23")); // wrong country prefix
        assert!(!swiss_avs_valid("756.1234.5678.9")); // too short
    }

    #[test]
    fn entropy_filters_low_information() {
        // A long key with high diversity → high entropy.
        let high = "aZ7xQ9pL3mK2nB8vF4cR6tY1wE5sD0";
        assert!(shannon_entropy(high) > 4.0);
        // A repetitive string → low entropy.
        let low = "aaaaaaaaaaaaaaaaaaaaaa";
        assert!(shannon_entropy(low) < 0.1);
    }

    #[test]
    fn vin_checksum_accepts_known_good() {
        // Public test VIN from the NHTSA decoder examples. Position 9 = '3'
        // matches sum 311 % 11 = 3.
        assert!(vin_checksum_valid("1HGCM82633A004352"));
    }

    #[test]
    fn vin_checksum_rejects_excel_garbage() {
        // 17-digit Excel cell value seen in the corpus 96× — passes the
        // bare regex but check digit doesn't match.
        assert!(!vin_checksum_valid("44791666666666669"));
        // Forbidden letters I/O/Q never appear in valid VINs.
        assert!(!vin_checksum_valid("1HGCM82633A00435I")); // contains I
        // Wrong length.
        assert!(!vin_checksum_valid("1HGCM82633A0043"));
    }
}
