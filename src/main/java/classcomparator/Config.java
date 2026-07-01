package classcomparator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/** 配置加载 + 分类标签常量。所有字段 public static，与原有访问模式一致。 */
public class Config {

    // ── 可配置字段（成员变量是默认值，config.properties 可覆盖）──

    /** 旧版 class 文件根目录 */
    public static String OLD_DIR = "project/old";

    /** 新版 class 文件根目录 */
    public static String NEW_DIR = "project/new";

    /** 报告输出目录 */
    public static String REPORT_DIR = "report";

    /** Phase 4 反编译线程数。0=自动检测 */
    public static int THREADS = 0;

    /** 反编译引擎: "cfr" (线程安全) 或 "procyon" (需全局锁) */
    public static String DECOMPILER = "cfr";

    /** true=启用 AI 审查 */
    public static boolean AI_ENABLED = false;

    /** DeepSeek OpenAI 兼容端点 */
    public static String AI_BASE_URL = "https://api.deepseek.com/v1";

    /** 模型名称 */
    public static String AI_MODEL = "deepseek-v4-flash";

    /** AI 抽检比例: 0.0~1.0。支持百分数(15%)或小数(0.15) */
    public static double AI_SAMPLE_RATE = 1;

    /** AI API 并发数 */
    public static int AI_CONCURRENCY = 64;

    /** DeepSeek API 密钥 —— 无默认值，启用 AI 时 config.properties 必须配置 */
    public static String AI_API_KEY = "";

    // ── 内置常量（非配置项，仅用于调优）──
    public static final int MAX_DECOMPILE_THREADS = 16;
    public static final long DECOMPILE_TIMEOUT_MINUTES = 30;
    public static final long AI_TIMEOUT_MINUTES = 10;
    public static final int PHASE2_PROGRESS_INTERVAL = 200;
    public static final int PHASE3_PROGRESS_INTERVAL = 100;

    // ── 分类标签 ──
    public static final String CAT_SAME_FILE = "SHA-256一致";
    public static final String CAT_SAME_ASM = "ASM指纹一致";
    public static final String CAT_SAME_DECOMPILED = "反编译文本一致";
    public static final String CAT_AI_VERIFIED = "AI验证一致";
    public static final String CAT_DIFFERENT = "逻辑不同";
    public static final String CAT_MISSING_OLD = "仅新版存在(新增)";
    public static final String CAT_MISSING_NEW = "仅旧版存在(删除)";
    public static final String CAT_ERROR = "工具异常";

    /** 系统提示词：强制约束模型严格遵循用户指令 */
    public static final String AI_SYSTEM_PROMPT = "你是一个精确的代码差异分析工具。严格遵循用户指令，只输出要求的内容，不要输出任何额外解释、分析过程、前缀或后缀文字。";

    /**
     * 解析抽检比例，支持百分数和小数两种格式。
     */
    public static double parseSampleRate(String raw) {
        raw = raw.trim();
        boolean isPercent = raw.endsWith("%");
        if (isPercent)
            raw = raw.substring(0, raw.length() - 1).trim();
        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            System.err.println("[配置] ai.sample.rate 格式错误: " + raw + "，使用默认值 " + AI_SAMPLE_RATE);
            return AI_SAMPLE_RATE;
        }
        if (isPercent)
            value = value / 100.0;
        if (value > 1.0)
            value = 1.0;
        if (value < 0.0)
            value = 0.0;
        value = Math.round(value * 100.0) / 100.0;
        return value;
    }

    /**
     * 加载配置：classpath 内置模板 → 外部 config.properties 覆盖。
     */
    public static void loadConfig() {
        Properties props = new Properties();

        // 第一级：classpath 内置模板
        try (InputStream in = Config.class.getResourceAsStream("/config.properties")) {
            if (in != null) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            /* 忽略，使用代码默认值 */ }

        // 第二级：外部 config.properties 覆盖
        Path externalFile = Paths.get("config.properties");
        if (Files.exists(externalFile)) {
            try (Reader reader = Files.newBufferedReader(externalFile, StandardCharsets.UTF_8)) {
                Properties external = new Properties();
                external.load(reader);
                props.putAll(external);
                System.out.println("[配置] 已加载外部 config.properties");
            } catch (IOException e) {
                System.out.println("[配置] 读取外部 config.properties 失败: " + e.getMessage());
            }
        }

        OLD_DIR = getProp(props, "old.dir", OLD_DIR);
        NEW_DIR = getProp(props, "new.dir", NEW_DIR);
        REPORT_DIR = getProp(props, "report.dir", REPORT_DIR);
        THREADS = parseIntSafe(getProp(props, "threads", String.valueOf(THREADS)), THREADS);
        DECOMPILER = getProp(props, "decompiler", DECOMPILER);
        AI_ENABLED = Boolean.parseBoolean(getProp(props, "ai.enabled", String.valueOf(AI_ENABLED)));
        AI_SAMPLE_RATE = parseSampleRate(getProp(props, "ai.sample.rate", String.valueOf(AI_SAMPLE_RATE)));
        AI_CONCURRENCY = parseIntSafe(getProp(props, "ai.concurrency", String.valueOf(AI_CONCURRENCY)), AI_CONCURRENCY);
        AI_MODEL = getProp(props, "ai.model", AI_MODEL);
        AI_BASE_URL = getProp(props, "ai.base.url", AI_BASE_URL);
        AI_API_KEY = props.getProperty("ai.api.key", "").trim();
    }

    private static String getProp(Properties props, String key, String defaultValue) {
        return props.getProperty(key, defaultValue).trim();
    }

    private static int parseIntSafe(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            System.err.println("[配置] 数值格式错误: " + raw + "，使用默认值 " + fallback);
            return fallback;
        }
    }
}
