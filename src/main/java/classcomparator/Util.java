package classcomparator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

/** 纯工具方法：SHA-256、文件扫描、字符串转义、格式化。无状态，全部 public static。 */
public class Util {

    /** ThreadLocal 复用 MessageDigest 实例，避免每次调用 getInstance 的开销 */
    private static final ThreadLocal<MessageDigest> SHA256_DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    });

    public static String sha256(byte[] data) {
        MessageDigest md = SHA256_DIGEST.get();
        md.reset();
        byte[] digest = md.digest(data);
        StringBuilder builder = new StringBuilder();
        for (byte b : digest)
            builder.append(String.format("%02x", b));
        return builder.toString();
    }

    /** 递归扫描目录，返回 全限定类名 → .class 文件 Path */
    public static Map<String, Path> scan(Path root) throws IOException {
        Map<String, Path> map = new HashMap<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".class")).forEach(x -> {
                String name = root.relativize(x).toString().replace('\\', '.').replace('/', '.');
                name = name.substring(0, name.length() - 6); // 去掉 ".class" 后缀
                map.put(name, x);
            });
        }
        return map;
    }

    /** 递归删除目录。异常发生时尽最大努力清空。 */
    public static void deleteRecursively(Path dir) {
        if (!Files.exists(dir))
            return;
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            /* 尽最大努力 */ }
                    });
        } catch (IOException e) {
            /* 尽最大努力 */ }
    }

    /** 写入文本行到文件 */
    public static void writeLines(Path p, List<String> data) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (String line : data)
            builder.append(line).append('\n');
        Files.write(p, builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 读取文件所有非空行，文件不存在返回空列表 */
    public static List<String> readLinesIfExists(Path file) throws IOException {
        if (!Files.exists(file))
            return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
            if (!line.trim().isEmpty())
                list.add(line.trim());
        return list;
    }

    /** com.example.Foo → com_example_Foo.txt（同时处理资源路径中的 / 和 \） */
    public static String toFileName(String className) {
        return className.replace('.', '_').replace('/', '_').replace('\\', '_') + ".txt";
    }

    /** com/example/config.properties → com_example_config.properties.txt */
    public static String toResFileName(String path) {
        return path.replace('/', '_').replace('\\', '_') + ".txt";
    }

    public static String pct(int part, int total) {
        if (total == 0)
            return "0%";
        return Math.round(part * 1000.0 / total) / 10.0 + "%";
    }

    public static String rpad(int n, int width) {
        String s = String.valueOf(n);
        while (s.length() < width)
            s = " " + s;
        return s;
    }

    /** HTML 转义 */
    public static String esc(String s) {
        if (s == null)
            return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** CSV 字段转义 */
    public static String escapeCsv(String s) {
        if (s == null)
            return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    /**
     * JSON 字符串转义 —— 用于将源码文本嵌入 HTML 的 &lt;script&gt; 标签中。
     * 额外转义 &lt; 防止 "&lt;/script&gt;" 等序列被 HTML 解析器提前关闭标签。
     */
    public static String escapeJson(String s) {
        if (s == null)
            return "null";
        StringBuilder sb = new StringBuilder(s.length() + 32);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '<':
                    sb.append("\\u003c");
                    break; // 防止 HTML 解析器误关 script 标签
                default:
                    if (c < 0x20)
                        sb.append(String.format("\\u%04x", (int) c));
                    else
                        sb.append(c);
            }
        }
        return sb.toString();
    }

    public static int countCat(Map<String, String> catMap, String cat) {
        int n = 0;
        for (String v : catMap.values())
            if (cat.equals(v))
                n++;
        return n;
    }

    /** com.example.Foo → com.example，无包名返回 "(默认包)" */
    public static String extractPackage(String className) {
        int idx = className.lastIndexOf('.');
        return idx > 0 ? className.substring(0, idx) : "(默认包)";
    }

    /**
     * 资源文件文本归一化：每行内 \s+→单个空格 → trim → 删除空行。
     * 消除仅换行符/缩进/尾部空格导致的 SHA-256 假阳性。
     */
    public static String normalizeResourceText(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : text.split("\\r?\\n")) {
            String t = line.replaceAll("\\s+", " ").trim();
            if (!t.isEmpty())
                sb.append(t).append('\n');
        }
        return sb.toString();
    }

    /** 判断字节数组是否为文本（不含 null 字节） */
    public static boolean isText(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0)
                return false;
        }
        return true;
    }

    /* ── JDK 版本检测 ── */

    /** 从 class 文件头读取主版本号。magic(4B) + minor(2B) + major(2B) */
    public static int getClassMajorVersion(byte[] data) {
        if (data == null || data.length < 8)
            return -1;
        return ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
    }

    public static String versionName(int major) {
        switch (major) {
            case 45:
                return "JDK 1.1";
            case 46:
                return "JDK 1.2";
            case 47:
                return "JDK 1.3";
            case 48:
                return "JDK 1.4";
            case 49:
                return "JDK 5";
            case 50:
                return "JDK 6";
            case 51:
                return "JDK 7";
            case 52:
                return "JDK 8";
            case 53:
                return "JDK 9";
            case 54:
                return "JDK 10";
            case 55:
                return "JDK 11";
            case 56:
                return "JDK 12";
            case 57:
                return "JDK 13";
            case 58:
                return "JDK 14";
            case 59:
                return "JDK 15";
            case 60:
                return "JDK 16";
            case 61:
                return "JDK 17";
            case 62:
                return "JDK 18";
            case 63:
                return "JDK 19";
            case 64:
                return "JDK 20";
            case 65:
                return "JDK 21";
            default:
                return "未知(" + major + ")";
        }
    }

    /** 打印 class 版本分布，读取 PhaseResults.versionCount */
    public static void reportVersionSummary(int[] versionCount) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < versionCount.length; i++) {
            if (versionCount[i] > 0) {
                if (builder.length() > 0)
                    builder.append(", ");
                builder.append(versionName(i)).append("=").append(versionCount[i]);
            }
        }
        if (builder.length() > 0)
            System.out.println("  class版本: " + builder.toString());
    }
}
