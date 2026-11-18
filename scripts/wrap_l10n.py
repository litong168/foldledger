#!/usr/bin/env python3
"""把 UI 源码里"能在本字典里查到"的中文字符串字面量包成 t("...")。

设计要点（安全优先）：
- 只扫 UI 相关目录（commonMain/ui、androidMain/ui、desktopMain/ui）。
- 只处理**整个字符串字面量就是一条字典 key**的情况：Text("取消") -> Text(t("取消"))。
  字符串模板、拼接、含 $ 的都不碰（避免改坏动态文案）。
- 已包过 t() / tr() 的跳过，可重复运行（幂等）。
- 跳过 // 行注释与 /* */ 块注释里的字面量（粗粒度按行判断）。
- 每个文件若发生改动，自动在 import 区补 `import com.foldledger.ui.l10n.t`（若缺）。

用法：
  python scripts/wrap_l10n.py           # 实际改写
  python scripts/wrap_l10n.py --dry     # 只预览命中数量，不写盘
"""
import json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DICT_PATH = os.path.join(ROOT, "scripts", "l10n-dict.json")
with open(DICT_PATH, encoding="utf-8") as f:
    KEYS = set(json.load(f).keys())

DIRS = [
    os.path.join(ROOT, "common", "src", "commonMain", "kotlin", "com", "foldledger", "ui"),
    os.path.join(ROOT, "common", "src", "androidMain", "kotlin", "com", "foldledger", "ui"),
    os.path.join(ROOT, "common", "src", "desktopMain", "kotlin", "com", "foldledger", "ui"),
]
IMPORT_LINE = "import com.foldledger.ui.l10n.t"
DRY = "--dry" in sys.argv

# 这些行整体跳过（字符串参与逻辑，不能包 t()）
SKIP_SUBSTR = [
    'it.title ==',          # AWT 窗口标题查找
    'Bundle.',              # 系统常量
]

# 匹配双引号字符串字面量（不含转义引号、不含换行的简单形式）
STR = re.compile(r'"([^"\\\n]*)"')


def wrap_file(path):
    with open(path, encoding="utf-8") as f:
        lines = f.readlines()
    changed = False
    hits = 0
    out = []
    for line in lines:
        stripped = line.lstrip()
        # 跳过纯注释行
        if stripped.startswith("//") or stripped.startswith("*"):
            out.append(line)
            continue
        # 跳过 import 行（避免把路径当文案）
        if stripped.startswith("import "):
            out.append(line)
            continue

        def repl(m):
            nonlocal changed, hits
            body = m.group(1)
            # 字符串模板（含 ${...}）不包 t()：字典按中文原文匹配，模板在运行期变量已展开，
            # 包了也匹配不到字典 key，反而增加噪音。保持原样。
            if "${" in body:
                return m.group(0)
            if body in KEYS:
                start = m.start()
                prefix = line[max(0, start - 4):start]
                # 已包过 t(/tr( ：幂等跳过
                if prefix.endswith("t(") or prefix.endswith("tr("):
                    return m.group(0)
                # 比较上下文（== / != ）：这些字符串参与逻辑判断/窗口查找，翻译会破坏功能
                left = line[:start].rstrip()
                if re.search(r'(==|!=)\s*$', left):
                    return m.group(0)
                # 常量/枚举存储上下文
                if re.search(r'\bconst val\b', left) or re.search(r'\bval\s+\w+\s*=\s*$', left):
                    return m.group(0)
                # 显式跳过清单（如窗口标题匹配）
                if any(s in line for s in SKIP_SUBSTR):
                    return m.group(0)
                changed = True
                hits += 1
                return 't("' + body + '")'
            return m.group(0)

        new = STR.sub(repl, line)
        out.append(new)

    if changed and IMPORT_LINE not in "".join(out):
        # 在最后一个 import 后插入，否则在 package 后
        idx = None
        for i, ln in enumerate(out):
            if ln.startswith("import "):
                idx = i
        insert_at = (idx + 1) if idx is not None else None
        if insert_at is None:
            for i, ln in enumerate(out):
                if ln.startswith("package "):
                    insert_at = i + 2  # package 行 + 其后的空行
                    break
        if insert_at is not None:
            out.insert(insert_at, IMPORT_LINE + "\n")

    if changed and not DRY:
        with open(path, "w", encoding="utf-8") as f:
            f.writelines(out)
    return hits


total = 0
files = 0
for d in DIRS:
    for base, _, names in os.walk(d):
        # 跳过 l10n 目录自身（Strings/EnStrings/LanguageCard 既不能用 t 也不能被 t 包）
        if os.sep + "l10n" in base + os.sep:
            continue
        for name in names:
            if not name.endswith(".kt"):
                continue
            n = wrap_file(os.path.join(base, name))
            if n:
                files += 1
                total += n

print(("预览" if DRY else "改写") + f": {total} 处命中，涉及 {files} 个文件")
