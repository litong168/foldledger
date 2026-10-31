"""
生成软著登记用的「源程序文档」（前 30 页 + 后 30 页）。

软著要求（全国版权保护中心）：
  · 源程序前 30 页 + 后 30 页，每页不少于 50 有效行；
  · 页眉标注软件全称、版本号，页脚或页眉标注页码；
  · 总页数不足 60 页则全部提交。

用法：
    python scripts/gen_copyright_source.py
    -> 生成 dist/软著-源程序文档-滴水记账.txt
"""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "dist", "软著-源程序文档-滴水记账.txt")

SOFTWARE = "滴水记账"
VERSION = "V9.0"
LINES_PER_PAGE = 50

# 参与提交的源文件：Kotlin 主源码 + Rust 核心（排除测试与构建产物）
SKIP_DIR_PARTS = {"test", "build", "dist", ".gradle", ".workbuddy", "jniLibs", "generated"}


def collect_files():
    files = []
    for base in ("app/src/main/java", "rust/drip-core/src"):
        top = os.path.join(ROOT, base)
        for dirpath, dirnames, filenames in os.walk(top):
            dirnames[:] = [d for d in dirnames if d not in SKIP_DIR_PARTS]
            for fn in sorted(filenames):
                if fn.endswith((".kt", ".rs")):
                    files.append(os.path.join(dirpath, fn))
    return files


def main():
    lines_out = []
    for path in collect_files():
        rel = os.path.relpath(path, ROOT).replace("\\", "/")
        with open(path, encoding="utf-8") as f:
            src = f.read().splitlines()
        # 去掉纯空行与纯注释行（软著只认有效代码行）
        body = []
        for ln in src:
            s = ln.strip()
            if not s or s.startswith("//") or s.startswith("*") or s.startswith("/*"):
                continue
            body.append(ln.rstrip())
        if not body:
            continue
        lines_out.append(f"/* 文件: {rel} */")
        lines_out.extend(body)
        lines_out.append("")

    total = len(lines_out)
    pages_needed = (total + LINES_PER_PAGE - 1) // LINES_PER_PAGE
    if pages_needed <= 60:
        selected = lines_out
        header_note = f"（全部源码共 {pages_needed} 页，少于 60 页，全文提交）"
    else:
        head = lines_out[: 30 * LINES_PER_PAGE]
        tail = lines_out[-30 * LINES_PER_PAGE:]
        selected = head + [f"/* …… 中间 {pages_needed - 60} 页按登记要求省略 …… */"] + tail
        header_note = f"（源码共 {pages_needed} 页，按规定提交前 30 页与后 30 页）"

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(f"{SOFTWARE} {VERSION} 源程序\n{header_note}\n")
        f.write("=" * 72 + "\n\n")
        for i, ln in enumerate(selected):
            page = i // LINES_PER_PAGE + 1
            if i % LINES_PER_PAGE == 0:
                f.write(f"\n【{SOFTWARE} {VERSION} 源程序】                                    第 {page} 页\n")
                f.write("-" * 72 + "\n")
            f.write(f"{i + 1:5d}  {ln}\n")

    print(f"已生成: {OUT}")
    print(f"有效代码行 {total} 行 → {pages_needed} 页（{header_note}）")


if __name__ == "__main__":
    main()
