#!/usr/bin/env python3
"""字号字阶校验（v13.7）。

只允许 `ui/theme/Type.kt` 里 `TypeScale.allowed` 那几个字号。
出现表外数字就报错 —— 1sp 的差在 440dpi 上只有 2.75px，
既读不成层级、又读得成"没对齐"，这类改动必须在合并前挡住。

用法：python scripts/check_type_scale.py
"""
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    'app', 'src', 'main', 'java', 'com', 'foldledger')
ALLOWED = {11.0, 13.0, 15.0, 17.0, 20.0, 24.0, 30.0, 44.0}

# 三条轴都要查：
# 1) `fontSize = 12.sp`      —— 内联 TextStyle / Text 参数
# 2) `fontSize = 23,`        —— 组件里以 Int 传的字号（AnimatedCentsText / AmountText / CategoryIcon）
# 3) `fontSize: Int = 22`    —— 上面那些组件的默认值
# 只查 (1) 会漏掉整整一条轴：v13.7 之前 Int 轴里有 21/22/23/26/32 五个表外值。
PAT_SP = re.compile(r'fontSize = ([0-9]+(?:\.[0-9]+)?)\.sp')
PAT_INT = re.compile(r'fontSize = ([0-9]+)(?=[,)])')
PAT_DEF = re.compile(r'fontSize: Int = ([0-9]+)')


def main() -> int:
    bad = []
    total = 0
    for dp, _, fns in os.walk(ROOT):
        for fn in sorted(fns):
            if not fn.endswith('.kt'):
                continue
            path = os.path.join(dp, fn)
            rel = os.path.relpath(path, ROOT).replace('\\', '/')
            with open(path, encoding='utf-8') as f:
                for no, line in enumerate(f, 1):
                    for pat in (PAT_SP, PAT_INT, PAT_DEF):
                        for m in pat.finditer(line):
                            total += 1
                            v = float(m.group(1))
                            if v not in ALLOWED:
                                bad.append((rel, no, v, line.strip()[:90]))

    print(f'字号使用点：{total}')
    if not bad:
        print('✅ 全部落在字阶内（11/13/15/17/20/24/30/44）')
        return 0

    print(f'❌ {len(bad)} 处不在字阶内：')
    for rel, no, v, code in bad:
        print(f'  {rel}:{no}  {v:g}.sp   {code}')
    return 1


if __name__ == '__main__':
    sys.exit(main())
