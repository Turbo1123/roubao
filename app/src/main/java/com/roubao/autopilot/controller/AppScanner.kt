package com.roubao.autopilot.controller

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * App 扫描器 - 获取所有已安装应用信息
 * 支持：预扫描缓存、拼音匹配、分类、语义搜索
 */
class AppScanner(private val context: Context) {

    companion object {
        private const val CACHE_FILE = "installed_apps.json"

        // 内存缓存 (应用生命周期内有效)
        @Volatile
        private var cachedApps: List<AppInfo>? = null

        // 预编译的正则表达式（避免重复创建）
        private val PINYIN_CLEAN_REGEX = Regex("[^a-z0-9\\u4e00-\\u9fa5]")

        // 应用分类关键词映射
        private val CATEGORY_KEYWORDS = mapOf(
            "社交" to listOf("微信", "QQ", "钉钉", "飞书", "Telegram", "WhatsApp", "Line", "微博", "陌陌", "探探"),
            "购物" to listOf("淘宝", "京东", "拼多多", "天猫", "苏宁", "唯品会", "得物", "闲鱼", "当当"),
            "外卖" to listOf("美团", "饿了么", "肯德基", "麦当劳", "星巴克", "瑞幸", "小美"),
            "出行" to listOf("滴滴", "高德", "百度地图", "腾讯地图", "嘀嗒", "哈啰", "曹操", "T3", "花小猪"),
            "地图" to listOf("高德", "百度地图", "腾讯地图", "谷歌地图", "导航"),
            "音乐" to listOf("网易云", "QQ音乐", "酷狗", "酷我", "Spotify", "Apple Music", "虾米"),
            "视频" to listOf("抖音", "快手", "B站", "bilibili", "优酷", "爱奇艺", "腾讯视频", "芒果TV", "西瓜视频"),
            "支付" to listOf("支付宝", "微信", "云闪付", "翼支付"),
            "笔记" to listOf("印象笔记", "有道云", "Notion", "备忘录", "便签", "笔记", "记事", "OneNote", "滴答"),
            "相机" to listOf("相机", "Camera", "拍照", "美颜", "美图", "轻颜"),
            "图片" to listOf("相册", "图库", "Photos", "Gallery", "照片"),
            "浏览器" to listOf("Chrome", "Safari", "Firefox", "Edge", "浏览器", "UC", "夸克", "Via"),
            "办公" to listOf("WPS", "Office", "钉钉", "飞书", "企业微信", "Slack", "Teams"),
            "AI" to listOf("ChatGPT", "Claude", "豆包", "文心", "通义", "讯飞", "Copilot", "即梦", "Midjourney"),
            "工具" to listOf("计算器", "手电筒", "指南针", "时钟", "闹钟", "日历", "天气", "文件管理"),
            "阅读" to listOf("微信读书", "Kindle", "掌阅", "番茄小说", "起点", "知乎", "今日头条"),
            "游戏" to listOf("王者荣耀", "和平精英", "原神", "崩坏", "阴阳师", "游戏")
        )

        // 拼音映射表 (常用应用)
        private val PINYIN_MAP = mapOf(
            // 社交
            "weixin" to "微信", "wechat" to "微信", "wx" to "微信",
            "qq" to "QQ",
            "dingding" to "钉钉", "dingtalk" to "钉钉",
            "feishu" to "飞书", "lark" to "飞书",
            "weibo" to "微博",

            // 购物
            "taobao" to "淘宝", "tb" to "淘宝",
            "jingdong" to "京东", "jd" to "京东",
            "pinduoduo" to "拼多多", "pdd" to "拼多多",
            "xianyu" to "闲鱼",

            // 外卖/餐饮
            "meituan" to "美团", "mt" to "美团",
            "eleme" to "饿了么", "elm" to "饿了么",
            "xiaomei" to "小美",
            "kfc" to "肯德基", "kendeji" to "肯德基",
            "maidanglao" to "麦当劳", "mcdonald" to "麦当劳",
            "starbucks" to "星巴克", "xingbake" to "星巴克",
            "ruixing" to "瑞幸", "luckin" to "瑞幸",

            // 出行/地图
            "didi" to "滴滴", "dd" to "滴滴",
            "gaode" to "高德", "amap" to "高德",
            "baidu" to "百度", "baidumap" to "百度地图",
            "ditu" to "地图",
            "daohang" to "导航",

            // 支付
            "zhifubao" to "支付宝", "alipay" to "支付宝", "zfb" to "支付宝",

            // 音乐
            "wangyiyun" to "网易云", "netease" to "网易云",
            "qqmusic" to "QQ音乐", "qqyinyue" to "QQ音乐",
            "kugou" to "酷狗",
            "yinyue" to "音乐", "music" to "音乐",

            // 视频
            "douyin" to "抖音", "tiktok" to "抖音", "dy" to "抖音",
            "kuaishou" to "快手", "ks" to "快手",
            "bilibili" to "哔哩哔哩", "bzhan" to "哔哩哔哩", "b站" to "哔哩哔哩",
            "youku" to "优酷",
            "iqiyi" to "爱奇艺", "aiqiyi" to "爱奇艺",
            "shipin" to "视频", "video" to "视频",

            // 笔记/办公
            "biji" to "笔记", "note" to "笔记", "notes" to "笔记",
            "beiwanglu" to "备忘录", "memo" to "备忘录",
            "bianjian" to "便签",
            "wps" to "WPS",

            // 系统
            "shezhi" to "设置", "settings" to "设置",
            "xiangji" to "相机", "camera" to "相机", "paizhao" to "相机",
            "xiangce" to "相册", "photos" to "相册", "gallery" to "相册", "tuku" to "图库",
            "dianhua" to "电话", "phone" to "电话",
            "duanxin" to "短信", "message" to "短信", "sms" to "短信",
            "liulanqi" to "浏览器", "browser" to "浏览器",
            "jisuanqi" to "计算器", "calculator" to "计算器",
            "shizhong" to "时钟", "clock" to "时钟",
            "naozhong" to "闹钟", "alarm" to "闹钟",
            "rili" to "日历", "calendar" to "日历",
            "tianqi" to "天气", "weather" to "天气",
            "wenjian" to "文件", "file" to "文件"
        )

        data class SemanticRule(
            val keywords: List<String>,
            val category: String,
            val priority: Int = 0
        )

        // 语义查询规则（支持整句匹配 + 优先级）
        private val SEMANTIC_RULES = listOf(
            SemanticRule(listOf("拍照", "照相", "自拍", "拍摄"), "相机", 10),
            SemanticRule(listOf("看照片", "看图", "图片"), "图片", 9),
            SemanticRule(listOf("聊天", "发消息", "通讯"), "社交", 10),
            SemanticRule(listOf("买东西", "购物", "网购", "下单"), "购物", 10),
            SemanticRule(listOf("点餐", "叫外卖", "点外卖", "订餐", "吃饭"), "外卖", 10),
            SemanticRule(listOf("打车", "叫车", "坐车"), "出行", 10),
            SemanticRule(listOf("出行"), "出行", 5),
            SemanticRule(listOf("导航", "找路", "怎么走", "去哪"), "地图", 10),
            SemanticRule(listOf("听歌", "听音乐", "放歌", "播放音乐"), "音乐", 10),
            SemanticRule(listOf("看视频", "刷视频", "追剧", "看电影", "看剧"), "视频", 10),
            SemanticRule(listOf("付款", "支付", "扫码", "收款"), "支付", 10),
            SemanticRule(listOf("记笔记", "写笔记", "记事"), "笔记", 10),
            SemanticRule(listOf("记录"), "笔记", 5), // 降低歧义词优先级
            SemanticRule(listOf("搜索", "查资料", "上网"), "浏览器", 9),
            SemanticRule(listOf("画图", "生成图片", "AI画图"), "AI", 10),
            SemanticRule(listOf("AI"), "AI", 5),
            SemanticRule(listOf("看书", "阅读", "读书", "小说"), "阅读", 10),
            SemanticRule(listOf("玩游戏", "游戏"), "游戏", 10),
            SemanticRule(listOf("办公", "工作", "文档"), "办公", 9)
        )
    }

    /**
     * 应用信息
     */
    data class AppInfo(
        val packageName: String,
        val appName: String,
        val pinyin: String,        // 拼音（自动生成）
        val category: String?,     // 分类
        val isSystem: Boolean,
        val keywords: List<String> // 关键词（用于搜索）
    )

    /**
     * 搜索结果
     */
    data class SearchResult(
        val app: AppInfo,
        val score: Float,          // 匹配分数 0-1
        val matchType: String      // 匹配类型：exact/contains/pinyin/category/semantic
    )

    /**
     * 获取应用列表 (优先内存 -> 文件 -> 扫描)
     */
    fun getApps(): List<AppInfo> {
        cachedApps?.let { return it }

        val cacheFile = File(context.filesDir, CACHE_FILE)
        if (cacheFile.exists()) {
            val loaded = loadFromFile(cacheFile)
            if (loaded.isNotEmpty()) {
                cachedApps = loaded
                println("[AppScanner] 从文件加载 ${loaded.size} 个应用")
                return loaded
            }
        }

        return refreshApps()
    }

    /**
     * 强制刷新应用列表
     */
    fun refreshApps(): List<AppInfo> {
        println("[AppScanner] 扫描已安装应用...")
        val apps = scanAllApps()
        cachedApps = apps

        val cacheFile = File(context.filesDir, CACHE_FILE)
        saveToFile(apps, cacheFile)
        println("[AppScanner] 已缓存 ${apps.size} 个应用")

        return apps
    }

    /**
     * 扫描所有已安装应用
     */
    private fun scanAllApps(): List<AppInfo> {
        val pm = context.packageManager
        val apps = mutableListOf<AppInfo>()

        try {
            // 使用 0 作为 flag，获取所有应用（不过滤）
            val packages = pm.getInstalledApplications(0)
            for (appInfo in packages) {
                val appName = pm.getApplicationLabel(appInfo).toString()
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val pinyin = toPinyin(appName)
                val category = detectCategory(appName, appInfo.packageName)
                val keywords = generateKeywords(appName, appInfo.packageName, category)

                apps.add(AppInfo(
                    packageName = appInfo.packageName,
                    appName = appName,
                    pinyin = pinyin,
                    category = category,
                    isSystem = isSystem,
                    keywords = keywords
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return apps.sortedBy { it.appName }
    }

    /**
    * 根据用户自然语言查询匹配应用分类
    * @param query 用户输入的搜索词或自然语言描述
    * @return 匹配到的应用分类；若未命中任何语义规则则返回 null
    */
    fun matchCategory(input: String): String? {
    return SEMANTIC_RULES
        .filter { rule -> rule.keywords.any { input.contains(it) } }
        .maxByOrNull { it.priority }
        ?.category
    }

    /**
     * 智能搜索应用
     * @param query 搜索词（支持：应用名、拼音、分类、语义描述）
     * @param topK 返回前 K 个结果
     * @param includeSystem 是否包含系统应用
     */
    fun searchApps(query: String, topK: Int = 5, includeSystem: Boolean = true): List<SearchResult> {
        val apps = getApps()
        val lowerQuery = query.lowercase().trim()
        val results = mutableListOf<SearchResult>()

        // 先检查是否是语义查询，转换为分类
        val category = matchSemanticCategory(userInput)
        val pinyinMapped = PINYIN_MAP[lowerQuery]

        for (app in apps) {
            if (!includeSystem && app.isSystem) continue

            var score = 0f
            var matchType = ""

            // 1. 精确匹配应用名 (最高优先级)
            if (app.appName.equals(query, ignoreCase = true)) {
                score = 1.0f
                matchType = "exact"
            }
            // 2. 拼音映射精确匹配
            else if (pinyinMapped != null && app.appName.contains(pinyinMapped)) {
                score = 0.95f
                matchType = "pinyin_exact"
            }
            // 3. 应用名包含查询词
            else if (app.appName.lowercase().contains(lowerQuery)) {
                score = 0.9f
                matchType = "contains"
            }
            // 4. 拼音包含
            else if (app.pinyin.contains(lowerQuery)) {
                score = 0.8f
                matchType = "pinyin"
            }
            // 5. 关键词匹配
            else if (app.keywords.any { it.contains(lowerQuery) || lowerQuery.contains(it) }) {
                score = 0.7f
                matchType = "keyword"
            }
            // 6. 分类匹配（语义查询）
            else if (semanticCategory != null && app.category == semanticCategory) {
                score = 0.6f
                matchType = "semantic"
            }
            // 7. 包名包含
            else if (app.packageName.lowercase().contains(lowerQuery)) {
                score = 0.5f
                matchType = "package"
            }

            if (score > 0) {
                // 非系统应用加分
                if (!app.isSystem) score += 0.05f
                results.add(SearchResult(app, score.coerceAtMost(1f), matchType))
            }
        }

        return results
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * 根据名称模糊搜索包名 (兼容旧接口)
     */
    fun findPackage(query: String): String? {
        val results = searchApps(query, topK = 1)
        return results.firstOrNull()?.app?.packageName
    }

    /**
     * 按分类获取应用
     */
    fun getAppsByCategory(category: String): List<AppInfo> {
        return getApps().filter { it.category == category }
    }

    /**
     * 获取所有分类
     */
    fun getAllCategories(): List<String> {
        return getApps().mapNotNull { it.category }.distinct().sorted()
    }

    /**
     * 格式化搜索结果给 LLM
     */
    fun formatSearchResultsForLLM(results: List<SearchResult>): String {
        if (results.isEmpty()) return "未找到匹配的应用"

        return buildString {
            append("找到以下应用，请选择最合适的：\n")
            results.forEachIndexed { index, result ->
                val app = result.app
                val categoryStr = app.category?.let { " [$it]" } ?: ""
                append("${index + 1}. ${app.appName}$categoryStr (${app.packageName})\n")
            }
        }
    }

    // ========== 辅助方法 ==========

    /**
     * 简单拼音转换（仅处理常见中文字符）
     */
    private fun toPinyin(text: String): String {
        // 简单实现：保留英文数字，中文用首字母拼音
        // 完整拼音需要引入 pinyin4j 库，这里用简化版本
        return text.lowercase()
            .replace(PINYIN_CLEAN_REGEX, "")
    }

    /**
     * 检测应用分类
     */
    private fun detectCategory(appName: String, packageName: String): String? {
        val lowerName = appName.lowercase()
        val lowerPackage = packageName.lowercase()

        for ((category, keywords) in CATEGORY_KEYWORDS) {
            for (keyword in keywords) {
                if (lowerName.contains(keyword.lowercase()) ||
                    lowerPackage.contains(keyword.lowercase())) {
                    return category
                }
            }
        }
        return null
    }

    /**
     * 生成搜索关键词
     */
    private fun generateKeywords(appName: String, packageName: String, category: String?): List<String> {
        val keywords = mutableListOf<String>()

        // 从包名提取关键词
        val packageParts = packageName.split(".")
        keywords.addAll(packageParts.filter { it.length > 2 })

        // 添加分类
        category?.let { keywords.add(it) }

        // 从拼音映射反向添加
        for ((pinyin, name) in PINYIN_MAP) {
            if (appName.contains(name)) {
                keywords.add(pinyin)
            }
        }

        return keywords.distinct()
    }

    // ========== 缓存相关 ==========

    private fun saveToFile(apps: List<AppInfo>, file: File) {
        try {
            val jsonArray = JSONArray()
            for (app in apps) {
                val obj = JSONObject()
                obj.put("package", app.packageName)
                obj.put("name", app.appName)
                obj.put("pinyin", app.pinyin)
                obj.put("category", app.category ?: "")
                obj.put("system", app.isSystem)
                obj.put("keywords", JSONArray(app.keywords))
                jsonArray.put(obj)
            }
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadFromFile(file: File): List<AppInfo> {
        val apps = mutableListOf<AppInfo>()
        try {
            val jsonArray = JSONArray(file.readText())
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val keywordsArray = obj.optJSONArray("keywords")
                val keywords = mutableListOf<String>()
                if (keywordsArray != null) {
                    for (j in 0 until keywordsArray.length()) {
                        keywords.add(keywordsArray.getString(j))
                    }
                }

                apps.add(AppInfo(
                    packageName = obj.getString("package"),
                    appName = obj.getString("name"),
                    pinyin = obj.optString("pinyin", ""),
                    category = obj.optString("category", null)?.takeIf { it.isNotEmpty() },
                    isSystem = obj.optBoolean("system", false),
                    keywords = keywords
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return apps
    }
}
