#!/usr/bin/env python3
"""字号字阶校验（v13.7，v16.2.5 修复扫描路径）。

只允许 `ui/theme/Type.kt` 里 `TypeScale.allowed` 那几个字号。
出现表外数字就报错 —— 1sp 的差在 440dpi 上只有 2.75px，
既读不成层级、又读得成"没对齐"，这类改动必须在合并前挡住。

⚠️ v16.2.5 事故：M1 拆分 KMP 后代码从 `app/src/main/java` 搬到
`common/src/commonMain`，本脚本的 ROOT 没跟着改 —— 结果**扫到 0 个文件
仍然打印 ✅**，字阶闸门静默失效了两个大版本（跑出来 12.sp/14.sp 各若干）。
因此本版加了三道自保：
  1. 扫描根目录列表化（common 三源集 + desktop 壳 + app 壳，存在才扫）；
  2. `total == 0` 直接判失败 —— 空扫描永远是事故，不是"全部合规"；
  3. 允许字号从 Type.kt 的 `allowed` 集合**实时解析**，脚本与主题表无法再漂移。

用法：python scripts/check_type_scale.py
"""
import os
import re
import sys

ROOT_OUT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 代码可能在的源集（哪里存在扫哪里）。KMP 拆分后 UI 全在 commonMain。
SCAN_ROOTS = [
    os.path.join('common', 'src', 'commonMain'),
    os.path.join('common', 'src', 'androidMain'),
    os.path.join('common', 'src', 'desktopMain'),
    os.path.join('desktop', 'src'),
    os.path.join('app', 'src'),
]

TYPE_KT = os.path.join(ROOT_OUT, 'common', 'src', 'commonMain', 'kotlin',
                       'com', 'foldledger', 'ui', 'theme', 'Type.kt')

# 兜底表：Type.kt 解析失败时用它，并给出警告（不静默）
FALLBACK_ALLOWED = {11.0, 13.0, 15.0, 17.0, 20.0, 24.0, 30.0, 44.0}

# 三条轴都要查：
# 1) `fontSize = 12.sp`      —— 内联 TextStyle / Text 参数
# 2) `fontSize = 23,`        —— 组件里以 Int 传的字号（AnimatedCentsText / AmountText / CategoryIcon）
# 3) `fontSize: Int = 22`    —— 上面那些组件的默认值
# 只查 (1) 会漏掉整整一条轴：v13.7 之前 Int 轴里有 21/22/23/26/32 五个表外值。
PAT_SP = re.compile(r'fontSize = ([0-9]+(?:\.[0-9]+)?)\.sp')
PAT_INT = re.compile(r'fontSize = ([0-9]+)(?=[,)])')
PAT_DEF = re.compile(r'fontSize: Int = ([0-9]+)')


def load_allowed():
    """从 Type.kt 的 `val allowed: Set<Float> = setOf(...)` 解析真实字阶。"""
    try:
        with open(TYPE_KT, encoding='utf-8') as f:
            src = f.read()
    except OSError:
        print(f'⚠️ 读不到 {os.path.relpath(TYPE_KT, ROOT_OUT)}，退回内置字阶表')
        return FALLBACK_ALLOWED
    m = re.search(r'val allowed\s*:\s*Set<Float>\s*=\s*setOf\(([^)]*)\)', src)
    if not m:
        print('⚠️ Type.kt 里没找到 `val allowed: Set<Float> = setOf(...)`，退回内置字阶表')
        return FALLBACK_ALLOWED
    nums = {float(x) for x in re.findall(r'[0-9]+(?:\.[0-9]+)?', m.group(1))}
    return nums or FALLBACK_ALLOWED


def fmt(s):
    return '/'.join(f'{v:g}' for v in sorted(s))


def main() -> int:
    allowed = load_allowed()
    bad = []
    total = 0
    scanned_files = 0
    scanned_roots = []

    for rel_root in SCAN_ROOTS:
        base = os.path.join(ROOT_OUT, rel_root)
        if not os.path.isdir(base):
            continue
        root_files = 0
        for dp, _, fns in os.walk(base):
            # 跳过构建产物，避免扫到生成的 Kotlin
            if f'{os.sep}build{os.sep}' in dp + os.sep:
                continue
            for fn in sorted(fns):
                if not fn.endswith('.kt'):
                    continue
                path = os.path.join(dp, fn)
                rel = os.path.relpath(path, ROOT_OUT).replace('\\', '/')
                root_files += 1
                scanned_files += 1
                with open(path, encoding='utf-8') as f:
                    for no, line in enumerate(f, 1):
                        for pat in (PAT_SP, PAT_INT, PAT_DEF):
                            for m in pat.finditer(line):
                                total += 1
                                v = float(m.group(1))
                                if v not in allowed:
                                    bad.append((rel, no, v, line.strip()[:90]))
        if root_files:
            scanned_roots.append(f'{rel_root} ({root_files})')

    print(f'扫描源集：{", ".join(scanned_roots) if scanned_roots else "（无）"}')
    print(f'扫描 .kt 文件：{scanned_files}')
    print(f'字号使用点：{total}（允许 {fmt(allowed)}）')

    # 空扫描 = 闸门失效，必须响亮地失败
    if total == 0:
        print('❌ 字号使用点 0 个 —— 扫描路径失效（代码可能已迁走），这不是"全部合规"')
        return 2

    if not bad:
        print('✅ 全部落在字阶内')
        return 0

    print(f'❌ {len(bad)} 处不在字阶内：')
    for rel, no, v, code in bad:
        print(f'  {rel}:{no}  {v:g}.sp   {code}')
    return 1


if __name__ == '__main__':
    sys.exit(main())
