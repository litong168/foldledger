//! 统计聚合与搜索匹配（纯函数，输入为扁平快照，便于 JNI 与测试）。

/// 一笔流水的最小视图（聚合/搜索用），金额为「分」。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Txn {
    pub amount_cents: i64,
    /// 0 = 支出，1 = 收入（与 Kotlin `Direction` 一致）
    pub direction: u8,
    pub category_id: i64,
    pub occurred_at: i64,
}

/// 一个分类的汇总。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CategoryTotal {
    pub category_id: i64,
    pub total_cents: i64,
}

/// 收支合计。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Totals {
    pub expense_cents: i64,
    pub income_cents: i64,
}

impl Totals {
    /// 结余 = 收入 − 支出。
    pub fn balance(&self) -> i64 {
        self.income_cents - self.expense_cents
    }
}

/// 只统计支出（消费洞察/分类占比固定看支出，与 Kotlin 侧一致）。
pub fn expense_category_totals(txns: &[Txn]) -> Vec<CategoryTotal> {
    let mut map: std::collections::BTreeMap<i64, i64> = std::collections::BTreeMap::new();
    for t in txns {
        if t.direction == 0 {
            *map.entry(t.category_id).or_insert(0) += t.amount_cents;
        }
    }
    map.into_iter()
        .map(|(category_id, total_cents)| CategoryTotal {
            category_id,
            total_cents,
        })
        .collect()
}

/// 收入合计。
pub fn income_total(txns: &[Txn]) -> i64 {
    txns.iter()
        .filter(|t| t.direction == 1)
        .map(|t| t.amount_cents)
        .sum()
}

/// 支出合计。
pub fn expense_total(txns: &[Txn]) -> i64 {
    txns.iter()
        .filter(|t| t.direction == 0)
        .map(|t| t.amount_cents)
        .sum()
}

/// 收支总计。
pub fn totals(txns: &[Txn]) -> Totals {
    Totals {
        expense_cents: expense_total(txns),
        income_cents: income_total(txns),
    }
}

/// 按给定时间区间过滤（`start_inclusive <= occurred_at < end_exclusive`）再合计。
pub fn totals_in_range(txns: &[Txn], start: i64, end: i64) -> Totals {
    totals(&filter_range(txns, start, end))
}

fn filter_range(txns: &[Txn], start: i64, end: i64) -> Vec<Txn> {
    txns.iter()
        .filter(|t| t.occurred_at >= start && t.occurred_at < end)
        .copied()
        .collect()
}

/// 支出环比：本月 vs 上月。返回 (本月, 上月) 月度支出额（分）。
pub fn expense_month_over_month(this_start: i64, this_end: i64, prev_start: i64, prev_end: i64, txns: &[Txn]) -> (i64, i64) {
    (
        expense_total(&filter_range(txns, this_start, this_end)),
        expense_total(&filter_range(txns, prev_start, prev_end)),
    )
}

/// 搜索匹配：命中「分类名 / 备注」任一子串（不区分大小写）。与 v2.0 明细搜索语义一致。
/// `name`/`note` 传入该笔的分类名与备注；`query` 为用户输入。
/// 搜索匹配（v4.5 增强）：支持**多关键词 AND** 与**金额匹配**。
///
/// - 关键词按空格切分，**每个词都要命中**（AND 语义，比整串匹配更符合直觉）；
/// - 命中范围：分类名 / 备注 / 金额文本（金额只在关键词是纯数字时才参与，
///   避免「午饭」这种中文词去跟金额文本瞎碰）；
/// - 金额同时比对「带千分位」与「不带千分位」两种写法，输入 `35`、`1,234.56`、
///   `1234.56` 都能找到对应流水。
pub fn matches_query(name: &str, note: &str, amount_cents: i64, query: &str) -> bool {
    let q = query.trim();
    if q.is_empty() {
        return true;
    }
    let name_l = name.to_lowercase();
    let note_l = note.to_lowercase();
    let plain = crate::money::to_plain(amount_cents.abs());
    let grouped = crate::money::format_cents(amount_cents.abs(), false);

    for token in q.split_whitespace() {
        let t = token.to_lowercase();
        if t.is_empty() {
            continue;
        }
        let mut hit = name_l.contains(&t) || note_l.contains(&t);
        if !hit && is_numeric_token(&t) {
            hit = plain.contains(&t) || grouped.contains(&t);
        }
        if !hit {
            return false;
        }
    }
    true
}

/// 关键词是不是「纯数字」（允许小数点与千分位逗号）—— 只有这种词才去匹配金额。
fn is_numeric_token(t: &str) -> bool {
    !t.is_empty() && t.chars().all(|c| c.is_ascii_digit() || c == '.' || c == ',')
}

/// 过滤出匹配搜索的流水索引（保持原顺序），供上层用索引取回完整实体。
pub fn search_indices(
    names: &[String],
    notes: &[String],
    amounts: &[i64],
    query: &str,
) -> Vec<usize> {
    names
        .iter()
        .enumerate()
        .filter(|(i, n)| {
            matches_query(
                n,
                notes.get(*i).map(|s| s.as_str()).unwrap_or(""),
                amounts.get(*i).copied().unwrap_or(0),
                query,
            )
        })
        .map(|(i, _)| i)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn t(amount: i64, dir: u8, cat: i64, at: i64) -> Txn {
        Txn {
            amount_cents: amount,
            direction: dir,
            category_id: cat,
            occurred_at: at,
        }
    }

    #[test]
    fn totals_and_balance() {
        let txns = vec![t(1000, 0, 1, 0), t(2000, 0, 2, 0), t(5000, 1, 3, 0)];
        let tot = totals(&txns);
        assert_eq!(tot.expense_cents, 3000);
        assert_eq!(tot.income_cents, 5000);
        assert_eq!(tot.balance(), 2000);
    }

    #[test]
    fn expense_only_category_totals() {
        let txns = vec![t(1000, 0, 1, 0), t(500, 0, 1, 0), t(999, 1, 1, 0)];
        let cats = expense_category_totals(&txns);
        assert_eq!(cats.len(), 1);
        assert_eq!(cats[0].category_id, 1);
        assert_eq!(cats[0].total_cents, 1500); // 不含收入那笔
    }

    #[test]
    fn range_filter() {
        let txns = vec![t(100, 0, 1, 10), t(200, 0, 1, 20), t(300, 0, 1, 30)];
        let tot = totals_in_range(&txns, 15, 30);
        assert_eq!(tot.expense_cents, 200);
    }

    #[test]
    fn month_over_month() {
        let txns = vec![t(100, 0, 1, 100), t(400, 0, 1, 300)];
        let (this_m, prev_m) = expense_month_over_month(200, 400, 0, 200, &txns);
        assert_eq!(this_m, 400);
        assert_eq!(prev_m, 100);
    }

    #[test]
    fn search_matches_name_or_note() {
        assert!(matches_query("餐饮", "午餐", 0, "餐"));
        assert!(matches_query("餐饮", "午餐", 0, "午餐"));
        assert!(!matches_query("餐饮", "午餐", 0, "不相关"));
        assert!(matches_query("餐饮", "午餐", 0, "")); // 空查询 = 全通过
        assert!(matches_query("Cafe", "note", 0, "cafe")); // 不区分大小写
    }

    #[test]
    fn search_multi_keyword_and() {
        // 多个词是 AND：两个词都命中才算
        assert!(matches_query("餐饮", "公司午餐", 0, "餐饮 午餐"));
        assert!(!matches_query("餐饮", "公司午餐", 0, "餐饮 打车"));
        // 多余空格不影响
        assert!(matches_query("餐饮", "午餐", 0, "  餐饮   午餐  "));
    }

    #[test]
    fn search_matches_amount() {
        // 3500 分 = ¥35.00；纯数字关键词才参与金额匹配
        assert!(matches_query("餐饮", "午饭", 3500, "35"));
        assert!(matches_query("餐饮", "午饭", 123456, "1234.56"));
        assert!(matches_query("餐饮", "午饭", 123456, "1,234"));
        assert!(!matches_query("餐饮", "午饭", 3500, "36"));
        // 非数字关键词不去碰金额，避免「午饭」误命中
        assert!(!matches_query("交通", "", 3500, "午饭"));
        // 关键词 + 金额组合（AND）
        assert!(matches_query("餐饮", "午饭", 3500, "餐饮 35"));
    }

    #[test]
    fn search_indices_order() {
        let names = vec!["餐饮".to_string(), "交通".to_string(), "餐饮酒".to_string()];
        let notes = vec!["a".to_string(), "b".to_string(), "c".to_string()];
        let amounts = vec![100i64, 12345, 300];
        assert_eq!(search_indices(&names, &notes, &amounts, "餐"), vec![0, 2]);
        // 金额也能命中：12345 分 = ¥123.45
        assert_eq!(search_indices(&names, &notes, &amounts, "123.45"), vec![1]);
    }
}
