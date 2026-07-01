package classcomparator;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import com.openai.models.chat.completions.ChatCompletionCreateParams;

/** 异步 AI 审查客户端：DeepSeek API 调用 + 结果缓存 + 判定解析。 */
public class AiReviewer {

    /** 单词边界匹配 YES/NO，避免误匹配 "yesterday"/"nothing" 等 */
    private static final Pattern RE_WORD_YES = Pattern.compile("\\bYES\\b");
    private static final Pattern RE_WORD_NO = Pattern.compile("\\bNO\\b");

    /**
     * OpenAI 异步客户端 —— 延迟初始化，静态内部类 holder 保证线程安全。
     */
    public static com.openai.client.OpenAIClientAsync getAsyncClient() {
        if (Config.AI_API_KEY.isEmpty()) {
            System.err.println("[警告] AI API Key 未配置，请检查 config.properties 中 ai.api.key 是否设置");
        }
        return AsyncClientHolder.INSTANCE;
    }

    private static class AsyncClientHolder {
        static final com.openai.client.OpenAIClientAsync INSTANCE = com.openai.client.okhttp.OpenAIOkHttpClientAsync
                .builder()
                .apiKey(Config.AI_API_KEY)
                .baseUrl(Config.AI_BASE_URL)
                .build();
    }

    /**
     * 异步调用 DeepSeek，判断两个文件是否逻辑等价（class 或资源文件）。
     *
     * @param oldContent 旧版内容
     * @param newContent 新版内容
     * @param isResource true=资源文件，使用资源专用提示词
     * @param methodInfo 方法级差异信息（仅 class 文件，由 Phase 3 提供）
     * @return CompletableFuture：成功时含 AI 响应全文，异常时返回 null
     */
    public static CompletableFuture<String> aiReviewAsync(String oldContent, String newContent,
            boolean isResource, String methodInfo) {
        String oldPrepared, newPrepared;
        String prompt;
        String cacheKey;

        if (isResource) {
            // 对文本资源做归一化以消除仅空白差异的重复 AI 请求
            if (Util.isText(oldContent.getBytes(StandardCharsets.UTF_8))
                    && Util.isText(newContent.getBytes(StandardCharsets.UTF_8))) {
                oldPrepared = Util.normalizeResourceText(oldContent);
                newPrepared = Util.normalizeResourceText(newContent);
            } else {
                oldPrepared = oldContent;
                newPrepared = newContent;
            }
            cacheKey = Util.sha256((oldPrepared + "\0" + newPrepared).getBytes(StandardCharsets.UTF_8));
            String cached = PhaseResults.AI_RESULT_CACHE.get(cacheKey);
            if (cached != null)
                return CompletableFuture.completedFuture(cached);

            prompt = String.format("【任务】比较以下两个新老两个文件的内容是否等价。"
                    + "【等价判定标准】"
                    + "以下情况视为等价(应返回 YES):"
                    + "- 仅空格/换行/缩进/注释不同"
                    + "- 仅元素/属性顺序不同但语义相同"
                    + "以下情况视为不等价(应返回 NO):"
                    + "- 任何字符、单词、数字、变量名的实际增删或修改"
                    + "- 任何 SQL 条件、字段、表名的增减或不同"
                    + "- 任何导致执行逻辑发生变化的内容差异"
                    + "【输出规则】\r\n"
                    + "1. 严禁输出任何思考、查找或对比的过程。\r\n"
                    + "2. 若未发现行为变化的证据，只输出一个单词：YES（前后无任何其他字符）。\r\n"
                    + "3. 若确信存在行为差异，先输出 NO，换行后按以下格式列举功能差异，忽略功能相同、逻辑相同、无实际影响的差异：\r\n"
                    + "NO\r\n"
                    + " 差异：【差异位置】 | 差异内容：【改动行前后 2 行以内】 | 差异概述：【简单描述差异】\r\n"
                    + "（若有多条，每行以 \"⬤ \" 开头，顺序排列）\r\n"
                    + "=== OLD FILE === %s"
                    + "=== NEW FILE === %s", oldContent, newContent);
        } else {
            oldPrepared = Decompiler.normalizeSource(oldContent);
            newPrepared = Decompiler.normalizeSource(newContent);
            cacheKey = Util.sha256((oldPrepared + "\0" + newPrepared).getBytes(StandardCharsets.UTF_8));
            String cached = PhaseResults.AI_RESULT_CACHE.get(cacheKey);
            if (cached != null)
                return CompletableFuture.completedFuture(cached);

            String methodHint = (methodInfo != null && !methodInfo.isEmpty())
                    ? "\n" + methodInfo + "\n"
                    : "";

            prompt = String.format("【上下文】输入代码由反编译器从 Java Class 字节码生成，请忽略反编译器导致的结构差异（如 label、goto、变量重命名、代码块重排等）。\r\n"
                    + methodHint
                    + "【核心原则】\r\n"
                    + "1. 寻找行为变化的确定性证据。可观察行为包括：方法返回值、抛出的异常、修改的成员/静态变量、输出流、数据库操作、外部调用。\r\n"
                    + "2. 不得因代码风格、重构方式、变量命名、表达式形式（如 if-else ↔ 三元）或无关语句顺序调整而输出 NO。\r\n"
                    + "3. 只有存在确凿证据证明可观察行为发生变化时，才允许输出 NO。证据不足时，必须输出 YES。\r\n"
                    + "4. 对于已确认不影响行为的差异，无需重复描述，但必须继续检查所有其他位置，不得提前终止。\r\n"
                    + "【输出规则】\r\n"
                    + "1. 严禁输出任何思考、查找或对比的过程。\r\n"
                    + "2. 若未发现行为变化的证据，只输出一个单词：YES（前后无任何其他字符）。\r\n"
                    + "3. 若确信存在行为差异，先输出 NO，换行后按以下格式列举功能差异，忽略功能相同、逻辑相同、无实际影响的差异：\r\n"
                    + "NO\r\n"
                    + " 方法：【方法名或全局】 | 代码证据：【改动行前后 2 行以内】 | 行为变化：【具体变化描述】\r\n"
                    + "（若有多条，每行以 \"⬤ \" 开头，顺序排列）\r\n"
                    + "=== OLD CLASS ===\r\n"
                    + "%s\r\n"
                    + "=== NEW CLASS ===\r\n"
                    + "%s", oldPrepared, newPrepared);
        }

        final String finalCacheKey = cacheKey;

        ChatCompletionCreateParams chatParameters = ChatCompletionCreateParams.builder()
                .model(Config.AI_MODEL)
                .addSystemMessage(Config.AI_SYSTEM_PROMPT)
                .addUserMessage(prompt)
                .temperature(0.0)
                .putAdditionalBodyProperty("thinking",
                        com.openai.core.JsonValue.from(Collections.singletonMap("type", "disabled")))
                .build();

        return getAsyncClient().chat().completions().create(chatParameters)
                .thenApply(completion -> {
                    if (completion.choices().isEmpty())
                        return ""; // 内容被过滤等边缘情况
                    String result = completion.choices().get(0).message().content().orElse("");
                    if (result != null && !result.isEmpty())
                        PhaseResults.AI_RESULT_CACHE.put(finalCacheKey, result);
                    return result;
                })
                .exceptionally(exception -> {
                    System.err.println("  [AI] API 调用失败: "
                            + (exception.getCause() != null
                                    ? exception.getCause().getMessage()
                                    : exception.getMessage()));
                    return null;
                });
    }

    /**
     * 从 AI 响应文本中提取判定结果。
     * 策略：先检查首行，若无法判定则全文搜索关键词兜底（单词边界匹配）。
     */
    public static String parseAiVerdict(String aiResponseText) {
        if (aiResponseText == null)
            return null;
        String firstLine = aiResponseText.split("\\r?\\n")[0].trim().toUpperCase();
        if (firstLine.startsWith("YES"))
            return "YES";
        if (firstLine.startsWith("NO"))
            return "NO";
        // 兜底：全文搜索关键词
        String upper = aiResponseText.toUpperCase();
        if (RE_WORD_YES.matcher(upper).find())
            return "YES";
        if (RE_WORD_NO.matcher(upper).find())
            return "NO";
        return null;
    }
}
