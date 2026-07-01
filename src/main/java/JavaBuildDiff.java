
/*
 * JavaBuildDiff.java
 *
 * 审计级 Java Class 批量比较器 —— 五阶段"过滤器"流水线（JDK 8 编译，JDK 8+ 运行）
 *
 * 五阶段：Phase 1(资源 SHA-256) → Phase 2(class SHA-256) → Phase 3(ASM 指纹)
 *         → Phase 4(反编译+归一化) → Phase 5(AI 审查)
 *
 * 用法：IDE Run 或 java -jar target/class-comparator-1.0.0.jar
 */

import classcomparator.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class JavaBuildDiff {

    public static void main(String[] args) throws Exception {
        long totalStart = System.currentTimeMillis();

        Config.loadConfig();

        if (Config.AI_ENABLED && Config.AI_API_KEY.isEmpty()) {
            System.err.println("[错误] AI 审查已启用 (ai.enabled=true)，但 config.properties 中未配置 ai.api.key");
            System.err.println("       请在 config.properties 中设置 ai.api.key=sk-xxx 或关闭 AI: ai.enabled=false");
            System.exit(1);
        }

        Path oldDir = Paths.get(Config.OLD_DIR);
        Path newDir = Paths.get(Config.NEW_DIR);
        Path reportDir = Paths.get(Config.REPORT_DIR);

        if (Files.exists(reportDir)) {
            Util.deleteRecursively(reportDir);
        }
        Files.createDirectories(reportDir);
        Files.createDirectories(reportDir.resolve("details"));
        Files.createDirectories(reportDir.resolve("ai"));

        try {
            // ── 扫描两版 class 目录 ──
            System.out.println();
            System.out.println("===== 扫描目录 =====");
            System.out.print("  扫描中...");
            System.out.flush();
            PhaseResults.oldMap = Util.scan(oldDir);
            PhaseResults.newMap = Util.scan(newDir);
            PhaseResults.oldCount = PhaseResults.oldMap.size();
            PhaseResults.newCount = PhaseResults.newMap.size();
            System.out.println("\r  旧版 " + PhaseResults.oldCount + " 个  |  新版 "
                    + PhaseResults.newCount + " 个\n");

            // ── Phase 1: 资源文件 ──
            List<String> resDiffs = runPhase1(reportDir);

            PhaseResults.ALL_CLASSES = new TreeSet<>();
            PhaseResults.ALL_CLASSES.addAll(PhaseResults.oldMap.keySet());
            PhaseResults.ALL_CLASSES.addAll(PhaseResults.newMap.keySet());

            // ── 预分类 ──
            List<String> missingOldList = new ArrayList<>();
            List<String> missingNewList = new ArrayList<>();
            List<String> candidates = new ArrayList<>();

            for (String cls : PhaseResults.ALL_CLASSES) {
                if (!PhaseResults.oldMap.containsKey(cls)) {
                    missingOldList.add(cls);
                    PhaseResults.missingOld++;
                } else if (!PhaseResults.newMap.containsKey(cls)) {
                    missingNewList.add(cls);
                    PhaseResults.missingNew++;
                } else
                    candidates.add(cls);
            }
            System.out.println("  候选 " + candidates.size() + "  |  新增 "
                    + PhaseResults.missingOld + "  |  删除 " + PhaseResults.missingNew + "\n");

            // ── 五阶段流水线 ──
            List<String> phase2Diffs = runPhase2(reportDir, candidates);
            List<String> phase3Diffs = runPhase3(reportDir, phase2Diffs);
            List<String> phase4Diffs = runPhase4(reportDir, phase3Diffs);
            List<String> allDiffs = new ArrayList<>(phase4Diffs);
            allDiffs.addAll(resDiffs);
            runPhase5(reportDir, allDiffs);

            // ── 生成报告 ──
            System.out.println("===== 生成汇总报告 =====");
            System.out.print("  summary.txt ... ");
            System.out.flush();
            Reporter.generateCombinedReport(reportDir, missingOldList, missingNewList);
            System.out.print("report.csv ... ");
            System.out.flush();
            Reporter.generateCsvReport(reportDir);
            System.out.print("report.html ... ");
            System.out.flush();
            Reporter.generateHtmlReport(reportDir);
            System.out.println("完成\n");

            Reporter.printFinalSummary();

            long totalElapsed = (System.currentTimeMillis() - totalStart) / 1000;
            System.out.println("总耗时 " + totalElapsed + "s  报告目录: " + reportDir.toAbsolutePath());
        } catch (Exception t) {
            System.err.println("\n[致命错误] " + t.getClass().getSimpleName() + ": " + t.getMessage());
            System.err.println("清空不完整报告目录: " + reportDir.toAbsolutePath());
            Util.deleteRecursively(reportDir);
            throw t;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Phase 1 — 非 class 资源文件 SHA-256 比对
    // ═══════════════════════════════════════════════════════════

    static Map<String, Path> scanResources(Path root) throws IOException {
        Map<String, Path> map = new LinkedHashMap<>();
        if (!Files.exists(root))
            return map;
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.filter(p -> Files.isRegularFile(p) && !p.toString().endsWith(".class")).forEach(x -> {
                String name = root.relativize(x).toString().replace('\\', '/');
                map.put(name, x);
            });
        }
        return map;
    }

    static void writeResourceDiff(Path file, String name, Path oldFile, Path newFile) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("=== 资源: ").append(name).append(" ===\n\n");
        b.append("=== 旧版 ===\n\n");
        appendResourceContent(b, oldFile);
        b.append("\n\n=== 新版 ===\n\n");
        appendResourceContent(b, newFile);
        Files.write(file, b.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void appendResourceContent(StringBuilder b, Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (Util.isText(bytes))
                b.append(new String(bytes, StandardCharsets.UTF_8));
            else
                b.append("[二进制文件] SHA-256: ").append(Util.sha256(bytes));
        } catch (Exception ex) {
            b.append("[读取失败] ").append(ex.getMessage());
        }
    }

    static List<String> runPhase1(Path reportDir) throws Exception {
        PhaseResults.oldResMap = scanResources(Paths.get(Config.OLD_DIR));
        PhaseResults.newResMap = scanResources(Paths.get(Config.NEW_DIR));

        Set<String> allRes = new TreeSet<>();
        allRes.addAll(PhaseResults.oldResMap.keySet());
        allRes.addAll(PhaseResults.newResMap.keySet());

        List<String> matchList = new ArrayList<>();
        List<String> diffList = new ArrayList<>();          // 归一化后仍不同，送 Phase 5
        List<String> normMatchList = new ArrayList<>();     // 仅空白差异，归一化后一致
        List<String> errorList = new ArrayList<>();
        List<String> missOldList = new ArrayList<>();
        List<String> missNewList = new ArrayList<>();
        Path detailsDir = reportDir.resolve("details");

        for (String name : allRes) {
            Path oldFile = PhaseResults.oldResMap.get(name);
            Path newFile = PhaseResults.newResMap.get(name);

            if (oldFile == null) {
                missOldList.add(name);
                PhaseResults.resMissingOld++;
            } else if (newFile == null) {
                missNewList.add(name);
                PhaseResults.resMissingNew++;
            } else {
                try {
                    byte[] oldBytes = Files.readAllBytes(oldFile);
                    byte[] newBytes = Files.readAllBytes(newFile);

                    if (Util.sha256(oldBytes).equals(Util.sha256(newBytes))) {
                        matchList.add(name);
                        PhaseResults.resMatch++;
                    } else if (Util.isText(oldBytes) && Util.isText(newBytes)) {
                        // 文本资源：归一化空白后再比较，过滤仅换行/空格/缩进差异
                        String oldNorm = Util.normalizeResourceText(new String(oldBytes, StandardCharsets.UTF_8));
                        String newNorm = Util.normalizeResourceText(new String(newBytes, StandardCharsets.UTF_8));
                        if (oldNorm.equals(newNorm)) {
                            normMatchList.add(name);
                            PhaseResults.resMatch++;
                            // 写入详情供 HTML 展示（内容相同，但保留原始差异供人工确认）
                            writeResourceDiff(detailsDir.resolve(Util.toResFileName(name)), name, oldFile, newFile);
                        } else {
                            diffList.add(name);
                            PhaseResults.resDiff++;
                            writeResourceDiff(detailsDir.resolve(Util.toResFileName(name)), name, oldFile, newFile);
                        }
                    } else {
                        // 二进制文件或不可读文本：直接标记差异送审
                        diffList.add(name);
                        PhaseResults.resDiff++;
                        writeResourceDiff(detailsDir.resolve(Util.toResFileName(name)), name, oldFile, newFile);
                    }
                } catch (IOException e) {
                    errorList.add(name);
                    diffList.add(name);
                    PhaseResults.resDiff++;
                    System.err.println("  [Phase1错误] " + name + " -> "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }

        Util.writeLines(reportDir.resolve("01-resource-match.txt"), matchList);
        Util.writeLines(reportDir.resolve("01-resource-diff.txt"), diffList);
        Util.writeLines(reportDir.resolve("01-resource-normalized-match.txt"), normMatchList);
        Util.writeLines(reportDir.resolve("01-resource-error.txt"), errorList);

        int normMatchCount = normMatchList.size();
        int resErrorCount = errorList.size();
        System.out.println("===== Phase 1  资源文件比对  =====");
        System.out.println("  -> 一致 " + PhaseResults.resMatch + "  |  不同 " + PhaseResults.resDiff
                + (normMatchCount > 0 ? "  |  空白差异" + normMatchCount : "")
                + (resErrorCount > 0 ? "  |  错误 " + resErrorCount : "")
                + "  |  新增 " + PhaseResults.resMissingOld
                + "  |  删除 " + PhaseResults.resMissingNew + "\n");
        return diffList;
    }

    // ═══════════════════════════════════════════════════════════
    // Phase 2 — class SHA-256 哈希比对
    // ═══════════════════════════════════════════════════════════

    static List<String> runPhase2(Path reportDir, List<String> candidates) throws Exception {
        System.out.println("===== Phase 2  SHA-256 哈希比对  (" + candidates.size() + " 个) =====");
        long startTime = System.currentTimeMillis();

        List<String> sameList = new ArrayList<>();
        List<String> diffList = new ArrayList<>();
        List<String> errorList = new ArrayList<>();
        int total = candidates.size();

        for (int i = 0; i < total; i++) {
            String cls = candidates.get(i);
            try {
                byte[] oldBytes = Files.readAllBytes(PhaseResults.oldMap.get(cls));
                byte[] newBytes = Files.readAllBytes(PhaseResults.newMap.get(cls));

                int oldVersion = Util.getClassMajorVersion(oldBytes);
                int newVersion = Util.getClassMajorVersion(newBytes);
                if (oldVersion > 0 && oldVersion < PhaseResults.versionCount.length)
                    PhaseResults.versionCount[oldVersion]++;
                if (newVersion > 0 && newVersion < PhaseResults.versionCount.length)
                    PhaseResults.versionCount[newVersion]++;

                if (Util.sha256(oldBytes).equals(Util.sha256(newBytes)))
                    sameList.add(cls);
                else
                    diffList.add(cls);
            } catch (Exception e) {
                errorList.add(cls);
                System.err.println("  [Phase2错误] " + cls + " -> "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            if ((i + 1) % Config.PHASE2_PROGRESS_INTERVAL == 0) {
                long elapsed = System.currentTimeMillis() - startTime;
                long eta = elapsed * total / (i + 1) - elapsed;
                System.out.printf("  [%d/%d] %d%%  预计剩余 %ds     \r",
                        i + 1, total, (i + 1) * 100 / total, eta / 1000);
                System.out.flush();
            }
        }

        PhaseResults.phase2Match = sameList.size();
        PhaseResults.phase2Error = errorList.size();
        Util.writeLines(reportDir.resolve("02-sha256-match.txt"), sameList);
        Util.writeLines(reportDir.resolve("02-sha256-diff.txt"), diffList);
        Util.writeLines(reportDir.resolve("02-sha256-error.txt"), errorList);

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println("  -> 一致 " + PhaseResults.phase2Match + "  |  差异 " + diffList.size()
                + (errorList.isEmpty() ? "" : "  |  错误 " + errorList.size())
                + "  |  " + elapsed + "s");
        Util.reportVersionSummary(PhaseResults.versionCount);
        System.out.println();
        return diffList;
    }

    // ═══════════════════════════════════════════════════════════
    // Phase 3 — ASM 字节码结构指纹比对
    // ═══════════════════════════════════════════════════════════

    static List<String> runPhase3(Path reportDir, List<String> candidates) throws Exception {
        System.out.println("===== Phase 3  ASM 结构指纹比对  (" + candidates.size() + " 个) =====");
        long startTime = System.currentTimeMillis();

        List<String> sameList = new ArrayList<>();
        List<String> diffList = new ArrayList<>();
        List<String> errorList = new ArrayList<>();
        int total = candidates.size();

        for (int i = 0; i < total; i++) {
            String className = candidates.get(i);
            try {
                byte[] oldBytes = Files.readAllBytes(PhaseResults.oldMap.get(className));
                byte[] newBytes = Files.readAllBytes(PhaseResults.newMap.get(className));

                Fingerprint.ClassFingerprint oldFp = Fingerprint.fingerprintClass(oldBytes);
                Fingerprint.ClassFingerprint newFp = Fingerprint.fingerprintClass(newBytes);

                if (oldFp.logicHash.equals(newFp.logicHash)) {
                    sameList.add(className);
                } else {
                    Fingerprint.FingerprintPair pair = new Fingerprint.FingerprintPair(oldFp, newFp);
                    PhaseResults.FINGERPRINTS.put(className, pair);
                    String methodDiff = Fingerprint.diffMethodNames(pair);
                    if (!methodDiff.isEmpty())
                        PhaseResults.METHOD_DIFFS.put(className, methodDiff);
                    diffList.add(className);
                }
            } catch (Exception e) {
                errorList.add(className);
                diffList.add(className);
                System.err.println("  [Phase3错误] " + className + " -> "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            if ((i + 1) % Config.PHASE3_PROGRESS_INTERVAL == 0) {
                long elapsed = System.currentTimeMillis() - startTime;
                long eta = elapsed * total / (i + 1) - elapsed;
                System.out.printf("  [%d/%d] %d%%  预计剩余 %ds     \r",
                        i + 1, total, (i + 1) * 100 / total, eta / 1000);
                System.out.flush();
            }
        }

        PhaseResults.phase3Match = sameList.size();
        PhaseResults.phase3Error = errorList.size();
        Util.writeLines(reportDir.resolve("03-asm-match.txt"), sameList);
        Util.writeLines(reportDir.resolve("03-asm-diff.txt"), diffList);
        Util.writeLines(reportDir.resolve("03-asm-error.txt"), errorList);

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println("  -> 一致 " + PhaseResults.phase3Match + "  |  差异 "
                + (diffList.size() - errorList.size())
                + (errorList.isEmpty() ? "" : "  |  错误 " + errorList.size())
                + "  |  " + elapsed + "s\n");
        return diffList;
    }

    // ═══════════════════════════════════════════════════════════
    // Phase 4 — 反编译 + 归一化文本比对
    // ═══════════════════════════════════════════════════════════

    static List<String> runPhase4(Path reportDir, List<String> candidates) throws Exception {
        if (candidates.isEmpty()) {
            System.out.println("===== Phase 4  反编译比对  (无候选) =====\n");
            return candidates;
        }

        int threadCount = Config.THREADS > 0 ? Config.THREADS
                : Math.min(Runtime.getRuntime().availableProcessors() * 2, Config.MAX_DECOMPILE_THREADS);

        System.out.println("===== Phase 4  反编译比对  (" + candidates.size() + " 个, "
                + threadCount + " 线程, " + Config.DECOMPILER + "引擎) =====");
        long startTime = System.currentTimeMillis();

        Path detailsDir = reportDir.resolve("details");
        int total = candidates.size();

        Map<String, String> results = new ConcurrentHashMap<>();
        AtomicInteger completed = new AtomicInteger(0);
        ConcurrentLinkedQueue<String> failedClasses = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> errorClasses = new ConcurrentLinkedQueue<>();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        ScheduledExecutorService progress = Executors.newSingleThreadScheduledExecutor();
        progress.scheduleAtFixedRate(() -> {
            int done = completed.get();
            if (done > 0 && done < total) {
                long elapsed = System.currentTimeMillis() - startTime;
                long eta = elapsed * total / done - elapsed;
                System.out.printf("  [%d/%d] %d%%  预计剩余 %ds     \r",
                        done, total, done * 100 / total, eta / 1000);
                System.out.flush();
            }
        }, 1, 1, TimeUnit.SECONDS);

        for (String className : candidates) {
            executor.submit(() -> {
                try {
                    Path detailFile = detailsDir.resolve(Util.toFileName(className));

                    String oldSource = Decompiler.decompileClass(PhaseResults.oldMap.get(className));
                    String newSource = Decompiler.decompileClass(PhaseResults.newMap.get(className));

                    if (oldSource.isEmpty() || newSource.isEmpty()) {
                        failedClasses.add(className + " (反编译为空)");
                        try {
                            Decompiler.writeDetailFromCache(detailFile, className);
                        } catch (IOException ioe) {
                            System.err.println("  [Phase4] 写入ASM回退详情失败: " + className); }
                        results.put(className, Config.CAT_DIFFERENT);
                    } else if (Decompiler.decompiledSourceEquals(oldSource, newSource)) {
                        results.put(className, Config.CAT_SAME_DECOMPILED);
                    } else {
                        Decompiler.writeDecompiledDiff(detailFile, className, oldSource, newSource);
                        results.put(className, Config.CAT_DIFFERENT);
                    }
                } catch (IOException e) {
                    errorClasses.add(className + " (IO: " + e.getClass().getSimpleName() + ")");
                    results.put(className, Config.CAT_ERROR);
                } catch (Exception e) {
                    failedClasses.add(className + " (" + e.getClass().getSimpleName() + ")");
                    try {
                        Decompiler.writeDetailFromCache(
                                detailsDir.resolve(Util.toFileName(className)), className);
                    } catch (IOException ioe) {
                        System.err.println("  [Phase4] 写入ASM回退详情失败: " + className); }
                    results.put(className, Config.CAT_DIFFERENT);
                } finally {
                    completed.incrementAndGet();
                }
            });
        }

        executor.shutdown();
        boolean finished = executor.awaitTermination(Config.DECOMPILE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        progress.shutdownNow();

        if (!finished) {
            executor.shutdownNow();
            System.out.print("                                          \r");
            System.out.println("  [警告] 反编译超时(" + Config.DECOMPILE_TIMEOUT_MINUTES + "分钟)！");
        }

        if (!failedClasses.isEmpty()) {
            System.out.print("                                          \r");
            List<String> sorted = new ArrayList<>(failedClasses);
            Collections.sort(sorted);
            System.out.println("  [反编译失败] " + sorted.size() + " 个类 (已回退到ASM指纹详情):");
            for (String f : sorted)
                System.out.println("    - " + f);
        }

        if (!errorClasses.isEmpty()) {
            List<String> sorted = new ArrayList<>(errorClasses);
            Collections.sort(sorted);
            System.out.println("  [工具异常] " + sorted.size() + " 个类:");
            for (String f : sorted)
                System.out.println("    - " + f);
        }

        List<String> sameList = new ArrayList<>();
        List<String> diffList = new ArrayList<>();
        List<String> errorList = new ArrayList<>();
        for (String className : candidates) {
            String r = results.get(className);
            if (Config.CAT_SAME_DECOMPILED.equals(r)) {
                sameList.add(className);
            } else if (Config.CAT_ERROR.equals(r)) {
                diffList.add(className);
                errorList.add(className);
            } else {
                diffList.add(className);
            }
        }

        PhaseResults.phase4Match = sameList.size();
        PhaseResults.phase4Error = errorList.size();
        Util.writeLines(reportDir.resolve("04-decompiled-match.txt"), sameList);
        Util.writeLines(reportDir.resolve("04-decompiled-diff.txt"), diffList);
        Util.writeLines(reportDir.resolve("04-decompiled-error.txt"), errorList);

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        System.out.print("                                          \r");
        System.out.println("  -> 一致 " + PhaseResults.phase4Match
                + "  |  差异 " + (diffList.size() - errorList.size())
                + (errorList.size() > 0 ? "  |  工具异常 " + errorList.size() : "")
                + "  |  " + elapsed + "s");
        System.out.println();
        return diffList;
    }

    // ═══════════════════════════════════════════════════════════
    // Phase 5 — AI 审查（DeepSeek）
    // ═══════════════════════════════════════════════════════════

    static String getDecompiledSource(Path reportDir, String name, boolean old) {
        boolean isResource = name.contains("/");
        String fileName = isResource ? Util.toResFileName(name) : Util.toFileName(name);
        Path detailFile = reportDir.resolve("details").resolve(fileName);
        if (Files.exists(detailFile)) {
            try {
                String text = new String(Files.readAllBytes(detailFile), StandardCharsets.UTF_8);
                if (isResource) {
                    String rMarker = old ? "=== 旧版 ===" : "=== 新版 ===";
                    String rEnd = old ? "=== 新版 ===" : null;
                    int rs = text.indexOf(rMarker);
                    if (rs < 0) {
                        System.err.println("  [DEBUG] 资源 " + name + " detailFile 中找不到 " + rMarker);
                        return null;
                    }
                    rs += rMarker.length();
                    int re = rEnd != null ? text.indexOf(rEnd, rs) : -1;
                    String result = (re >= 0 ? text.substring(rs, re) : text.substring(rs)).trim();
                    if (result.isEmpty()) {
                        System.err.println("  [DEBUG] 资源 " + name + " 提取内容为空 old=" + old
                                + " (detailFile=" + detailFile.getFileName() + ")");
                    }
                    return result;
                }
                String cMarker = old ? "=== 旧版反编译 ===" : "=== 新版反编译 ===";
                String cEnd = old ? "=== 新版反编译 ===" : "=== 归一化行数对比 ===";
                int cs = text.indexOf(cMarker);
                if (cs < 0)
                    return null;
                cs += cMarker.length();
                int ce = text.indexOf(cEnd, cs);
                return (ce >= 0 ? text.substring(cs, ce) : text.substring(cs)).trim();
            } catch (IOException e) {
                return null;
            }
        }
        if (isResource)
            return null;
        try {
            String src = Decompiler.decompileClass(
                    old ? PhaseResults.oldMap.get(name) : PhaseResults.newMap.get(name));
            return (src != null && !src.isEmpty()) ? src : null;
        } catch (Exception e) {
            return null;
        }
    }

    static List<String> runPhase5(Path reportDir, List<String> candidates) {
        if (candidates.isEmpty()) {
            System.out.println("===== Phase 5  AI 审查  (无候选) =====\n");
            return candidates;
        }
        if (!Config.AI_ENABLED || Config.AI_API_KEY.isEmpty()) {
            System.out.println("===== Phase 5  AI 审查  (已禁用) =====\n");
            try {
                Util.writeLines(reportDir.resolve("05-ai-diff.txt"), candidates);
            } catch (IOException e) {
                System.err.println("  [Phase5] 写入 05-ai-diff.txt 失败: " + e.getMessage()); }
            PhaseResults.phase5Diff = candidates.size();
            return candidates;
        }

        System.out.println("===== Phase 5  AI 审查  (" + candidates.size() + " 个候选, "
                + Config.AI_CONCURRENCY + " 并发) =====");
        long startTime = System.currentTimeMillis();

        Path verifiedFile = reportDir.resolve("05-ai-match.txt");
        Path diffFile = reportDir.resolve("05-ai-diff.txt");

        // 续跑恢复
        Set<String> alreadyDone = new TreeSet<>();
        try {
            if (Files.exists(verifiedFile))
                for (String line : Files.readAllLines(verifiedFile, StandardCharsets.UTF_8))
                    if (!line.trim().isEmpty())
                        alreadyDone.add(line.trim());
        } catch (IOException e) {
            /* ignore */ }

        // 抽检采样
        List<String> reviewList;
        if (Config.AI_SAMPLE_RATE < 1.0) {
            List<String> shuffled = new ArrayList<>(candidates);
            Collections.shuffle(shuffled, new Random(42));
            int sampleCount = Math.max(1, (int) Math.ceil(candidates.size() * Config.AI_SAMPLE_RATE));
            reviewList = shuffled.subList(0, Math.min(sampleCount, shuffled.size()));
        } else {
            reviewList = candidates;
        }
        System.out.println("  审查 " + reviewList.size() + "/" + candidates.size()
                + (Config.AI_SAMPLE_RATE < 1.0
                        ? " (" + String.format("%.2f", Config.AI_SAMPLE_RATE * 100) + "% 抽检)"
                        : " (全量)")
                + (alreadyDone.isEmpty() ? "" : "  |  续跑跳过 " + alreadyDone.size()));

        List<String> pendingList = new ArrayList<>();
        for (String className : reviewList)
            if (!alreadyDone.contains(className))
                pendingList.add(className);

        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger verifiedCount = new AtomicInteger(0);
        AtomicInteger differentCount = new AtomicInteger(0);
        int totalPending = pendingList.size();
        int counterWidth = String.valueOf(totalPending).length();
        String rowFmt = "  [%0" + counterWidth + "d/%d] %-50s %s\n";

        if (totalPending > 0) {
            Semaphore concurrencySemaphore = new Semaphore(Config.AI_CONCURRENCY);
            ExecutorService aiExecutorService = Executors.newFixedThreadPool(Config.AI_CONCURRENCY);
            List<CompletableFuture<?>> allFutures = new ArrayList<>(totalPending);

            try (BufferedWriter verifiedWriter = Files.newBufferedWriter(verifiedFile,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

                for (String className : pendingList) {
                    try {
                        concurrencySemaphore.acquire();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    String qualifiedName = className;

                    try {
                        CompletableFuture<?> reviewFuture = CompletableFuture
                                .supplyAsync(() -> {
                                    String oldSource = getDecompiledSource(reportDir, qualifiedName, true);
                                    String newSource = getDecompiledSource(reportDir, qualifiedName, false);
                                    if (oldSource == null || newSource == null) {
                                        if (qualifiedName.contains("/"))
                                            System.err.println("  [DEBUG] 资源 " + qualifiedName
                                                    + " 源码不可用: old=" + (oldSource != null)
                                                    + " new=" + (newSource != null));
                                        return null;
                                    }
                                    return new String[] { oldSource, newSource };
                                }, aiExecutorService)
                                .thenCompose(sources -> {
                                    if (sources == null)
                                        return CompletableFuture.completedFuture("__NO_SOURCE__");
                                    boolean isResource = qualifiedName.contains("/");
                                    String methodInfo = isResource ? null
                                            : PhaseResults.METHOD_DIFFS.get(qualifiedName);
                                    return AiReviewer.aiReviewAsync(
                                            sources[0], sources[1], isResource, methodInfo);
                                })
                                .whenComplete((response, error) -> {
                                    concurrencySemaphore.release();
                                    int done = completedCount.incrementAndGet();

                                    String shortName;
                                    if (qualifiedName.contains("/")) {
                                        int slash = qualifiedName.lastIndexOf('/');
                                        shortName = slash >= 0
                                                ? qualifiedName.substring(slash + 1) : qualifiedName;
                                    } else {
                                        int dot = qualifiedName.lastIndexOf('.');
                                        shortName = (dot >= 0 ? qualifiedName.substring(dot + 1)
                                                : qualifiedName) + ".java";
                                    }

                                    if (error != null) {
                                        String errorDetail = (error.getCause() != null
                                                ? error.getCause().getMessage()
                                                : error.getMessage());
                                        System.out.printf(rowFmt,
                                                done, totalPending, shortName, "异常: " + errorDetail);
                                        return;
                                    }
                                    if ("__NO_SOURCE__".equals(response)) {
                                        System.out.printf(rowFmt,
                                                done, totalPending, shortName, "源码不可用（跳过）");
                                        return;
                                    }
                                    if (response == null) {
                                        System.out.printf(rowFmt,
                                                done, totalPending, shortName, "API调用失败");
                                        return;
                                    }

                                    String verdict = AiReviewer.parseAiVerdict(response);
                                    if ("YES".equals(verdict)) {
                                        int yesCount = verifiedCount.incrementAndGet();
                                        synchronized (verifiedWriter) {
                                            try {
                                                verifiedWriter.write(qualifiedName);
                                                verifiedWriter.newLine();
                                                if (yesCount % 50 == 0)
                                                    verifiedWriter.flush();
                                            } catch (IOException ignored) {
                                                System.err.println(
                                                        "  [Phase5] 写入 verified 文件失败，下一轮可重试"); }
                                        }
                                    } else if ("NO".equals(verdict)) {
                                        differentCount.incrementAndGet();
                                    }
                                    System.out.printf(rowFmt,
                                            done, totalPending, shortName,
                                            "YES".equals(verdict) ? "一致"
                                                    : "NO".equals(verdict) ? "不同" : "未能解析YES/NO");

                                    // AI 日志写入：资源用 toResFileName（保留扩展名），class 用 toFileName
                                    String safeName = qualifiedName.contains("/")
                                            ? Util.toResFileName(qualifiedName)
                                            : Util.toFileName(qualifiedName);
                                    Path aiFile = reportDir.resolve("ai").resolve(safeName + ".ai.txt");
                                    Path aiTmp = reportDir.resolve("ai")
                                            .resolve(safeName + ".ai.txt.tmp");
                                    try {
                                        Files.createDirectories(aiFile.getParent()); // 确保父目录存在
                                        Files.write(aiTmp, response.getBytes(StandardCharsets.UTF_8));
                                        try {
                                            Files.move(aiTmp, aiFile, StandardCopyOption.ATOMIC_MOVE,
                                                    StandardCopyOption.REPLACE_EXISTING);
                                        } catch (AtomicMoveNotSupportedException e) {
                                            // Windows 某些配置下 ATOMIC_MOVE 不可用，降级为普通 move
                                            Files.move(aiTmp, aiFile, StandardCopyOption.REPLACE_EXISTING);
                                        }
                                    } catch (IOException e) {
                                        System.err.println(
                                                "  [Phase5] AI日志写入失败: " + qualifiedName
                                                        + " -> " + e.getClass().getSimpleName()
                                                        + ": " + e.getMessage()); }
                                });
                        allFutures.add(reviewFuture);
                    } catch (RejectedExecutionException rejectedEx) {
                        concurrencySemaphore.release();
                        System.err.println("  [Phase5] 任务提交失败(线程池拒绝): " + qualifiedName);
                    }
                }

                try {
                    CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0]))
                            .get(Config.AI_TIMEOUT_MINUTES, TimeUnit.MINUTES);
                } catch (TimeoutException timeoutEx) {
                    int cancelled = 0;
                    for (CompletableFuture<?> f : allFutures)
                        if (!f.isDone() && f.cancel(true))
                            cancelled++;
                    System.out.println("  [警告] AI 审查超时(" + Config.AI_TIMEOUT_MINUTES
                            + "分钟)！已取消 " + cancelled + " 个未完成请求。");
                } catch (ExecutionException executionEx) {
                    Throwable rootCause = executionEx.getCause();
                    System.out.println("  [警告] AI 审查执行异常: "
                            + (rootCause != null ? rootCause.getMessage() : executionEx.getMessage()));
                } catch (InterruptedException interruptedEx) {
                    Thread.currentThread().interrupt();
                    System.out.println("  [警告] AI 审查被中断。");
                }
            } catch (IOException ioException) {
                System.out.println("  [错误] 写入 AI 结果文件失败: " + ioException.getMessage());
            } finally {
                aiExecutorService.shutdown();
                try {
                    if (!aiExecutorService.awaitTermination(60, TimeUnit.SECONDS)) {
                        aiExecutorService.shutdownNow();
                        System.out.println("  [警告] AI 线程池未能在 60s 内完全终止，已强制关闭。");
                    }
                } catch (InterruptedException shutdownInterrupted) {
                    aiExecutorService.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }

        // 统计
        Set<String> verifiedSet = new TreeSet<>();
        try {
            if (Files.exists(verifiedFile))
                for (String line : Files.readAllLines(verifiedFile, StandardCharsets.UTF_8))
                    if (!line.trim().isEmpty())
                        verifiedSet.add(line.trim());
        } catch (IOException e) {
            /* ignore */ }
        PhaseResults.phase5Match = verifiedSet.size();

        List<String> finalDiffs = new ArrayList<>();
        for (String className : candidates)
            if (!verifiedSet.contains(className))
                finalDiffs.add(className);
        Collections.sort(finalDiffs);
        PhaseResults.phase5Diff = finalDiffs.size();
        try {
            Util.writeLines(diffFile, finalDiffs);
        } catch (IOException e) {
            System.err.println("  [Phase5] 写入 05-ai-diff.txt 失败: " + e.getMessage()); }

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println("  -> 本次: 一致 " + verifiedCount + "  不同 " + differentCount
                + "  |  累计 AI一致 " + PhaseResults.phase5Match
                + "  仍需人工 " + PhaseResults.phase5Diff
                + "  |  " + elapsed + "s\n");
        return finalDiffs;
    }
}
