package com.foldledger.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LedgerEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        GoalEntity::class,
        ProjectEntity::class,
        RecurringEntity::class,
        AccountEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun ledgerDao(): LedgerDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun goalDao(): GoalDao
    abstract fun projectDao(): ProjectDao
    abstract fun recurringDao(): RecurringDao
    abstract fun accountDao(): AccountDao

    companion object {
        private const val DB_NAME = "foldledger.db"

        /**
         * v1 -> v2：分类表加「月度预算」列（budgetCents）。
         * 用显式迁移而不是依赖 fallbackToDestructiveMigration，否则老用户一升级数据就被清空。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE categories ADD COLUMN budgetCents INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v2 -> v3（v5.1 项目 / 空间）：
         * 1) 新建 projects 表（归属账本，账本删除级联）；
         * 2) transactions 加 projectId（0 = 未归类，默认 0 所以老流水自动"未归类"）；
         * 3) 补索引（Room 的实体声明了 projectId 索引，迁移必须建出来）。
         *
         * 依然是**显式迁移**：绝不能靠 fallbackToDestructiveMigration 把老用户的账清掉。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `projects` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `ledgerId` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `emoji` TEXT NOT NULL,
                        `colorHex` TEXT NOT NULL,
                        `budgetCents` INTEGER NOT NULL,
                        `note` TEXT NOT NULL,
                        `closed` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_projects_ledgerId` ON `projects` (`ledgerId`)")
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `projectId` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transactions_projectId` ON `transactions` (`projectId`)"
                )
            }
        }

        /**
         * v3 -> v4（v5.7 周期性记账）：新建 recurring 表 + 两个索引。
         *
         * 又是**显式迁移** —— 这张表是新增的，老数据一条都不动，纯加表。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recurring` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `ledgerId` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `categoryId` INTEGER,
                        `categoryName` TEXT NOT NULL,
                        `categoryEmoji` TEXT NOT NULL,
                        `direction` INTEGER NOT NULL,
                        `amountCents` INTEGER NOT NULL,
                        `note` TEXT NOT NULL,
                        `intervalKind` INTEGER NOT NULL,
                        `nextDueAt` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `projectId` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_recurring_ledgerId` ON `recurring` (`ledgerId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_recurring_nextDueAt` ON `recurring` (`nextDueAt`)"
                )
            }
        }

        /**
         * v4 -> v5（v6.1 资产账户）：新建 accounts 表 + 一个索引 + 流水加 accountId 列。
         *
         * 三条约束仍然成立：
         *  1. 老用户数据一条不动（accountId 默认 0 = 未指定）；
         *  2. 账户被删时流水退回「未指定」，绝不连带删流水（见 [AccountDao.deleteById] +
         *     [TransactionDao.clearAccount]）；
         *  3. 余额不在库里存，从 `initialBalanceCents + Σ流水` 实时推算 —— 改流水不会让余额对不上。
         *
         * ⚠️ v7.0.0 事故复盘：这一版曾多建 `index_transactions_accountId`，但 [TransactionEntity]
         * 当时没声明该索引 —— Room 迁移后全量校验 schema（索引集合双向相等），多余索引同样判失败，
         * 抛 "Migration didn't properly handle" 直接闪退。索引改由 [MIGRATION_5_6] 在实体声明后补建。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `accounts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `ledgerId` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `type` INTEGER NOT NULL,
                        `emoji` TEXT NOT NULL,
                        `colorHex` TEXT NOT NULL,
                        `initialBalanceCents` INTEGER NOT NULL,
                        `includeInNetWorth` INTEGER NOT NULL,
                        `note` TEXT NOT NULL,
                        `closed` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_accounts_ledgerId` ON `accounts` (`ledgerId`)"
                )
                db.execSQL(
                    "ALTER TABLE `transactions` ADD COLUMN `accountId` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v5 -> v6（v7.0.1 热修）：给 `transactions.accountId` 补索引。
         *
         * [TransactionEntity] 已声明 `Index("accountId")`，迁移必须建出同名索引，
         * 否则 schema 校验同样炸。`IF NOT EXISTS`：对 v4 一路上来的库（4_5 不再建）和
         * v5 新装库都安全。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transactions_accountId` ON `transactions` (`accountId`)"
                )
            }
        }

        /**
         * v6 -> v7（v8.1 回收站）：ledgers 加 `deletedAt`（0 = 正常，>0 = 回收站时间戳）。
         *
         * `ALTER ADD COLUMN ... DEFAULT 0` 与实体无 defaultValue 不冲突（列相等性对该比较宽容），
         * 和 [MIGRATION_1_2] 同款做法。老账本自动落 0 = 不受影响。
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ledgers ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v9 -> v10（v9.7 存钱目标独立化）：`goals.ledgerId` 改成**可空**。
         *
         * SQLite 改列的可空性/外键动作必须重建表。顺带把外键从 CASCADE 改成 SET_NULL ——
         * 目标不再"隶属于"账本，删账本不该连带删目标。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `goals_new` (
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
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "INSERT INTO `goals_new` (`id`,`ledgerId`,`name`,`emoji`,`targetCents`,`savedCents`," +
                        "`targetDate`,`accountId`,`note`,`archived`,`deletedAt`,`createdAt`) " +
                        "SELECT `id`, CASE WHEN `ledgerId` > 0 THEN `ledgerId` ELSE NULL END, " +
                        "`name`,`emoji`,`targetCents`,`savedCents`,`targetDate`,`accountId`,`note`," +
                        "`archived`,`deletedAt`,`createdAt` FROM `goals`"
                )
                db.execSQL("DROP TABLE `goals`")
                db.execSQL("ALTER TABLE `goals_new` RENAME TO `goals`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_goals_ledgerId` ON `goals` (`ledgerId`)")
            }
        }

        /**
         * v8 -> v9（v9.4 全局回收站）：`goals` 加 `deletedAt` 软删位。
         *
         * 与账本的 deletedAt 同款：0 = 正常，>0 = 进回收站的时间戳。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE goals ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v7 -> v8（v9.2 存钱目标）：新增 `goals` 表。
         *
         * 攒钱目标**不进流水表** —— 存钱不是支出，混进去会污染账目统计。
         * 建表语句与 [MIGRATION_2_3] 的 projects 同款（含外键与索引）。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `goals` (
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
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_goals_ledgerId` ON `goals` (`ledgerId`)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        DB_NAME
                    )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10
                    )
                    // ⚠️ 绝不能加 fallbackToDestructiveMigration：缺迁移时它会把老用户整库清空。
                    // 迁移链覆盖 1→6 全程，真缺迁移就该炸出来修，而不是静默吞掉用户的账。
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
