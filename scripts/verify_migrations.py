#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
DB 迁移实测（发布前必跑）：
    python scripts/verify_migrations.py

做什么：
  1. 从 KSP 生成的 app/build/generated/ksp/**/AppDatabase_Impl.kt 自动抽取「当前实体期望 schema」
     （列 + 索引），和 Room 运行时 validateSchema 用的是同一份数据，不会漂移；
  2. 用 sqlite3 逐个构造 v1..v5 的旧库（真实旧 schema），跑对应迁移链 SQL；
  3. 比对迁移产物与期望 schema —— **多余/缺少索引或列都会判失败**，
     这正是 v7.0.0「升级即闪退」的根因（MIGRATION_4_5 多建 index_transactions_accountId）。

迁移 SQL 在本脚本内与 AppDatabase.kt 保持同文；改迁移必须同步改这里。
"""
import os, re, sqlite3, sys, glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ---------------- 1. 从 KSP 产物抽期望 schema ----------------

def load_expected_from_ksp():
    # DB 已迁入 :common（KMP 模块）：androidTarget 的 KSP 产物在 common/build/generated/ksp/android/*/
    paths = glob.glob(os.path.join(ROOT, "common/build/generated/ksp/android/*/kotlin/com/foldledger/data/db/AppDatabase_Impl.kt"))
    if not paths:
        print("找不到 AppDatabase_Impl.kt —— 请先跑一次 :common:compileDebugKotlinAndroid")
        sys.exit(2)
    src = open(paths[0], encoding="utf-8").read()
    tables = {}
    # 列： _columnsXxx.put("col", TableInfo.Column("col", ...))
    for m in re.finditer(r'_columns(\w+)\.put\("(\w+)"', src):
        tables.setdefault(m.group(1), {"columns": [], "indices": []})
        tables[m.group(1)]["columns"].append(m.group(2))
    # 索引： _indicesXxx.add(TableInfo.Index("name", ...))
    for m in re.finditer(r'_indices(\w+)\.add\(TableInfo\.Index\("(\w+)"', src):
        tables.setdefault(m.group(1), {"columns": [], "indices": []})
        tables[m.group(1)]["indices"].append(m.group(2))
    # 表名： TableInfo("tableName", _columnsXxx ...
    out = {}
    for m in re.finditer(r'TableInfo\("(\w+)", _columns(\w+)', src):
        t = tables[m.group(2)]
        out[m.group(1)] = {"columns": sorted(t["columns"]), "indices": sorted(t["indices"])}
    return out

# ---------------- 2. 迁移 SQL（与 AppDatabase.kt 同文） ----------------

M12 = ["ALTER TABLE categories ADD COLUMN budgetCents INTEGER NOT NULL DEFAULT 0"]
M23 = [
    """CREATE TABLE IF NOT EXISTS `projects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ledgerId` INTEGER NOT NULL, `name` TEXT NOT NULL, `emoji` TEXT NOT NULL, `colorHex` TEXT NOT NULL, `budgetCents` INTEGER NOT NULL, `note` TEXT NOT NULL, `closed` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    "CREATE INDEX IF NOT EXISTS `index_projects_ledgerId` ON `projects` (`ledgerId`)",
    "ALTER TABLE `transactions` ADD COLUMN `projectId` INTEGER NOT NULL DEFAULT 0",
    "CREATE INDEX IF NOT EXISTS `index_transactions_projectId` ON `transactions` (`projectId`)",
]
M34 = [
    """CREATE TABLE IF NOT EXISTS `recurring` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ledgerId` INTEGER NOT NULL, `name` TEXT NOT NULL, `categoryId` INTEGER, `categoryName` TEXT NOT NULL, `categoryEmoji` TEXT NOT NULL, `direction` INTEGER NOT NULL, `amountCents` INTEGER NOT NULL, `note` TEXT NOT NULL, `intervalKind` INTEGER NOT NULL, `nextDueAt` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `projectId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    "CREATE INDEX IF NOT EXISTS `index_recurring_ledgerId` ON `recurring` (`ledgerId`)",
    "CREATE INDEX IF NOT EXISTS `index_recurring_nextDueAt` ON `recurring` (`nextDueAt`)",
]
M45 = [
    """CREATE TABLE IF NOT EXISTS `accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ledgerId` INTEGER NOT NULL, `name` TEXT NOT NULL, `type` INTEGER NOT NULL, `emoji` TEXT NOT NULL, `colorHex` TEXT NOT NULL, `initialBalanceCents` INTEGER NOT NULL, `includeInNetWorth` INTEGER NOT NULL, `note` TEXT NOT NULL, `closed` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    "CREATE INDEX IF NOT EXISTS `index_accounts_ledgerId` ON `accounts` (`ledgerId`)",
    "ALTER TABLE `transactions` ADD COLUMN `accountId` INTEGER NOT NULL DEFAULT 0",
]
M56 = ["CREATE INDEX IF NOT EXISTS `index_transactions_accountId` ON `transactions` (`accountId`)"]
M67 = ["ALTER TABLE ledgers ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0"]
M78 = [
    """CREATE TABLE IF NOT EXISTS `goals` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `ledgerId` INTEGER NOT NULL,
        `name` TEXT NOT NULL,
        `emoji` TEXT NOT NULL,
        `targetCents` INTEGER NOT NULL,
        `savedCents` INTEGER NOT NULL,
        `targetDate` INTEGER NOT NULL,
        `accountId` INTEGER NOT NULL,
        `note` TEXT NOT NULL,
        `archived` INTEGER NOT NULL,
        `createdAt` INTEGER NOT NULL,
        FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`)
            ON UPDATE NO ACTION ON DELETE CASCADE
    )""",
    "CREATE INDEX IF NOT EXISTS `index_goals_ledgerId` ON `goals` (`ledgerId`)",
]

M89 = ["ALTER TABLE goals ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0"]

# v9 -> v10：goals.ledgerId 改可空（独立目标），外键 CASCADE -> SET_NULL（删账本不删目标）
M910 = [
    """CREATE TABLE IF NOT EXISTS `goals_new` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `ledgerId` INTEGER,
        `name` TEXT NOT NULL,
        `emoji` TEXT NOT NULL,
        `targetCents` INTEGER NOT NULL,
        `savedCents` INTEGER NOT NULL,
        `targetDate` INTEGER NOT NULL,
        `accountId` INTEGER NOT NULL,
        `note` TEXT NOT NULL,
        `archived` INTEGER NOT NULL,
        `deletedAt` INTEGER NOT NULL,
        `createdAt` INTEGER NOT NULL,
        FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`)
            ON UPDATE NO ACTION ON DELETE SET NULL
    )""",
    "INSERT INTO `goals_new` (`id`,`ledgerId`,`name`,`emoji`,`targetCents`,`savedCents`," +
    "`targetDate`,`accountId`,`note`,`archived`,`deletedAt`,`createdAt`) " +
    "SELECT `id`, CASE WHEN `ledgerId` > 0 THEN `ledgerId` ELSE NULL END, " +
    "`name`,`emoji`,`targetCents`,`savedCents`,`targetDate`,`accountId`,`note`," +
    "`archived`,`deletedAt`,`createdAt` FROM `goals`",
    "DROP TABLE `goals`",
    "ALTER TABLE `goals_new` RENAME TO `goals`",
    "CREATE INDEX IF NOT EXISTS `index_goals_ledgerId` ON `goals` (`ledgerId`)",
]

# v10 -> v11（M4 双端同步）：全表加同步字段 —— 7 表 + updatedAt/updatedByDeviceId，
# 5 表（categories/transactions/projects/accounts/recurring）补 deletedAt 墓碑。
# 与 AppDatabase.kt 的 MIGRATION_10_11 保持同文（纯 ALTER 加列，无重建）。
M1011 = [
    "ALTER TABLE `ledgers` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `ledgers` ADD COLUMN `updatedByDeviceId` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `categories` ADD COLUMN `deletedAt` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `categories` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `categories` ADD COLUMN `updatedByDeviceId` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `transactions` ADD COLUMN `deletedAt` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `transactions` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `transactions` ADD COLUMN `updatedByDeviceId` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `goals` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `goals` ADD COLUMN `updatedByDeviceId` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `projects` ADD COLUMN `deletedAt` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `projects` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `projects` ADD COLUMN `updatedByDeviceId` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `recurring` ADD COLUMN `deletedAt` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `recurring` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `recurring` ADD COLUMN `updatedByDeviceId` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `accounts` ADD COLUMN `deletedAt` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `accounts` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE `accounts` ADD COLUMN `updatedByDeviceId` INTEGER NOT NULL DEFAULT 0",
]

CHAIN = {1: [M12, M23, M34, M45, M56, M67, M78, M89, M910, M1011],
         2: [M23, M34, M45, M56, M67, M78, M89, M910, M1011],
         3: [M34, M45, M56, M67, M78, M89, M910, M1011],
         4: [M45, M56, M67, M78, M89, M910, M1011],
         5: [M56, M67, M78, M89, M910, M1011], 6: [M67, M78, M89, M910, M1011],
         7: [M78, M89, M910, M1011], 8: [M89, M910, M1011], 9: [M910, M1011],
         10: [M1011], 11: []}
MAX_VER = 11

# ---------------- 3. 造旧库 ----------------

def build_old_db(path, ver):
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path)
    c = db.cursor()
    c.execute("CREATE TABLE ledgers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, sceneKey TEXT NOT NULL, emoji TEXT NOT NULL, colorHex TEXT NOT NULL, budgetCents INTEGER NOT NULL, note TEXT NOT NULL, archived INTEGER NOT NULL, sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
    c.execute("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ledgerId INTEGER NOT NULL, name TEXT NOT NULL, emoji TEXT NOT NULL, direction INTEGER NOT NULL, sortOrder INTEGER NOT NULL, builtIn INTEGER NOT NULL)")
    if ver >= 2:
        c.execute(M12[0])
    c.execute("CREATE INDEX index_categories_ledgerId ON categories (ledgerId)")
    c.execute("CREATE UNIQUE INDEX index_categories_ledgerId_direction_name ON categories (ledgerId, direction, name)")
    c.execute("""CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ledgerId INTEGER NOT NULL,
        categoryId INTEGER, categoryName TEXT NOT NULL, categoryEmoji TEXT NOT NULL, direction INTEGER NOT NULL,
        amountCents INTEGER NOT NULL, note TEXT NOT NULL, occurredAt INTEGER NOT NULL, extraJson TEXT NOT NULL,
        reimburseState INTEGER NOT NULL, createdAt INTEGER NOT NULL,
        FOREIGN KEY(ledgerId) REFERENCES ledgers(id) ON UPDATE NO ACTION ON DELETE CASCADE,
        FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE SET NULL)""")
    for idx, col in [("index_transactions_ledgerId", "ledgerId"), ("index_transactions_categoryId", "categoryId"),
                     ("index_transactions_occurredAt", "occurredAt")]:
        c.execute(f"CREATE INDEX {idx} ON transactions ({col})")
    c.execute("INSERT INTO ledgers(name,sceneKey,emoji,colorHex,budgetCents,note,archived,sortOrder,createdAt) VALUES('日常','daily','B','#333',0,'',0,0,1)")
    c.execute("INSERT INTO transactions(ledgerId,categoryName,categoryEmoji,direction,amountCents,note,occurredAt,extraJson,reimburseState,createdAt) VALUES(1,'餐饮','F',0,2500,'lunch',1700000000000,'',-1,1700000000000)")
    if ver >= 3:
        for s in M23:
            c.execute(s)
    if ver >= 4:
        for s in M34:
            c.execute(s)
    if ver >= 5:
        for s in M45:
            c.execute(s)
    if ver >= 6:
        for s in M56:
            c.execute(s)
    if ver >= 7:
        for s in M67:
            c.execute(s)
    if ver >= 8:
        for s in M78:
            c.execute(s)
    if ver >= 9:
        for s in M89:
            c.execute(s)
    if ver >= 10:
        for s in M910:
            c.execute(s)
    if ver >= 11:
        for s in M1011:
            c.execute(s)
    db.commit()
    return db

def table_cols(db, t):
    return sorted(r[1] for r in db.execute(f"PRAGMA table_info(`{t}`)").fetchall())

def table_idx(db, t):
    return sorted(r[1] for r in db.execute(f"PRAGMA index_list(`{t}`)").fetchall()
                  if not r[1].startswith("sqlite_autoindex"))

def main():
    expected = load_expected_from_ksp()
    print(f"期望 schema 来自 KSP 产物，共 {len(expected)} 张表\n")
    tmpdir = os.path.join(ROOT, ".workbuddy", "tmp")
    os.makedirs(tmpdir, exist_ok=True)
    all_ok = True
    for ver in range(1, MAX_VER + 1):
        db = build_old_db(os.path.join(tmpdir, f"mig_v{ver}.db"), ver)
        for stmts in CHAIN[ver]:
            for s in stmts:
                db.execute(s)
        db.commit()
        problems = []
        # ⚠️ 表集合必须**完全相等**：迁移多建一张表（Room 期望里没有）同样会让
        # validateSchema 失败 —— v7.0.1 事故就是"多建了索引"，多表同理。
        actual_tables = {
            r[0] for r in db.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            ).fetchall()
        } - {"sqlite_sequence", "android_metadata"}
        if actual_tables != set(expected.keys()):
            extra = actual_tables - set(expected.keys())
            missing = set(expected.keys()) - actual_tables
            if extra:
                problems.append(f"  ❌ 多余的表（Room 期望里没有）: {sorted(extra)}")
            if missing:
                problems.append(f"  ❌ 缺少的表: {sorted(missing)}")
        for t, exp in expected.items():
            if table_cols(db, t) != exp["columns"]:
                problems.append(f"  {t} 列不一致: {table_cols(db, t)} != {exp['columns']}")
            if table_idx(db, t) != exp["indices"]:
                problems.append(f"  {t} 索引不一致: {table_idx(db, t)} != {exp['indices']}")
        rows = db.execute("SELECT COUNT(*) FROM transactions").fetchone()[0]
        if rows != 1:
            problems.append("  ❌ 流水丢失")
        mark = "✔" if not problems else "❌"
        print(f"[{mark}] v{ver} → v{MAX_VER}：流水 {rows} 条")
        for p in problems:
            print(p)
            all_ok = False
        db.close()
    print("\n" + ("迁移链全部通过 ✔" if all_ok else "存在失败 ❌ —— 这会让 Room 抛 Migration didn't properly handle 直接闪退"))
    sys.exit(0 if all_ok else 1)

if __name__ == "__main__":
    main()
