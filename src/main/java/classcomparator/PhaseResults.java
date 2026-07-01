package classcomparator;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * 各阶段共享的可变状态：计数器、类文件映射、指纹缓存、AI 结果缓存。
 * 所有字段均为包级可见 static，与原有访问模式一致。
 */
public class PhaseResults {

    // ── Phase 2-5 计数器 ──
    public static int phase2Match, phase3Match, phase4Match, phase5Match, phase5Diff, phase4Error;
    public static int phase2Error, phase3Error;
    public static int missingOld, missingNew;

    // ── Phase 1 资源文件计数器 ──
    public static int resMatch, resDiff, resMissingOld, resMissingNew;

    // ── class / 资源文件映射 ──
    public static Set<String> ALL_CLASSES = Collections.emptySet();
    public static Map<String, Path> oldMap = Collections.emptyMap(), newMap = Collections.emptyMap();
    public static Map<String, Path> oldResMap = Collections.emptyMap(), newResMap = Collections.emptyMap();

    // ── class 版本分布 ──
    public static final int[] versionCount = new int[66];
    public static int oldCount, newCount;

    // ── 缓存 ──
    public static final Map<String, Fingerprint.FingerprintPair> FINGERPRINTS = new ConcurrentHashMap<>();
    public static final Map<String, String> METHOD_DIFFS = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<String, String> AI_RESULT_CACHE = new ConcurrentHashMap<>();

    // ── 锁 ──
    public static final Object PROCYON_LOCK = new Object();
}
