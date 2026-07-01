package classcomparator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;

import com.strobel.decompiler.DecompilerSettings;
import com.strobel.decompiler.PlainTextOutput;

/** CFR/Procyon 反编译 + 源码归一化 + 差异详情文件写入。 */
public class Decompiler {

    /** 反编译 .class → Java 源码。根据 DECOMPILER 配置选择引擎。 */
    public static String decompileClass(Path classFile) {
        if ("cfr".equals(Config.DECOMPILER)) {
            return decompileCFR(classFile);
        } else {
            return decompileProcyon(classFile);
        }
    }

    /** CFR 反编译器 —— 线程安全，每个线程独立创建实例。 */
    public static String decompileCFR(Path classFile) {
        try {
            StringBuilder result = new StringBuilder();
            OutputSinkFactory sinkFactory = new OutputSinkFactory() {
                @Override
                public List<SinkClass> getSupportedSinks(SinkType sinkType,
                        Collection<SinkClass> available) {
                    return Arrays.asList(SinkClass.DECOMPILED);
                }

                @Override
                public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                    if (sinkType == SinkType.JAVA && sinkClass == SinkClass.DECOMPILED) {
                        return sinkable -> {
                            if (sinkable instanceof SinkReturns.Decompiled) {
                                result.append(((SinkReturns.Decompiled) sinkable).getJava());
                            }
                        };
                    }
                    return x -> {
                    };
                }
            };

            Map<String, String> cfrOptions = new HashMap<>();
            cfrOptions.put("hidebridgemethods", "true");
            cfrOptions.put("hidelangimports", "true");
            cfrOptions.put("hideutf", "false");
            cfrOptions.put("showversion", "false");

            CfrDriver driver = new CfrDriver.Builder()
                    .withOutputSink(sinkFactory)
                    .withOptions(cfrOptions)
                    .build();
            driver.analyse(Collections.singletonList(
                    classFile.toAbsolutePath().toString()));
            return result.toString();
        } catch (Exception e) {
            System.err.println("  [CFR] " + classFile.getFileName() + " -> "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return "";
        }
    }

    /** Procyon 反编译器 —— 需全局锁串行调用。 */
    public static String decompileProcyon(Path classFile) {
        try {
            StringWriter writer = new StringWriter();
            DecompilerSettings settings = DecompilerSettings.javaDefaults();
            settings.setForceExplicitImports(true);
            settings.setFlattenSwitchBlocks(true);
            synchronized (PhaseResults.PROCYON_LOCK) {
                com.strobel.decompiler.Decompiler.decompile(classFile.toAbsolutePath().toString(),
                        new PlainTextOutput(writer), settings);
            }
            return writer.toString();
        } catch (Exception e) {
            System.err.println("  [Procyon] " + classFile.getFileName() + " -> "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return "";
        }
    }

    // ── 源码归一化正则（预编译）──

    static final Pattern RE_SINGLE_COMMENT = Pattern.compile("//[^\n\r]*");
    static final Pattern RE_MULTI_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    static final Pattern RE_WHITESPACE = Pattern.compile("\\s+");
    static final Pattern RE_LINE_SPLIT = Pattern.compile("\\r?\\n");
    static final Pattern RE_ENUM_VALUES = Pattern.compile(
            "public static \\w+\\[\\] values\\(\\)\\s*\\{[^}]*\\}");
    static final Pattern RE_ENUM_VALUEOF = Pattern.compile(
            "public static \\w+ valueOf\\(String \\w+\\)\\s*\\{[^}]*\\}");

    public static boolean decompiledSourceEquals(String src1, String src2) {
        return normalizeSource(src1).equals(normalizeSource(src2));
    }

    /**
     * 反编译源码归一化：去注释/导包/空白/enum隐式方法，保留方法体逻辑骨架。
     */
    public static String normalizeSource(String source) {
        if (source == null || source.isEmpty())
            return "";
        source = RE_SINGLE_COMMENT.matcher(source).replaceAll("");
        source = RE_MULTI_COMMENT.matcher(source).replaceAll("");
        source = RE_ENUM_VALUES.matcher(source).replaceAll("");
        source = RE_ENUM_VALUEOF.matcher(source).replaceAll("");
        StringBuilder result = new StringBuilder();
        for (String line : RE_LINE_SPLIT.split(source)) {
            String collapsed = line.trim();
            if (collapsed.isEmpty() || collapsed.startsWith("package ")
                    || collapsed.startsWith("import "))
                continue;
            result.append(RE_WHITESPACE.matcher(collapsed).replaceAll(" ")).append('\n');
        }
        return result.toString();
    }

    // ── 差异详情文件写入 ──

    /** 写入反编译源码逐行对比（供人工审查和 Phase 5 AI 读取） */
    public static void writeDecompiledDiff(Path file, String className, String oldSrc, String newSrc)
            throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("=== 类: ").append(className).append(" ===\n\n");
        builder.append("=== 旧版反编译 ===\n\n").append(oldSrc);
        builder.append("\n\n=== 新版反编译 ===\n\n").append(newSrc);
        builder.append("\n\n=== 归一化行数对比 ===\n");
        builder.append("旧版: ").append(normalizeSource(oldSrc).split("\n").length).append(" 行\n");
        builder.append("新版: ").append(normalizeSource(newSrc).split("\n").length).append(" 行\n");
        Files.write(file, builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 回退写入 ASM 指纹详情（反编译失败时使用） */
    public static void writeDetailFromCache(Path file, String cls) throws IOException {
        Fingerprint.FingerprintPair pair = PhaseResults.FINGERPRINTS.get(cls);
        if (pair != null)
            writeDetail(file, pair.oldFp, pair.newFp);
    }

    static void writeDetail(Path file, Fingerprint.ClassFingerprint oldFp,
            Fingerprint.ClassFingerprint newFp) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("===== 旧版 (ASM 指纹) =====\n\n").append(oldFp.normalized);
        builder.append("\n\n===== 新版 (ASM 指纹) =====\n\n").append(newFp.normalized);
        Files.write(file, builder.toString().getBytes(StandardCharsets.UTF_8));
    }
}
