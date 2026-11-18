#!/usr/bin/env python3
"""把 l10n-dict.json 生成 Kotlin 字典 EnStrings.kt。

字典维护在 scripts/l10n-dict.json（中文原文 -> 英文）。生成物提交到源码树，
运行时零依赖、离线可构建。未命中的词条在 Strings.kt#t() 里回退中文。
"""
import json, os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DICT = os.path.join(HERE, "l10n-dict.json")
OUT = os.path.join(
    ROOT, "common", "src", "commonMain", "kotlin", "com", "foldledger", "ui", "l10n", "EnStrings.kt"
)

with open(DICT, encoding="utf-8") as f:
    d = json.load(f)


def kt_str(s: str) -> str:
    # Kotlin 字符串转义：反斜杠、双引号、$、换行
    return (
        s.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("$", "\\$")
        .replace("\n", "\\n")
        .replace("\r", "")
    )


lines = [
    "// 由 scripts/gen_l10n.py 从 l10n-dict.json 生成，请勿手改。",
    "package com.foldledger.ui.l10n",
    "",
    "object EnStrings {",
    f"    val map: Map<String, String> = mapOf(",
]
for k, v in d.items():
    lines.append(f'        "{kt_str(k)}" to "{kt_str(v)}",')
lines += ["    )", "}", ""]

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))

print(f"生成 {OUT}，共 {len(d)} 条")
