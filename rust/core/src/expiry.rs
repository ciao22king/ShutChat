//! Invite expiry, stored as a day number so every peer agrees on the deadline.

use std::time::{SystemTime, UNIX_EPOCH};

/// 2024-01-01T00:00Z in whole days since the Unix epoch.
pub const ORIGIN_DAY: i32 = 19_723;

/// Stored in the code to mean "never expires".
pub const NEVER: i32 = 0xFFFF;

const DAY_MILLIS: i64 = 86_400_000;

pub fn day_of(epoch_millis: i64) -> i32 {
    epoch_millis.div_euclid(DAY_MILLIS) as i32
}

pub fn end_of_day_millis(day: i32) -> i64 {
    (day as i64 + 1) * DAY_MILLIS
}

pub fn expires_at_millis(expiry_day: Option<i32>) -> Option<i64> {
    expiry_day.map(|day| end_of_day_millis(day + ORIGIN_DAY))
}

pub fn day_from_expiry_millis(expires_at_millis: i64) -> i32 {
    day_of(expires_at_millis) - ORIGIN_DAY
}

pub fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

pub fn is_expired(expiry_day: Option<i32>) -> bool {
    match expires_at_millis(expiry_day) {
        Some(deadline) => now_millis() >= deadline,
        None => false,
    }
}

/// Day number for "a code that lasts [days] from today".
pub fn day_in(days: i64) -> i32 {
    day_from_expiry_millis(now_millis() + days * DAY_MILLIS) - 1
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn origin_matches_the_kotlin_constant() {
        // 2024-01-01T00:00:00Z
        assert_eq!(day_of(1_704_067_200_000), ORIGIN_DAY);
    }

    #[test]
    fn a_day_number_ends_at_utc_midnight() {
        assert_eq!(expires_at_millis(Some(0)), Some(86_400_000 + 19_723 * DAY_MILLIS));
        assert_eq!(expires_at_millis(None), None);
    }

    #[test]
    fn presets_land_on_the_right_day() {
        let day = day_in(7);
        assert!(day > 0);
        assert!(!is_expired(Some(day)));
    }
}
