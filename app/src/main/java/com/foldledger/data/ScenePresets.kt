package com.foldledger.data

import com.foldledger.data.db.Direction

// ============================================================
//  预置记账场景
//  ------------------------------------------------------------
//  每个场景 = 一套「支出分类 + 收入分类 + 场景专属字段 + 配色」。
//  用户新建账本时选一个场景，即可一键生成整套分类，开箱即用。
// ============================================================

enum class FieldKind { TEXT, NUMBER, CHOICE, MONEY, DATE }

data class FieldOption(val value: String, val label: String)

data class SceneField(
    val key: String,
    val label: String,
    val kind: FieldKind,
    val options: List<FieldOption> = emptyList(),
    val placeholder: String = ""
)

data class CategoryPreset(val name: String, val emoji: String)

data class ScenePreset(
    val key: String,
    val name: String,
    val emoji: String,
    val tagline: String,
    val colorHex: String,
    val expenses: List<CategoryPreset>,
    val incomes: List<CategoryPreset>,
    val fields: List<SceneField> = emptyList(),
    /** 是否支持「报销状态」看板 */
    val supportsReimbursement: Boolean = false,
    /** 新建时建议的默认预算（元），0 = 不设置 */
    val suggestedBudgetYuan: Long = 0L,
    /** v8.1：系统预置场景（如自动记账）不出现在「新建账本」画廊里 */
    val hidden: Boolean = false
) {
    val allCategories: List<CategoryPreset> get() = expenses + incomes
}

object ScenePresets {

    val CUSTOM_KEY = "custom"

    /** v8.1 自动记账账本的场景 key */
    val AUTO_KEY = "auto"

    // ---------------- 1. 日常 ----------------
    private val DAILY = ScenePreset(
        key = "daily",
        name = "日常",
        emoji = "🏠",
        tagline = "吃喝住行，记录每一天",
        colorHex = "#1F6FEB",
        expenses = listOf(
            CategoryPreset("餐饮", "🍜"),
            CategoryPreset("买菜", "🥬"),
            CategoryPreset("零食饮料", "🧋"),
            CategoryPreset("日用品", "🧻"),
            CategoryPreset("服饰", "👕"),
            CategoryPreset("美容美发", "💅"),
            CategoryPreset("娱乐", "🎮"),
            CategoryPreset("医疗买药", "💊"),
            CategoryPreset("学习", "📚"),
            CategoryPreset("通讯网费", "📱"),
            CategoryPreset("交通", "🚌"),
            CategoryPreset("人情往来", "🎁"),
            CategoryPreset("宠物", "🐾"),
            CategoryPreset("其他支出", "📦")
        ),
        incomes = listOf(
            CategoryPreset("工资", "💰"),
            CategoryPreset("奖金", "🏆"),
            CategoryPreset("红包", "🧧"),
            CategoryPreset("理财收益", "📈"),
            CategoryPreset("兼职", "💼"),
            CategoryPreset("报销到账", "🧾"),
            CategoryPreset("其他收入", "✨")
        ),
        fields = listOf(
            SceneField("payment", "支付方式", FieldKind.CHOICE, listOf(
                FieldOption("wechat", "微信"),
                FieldOption("alipay", "支付宝"),
                FieldOption("cash", "现金"),
                FieldOption("card", "银行卡")
            ))
        )
    )

    // ---------------- 2. 家装 ----------------
    private val RENOVATION = ScenePreset(
        key = "renovation",
        name = "家装",
        emoji = "🛠️",
        tagline = "主材辅材人工，装修不超支",
        colorHex = "#E8590C",
        expenses = listOf(
            CategoryPreset("主材", "🧱"),
            CategoryPreset("辅材", "🧰"),
            CategoryPreset("人工费", "👷"),
            CategoryPreset("设计费", "📐"),
            CategoryPreset("拆改", "🔨"),
            CategoryPreset("水电改造", "🔌"),
            CategoryPreset("防水瓦工", "🚿"),
            CategoryPreset("木工吊顶", "🪚"),
            CategoryPreset("油漆涂料", "🎨"),
            CategoryPreset("门窗", "🚪"),
            CategoryPreset("定制柜", "🗄️"),
            CategoryPreset("家具", "🛋️"),
            CategoryPreset("家电", "📺"),
            CategoryPreset("软装布艺", "🪟"),
            CategoryPreset("灯具五金", "💡"),
            CategoryPreset("运输上楼", "🚚"),
            CategoryPreset("保洁", "🧹"),
            CategoryPreset("监理", "📋"),
            CategoryPreset("其他", "📦")
        ),
        incomes = listOf(
            CategoryPreset("商家退款", "↩️"),
            CategoryPreset("优惠返现", "🎫"),
            CategoryPreset("尾款结余", "💵")
        ),
        fields = listOf(
            SceneField("space", "空间", FieldKind.CHOICE, listOf(
                FieldOption("whole", "全屋"),
                FieldOption("living", "客厅"),
                FieldOption("master", "主卧"),
                FieldOption("second", "次卧"),
                FieldOption("kitchen", "厨房"),
                FieldOption("bathroom", "卫生间"),
                FieldOption("balcony", "阳台"),
                FieldOption("study", "书房")
            )),
            SceneField("vendor", "商家/施工方", FieldKind.TEXT, placeholder = "如：XX装饰公司"),
            SceneField("stage", "阶段", FieldKind.CHOICE, listOf(
                FieldOption("design", "设计"),
                FieldOption("hardcover", "硬装"),
                FieldOption("softcover", "软装"),
                FieldOption("appliance", "家电进场"),
                FieldOption("done", "已完工")
            )),
            SceneField("isPaid", "是否已付款", FieldKind.CHOICE, listOf(
                FieldOption("paid", "已付全款"),
                FieldOption("deposit", "已付定金"),
                FieldOption("credit", "赊账待付")
            ))
        ),
        suggestedBudgetYuan = 200_000L
    )

    // ---------------- 3. 报销 ----------------
    private val REIMBURSEMENT = ScenePreset(
        key = "reimbursement",
        name = "报销",
        emoji = "🧾",
        tagline = "先垫后报，进度一目了然",
        colorHex = "#0B7285",
        expenses = listOf(
            CategoryPreset("交通费", "🚕"),
            CategoryPreset("住宿费", "🏨"),
            CategoryPreset("餐饮补助", "🍱"),
            CategoryPreset("办公用品", "🖇️"),
            CategoryPreset("快递邮寄", "📮"),
            CategoryPreset("通讯补贴", "📞"),
            CategoryPreset("业务招待", "🍷"),
            CategoryPreset("会议培训", "🎓"),
            CategoryPreset("差旅其他", "🧳"),
            CategoryPreset("团建", "🎉"),
            CategoryPreset("其他垫付", "📦")
        ),
        incomes = listOf(
            CategoryPreset("报销到账", "✅"),
            CategoryPreset("差旅补贴", "💵"),
            CategoryPreset("预借款", "🏦")
        ),
        fields = listOf(
            SceneField("applicant", "报销人", FieldKind.TEXT, placeholder = "填写姓名"),
            SceneField("docNo", "单据号", FieldKind.TEXT, placeholder = "如：BX20261013"),
            SceneField("project", "项目/客户", FieldKind.TEXT, placeholder = "如：深圳分公司"),
            SceneField("state", "报销状态", FieldKind.CHOICE, listOf(
                FieldOption("0", "待提交"),
                FieldOption("1", "已提交"),
                FieldOption("2", "已回款")
            ))
        ),
        supportsReimbursement = true
    )

    // ---------------- 4. 母婴 ----------------
    private val BABY = ScenePreset(
        key = "baby",
        name = "母婴",
        emoji = "🍼",
        tagline = "奶粉尿裤疫苗，育儿开支清单",
        colorHex = "#D6336C",
        expenses = listOf(
            CategoryPreset("奶粉", "🍼"),
            CategoryPreset("尿不湿", "🧷"),
            CategoryPreset("辅食", "🥣"),
            CategoryPreset("婴儿衣物", "👶"),
            CategoryPreset("玩具绘本", "🧸"),
            CategoryPreset("洗护用品", "🧴"),
            CategoryPreset("医疗疫苗", "💉"),
            CategoryPreset("早教课程", "🎠"),
            CategoryPreset("产检", "🩺"),
            CategoryPreset("月子护理", "🛏️"),
            CategoryPreset("推车安全座椅", "🚼"),
            CategoryPreset("孕妇用品", "🤰"),
            CategoryPreset("托育入园", "🏫"),
            CategoryPreset("其他", "📦")
        ),
        incomes = listOf(
            CategoryPreset("红包礼金", "🧧"),
            CategoryPreset("生育津贴", "🏛️"),
            CategoryPreset("商业保险理赔", "🛡️")
        ),
        fields = listOf(
            SceneField("kid", "宝宝", FieldKind.CHOICE, listOf(
                FieldOption("babyl", "大宝"),
                FieldOption("baby2", "二宝"),
                FieldOption("tummy", "孕期")
            )),
            SceneField("monthAge", "月龄", FieldKind.NUMBER, placeholder = "如：8"),
            SceneField("size", "规格", FieldKind.TEXT, placeholder = "如：L 码 / 900g")
        )
    )

    // ---------------- 5. 用车 ----------------
    private val CAR = ScenePreset(
        key = "car",
        name = "用车",
        emoji = "🚗",
        tagline = "油电保养保险，一车一本账",
        colorHex = "#2F9E44",
        expenses = listOf(
            CategoryPreset("加油", "⛽"),
            CategoryPreset("充电", "🔋"),
            CategoryPreset("停车费", "🅿️"),
            CategoryPreset("过路桥费", "🛣️"),
            CategoryPreset("洗车美容", "🚿"),
            CategoryPreset("常规保养", "🔧"),
            CategoryPreset("维修", "🔩"),
            CategoryPreset("轮胎", "⚙️"),
            CategoryPreset("保险", "🛡️"),
            CategoryPreset("车船税", "📄"),
            CategoryPreset("年检", "🧾"),
            CategoryPreset("违章罚款", "🚨"),
            CategoryPreset("车载配件", "🎧"),
            CategoryPreset("车位租金", "🏗️"),
            CategoryPreset("其他", "📦")
        ),
        incomes = listOf(
            CategoryPreset("顺风车", "🧭"),
            CategoryPreset("油费报销", "🧾"),
            CategoryPreset("保险返现", "🎫")
        ),
        fields = listOf(
            SceneField("vehicle", "车辆", FieldKind.TEXT, placeholder = "如：小米 SU7"),
            SceneField("odometer", "当前里程", FieldKind.NUMBER, placeholder = "km"),
            SceneField("fuel", "加油量/电量", FieldKind.NUMBER, placeholder = "L 或 kWh"),
            SceneField("plate", "车牌", FieldKind.TEXT, placeholder = "粤B·XXXXX")
        )
    )

    // ---------------- 6. 房屋 ----------------
    private val HOUSING = ScenePreset(
        key = "housing",
        name = "房屋",
        emoji = "🏡",
        tagline = "房租房贷物业水电，居住成本",
        colorHex = "#5F3DC4",
        expenses = listOf(
            CategoryPreset("房租", "🔑"),
            CategoryPreset("房贷", "🏦"),
            CategoryPreset("物业费", "🏢"),
            CategoryPreset("水费", "💧"),
            CategoryPreset("电费", "⚡"),
            CategoryPreset("燃气费", "🔥"),
            CategoryPreset("取暖费", "♨️"),
            CategoryPreset("宽带电视", "📡"),
            CategoryPreset("家政保洁", "🧹"),
            CategoryPreset("维修", "🧰"),
            CategoryPreset("家具家电", "🛋️"),
            CategoryPreset("中介费", "🤝"),
            CategoryPreset("垃圾清运", "🗑️"),
            CategoryPreset("其他", "📦")
        ),
        incomes = listOf(
            CategoryPreset("租金收入", "💵"),
            CategoryPreset("物业返还", "↩️"),
            CategoryPreset("住房补贴", "🏛️")
        ),
        fields = listOf(
            SceneField("property", "房产", FieldKind.TEXT, placeholder = "如：南山 xx 花园 A 栋"),
            SceneField("period", "周期", FieldKind.CHOICE, listOf(
                FieldOption("month", "按月"),
                FieldOption("quarter", "按季"),
                FieldOption("year", "按年"),
                FieldOption("once", "一次性")
            )),
            SceneField("meterReading", "抄表读数", FieldKind.NUMBER, placeholder = "如：1234")
        )
    )

    // ---------------- 7. 旅行 ----------------
    private val TRAVEL = ScenePreset(
        key = "travel",
        name = "旅行",
        emoji = "✈️",
        tagline = "机票酒店门票，玩得明白",
        colorHex = "#4263EB",
        expenses = listOf(
            CategoryPreset("机票", "✈️"),
            CategoryPreset("火车票", "🚄"),
            CategoryPreset("住宿", "🏨"),
            CategoryPreset("门票", "🎟️"),
            CategoryPreset("餐饮", "🍽️"),
            CategoryPreset("当地交通", "🚕"),
            CategoryPreset("购物", "🛍️"),
            CategoryPreset("签证", "🛂"),
            CategoryPreset("保险", "🛡️"),
            CategoryPreset("行李托运", "🧳"),
            CategoryPreset("其他", "📦")
        ),
        incomes = listOf(
            CategoryPreset("退款", "↩️"),
            CategoryPreset("旅游补贴", "🏛️")
        ),
        fields = listOf(
            SceneField("trip", "行程", FieldKind.TEXT, placeholder = "如：大阪 5 日"),
            SceneField("companion", "同行人", FieldKind.TEXT, placeholder = "如：家人 3 人"),
            SceneField("city", "城市", FieldKind.TEXT, placeholder = "如：Osaka")
        ),
        suggestedBudgetYuan = 10_000L
    )

    // ---------------- 8. 宠物 ----------------
    private val PET = ScenePreset(
        key = "pet",
        name = "宠物",
        emoji = "🐾",
        tagline = "主子口粮医疗，花销有数",
        colorHex = "#C2255C",
        expenses = listOf(
            CategoryPreset("主粮", "🥫"),
            CategoryPreset("零食", "🦴"),
            CategoryPreset("猫砂/尿垫", "🧹"),
            CategoryPreset("疫苗驱虫", "💉"),
            CategoryPreset("看病", "🩺"),
            CategoryPreset("洗护美容", "🛁"),
            CategoryPreset("玩具窝笼", "🧶"),
            CategoryPreset("寄养", "🏠"),
            CategoryPreset("服饰牵引", "🦺"),
            CategoryPreset("其他", "📦")
        ),
        incomes = listOf(
            CategoryPreset("保险理赔", "🛡️"),
            CategoryPreset("转让收入", "💵")
        ),
        fields = listOf(
            SceneField("petName", "宠物名", FieldKind.TEXT, placeholder = "如：布丁"),
            SceneField("species", "类型", FieldKind.CHOICE, listOf(
                FieldOption("cat", "猫"),
                FieldOption("dog", "狗"),
                FieldOption("other", "其他")
            ))
        )
    )

    // ---------------- 9. 自定义 ----------------
    private val CUSTOM = ScenePreset(
        key = CUSTOM_KEY,
        name = "自定义",
        emoji = "✨",
        tagline = "从零开始，完全按你的方式来",
        colorHex = "#495057",
        expenses = listOf(CategoryPreset("其他支出", "📦")),
        incomes = listOf(CategoryPreset("其他收入", "✨"))
    )

    // ---------------- 10. 自动记账（v8.1 系统预置，不进画廊） ----------------
    /**
     * 自动记账账本的场景。
     *
     * 捕获（支付通知 / 银行短信）进来的条目先落在这里，由用户手动移去正式账本 ——
     * 自动识别永远有错，**人来决定这笔钱记到哪个账本**，顺带把分类/场景字段适配过去。
     */
    private val AUTO = ScenePreset(
        key = AUTO_KEY,
        name = "自动记账",
        emoji = "🤖",
        tagline = "支付通知自动进账，确认后移去正式账本",
        colorHex = "#7048E8",
        expenses = listOf(CategoryPreset("自动记账", "🤖")),
        incomes = listOf(CategoryPreset("自动收入", "🤖")),
        hidden = true
    )

    /** 场景画廊顺序（自定义放最后） */
    val all: List<ScenePreset> = listOf(
        DAILY, RENOVATION, REIMBURSEMENT, BABY, CAR, HOUSING, TRAVEL, PET, CUSTOM, AUTO
    )

    /** 「新建账本」画廊：隐藏系统预置场景 */
    val gallery: List<ScenePreset> = all.filter { !it.hidden }

    /**
     * 「一键创建」推荐的 6 大场景（新建分账本页面置顶展示）。
     * 首次启动**不再**自动生成账本，全部由用户按需创建。
     */
    val quickCreate: List<ScenePreset> by lazy {
        listOf(DAILY, RENOVATION, REIMBURSEMENT, BABY, CAR, HOUSING)
    }

    fun byKey(key: String): ScenePreset = all.firstOrNull { it.key == key } ?: CUSTOM

    fun directionOf(preset: CategoryPreset, scene: ScenePreset): Int =
        if (scene.expenses.contains(preset)) Direction.EXPENSE else Direction.INCOME

    // ============================================================
    //  场景字段：值 → 中文文案
    // ------------------------------------------------------------
    //  流水里存的是字段的 **value**（如 "design" / "hardcover"），这是有意的：
    //  「报销状态」的 value 是 "0"/"1"/"2"，业务代码要靠它 `toIntOrNull()`。
    //  但直接把这个值显示出来就是英文了 —— 之前流水页副标题显示成
    //  「design · hardcover」就是这么来的。
    //  所以显示时统一走下面两个函数翻成中文 label，历史数据也一样能救回来。
    // ============================================================

    /** 单个字段值 → 中文 label；查不到就原样返回 */
    fun displayValue(sceneKey: String, fieldKey: String, raw: String): String {
        if (raw.isBlank()) return ""
        val field = byKey(sceneKey).fields.firstOrNull { it.key == fieldKey } ?: return raw
        return field.options.firstOrNull { it.value == raw }?.label ?: raw
    }

    /**
     * 给流水副标题用：按**场景定义的字段顺序**取前 [max] 个有值的字段，并翻成中文。
     *
     * 顺序很重要 —— 之前是 `extra.entries.take(2)`，取的是 JSON 键序，
     * 显示哪两个字段完全看运气。现在按场景里定义的顺序（空间 → 商家 → 阶段 → 付款状态），
     * 显示内容稳定且有意义。
     */
    fun extraSummary(sceneKey: String, extra: Map<String, String>, max: Int = 2): List<String> {
        if (extra.isEmpty()) return emptyList()
        val scene = byKey(sceneKey)
        val out = mutableListOf<String>()
        val known = HashSet<String>()

        scene.fields.forEach { f ->
            known += f.key
            if (out.size >= max) return@forEach
            // 报销状态已经由流水上的徽标表达，不再重复占副标题的位置
            if (f.key == "state") return@forEach
            val raw = extra[f.key]?.takeIf { it.isNotBlank() } ?: return@forEach
            out += when (f.kind) {
                FieldKind.CHOICE -> f.options.firstOrNull { it.value == raw }?.label ?: raw
                else -> "${f.label} $raw"
            }
        }

        // 不在场景定义里的键（例如账本换过场景）也照原样补上，不丢信息
        if (out.size < max) {
            extra.forEach { (k, v) ->
                if (k !in known && k != "state" && v.isNotBlank() && out.size < max) out += v
            }
        }
        return out
    }
}
