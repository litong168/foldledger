//! 金额运算（以「分 / i64」为唯一单位，避免浮点误差）。
//!
//! 与 Kotlin 侧 `util/Money.kt` **逐字符等价**——两边的格式化/解析输出必须完全一致，
//! 否则 UI 显示的金额、输入框解析会互相打架。所有函数均为纯函数，`cargo test` 可验证。

/// 1234 分 -> "12.34"（不带符号、不带千分位，用于输入框）。
/// 负数带前导 `-`：-1234 -> "-12.34"。
pub fn to_plain(cents: i64) -> String {
    let neg = cents < 0;
    let v = cents.unsigned_abs();
    let s = format!("{}.{:02}", v / 100, v % 100);
    if neg {
        format!("-{s}")
    } else {
        s
    }
}

/// 12345678 分 -> "¥123,456.78"。`symbol=false` 时不带 ¥。
///
/// 规则（与 Kotlin `Money.format` 完全一致）：
/// - 负号在 ¥ 之前："−¥123"；
/// - 整数部分三位一撇；
/// - **分为 0 时省略小数**："¥123"，而不是 "¥123.00"。
pub fn format_cents(cents: i64, symbol: bool) -> String {
    let neg = cents < 0;
    let v = cents.unsigned_abs();
    let yuan = v / 100;
    let fen = v % 100;
    let grouped = group_thousands(yuan);

    let mut out = String::with_capacity(grouped.len() + 6);
    if neg {
        out.push('-');
    }
    if symbol {
        out.push('¥');
    }
    out.push_str(&grouped);
    if fen != 0 {
        out.push('.');
        out.push_str(&format!("{fen:02}"));
    }
    out
}

/// 紧凑显示：2500000 分 -> "2.5万"、12345678 分 -> "12.35万"。与 Kotlin `Money.formatCompact` 等价。
pub fn format_compact(cents: i64) -> String {
    let neg = cents < 0;
    let sign = if neg { "-" } else { "" };
    let yuan = cents.unsigned_abs() as f64 / 100.0;
    if yuan >= 100_000_000.0 {
        format!("{sign}{}亿", trim_zero(yuan / 100_000_000.0))
    } else if yuan >= 10_000.0 {
        format!("{sign}{}万", trim_zero(yuan / 10_000.0))
    } else {
        format_cents(cents, true)
    }
}

/// "12.34" -> 1234；非法输入返回 None。与 Kotlin `Money.parseToCents` 等价。
pub fn parse_to_cents(input: &str) -> Option<i64> {
    let t = input.trim().replace('¥', "").replace(',', "");
    if t.is_empty() || t == "." || t == "-" {
        return None;
    }
    let (int_part, frac_part) = match t.split_once('.') {
        Some((i, f)) => (i, f),
        None => (t.as_str(), ""),
    };
    // 整数部分允许一个前导负号；其余必须是数字
    let (neg, digits) = match int_part.strip_prefix('-') {
        Some(rest) => (true, rest),
        None => (false, int_part),
    };
    if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    if !frac_part.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    // 取两位小数，四舍五入（HALF_UP，与 Kotlin BigDecimal.setScale(2, HALF_UP) 一致）
    let mut frac = String::new();
    frac.push(frac_part.as_bytes().get(0).map(|&c| c as char).unwrap_or('0'));
    frac.push(frac_part.as_bytes().get(1).map(|&c| c as char).unwrap_or('0'));
    let mut round_up = false;
    if frac_part.len() > 2 {
        round_up = frac_part.as_bytes()[2] >= b'5';
    }
    let yuan: i64 = digits.parse().ok()?;
    let mut cents = yuan * 100 + frac.parse::<i64>().ok()?;
    if round_up {
        cents += 1;
    }
    Some(if neg { -cents } else { cents })
}

/// 百分比：0.256 -> "25.6%"。与 Kotlin `Money.percent` 等价。
pub fn percent(part: i64, total: i64) -> String {
    if total <= 0 {
        return "0%".to_string();
    }
    let p = part as f64 / total as f64 * 100.0;
    format!("{p:.1}%")
}

/// 三位一撇（usize）。1234567 -> "1,234,567"。
fn group_thousands(n: u64) -> String {
    let s = n.to_string();
    let bytes = s.as_bytes();
    let mut out = String::with_capacity(s.len() + s.len() / 3);
    for (i, &b) in bytes.iter().enumerate() {
        if i > 0 && (bytes.len() - i) % 3 == 0 {
            out.push(',');
        }
        out.push(b as char);
    }
    out
}

/// 去掉多余 0："1.20" -> "1.2"、"3.00" -> "3"。
fn trim_zero(v: f64) -> String {
    let s = format!("{v:.2}");
    s.trim_end_matches('0').trim_end_matches('.').to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_formats() {
        assert_eq!(to_plain(1234), "12.34");
        assert_eq!(to_plain(5), "0.05");
        assert_eq!(to_plain(0), "0.00");
        assert_eq!(to_plain(-1234), "-12.34");
    }

    #[test]
    fn format_with_symbol_and_commas() {
        assert_eq!(format_cents(12345678, true), "¥123,456.78");
        assert_eq!(format_cents(12345678, false), "123,456.78");
        assert_eq!(format_cents(12300, true), "¥123");
        assert_eq!(format_cents(-12300, true), "-¥123");
        assert_eq!(format_cents(100000, true), "¥1,000");
    }

    #[test]
    fn compact() {
        assert_eq!(format_compact(12345678), "12.35万");
        assert_eq!(format_compact(2_500_000), "2.5万");
        assert_eq!(format_compact(12300), "¥123");
        assert_eq!(format_compact(1_230_000_000_00), "12.3亿");
    }

    #[test]
    fn parse_roundtrip() {
        assert_eq!(parse_to_cents("12.34"), Some(1234));
        assert_eq!(parse_to_cents("¥12.34"), Some(1234));
        assert_eq!(parse_to_cents("1,234.56"), Some(123456));
        assert_eq!(parse_to_cents("-12.34"), Some(-1234));
        assert_eq!(parse_to_cents("12"), Some(1200));
        assert_eq!(parse_to_cents("12.345"), Some(1235)); // HALF_UP
        assert_eq!(parse_to_cents(""), None);
        assert_eq!(parse_to_cents("abc"), None);
        assert_eq!(parse_to_cents("."), None);
    }

    #[test]
    fn percent_works() {
        assert_eq!(percent(256, 1000), "25.6%");
        assert_eq!(percent(1, 0), "0%");
    }
}
