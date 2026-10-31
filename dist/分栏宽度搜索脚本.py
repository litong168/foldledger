"""
展开态「左侧主列表栏」宽度的离线搜索。

为什么要搜：这个值同时受两侧内容的最小可用宽度约束，而折叠屏展开后的
可用宽度从 573dp（Galaxy Z Fold 内屏，减掉左侧导航栏）到 1286dp（桌面窗口）
跨度很大。拍脑袋定一个数，必然在某几档机型上偏窄或偏宽。

做法：
  1. 列出主流展开宽度的设备矩阵，换算成「真正能分栏的宽度」（减掉左侧导航栏 80dp）
  2. 枚举两类策略：定值 / 按比例（都带上下限或占比上限）
  3. 用两侧内容实测出来的宽度需求做打分，逐设备累加惩罚
  4. 排名，挑最优，并打印它在各机型下的实际宽度

内容约束的来源（读代码量出来的，不是猜的）：
  左栏 · 流水行 TxnRow
      左右 padding 14×2 + 分类图标 36 + 间隔 10 + 金额列 ~88 + 报销徽标 ~54 + 更多按钮 40
      ≈ 固定 242 + 分类名/备注文字最少 60 → 硬下限 284，舒适 ≥ 300
  左栏 · 账本卡片
      头像 46 + 间隔 12 + 金额/占比 ~92 + padding 16×2 ≈ 固定 182 + 文字 ≥ 110 → 舒适 ≥ 300
  右栏 · 报销看板（三块并排）
      每块「待提交」11sp 标签 + 金额 15sp ≈ 84，三块 + 间隔 10×2 + 卡片 padding 32
      ≈ 固定 304 → 硬下限 300，舒适 ≥ 340
  右栏 · 概览卡（两列金额）
      两列各 ~115 + 间隔 20 + padding 32 ≈ 282 → 舒适 ≥ 320
  右栏不宜过宽：概览/看板这类卡片拉伸到 900dp 以上就会显得空，且视线跨度大
"""

# ---------------------------------------------------------------- 设备矩阵
# (名称, 展开态窗口宽度 dp)
DEVICES = [
    ("Galaxy Z Fold 3/4/5/6 · Pixel Fold", 673),
    ("Galaxy Z Fold 内屏（旧）", 653),
    ("小米 MIX Fold 3/4", 720),
    ("OPPO Find N3 · vivo X Fold", 720),
    ("荣耀 Magic V2", 745),
    ("华为 Mate X5 / X6", 720),
    ("小尺寸平板（有铰链）", 768),
    ("平板横屏 800", 800),
    ("平板 / 桌面窗口 840", 840),
    ("桌面窗口 900", 900),
    ("桌面窗口 960", 960),
    ("桌面窗口 1000", 1000),
    ("大平板 1084", 1084),
    ("桌面窗口 1200", 1200),
    ("桌面最大化 1366", 1366),
]

NAV_RAIL_DP = 80          # AdaptiveScaffold 左侧 NavigationRail 宽度
RAIL_FROM = [0, 700, 760, 800, 840, 10000]   # 从多宽开始用侧边导航栏（0=从不，10000=总是）

# 约束：(硬下限, 舒适下限, 舒适上限, 权重)
LEFT = dict(hard=284, lo=300, hi=372, w=1.0)
RIGHT = dict(hard=300, lo=340, hi=860, w=1.35)   # 右栏内容更密，罚款更重


def penalty(v, spec):
    if v < spec["hard"]:
        return (spec["hard"] - v) ** 1.35 * 3.0 * spec["w"]   # 触硬下限，重罚
    p = 0.0
    if v < spec["lo"]:
        p += (spec["lo"] - v) * spec["w"]
    if v > spec["hi"]:
        p += (v - spec["hi"]) * spec["w"] * 0.6
    return p


def evaluate(master_fn, rail_from):
    """master_fn(avail) -> 左栏宽度 dp；rail_from 以下改用底部导航，把 80dp 还给内容"""
    total = 0.0
    widths = []
    for name, w in DEVICES:
        avail = w - (NAV_RAIL_DP if w >= rail_from else 0)
        left = master_fn(avail)
        right = avail - left
        total += penalty(left, LEFT) + penalty(right, RIGHT)
        widths.append((name, w, avail, left, right))
    # 一致性：各机型左栏宽度不要太跳（用户换机/换姿态时手感一致）
    vals = [x[3] for x in widths]
    spread = (max(vals) - min(vals)) * 0.35
    return total + spread, widths


def search_fixed():
    res = []
    for rf in RAIL_FROM:
        for f in range(288, 409, 8):
            for share in (None, 0.46, 0.48, 0.50, 0.52):
                def fn(avail, f=f, share=share):
                    v = f
                    if share is not None:
                        v = min(v, int(avail * share))
                    return max(240, v)
                score, ws = evaluate(fn, rf)
                tag = "总是侧栏" if rf == 10000 else ("从不侧栏" if rf == 0 else f"≥{rf} 用侧栏")
                res.append((score, f"定值 {f}dp" + (f"·占比≤{share:.0%}" if share else "") + f" | {tag}", ws))
    return res


def search_ratio():
    res = []
    for rf in RAIL_FROM:
        for r in range(40, 57, 2):
            for lo in range(272, 321, 8):
                for hi in range(320, 449, 16):
                    def fn(avail, r=r, lo=lo, hi=hi):
                        return max(lo, min(hi, int(avail * r / 100)))
                    score, ws = evaluate(fn, rf)
                    tag = "总是侧栏" if rf == 10000 else ("从不侧栏" if rf == 0 else f"≥{rf} 用侧栏")
                    res.append((score, f"比例 {r}% clamp[{lo},{hi}] | {tag}", ws))
    return res


def show(rank, score, label, ws, keys=(673, 720, 840, 1084)):
    print(f"#{rank}  score={score:7.1f}   {label}")
    cells = []
    for name, w, avail, left, right in ws:
        if w in keys:
            cells.append(f"{w}→{left}/{right}")
    print("      " + "   ".join(cells))


if __name__ == "__main__":
    allres = search_fixed() + search_ratio()
    allres.sort(key=lambda x: x[0])
    print("=" * 74)
    print("展开态分栏搜索：共评估 %d 种策略（含「导航栏 vs 底部栏」这个变量）" % len(allres))
    print("=" * 74)
    for i, (score, label, ws) in enumerate(allres[:14], 1):
        show(i, score, label, ws)

    best = allres[0]
    print()
    print("=" * 74)
    print("最优策略在各机型下的实际分栏：", best[1])
    print("=" * 74)
    print(f"{'机型':<34}{'窗口':>6}{'可分栏':>8}{'左栏':>7}{'右栏':>7}")
    for name, w, avail, left, right in best[2]:
        flag = ""
        if left < LEFT["hard"] or right < RIGHT["hard"]:
            flag = "  ← 触硬下限"
        elif left < LEFT["lo"] or right < RIGHT["lo"]:
            flag = "  ← 偏紧"
        print(f"{name:<34}{w:>6}{avail:>8}{left:>7}{right:>7}{flag}")
