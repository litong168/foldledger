//! 备份完整性：稳定哈希（校验）+ 信封摘要（快速窥探）。
//!
//! ⚠️ 这里**刻意不重写备份格式**——`.foldledger.json` 的编解码留在 Kotlin（已验证、
//! 老备份可恢复）。Rust 只提供「算得快、可并行、确定性」的纯计算：完整性校验和摘要，
//! 让「这份备份是否完整 / 里面有几本账本几笔流水」不依赖整段 JSON 反序列化。

/// FNV-1a 32 位稳定哈希：跨语言、跨平台、跨版本结果恒定。
/// 用于备份文件完整性标识（与 Kotlin 侧 `DripCore.fnv1a` 对齐）。
pub fn fnv1a(data: &[u8]) -> u32 {
    let mut h: u32 = 0x811c9dc5;
    for &b in data {
        h ^= b as u32;
        h = h.wrapping_mul(0x01000193);
    }
    h
}

/// 备份摘要：`format` 是否为本 App、版本号、账本数、流水数。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BackupSummary {
    pub is_drip_backup: bool,
    pub version: i32,
    pub ledger_count: i64,
    pub txn_count: i64,
}

/// 快速窥探备份信封（前缀扫描，不做完整反序列化）。
///
/// 只认 `format` 是否等于 `"foldledger.backup"`，并把 `version/ledgerCount/txnCount`
/// 三个数字抠出来。文件损坏 / 不是本 App 备份时 `is_drip_backup = false`。
pub fn peek(json: &str) -> BackupSummary {
    let is_drip = json.contains("\"format\":\"foldledger.backup\"");
    BackupSummary {
        is_drip_backup: is_drip,
        version: json_int(json, "version").unwrap_or(0) as i32,
        ledger_count: json_int(json, "ledgerCount").unwrap_or(0),
        txn_count: json_int(json, "txnCount").unwrap_or(0),
    }
}

/// 校验一份备份字节是否「像是完整且可读」的滴水记账备份。
/// 判据：含 format 标签 + version 在 [1, 2] + 有 transactions/ledgers 数组开口。
pub fn validate(bytes: &[u8]) -> bool {
    let s = String::from_utf8_lossy(bytes);
    let summary = peek(&s);
    summary.is_drip_backup
        && (1..=2).contains(&summary.version)
        && s.contains("\"ledgers\":")
        && s.contains("\"transactions\":")
}

/// 在一段 `text` 里找 `"key":` 后面的整数（无引号）。找不到返回 None。
fn json_int(text: &str, key: &str) -> Option<i64> {
    let pat = format!("\"{key}\":");
    let idx = text.find(&pat)?;
    let rest = &text[idx + pat.len()..];
    let num: String = rest
        .chars()
        .take_while(|c| c.is_ascii_digit() || *c == '-')
        .collect();
    if num.is_empty() || num == "-" {
        None
    } else {
        num.parse().ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fnv1a_stable() {
        // 已知向量：FNV-1a("") = 0x811c9dc5
        assert_eq!(fnv1a(b""), 0x811c9dc5);
        assert_eq!(fnv1a(b"a"), 0xe40c292c);
        assert_eq!(fnv1a("滴水记账".as_bytes()), fnv1a("滴水记账".as_bytes()));
        assert_ne!(fnv1a("滴水记账".as_bytes()), fnv1a("滴水记帐".as_bytes()));
    }

    #[test]
    fn peek_reads_envelope() {
        let s = r#"{"format":"foldledger.backup","version":2,"appVersion":"4.0.0","exportedAt":1,"ledgerCount":3,"txnCount":42,"ledgers":[],"categories":[],"transactions":[]}"#;
        let s2 = peek(s);
        assert!(s2.is_drip_backup);
        assert_eq!(s2.version, 2);
        assert_eq!(s2.ledger_count, 3);
        assert_eq!(s2.txn_count, 42);
    }

    #[test]
    fn validate_accepts_good_rejects_bad() {
        let good = r#"{"format":"foldledger.backup","version":2,"ledgerCount":0,"txnCount":0,"ledgers":[],"transactions":[]}"#;
        assert!(validate(good.as_bytes()));
        assert!(!validate(b"not a backup"));
        assert!(!validate(br#"{"format":"foldledger.backup","version":99,"ledgers":[],"transactions":[]}"#));
    }
}
