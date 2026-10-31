//! 滴水记账 Rust 核心。
//!
//! 职责边界（v4.0）：**纯业务计算**移到 Rust —— 金额运算、统计聚合、搜索匹配、
//! 备份完整性。UI 仍是 Kotlin / Jetpack Compose（保留折叠屏适配与澎湃动画），
//! 通过 JNI 调用这里；`.so` 不可用时 Kotlin 侧无缝回退到等价实现。
//!
//! 这条「Rust 核心 + JNI + Compose 外壳」是让 App **继续安装在小米 18 FOLD** 的
//! 唯一稳妥路线：不换 UI 框架、不动包名/签名/DB/备份格式，升级链完好。

pub mod backup;
pub mod money;
pub mod stats;

pub use backup::{fnv1a, peek as backup_peek, validate as validate_backup, BackupSummary};
pub use money::{format_cents, format_compact, parse_to_cents, percent, to_plain};
pub use stats::{
    expense_category_totals, expense_month_over_month, expense_total, income_total,
    matches_query, search_indices, totals, totals_in_range, CategoryTotal, Totals, Txn,
};

/// 无效解析的哨兵（JNI 传 Long 时用；Kotlin 映射为 null）。
pub const PARSE_INVALID: i64 = i64::MIN;

// ============================================================
//  JNI 导出（仅在 `android` feature + Android 目标下编入 .so）
// ============================================================
#[cfg(all(feature = "android", target_os = "android"))]
#[allow(non_snake_case)]
mod ffi {
    use super::*;
    use jni::objects::{JByteArray, JClass, JString};
    use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
    use jni::JNIEnv;

    fn bool_to_j(b: bool) -> jboolean {
        if b {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }

    /// 读 Java String；读取失败时退化为空串（不让异常穿透到 JNI 边界）
    fn read_string(env: &mut JNIEnv, s: &JString) -> String {
        env.get_string(s)
            .map(|v| v.into())
            .unwrap_or_else(|_| String::new())
    }

    #[no_mangle]
    pub extern "system" fn Java_com_foldledger_core_DripCore_nativeFormatCents(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
        cents: jlong,
        symbol: jboolean,
    ) -> jstring {
        let s = format_cents(cents, symbol == JNI_TRUE);
        env.new_string(s)
            .map(|v| v.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }

    #[no_mangle]
    pub extern "system" fn Java_com_foldledger_core_DripCore_nativeToPlain(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
        cents: jlong,
    ) -> jstring {
        env.new_string(to_plain(cents))
            .map(|v| v.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }

    #[no_mangle]
    pub extern "system" fn Java_com_foldledger_core_DripCore_nativeParseToCents(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
        input: JString<'_>,
    ) -> jlong {
        let s = read_string(&mut env, &input);
        parse_to_cents(&s).unwrap_or(PARSE_INVALID)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_foldledger_core_DripCore_nativeFnv1a(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
        data: JByteArray<'_>,
    ) -> jint {
        match env.convert_byte_array(&data) {
            Ok(bytes) => fnv1a(&bytes) as jint,
            Err(_) => 0,
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_com_foldledger_core_DripCore_nativeValidateBackup(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
        data: JByteArray<'_>,
    ) -> jboolean {
        match env.convert_byte_array(&data) {
            Ok(bytes) => bool_to_j(validate_backup(&bytes)),
            Err(_) => JNI_FALSE,
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_com_foldledger_core_DripCore_nativeMatchesQuery(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
        name: JString<'_>,
        note: JString<'_>,
        amount_cents: jlong,
        query: JString<'_>,
    ) -> jboolean {
        let n = read_string(&mut env, &name);
        let nt = read_string(&mut env, &note);
        let q = read_string(&mut env, &query);
        bool_to_j(matches_query(&n, &nt, amount_cents, &q))
    }
}
