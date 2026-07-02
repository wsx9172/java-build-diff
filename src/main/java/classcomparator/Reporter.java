package classcomparator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 报告生成：summary.txt / report.csv / report.html + JS 详情文件。
 * 所有方法 public static，通过 PhaseResults / Config / Util 读取共享状态。
 */
public class Reporter {

    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));

    /** 从各阶段独立文件重建 类名→分类 映射 */
    public static Map<String, String> buildCategoryMap(Path reportDir) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();

        for (String cls : PhaseResults.ALL_CLASSES) {
            if (!PhaseResults.oldMap.containsKey(cls))
                map.put(cls, Config.CAT_MISSING_OLD);
            else if (!PhaseResults.newMap.containsKey(cls))
                map.put(cls, Config.CAT_MISSING_NEW);
        }

        // 信任度从低到高覆盖：后面的会覆盖前面的，AI 结果必须最后
        addCat(map, reportDir, "02-sha256-match.txt", Config.CAT_SAME_FILE);
        addCat(map, reportDir, "03-asm-match.txt", Config.CAT_SAME_ASM);
        addCat(map, reportDir, "04-decompiled-match.txt", Config.CAT_SAME_DECOMPILED);
        addCat(map, reportDir, "02-sha256-error.txt", Config.CAT_ERROR);
        addCat(map, reportDir, "03-asm-error.txt", Config.CAT_ERROR);
        addCat(map, reportDir, "04-decompiled-error.txt", Config.CAT_ERROR);

        // 资源文件结果
        addCat(map, reportDir, "01-resource-match.txt", Config.CAT_SAME_FILE);
        addCat(map, reportDir, "01-resource-normalized-match.txt", Config.CAT_SAME_FILE);
        addCat(map, reportDir, "01-resource-diff.txt", Config.CAT_DIFFERENT);

        // AI 结果最后应用——覆盖前面的分类（"需人工"→"AI验证一致" 或保持"需人工"）
        addCat(map, reportDir, "05-ai-match.txt", Config.CAT_AI_VERIFIED);
        addCat(map, reportDir, "05-ai-diff.txt", Config.CAT_DIFFERENT);

        Set<String> allRes = new TreeSet<>();
        if (PhaseResults.oldResMap != null)
            allRes.addAll(PhaseResults.oldResMap.keySet());
        if (PhaseResults.newResMap != null)
            allRes.addAll(PhaseResults.newResMap.keySet());
        for (String name : allRes) {
            if (PhaseResults.oldResMap != null && !PhaseResults.oldResMap.containsKey(name))
                map.put(name, Config.CAT_MISSING_OLD);
            else if (PhaseResults.newResMap != null && !PhaseResults.newResMap.containsKey(name))
                map.put(name, Config.CAT_MISSING_NEW);
        }
        return map;
    }

    static void addCat(Map<String, String> map, Path dir, String file, String cat) throws IOException {
        Path p = dir.resolve(file);
        if (!Files.exists(p))
            return;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8))
            if (!line.trim().isEmpty())
                map.put(line.trim(), cat);
    }

    /** 生成 summary.txt */
    public static void generateCombinedReport(Path reportDir,
            List<String> missingOldList, List<String> missingNewList) throws IOException {
        List<String> p2Match = Util.readLinesIfExists(reportDir.resolve("02-sha256-match.txt"));
        List<String> p2Error = Util.readLinesIfExists(reportDir.resolve("02-sha256-error.txt"));
        List<String> p3Match = Util.readLinesIfExists(reportDir.resolve("03-asm-match.txt"));
        List<String> p3Error = Util.readLinesIfExists(reportDir.resolve("03-asm-error.txt"));
        List<String> p4Match = Util.readLinesIfExists(reportDir.resolve("04-decompiled-match.txt"));
        List<String> p4Error = Util.readLinesIfExists(reportDir.resolve("04-decompiled-error.txt"));
        List<String> p5Match = Util.readLinesIfExists(reportDir.resolve("05-ai-match.txt"));
        List<String> p5Diff = Util.readLinesIfExists(reportDir.resolve("05-ai-diff.txt"));

        int logicalSame = p2Match.size() + p3Match.size() + p4Match.size() + p5Match.size();
        int total = PhaseResults.ALL_CLASSES.size();

        StringBuilder s = new StringBuilder();
        s.append("========== 产物比较汇总报告 ==========\n\n");
        s.append("总计: ").append(total).append(" 个文件\n\n");

        s.append("Phase 1  资源文件 SHA-256:  ").append(Util.rpad(PhaseResults.resMatch, 6))
                .append("一致  |  ").append(PhaseResults.resDiff).append(" 差异")
                .append("  |  +").append(PhaseResults.resMissingOld)
                .append("  |  -").append(PhaseResults.resMissingNew).append("\n");
        s.append("Phase 2  SHA-256 一致:      ").append(Util.rpad(p2Match.size() + PhaseResults.resMatch, 6))
                .append("（字节完全相同，含资源文件）\n");
        if (!p2Error.isEmpty())
            s.append("  Phase 2 读取错误:         ").append(Util.rpad(p2Error.size(), 6))
                    .append("（class 文件损坏/权限不足）\n");
        s.append("Phase 3  ASM 指纹一致:      ").append(Util.rpad(p3Match.size(), 6))
                .append("（字节码结构相同）\n");
        if (!p3Error.isEmpty())
            s.append("  Phase 3 解析错误:         ").append(Util.rpad(p3Error.size(), 6))
                    .append("（ASM 解析失败，已转入下阶段）\n");
        s.append("Phase 4  反编译文本一致:    ").append(Util.rpad(p4Match.size(), 6))
                .append("（归一化源码逐行匹配，JDK 版本差异）\n");
        s.append("Phase 5  AI 验证一致:        ").append(Util.rpad(p5Match.size(), 6))
                .append("（DeepSeek 自动判定逻辑等价，含资源文件）\n");
        s.append("          -> 逻辑一致合计:  ").append(logicalSame)
                .append(" (").append(Util.pct(logicalSame, total)).append(")\n\n");
        s.append("仍需人工审查:              ").append(Util.rpad(p5Diff.size(), 6))
                .append(" (").append(Util.pct(p5Diff.size(), total)).append(")\n");
        if (!p4Error.isEmpty() || !p2Error.isEmpty())
            s.append("  其中工具异常(需重试):    ")
                    .append(Util.rpad(p4Error.size() + p2Error.size(), 6))
                    .append("（IO错误/磁盘满/文件损坏，非逻辑差异）\n");
        s.append("新增(仅新版):              ").append(missingOldList.size() + PhaseResults.resMissingOld)
                .append("\n");
        s.append("删除(仅旧版):              ").append(missingNewList.size() + PhaseResults.resMissingNew)
                .append("\n\n");

        s.append("--- 来源明细 ---\n");
        s.append("Class 候选:                ").append(total).append("\n");
        s.append("资源文件:                  ")
                .append(PhaseResults.resMatch + PhaseResults.resDiff
                        + PhaseResults.resMissingOld + PhaseResults.resMissingNew)
                .append("\n");

        Files.write(reportDir.resolve("summary.txt"),
                s.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 将内置分类标签映射为 HTML 下拉框中使用的显示名称 */
    private static String toDisplayCategory(String cat) {
        switch (cat) {
            case Config.CAT_SAME_FILE:       return "SHA-256一致";
            case Config.CAT_SAME_ASM:        return "ASM指纹一致";
            case Config.CAT_SAME_DECOMPILED: return "反编译一致";
            case Config.CAT_AI_VERIFIED:     return "AI验证";
            case Config.CAT_DIFFERENT:       return "需人工";
            case Config.CAT_ERROR:           return "工具异常";
            case Config.CAT_MISSING_OLD:     return "新增";
            case Config.CAT_MISSING_NEW:     return "删除";
            default:                         return cat;
        }
    }

    /** 生成 report.csv */
    public static void generateCsvReport(Path reportDir) throws IOException {
        Map<String, String> catMap = buildCategoryMap(reportDir);

        StringBuilder csv = new StringBuilder();
        csv.append('﻿'); // UTF-8 BOM
        csv.append("文件名,目录,比对结果,备注\n");

        for (Map.Entry<String, String> entry : catMap.entrySet()) {
            String fullName = entry.getKey(), cat = entry.getValue();
            boolean isResource = fullName.contains("/");
            String shortName, directory, notes;
            if (isResource) {
                int idx = fullName.lastIndexOf('/');
                shortName = idx >= 0 ? fullName.substring(idx + 1) : fullName;
                directory = idx > 0 ? fullName.substring(0, idx) : "(根目录)";
                notes = Config.CAT_DIFFERENT.equals(cat)
                        ? "详见 details/" + Util.toResFileName(fullName) : "";
            } else {
                int idx = fullName.lastIndexOf('.');
                shortName = (idx >= 0 ? fullName.substring(idx + 1) : fullName) + ".java";
                directory = idx > 0 ? fullName.substring(0, idx).replace('.', '/') : "(根目录)";
                notes = Config.CAT_DIFFERENT.equals(cat)
                        ? "详见 details/" + Util.toFileName(fullName) : "";
            }
            csv.append(Util.escapeCsv(shortName)).append(',')
                    .append(Util.escapeCsv(directory)).append(',')
                    .append(Util.escapeCsv(toDisplayCategory(cat))).append(',')
                    .append(Util.escapeCsv(notes)).append('\n');
        }

        Files.write(reportDir.resolve("report.csv"),
                csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 生成 report.html + details/*.js 数据文件 */
    public static void generateHtmlReport(Path reportDir) throws IOException {
        Map<String, String> catMap = buildCategoryMap(reportDir);

        int sameFile = Util.countCat(catMap, Config.CAT_SAME_FILE);
        int sameAsm = Util.countCat(catMap, Config.CAT_SAME_ASM);
        int sameDec = Util.countCat(catMap, Config.CAT_SAME_DECOMPILED);
        int aiOk = Util.countCat(catMap, Config.CAT_AI_VERIFIED);
        int diff = Util.countCat(catMap, Config.CAT_DIFFERENT);
        int error = Util.countCat(catMap, Config.CAT_ERROR);
        int missOld = Util.countCat(catMap, Config.CAT_MISSING_OLD);
        int missNew = Util.countCat(catMap, Config.CAT_MISSING_NEW);
        int total = catMap.size();
        int logicalSame = sameFile + sameAsm + sameDec + aiOk;

        // ── 写 JS 详情数据文件 ──
        for (Map.Entry<String, String> entry : catMap.entrySet()) {
            String name = entry.getKey();
            String cat = entry.getValue();
            boolean isResource = name.contains("/");
            if (!isResource && (Config.CAT_SAME_FILE.equals(cat) || Config.CAT_SAME_ASM.equals(cat)))
                continue;
            if (isResource && Config.CAT_SAME_FILE.equals(cat))
                continue;
            String fileName = isResource ? Util.toResFileName(name) : Util.toFileName(name);
            Path detailFile = reportDir.resolve("details").resolve(fileName);
            Path aiFile = reportDir.resolve("ai").resolve(fileName + ".ai.txt");
            Path jsFile = reportDir.resolve("details").resolve(fileName + ".js");
            StringBuilder js = new StringBuilder();
            js.append("DETAIL_DATA[\"").append(Util.escapeJson(fileName)).append("\"]={");
            try {
                if (isResource) {
                    if (Files.exists(detailFile)) {
                        String text = new String(Files.readAllBytes(detailFile), StandardCharsets.UTF_8);
                        String oldSrc = "", newSrc = "";
                        int oi = text.indexOf("=== 旧版 ===");
                        int ni = text.indexOf("=== 新版 ===");
                        if (oi >= 0 && ni > oi) {
                            oldSrc = text.substring(oi + "=== 旧版 ===".length(), ni).trim();
                            newSrc = text.substring(ni + "=== 新版 ===".length()).trim();
                        }
                        // 读取 AI 审查结果（与 class 文件逻辑一致）
                        String aiText = "";
                        if (Files.exists(aiFile)) {
                            aiText = new String(Files.readAllBytes(aiFile), StandardCharsets.UTF_8).trim();
                            String verdict = AiReviewer.parseAiVerdict(aiText);
                            if ("YES".equals(verdict)) {
                                aiText = "";
                            } else if ("NO".equals(verdict)) {
                                int nl = aiText.indexOf('\n');
                                if (nl >= 0)
                                    aiText = aiText.substring(nl + 1).trim();
                                else
                                    aiText = "";
                            }
                        }
                        js.append("\"ai\":\"").append(Util.escapeJson(aiText)).append("\",");
                        js.append("\"old\":\"").append(Util.escapeJson(oldSrc)).append("\",");
                        js.append("\"new\":\"").append(Util.escapeJson(newSrc)).append("\"");
                    } else {
                        js.append("\"match\":\"").append(Util.escapeJson(cat)).append("\"");
                    }
                } else if (Config.CAT_MISSING_OLD.equals(cat)) {
                    Path classPath = PhaseResults.newMap.get(name);
                    String src = classPath != null
                            ? Decompiler.decompileClass(classPath) : "[ 反编译失败 ]";
                    js.append("\"new\":\"").append(Util.escapeJson(src)).append("\",");
                    js.append("\"old\":\"").append(Util.escapeJson("[ 新增 ]")).append("\",");
                    js.append("\"missing\":\"old\"");
                } else if (Config.CAT_MISSING_NEW.equals(cat)) {
                    Path classPath = PhaseResults.oldMap.get(name);
                    String src = classPath != null
                            ? Decompiler.decompileClass(classPath) : "[ 反编译失败 ]";
                    js.append("\"old\":\"").append(Util.escapeJson(src)).append("\",");
                    js.append("\"new\":\"").append(Util.escapeJson("[ 删除 ]")).append("\",");
                    js.append("\"missing\":\"new\"");
                } else if (Files.exists(detailFile)) {
                    String text = new String(Files.readAllBytes(detailFile), StandardCharsets.UTF_8);
                    String oldSrc = "", newSrc = "";
                    int oi = text.indexOf("=== 旧版反编译 ===");
                    int ni = text.indexOf("=== 新版反编译 ===");
                    int ei = text.indexOf("=== 归一化行数对比 ===");
                    if (oi >= 0 && ni > oi) {
                        oldSrc = text.substring(oi + "=== 旧版反编译 ===".length(), ni).trim();
                        newSrc = ei > ni
                                ? text.substring(ni + "=== 新版反编译 ===".length(), ei).trim()
                                : text.substring(ni + "=== 新版反编译 ===".length()).trim();
                    }
                    String aiText = "";
                    if (Files.exists(aiFile)) {
                        aiText = new String(Files.readAllBytes(aiFile), StandardCharsets.UTF_8).trim();
                        String verdict = AiReviewer.parseAiVerdict(aiText);
                        if ("YES".equals(verdict)) {
                            aiText = "";
                        } else if ("NO".equals(verdict)) {
                            int nl = aiText.indexOf('\n');
                            if (nl >= 0)
                                aiText = aiText.substring(nl + 1).trim();
                            else
                                aiText = "";
                        }
                    }
                    js.append("\"ai\":\"").append(Util.escapeJson(aiText)).append("\",");
                    js.append("\"old\":\"").append(Util.escapeJson(oldSrc)).append("\",");
                    js.append("\"new\":\"").append(Util.escapeJson(newSrc)).append("\"");
                } else {
                    js.append("\"match\":\"").append(Util.escapeJson(cat)).append("\"");
                }
            } catch (Exception ex) {
                js.append("\"match\":\"").append(Util.escapeJson(cat + " (读取失败)")).append("\"");
            }
            js.append("};");
            try {
                Files.write(jsFile, js.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException ex) {
                System.err.println("  [HTML] JS数据文件写入失败: " + fileName); }
        }

        // ── 构建 HTML ──
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n");
        h.append("<meta charset=\"UTF-8\">\n");
        h.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\">\n");
        h.append("<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,");
        h.append("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'><defs><linearGradient id='g' x1='0' y1='0' x2='1' y2='1'><stop offset='0%' stop-color='%233b82f6'/><stop offset='100%' stop-color='%231d4ed8'/></linearGradient></defs>");
        h.append("<rect x='4' y='14' width='22' height='28' rx='3' fill='url(%23g)' opacity='.85'/><rect x='8' y='20' width='14' height='3' rx='1' fill='%23fff' opacity='.9'/><rect x='8' y='26' width='10' height='3' rx='1' fill='%23fff' opacity='.6'/><rect x='8' y='32' width='12' height='3' rx='1' fill='%23fff' opacity='.4'/>");
        h.append("<rect x='28' y='8' width='22' height='28' rx='3' fill='url(%23g)' opacity='.55'/><rect x='32' y='14' width='14' height='3' rx='1' fill='%23fff' opacity='.9'/><rect x='32' y='20' width='10' height='3' rx='1' fill='%23fff' opacity='.6'/><rect x='32' y='26' width='12' height='3' rx='1' fill='%23fff' opacity='.4'/>");
        h.append("<circle cx='46' cy='46' r='14' fill='%23fff' stroke='%233b82f6' stroke-width='3'/><text x='46' y='51' text-anchor='middle' font-size='17' font-weight='bold' fill='%233b82f6' font-family='sans-serif'>≠</text></svg>\">\n");
        h.append("<title>Java产物比较报告</title>\n");
        appendCss(h);

        h.append("</style>\n</head>\n<body>\n<div class=\"container\">\n");
        h.append("<h1>&#128270; Java产物比较报告</h1>\n");
        h.append("<p class=\"subtitle\">生成时间: ")
                .append(DATE_FORMAT.get().format(new Date()))
                .append(" &nbsp;|&nbsp; 旧版: ").append(PhaseResults.oldCount).append(" 个")
                .append(" &nbsp;|&nbsp; 新版: ").append(PhaseResults.newCount).append(" 个</p>\n");

        // 汇总行
        h.append("<div class=\"summary-line\">");
        h.append("<span>总计 <b>").append(total).append("</b> 个文件</span>");
        h.append("<span>逻辑一致 <b class=\"ok\">").append(logicalSame)
                .append("</b> (").append(Util.pct(logicalSame, total)).append(")</span>");
        if (diff > 0)
            h.append("<span>需人工 <b class=\"warn\">").append(diff)
                    .append("</b> (").append(Util.pct(diff, total)).append(")</span>");
        int moTotal = missOld + PhaseResults.resMissingOld;
        int mnTotal = missNew + PhaseResults.resMissingNew;
        if (moTotal > 0)
            h.append("<span>新增 <b>").append(moTotal).append("</b></span>");
        if (mnTotal > 0)
            h.append("<span>删除 <b class=\"warn\">").append(mnTotal).append("</b></span>");
        if (error > 0)
            h.append("<span>工具异常 <b class=\"warn\">").append(error).append("</b></span>");
        h.append("</div>\n");

        // 流水线
        h.append("<div class=\"pipeline\">");
        h.append("<div class=\"step sha\"><div class=\"cnt\">").append(sameFile)
                .append("</div><div class=\"hint\">SHA-256 一致</div></div>");
        h.append("<div class=\"arrow\">▸</div>");
        h.append("<div class=\"step asm\"><div class=\"cnt\">").append(sameAsm)
                .append("</div><div class=\"hint\">ASM 指纹一致</div></div>");
        h.append("<div class=\"arrow\">▸</div>");
        h.append("<div class=\"step dec\"><div class=\"cnt\">").append(sameDec)
                .append("</div><div class=\"hint\">反编译一致</div></div>");
        h.append("<div class=\"arrow\">▸</div>");
        h.append("<div class=\"step ai\"><div class=\"cnt\">").append(aiOk)
                .append("</div><div class=\"hint\">AI 验证一致</div></div>");
        h.append("<div class=\"arrow\">▸</div>");
        h.append("<div class=\"step diff\"><div class=\"cnt\">").append(diff)
                .append("</div><div class=\"hint\">逻辑不同</div></div>");
        h.append("</div>\n");

        // 全部类明细表
        h.append("<section>\n<h2 onclick=\"toggleSection(this)\">&#128218; 对比明细</h2>\n");
        h.append("<div class=\"section-body\">\n");
        h.append("<div class=\"toolbar\">\n");
        h.append("<input type=\"text\" id=\"search\" placeholder=\"搜索文件名或路径...\" oninput=\"applyFilter()\">\n");
        h.append("<select id=\"catFilter\" onchange=\"applyFilter()\">\n");
        int sha256Total = sameFile + PhaseResults.resMatch;
        h.append("<option value=\"all\">全部分类 (").append(total).append(")</option>\n");
        if (sha256Total > 0)
            h.append("<option value=\"sha256\">&#128994; SHA-256一致 (").append(sha256Total)
                    .append(")</option>\n");
        if (sameAsm > 0)
            h.append("<option value=\"asm\">&#128994; ASM指纹一致 (").append(sameAsm).append(")</option>\n");
        if (sameDec > 0)
            h.append("<option value=\"decompiled\">&#128994; 反编译一致 (").append(sameDec)
                    .append(")</option>\n");
        if (aiOk > 0)
            h.append("<option value=\"ai\">&#128994; AI验证 (").append(aiOk).append(")</option>\n");
        if (diff > 0)
            h.append("<option value=\"diff\">&#128308; 需人工 (").append(diff).append(")</option>\n");
        if (error > 0)
            h.append("<option value=\"error\">&#9888; 工具异常 (").append(error).append(")</option>\n");
        if (moTotal > 0)
            h.append("<option value=\"miss-new\">&#9898; 新增 (").append(moTotal).append(")</option>\n");
        if (mnTotal > 0)
            h.append("<option value=\"miss-old\">&#128308; 删除 (").append(mnTotal).append(")</option>\n");
        h.append("</select>\n");
        h.append("<span id=\"rowCount\" style=\"display:inline-flex;align-items:center;padding:7px 0;font-size:13px;color:#64748b\"></span>\n</div>\n");

        h.append("<table id=\"classTable\">\n<thead><tr>");
        h.append("<th onclick=\"sortTable(0,'classTable')\">文件名</th>");
        h.append("<th onclick=\"sortTable(1,'classTable')\">包名</th>");
        h.append("<th onclick=\"sortTable(2,'classTable')\">状态</th>");
        h.append("</tr></thead>\n<tbody>\n");

        for (Map.Entry<String, String> entry : catMap.entrySet()) {
            appendTableRow(h, entry.getKey(), entry.getValue());
        }
        h.append("</tbody></table>\n</div>\n</section>\n");

        // 按包分布表
        appendPackageStats(h, catMap);

        // 全屏对比面板
        h.append("<div id=\"diffPanel\"><div id=\"diffHeader\">");
        h.append("<span class=\"title\" id=\"diffTitle\"></span>");
        h.append("<div id=\"diffNav\">");
        h.append("<button onclick=\"prevDiff()\" title=\"上一个差异 (Shift+↑)\">&#9650;</button>");
        h.append("<span id=\"diffPos\">-</span>");
        h.append("<button onclick=\"nextDiff()\" title=\"下一个差异 (Shift+↓)\">&#9660;</button>");
        h.append("</div>");
        h.append("<button onclick=\"closeDiff()\">&#10005; 关闭</button>");
        h.append("</div><div id=\"diffAiBox\"><div id=\"diffAiHeader\" onclick=\"toggleAiBox()\"><span id=\"diffAiTitle\">AI 报告</span><button id=\"diffAiToggle\">▾ 收起</button></div><div id=\"diffAiBody\"></div></div>");
        h.append("<div id=\"diffLabels\"><span>旧版 <button class=\"copy-btn\" onclick=\"copyColumn('diffOldCol',this)\" title=\"复制旧版全部内容\">&#128203;</button></span><span>新版 <button class=\"copy-btn\" onclick=\"copyColumn('diffNewCol',this)\" title=\"复制新版全部内容\">&#128203;</button></span></div>");
        h.append("<div id=\"diffContent\"><div id=\"diffOldCol\"></div><div id=\"diffNewCol\"></div></div>");
        h.append("</div>\n");

        h.append("<script>\nvar DETAIL_DATA={};\nvar PENDING_LOADS={};\n</script>\n");
        appendJs(h);
        h.append("</div>\n</body>\n</html>");

        Files.write(reportDir.resolve("report.html"),
                h.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ── HTML 辅助方法 ──

    static void appendTableRow(StringBuilder h, String name, String cat) {
        boolean isResource = name.contains("/");
        String shortName, pkg, detailFile;
        if (isResource) {
            int idx = name.lastIndexOf('/');
            shortName = idx >= 0 ? name.substring(idx + 1) : name;
            pkg = idx > 0 ? name.substring(0, idx) : "(根目录)";
            detailFile = Util.toResFileName(name);
        } else {
            int idx = name.lastIndexOf('.');
            shortName = (idx >= 0 ? name.substring(idx + 1) : name) + ".java";
            pkg = idx > 0 ? name.substring(0, idx).replace('.', '/') : "(根目录)";
            detailFile = Util.toFileName(name);
        }
        String badgeClass, badgeText, dataCat;
        switch (cat) {
            case Config.CAT_SAME_FILE:
                badgeClass = "ok"; badgeText = "SHA-256一致"; dataCat = "sha256"; break;
            case Config.CAT_SAME_ASM:
                badgeClass = "ok"; badgeText = "ASM指纹一致"; dataCat = "asm"; break;
            case Config.CAT_SAME_DECOMPILED:
                badgeClass = "dec"; badgeText = "反编译一致"; dataCat = "decompiled"; break;
            case Config.CAT_AI_VERIFIED:
                badgeClass = "ok"; badgeText = "AI验证"; dataCat = "ai"; break;
            case Config.CAT_DIFFERENT:
                badgeClass = "diff"; badgeText = "需人工"; dataCat = "diff"; break;
            case Config.CAT_ERROR:
                badgeClass = "miss"; badgeText = "⚠ 工具异常"; dataCat = "error"; break;
            case Config.CAT_MISSING_OLD:
                badgeClass = "miss"; badgeText = "新增"; dataCat = "miss-new"; break;
            case Config.CAT_MISSING_NEW:
                badgeClass = "diff"; badgeText = "删除"; dataCat = "miss-old"; break;
            default:
                badgeClass = "miss"; badgeText = cat; dataCat = "miss"; break;
        }
        boolean canClick = !Config.CAT_SAME_FILE.equals(cat) && !Config.CAT_SAME_ASM.equals(cat);
        String searchName = pkg + "/ " + shortName;
        h.append("<tr data-cat=\"").append(dataCat)
                .append("\" data-name=\"").append(Util.esc(searchName.toLowerCase())).append("\"")
                .append(canClick ? " data-detail=\"" + detailFile + "\"" : "")
                .append(">");
        h.append("<td class=\"cn").append(canClick ? " clickable" : "")
                .append("\" title=\"").append(Util.esc(name)).append("\"")
                .append(canClick ? " onclick=\"showDiff('" + detailFile + "','" + Util.esc(name) + "')\"" : "")
                .append(">").append(Util.esc(shortName)).append("</td>");
        h.append("<td style=\"color:#999;font-size:11px\">").append(Util.esc(pkg)).append("</td>");
        h.append("<td><span class=\"badge ").append(badgeClass).append("\">").append(badgeText)
                .append("</span></td>");
        h.append("</tr>\n");
    }

    static void appendPackageStats(StringBuilder h, Map<String, String> catMap) {
        h.append("<section>\n<h2 onclick=\"toggleSection(this)\">&#128230; 按目录统计</h2>\n");
        h.append("<div class=\"section-body\">\n<table id=\"pkgTbl\">\n<thead><tr>");
        h.append("<th onclick=\"sortTable(0,'pkgTbl')\">包名</th>");
        h.append("<th onclick=\"sortTable(1,'pkgTbl')\">SHA-256一致</th>");
        h.append("<th onclick=\"sortTable(2,'pkgTbl')\">ASM一致</th>");
        h.append("<th onclick=\"sortTable(3,'pkgTbl')\">反编译一致</th>");
        h.append("<th onclick=\"sortTable(4,'pkgTbl')\">AI一致</th>");
        h.append("<th onclick=\"sortTable(5,'pkgTbl')\">需人工</th>");
        h.append("</tr></thead>\n<tbody>\n");
        Map<String, int[]> stats = new TreeMap<>();
        for (Map.Entry<String, String> entry : catMap.entrySet()) {
            String name = entry.getKey(), cat = entry.getValue();
            String path = name.contains("/") ? name : name.replace('.', '/');
            int idx = path.lastIndexOf('/');
            String group = idx > 0 ? path.substring(0, idx) : "(根目录)";
            int[] counts = stats.computeIfAbsent(group, k -> new int[5]);
            int column = -1;
            if (Config.CAT_SAME_FILE.equals(cat)) column = 0;
            else if (Config.CAT_SAME_ASM.equals(cat)) column = 1;
            else if (Config.CAT_SAME_DECOMPILED.equals(cat)) column = 2;
            else if (Config.CAT_AI_VERIFIED.equals(cat)) column = 3;
            else if (Config.CAT_DIFFERENT.equals(cat)) column = 4;
            if (column >= 0) counts[column]++;
        }
        for (Map.Entry<String, int[]> entry : stats.entrySet()) {
            int[] counts = entry.getValue();
            h.append("<tr>");
            h.append("<td style=\"font-size:12px\">").append(Util.esc(entry.getKey())).append("</td>");
            for (int i = 0; i < 5; i++)
                h.append("<td>").append(counts[i]).append("</td>");
            h.append("</tr>\n");
        }
        h.append("</tbody></table>\n</div>\n</section>\n");
    }

    /** 控制台汇总输出 */
    public static void printFinalSummary() {
        int total = PhaseResults.ALL_CLASSES.size();
        int logicalSame = PhaseResults.phase2Match + PhaseResults.phase3Match
                + PhaseResults.phase4Match + PhaseResults.phase5Match;

        System.out.println();
        System.out.println("========================================");
        System.out.println("  产物比较完成");
        System.out.println("========================================");
        System.out.println("  Phase 1 资源文件:    " + PhaseResults.resMatch + " 一致  |  "
                + PhaseResults.resDiff + " 差异"
                + "  |  +" + PhaseResults.resMissingOld
                + "  |  -" + PhaseResults.resMissingNew);
        System.out.println("  Phase 2 SHA-256:    "
                + (PhaseResults.phase2Match + PhaseResults.resMatch) + " 一致 (含资源)"
                + (PhaseResults.phase2Error > 0 ? "  |  " + PhaseResults.phase2Error + " 错误" : ""));
        System.out.println("  Phase 3 ASM指纹:    " + PhaseResults.phase3Match + " 一致"
                + (PhaseResults.phase3Error > 0
                        ? "  |  " + PhaseResults.phase3Error + " 解析失败(已转入下阶段)"
                        : ""));
        System.out.println("  Phase 4 反编译:     " + PhaseResults.phase4Match + " 一致 (JDK差异)"
                + (PhaseResults.phase4Error > 0
                        ? "  |  " + PhaseResults.phase4Error + " 工具异常"
                        : ""));
        System.out.println("  Phase 5 AI验证:     " + PhaseResults.phase5Match + " 一致");
        System.out.println(
                "  -> 逻辑一致合计:    " + logicalSame + " (" + Util.pct(logicalSame, total) + ")");
        System.out.println("  需人工审查:         " + PhaseResults.phase5Diff + " ("
                + Util.pct(PhaseResults.phase5Diff, total) + ")");
        int moTotal = PhaseResults.missingOld + PhaseResults.resMissingOld;
        int mnTotal = PhaseResults.missingNew + PhaseResults.resMissingNew;
        System.out.println("  新增/删除:          " + moTotal + " / " + mnTotal);
        System.out.println("========================================");

        if (PhaseResults.phase2Error > 0 || PhaseResults.phase4Error > 0) {
            System.out.println("  -> " + (PhaseResults.phase2Error + PhaseResults.phase4Error)
                    + " 个类发生工具异常(IO/磁盘/文件损坏)，非逻辑差异，建议重试");
        }
        if (PhaseResults.phase5Diff > 0) {
            System.out.println("  -> 浏览器打开 report.html 进行交互式分析");
            System.out.println("  -> 查看 05-ai-diff.txt 获取需人工审查清单");
        }
        System.out.println();
    }

    // ── CSS / JS 内联 ──

    static void appendCss(StringBuilder h) {
        h.append("<style>\n");
        h.append("*{margin:0;padding:0;box-sizing:border-box}\n");
        h.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','Microsoft YaHei',sans-serif;background:#f0f2f5;color:#333;padding:20px}\n");
        h.append(".container{max-width:1500px;margin:0 auto}\n");
        h.append("h1{font-size:22px;margin-bottom:4px}\n");
        h.append(".subtitle{color:#999;margin-bottom:20px;font-size:13px}\n");
        h.append("section{margin-bottom:20px}\n");
        h.append("h2{font-size:16px;margin-bottom:10px;padding-bottom:6px;border-bottom:2px solid #e5e7eb;cursor:pointer;user-select:none}\n");
        h.append("h2:hover{color:#3b82f6}\n");
        h.append("h2::after{content:'  ▾';font-size:12px;color:#94a3b8}\n");
        h.append("h2.collapsed::after{content:'  ▸'}\n");
        h.append(".section-body{transition:opacity .2s}\n");
        h.append(".section-body.hidden{display:none}\n");
        h.append(".summary-line{display:flex;flex-wrap:wrap;margin-bottom:16px;font-size:14px;color:#475569}\n");
        h.append(".summary-line>*{margin-right:24px}\n");
        h.append(".summary-line b{color:#1e293b}\n");
        h.append(".summary-line .ok{color:#16a34a}\n");
        h.append(".summary-line .warn{color:#dc2626}\n");
        h.append(".pipeline{display:flex;align-items:stretch;margin-bottom:24px}\n");
        h.append(".pipeline .step{flex:1;min-width:105px;text-align:center;padding:16px 12px 12px;background:#fff;border-top:3px solid #e5e7eb;border-bottom:3px solid #e5e7eb;border-radius:10px;box-shadow:0 1px 3px rgba(0,0,0,.06);margin-right:8px}\n");
        h.append(".pipeline .step.sha{border-top-color:#64748b;border-bottom-color:#64748b}\n");
        h.append(".pipeline .step.asm{border-top-color:#3b82f6;border-bottom-color:#3b82f6}\n");
        h.append(".pipeline .step.dec{border-top-color:#eab308;border-bottom-color:#eab308}\n");
        h.append(".pipeline .step.ai{border-top-color:#22c55e;border-bottom-color:#22c55e}\n");
        h.append(".pipeline .step.diff{border-top-color:#ef4444;border-bottom-color:#ef4444}\n");
        h.append(".pipeline .step .cnt{font-size:32px;font-weight:700;color:#0f172a;line-height:1.1}\n");
        h.append(".pipeline .step .hint{font-size:13px;color:#475569;margin-top:4px;font-weight:500}\n");
        h.append(".pipeline .arrow{display:flex;align-items:center;justify-content:center;width:20px;flex-shrink:0;color:#94a3b8;font-size:30px;margin-right:8px}\n");
        h.append(".toolbar{display:flex;margin-bottom:10px;flex-wrap:wrap}\n");
        h.append(".toolbar>*{margin-right:8px}\n");
        h.append(".toolbar input,.toolbar select{padding:7px 10px;border:1px solid #d1d5db;border-radius:6px;font-size:13px;outline:none}\n");
        h.append(".toolbar input:focus,.toolbar select:focus{border-color:#3b82f6;box-shadow:0 0 0 2px rgba(59,130,246,.12)}\n");
        h.append(".toolbar input{flex:1;min-width:180px}\n");
        h.append("table{width:100%;border-collapse:collapse;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,.06)}\n");
        h.append("th{background:#f8fafc;padding:9px 12px;text-align:left;font-size:12px;font-weight:600;color:#64748b;border-bottom:2px solid #e5e7eb;cursor:pointer;user-select:none;white-space:nowrap}\n");
        h.append("th:hover{color:#1a1a1a}\n");
        h.append("td{padding:7px 12px;font-size:12px;border-bottom:1px solid #f1f5f9}\n");
        h.append("tr:hover{background:#f8fafc}\n");
        h.append(".cn{font-family:'SF Mono','Cascadia Code','Consolas',monospace;font-size:11px;max-width:400px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}\n");
        h.append(".cn.clickable{color:#1e293b;cursor:pointer;text-decoration:underline;text-decoration-style:dotted;text-underline-offset:2px}\n");
        h.append(".cn.clickable:hover{color:#3b82f6}\n");
        h.append(".cn.clickable.visited{color:#1d4ed8;text-decoration:underline;text-decoration-style:dotted;text-underline-offset:2px}\n");
        h.append(".badge{display:inline-block;padding:2px 8px;border-radius:10px;font-size:11px;font-weight:600;white-space:nowrap}\n");
        h.append(".badge.ok{background:#dcfce7;color:#16a34a}\n");
        h.append(".badge.dec{background:#fef9c3;color:#ca8a04}\n");
        h.append(".badge.diff{background:#fee2e2;color:#dc2626}\n");
        h.append(".badge.miss{background:#f1f5f9;color:#64748b}\n");
        h.append("#diffPanel{position:fixed;top:0;left:0;width:100vw;height:100vh;background:#fff;color:#333;z-index:1000;display:none;flex-direction:column;overflow:hidden}\n");
        h.append("#diffPanel.show{display:flex}\n");
        h.append("#diffHeader{display:flex;align-items:center;padding:10px 16px;background:#f8fafc;border-bottom:1px solid #e5e7eb;font-size:13px}\n");
        h.append("#diffHeader>*{margin-right:10px}\n");
        h.append("#diffHeader .title{flex:1;font-weight:600;color:#1e293b;font-size:14px}\n");
        h.append("#diffHeader button{background:#fff;border:1px solid #d1d5db;color:#374151;padding:5px 12px;border-radius:5px;cursor:pointer;font-size:12px;transition:all .15s}\n");
        h.append("#diffHeader button:hover{background:#f1f5f9;border-color:#9ca3af}\n");
        h.append("#diffNav{display:flex;align-items:center}\n");
        h.append("#diffNav>*{margin-right:2px}\n");
        h.append("#diffNav button{font-size:14px;padding:5px 8px;border-radius:5px}\n");
        h.append("#diffNav span{font-size:11px;color:#64748b;min-width:50px;text-align:center}\n");
        h.append("#diffAiBox{display:none;background:#fffbeb;border-bottom:1px solid #fde68a}\n");
        h.append("#diffAiHeader{display:flex;align-items:center;padding:8px 16px;cursor:pointer;user-select:none}\n");
        h.append("#diffAiHeader>*{margin-right:8px}\n");
        h.append("#diffAiHeader:hover{background:#fef3c7}\n");
        h.append("#diffAiTitle{flex:1;font-size:12px;font-weight:600;color:#92400e}\n");
        h.append("#diffAiToggle{background:none;border:1px solid #fde68a;color:#92400e;font-size:11px;padding:2px 8px;border-radius:4px;cursor:pointer}\n");
        h.append("#diffAiToggle:hover{background:#fef3c7}\n");
        h.append("#diffAiBody{margin:0;font-size:12px;color:#92400e;line-height:1.5;max-height:200px;overflow:auto;font-family:inherit}\n");
        h.append("#diffAiBody>pre{margin:0;padding:0 16px 10px;white-space:pre-wrap}\n");
        h.append("#diffAiBox.collapsed #diffAiBody{display:none}\n");
        h.append(".ai-tbl{width:100%;border-collapse:collapse;font-size:11px}\n");
        h.append(".ai-tbl th{background:#fef3c7;padding:4px 8px;text-align:left;border-bottom:1px solid #fde68a;color:#92400e;position:sticky;top:0}\n");
        h.append(".ai-tbl td{padding:4px 8px;border-bottom:1px solid #fef3c7;cursor:pointer;vertical-align:top}\n");
        h.append(".ai-tbl td:first-child{white-space:nowrap;max-width:180px;overflow:hidden;text-overflow:ellipsis}\n");
        h.append(".ai-tbl td:last-child{min-width:180px}\n");
        h.append(".ai-tbl tr:hover td{background:#fef3c7}\n");
        h.append(".diff-row.ai-match{box-shadow:inset 3px 0 0 #f59e0b;background:rgba(245,158,11,.08)}\n");
        h.append("#diffLabels{display:flex;background:#f1f5f9;border-bottom:1px solid #e5e7eb}\n");
        h.append("#diffLabels>span{flex:1;padding:5px 16px;font-size:12px;color:#64748b;text-align:center;font-weight:600;display:flex;align-items:center;justify-content:center;gap:8px}\n");
        h.append("#diffLabels>span+span{border-left:1px solid #e5e7eb}\n");
        h.append(".copy-btn{background:none;border:1px solid #d1d5db;border-radius:4px;padding:1px 6px;font-size:11px;cursor:pointer;color:#64748b;line-height:1.4}\n");
        h.append(".copy-btn:hover{background:#e5e7eb;color:#374151}\n");
        h.append("#diffContent{flex:1;display:flex;overflow:hidden}\n");
        h.append("#diffOldCol,#diffNewCol{flex:1;overflow-y:auto;min-height:0;font-family:'SF Mono','Cascadia Code',Consolas,monospace;font-size:12px;line-height:1.6}\n");
        h.append("#diffNewCol{border-left:1px solid #e5e7eb}\n");
        h.append(".diff-row{display:flex;min-height:1.45em}\n");
        h.append(".diff-row .ln{display:inline-block;min-width:42px;padding:0 8px 0 4px;text-align:right;color:#94a3b8;user-select:none;flex-shrink:0}\n");
        h.append(".diff-row .code{flex:1;white-space:pre;padding-right:12px}\n");
        h.append(".diff-row.added{background:#dcfce7}\n");
        h.append(".diff-row.added .code{color:#166534}\n");
        h.append(".diff-row.removed{background:#fee2e2}\n");
        h.append(".diff-row.removed .code{color:#991b1b}\n");
        h.append(".diff-row.modified{background:#fef9c3}\n");
        h.append(".diff-row.modified .code{color:#92400e}\n");
        h.append(".diff-row.equal{background:transparent}\n");
        h.append(".diff-row.empty{background:#f8fafc}\n");
        h.append(".diff-row.highlight{box-shadow:inset 3px 0 0 #3b82f6}\n");
        h.append(".diff-row.modified .code b{background:#fbbf24;color:#78350f;font-weight:700;border-radius:2px;padding:0 1px}\n");
    }

    static void appendJs(StringBuilder h) {
        h.append("<script>\n");
        h.append("function toggleSection(h2){h2.classList.toggle('collapsed');");
        h.append("var body=h2.nextElementSibling;body.classList.toggle('hidden')}\n");
        h.append("function applyFilter(){var s=document.getElementById('search').value.toLowerCase();")
                .append("var c=document.getElementById('catFilter').value;")
                .append("var rows=document.querySelectorAll('#classTable tbody tr');")
                .append("var visible=0;")
                .append("for(var i=0;i<rows.length;i++){var r=rows[i];")
                .append("var name=r.getAttribute('data-name')||'';")
                .append("var cat=r.getAttribute('data-cat')||'';")
                .append("var ok=true;if(s&&name.indexOf(s)===-1)ok=false;")
                .append("if(c!=='all'&&cat!==c)ok=false;")
                .append("r.style.display=ok?'':'none';if(ok)visible++}")
                .append("document.getElementById('rowCount').textContent=visible+'/'+rows.length+' 条'};\n");
        h.append("var dirs={};function sortTable(n,tid){var t=document.getElementById(tid);")
                .append("var k=tid+'_'+n;var d=dirs[k]||1;dirs[k]=-d;")
                .append("var rows=Array.prototype.slice.call(t.tBodies[0].rows);")
                .append("rows.sort(function(a,b){")
                .append("var va=(a.cells[n].textContent||'').trim();")
                .append("var vb=(b.cells[n].textContent||'').trim();")
                .append("var na=parseInt(va),nb=parseInt(vb);")
                .append("if(!isNaN(na)&&!isNaN(nb))return (na-nb)*d;")
                .append("return va.toLowerCase().localeCompare(vb.toLowerCase())*d});")
                .append("for(var ri=0;ri<rows.length;ri++)t.tBodies[0].appendChild(rows[ri])};\n");
        h.append("var diffBlocks=[];\nvar currentDiffIdx=-1;\n");
        h.append("function collapseBlanks(lines){")
                .append("var out=[],prev=false;")
                .append("for(var i=0;i<lines.length;i++){")
                .append("var blank=/^\\s*$/.test(lines[i]);")
                .append("if(blank){if(!prev)out.push('');prev=true}")
                .append("else{out.push(lines[i]);prev=false}}")
                .append("return out}\n");
        h.append("function computeDiff(O,N){")
                .append("var o=O.length,n=N.length;")
                .append("var dp=[];for(var i=0;i<=o;i++){dp[i]=[];for(var j=0;j<=n;j++)dp[i][j]=0}")
                .append("for(var i=1;i<=o;i++)for(var j=1;j<=n;j++)")
                .append("dp[i][j]=O[i-1]===N[j-1]?dp[i-1][j-1]+1:Math.max(dp[i-1][j],dp[i][j-1]);")
                .append("var R=[],i=o,j=n;")
                .append("while(i>0||j>0){")
                .append("if(i>0&&j>0&&O[i-1]===N[j-1]){R.unshift({o:i-1,n:j-1,ol:O[i-1],nl:N[j-1],type:'equal'});i--;j--}")
                .append("else if(j>0&&(i===0||dp[i][j-1]>=dp[i-1][j])){R.unshift({o:-1,n:j-1,ol:'',nl:N[j-1],type:'added'});j--}")
                .append("else{R.unshift({o:i-1,n:-1,ol:O[i-1],nl:'',type:'removed'});i--}")
                .append("}return R}\n");
        h.append("function inlineDiff(os,ns){")
                .append("var i=0,olen=os.length,nlen=ns.length;")
                .append("while(i<olen&&i<nlen&&os[i]===ns[i])i++;")
                .append("var j=0;while(j<(olen-i)&&j<(nlen-i)&&os[olen-1-j]===ns[nlen-1-j])j++;")
                .append("var pre=escHtml(os.substring(0,i));")
                .append("var osMid=escHtml(os.substring(i,olen-j));")
                .append("var nsMid=escHtml(ns.substring(i,nlen-j));")
                .append("var suf=escHtml(os.substring(olen-j));")
                .append("return{old:pre+'<b>'+osMid+'</b>'+suf,new:pre+'<b>'+nsMid+'</b>'+suf}}\n");
        h.append("function showDiff(file,title){document.body.style.overflow='hidden';")
                .append("document.getElementById('diffTitle').textContent=title;")
                .append("document.getElementById('diffPanel').classList.add('show');")
                .append("var td=document.querySelector('[data-detail=\"'+file+'\"] .cn.clickable');")
                .append("if(td)td.classList.add('visited');")
                .append("function render(){var d=DETAIL_DATA[file];")
                .append("if(!d){document.getElementById('diffOldCol').innerHTML='<div style=\"padding:20px;color:#999\">加载详情失败</div>';")
                .append("document.getElementById('diffNewCol').innerHTML='';")
                .append("document.getElementById('diffAiBox').style.display='none';return}")
                .append("if(d.match){document.getElementById('diffOldCol').innerHTML='<div style=\"display:flex;align-items:center;justify-content:center;height:100%;font-size:15px;color:#94a3b8;font-weight:600\">'+escHtml(d.match)+'</div>';")
                .append("document.getElementById('diffNewCol').innerHTML='<div style=\"display:flex;align-items:center;justify-content:center;height:100%;font-size:15px;color:#94a3b8;font-weight:600\">—</div>';")
                .append("document.getElementById('diffAiBox').style.display='none';")
                .append("diffBlocks=[];document.getElementById('diffPos').textContent='-';return}")
                .append("if(d.missing==='old'){document.getElementById('diffOldCol').innerHTML='<div style=\"display:flex;align-items:center;justify-content:center;height:100%;font-size:15px;color:#94a3b8;font-weight:600\">[ 新增 ]</div>';")
                .append("document.getElementById('diffNewCol').innerHTML='<pre style=\"padding:8px 16px;font-family:inherit;font-size:13px;line-height:1.7;white-space:pre-wrap;word-break:break-all\">'+escHtml(d.new)+'</pre>';")
                .append("document.getElementById('diffAiBox').style.display='none';")
                .append("diffBlocks=[];document.getElementById('diffPos').textContent='-';return}")
                .append("if(d.missing==='new'){document.getElementById('diffOldCol').innerHTML='<pre style=\"padding:8px 16px;font-family:inherit;font-size:13px;line-height:1.7;white-space:pre-wrap;word-break:break-all\">'+escHtml(d.old)+'</pre>';")
                .append("document.getElementById('diffNewCol').innerHTML='<div style=\"display:flex;align-items:center;justify-content:center;height:100%;font-size:15px;color:#94a3b8;font-weight:600\">[ 删除 ]</div>';")
                .append("document.getElementById('diffAiBody').textContent='';")
                .append("document.getElementById('diffAiBox').style.display='none';")
                .append("diffBlocks=[];document.getElementById('diffPos').textContent='-';return}")
                .append("var aiBox=document.getElementById('diffAiBox');")
                .append("var aiBody=document.getElementById('diffAiBody');")
                .append("var aiToggle=document.getElementById('diffAiToggle');")
                .append("if(d.ai){var findings=[];var re=/【(?:差异)?位置】(.+?)\\s*【(?:差异)?概述】：(.+)/g;var m;")
                .append("while((m=re.exec(d.ai))!==null){")
                .append("findings.push({loc:m[1].trim(),desc:m[2].trim()})}")
                .append("if(findings.length>0){var h='<table class=\"ai-tbl\"><thead><tr><th>位置</th><th>差异描述</th></tr></thead><tbody>';")
                .append("for(var fi=0;fi<findings.length;fi++){var f=findings[fi];")
                .append("h+='<tr onclick=\"scrollToAiFinding('+fi+')\" title=\"点击跳转到差异位置\">';")
                .append("h+='<td>'+escHtml(f.loc)+'</td><td>'+escHtml(f.desc)+'</td></tr>'}")
                .append("h+='</tbody></table>';aiBody.innerHTML=h}")
                .append("else{aiBody.innerHTML='<pre>'+escHtml(d.ai)+'</pre>'}")
                .append("aiBox.classList.remove('collapsed');aiToggle.textContent='▾ 收起';aiBox.style.display='block'}")
                .append("else{aiBox.style.display='none'}")
                .append("var oldLines=collapseBlanks(d.old.replace(/\\r/g,'').split('\\n'));")
                .append("var newLines=collapseBlanks(d.new.replace(/\\r/g,'').split('\\n'));")
                .append("var rows=computeDiff(oldLines,newLines);")
                .append("diffBlocks=[];var ohtml='',nhtml='';")
                .append("for(var r=0;r<rows.length;r++){var row=rows[r];")
                .append("var prev=r>0?rows[r-1]:null;")
                .append("if(row.type!=='equal'){")
                .append("if(!prev||prev.type==='equal'||prev.type!==row.type)diffBlocks.push({start:r,end:r});")
                .append("else diffBlocks[diffBlocks.length-1].end=r}")
                .append("var onum=row.o>=0?(row.o+1):'';var nnum=row.n>=0?(row.n+1):'';")
                .append("var ocode=row.ol,ncode=row.nl;")
                .append("if(row.type==='modified'){var id=inlineDiff(row.ol,row.nl);ocode=id.old;ncode=id.new}")
                .append("else{ocode=escHtml(ocode);ncode=escHtml(ncode)}")
                .append("ohtml+='<div class=\"diff-row '+row.type+'\" data-dr=\"'+r+'\"><span class=\"ln\">'+onum+'</span><span class=\"code\">'+ocode+'</span></div>';")
                .append("nhtml+='<div class=\"diff-row '+row.type+'\" data-dr=\"'+r+'\"><span class=\"ln\">'+nnum+'</span><span class=\"code\">'+ncode+'</span></div>'}")
                .append("var oc=document.getElementById('diffOldCol');oc.innerHTML=ohtml;oc.scrollTop=0;")
                .append("var nc=document.getElementById('diffNewCol');nc.innerHTML=nhtml;nc.scrollTop=0;")
                .append("currentDiffIdx=-1;")
                .append("document.getElementById('diffPos').textContent=diffBlocks.length>0?'差异 1/'+diffBlocks.length:'-';}")
                .append("if(DETAIL_DATA[file]){render()}else{")
                .append("document.getElementById('diffOldCol').innerHTML='<div style=\"padding:20px;color:#999\">加载中...</div>';")
                .append("document.getElementById('diffNewCol').innerHTML='';")
                .append("document.getElementById('diffAiBox').style.display='none';")
                .append("if(!PENDING_LOADS[file]){PENDING_LOADS[file]=true;")
                .append("var s=document.createElement('script');s.src='details/'+file+'.js';")
                .append("s.onload=function(){delete PENDING_LOADS[file];render()};")
                .append("s.onerror=function(){delete PENDING_LOADS[file];")
                .append("document.getElementById('diffOldCol').innerHTML='<div style=\"padding:20px;color:#999\">加载详情失败（需重新生成报告）</div>';")
                .append("document.getElementById('diffNewCol').innerHTML=''};")
                .append("document.head.appendChild(s)}}}\n");
        h.append("function nextDiff(){if(diffBlocks.length===0)return;")
                .append("currentDiffIdx=(currentDiffIdx+1)%diffBlocks.length;")
                .append("scrollToBlock(diffBlocks[currentDiffIdx])}\n");
        h.append("function prevDiff(){if(diffBlocks.length===0)return;")
                .append("currentDiffIdx=currentDiffIdx<=0?diffBlocks.length-1:currentDiffIdx-1;")
                .append("scrollToBlock(diffBlocks[currentDiffIdx])}\n");
        h.append("function scrollToBlock(block){")
                .append("var els=document.querySelectorAll('.diff-row.highlight');")
                .append("for(var hi=0;hi<els.length;hi++)els[hi].classList.remove('highlight');")
                .append("for(var r=block.start;r<=block.end;r++){")
                .append("var drs=document.querySelectorAll('.diff-row[data-dr=\"'+r+'\"]');")
                .append("for(var di=0;di<drs.length;di++)drs[di].classList.add('highlight')}")
                .append("var first=document.querySelector('.diff-row[data-dr=\"'+block.start+'\"]');")
                .append("if(first){var oldCol=document.getElementById('diffOldCol');")
                .append("var newCol=document.getElementById('diffNewCol');")
                .append("var top=first.getBoundingClientRect().top-oldCol.getBoundingClientRect().top+oldCol.scrollTop-oldCol.clientHeight/2;")
                .append("oldCol.scrollTop=top;newCol.scrollTop=top;}")
                .append("document.getElementById('diffPos').textContent='差异 '+(currentDiffIdx+1)+'/'+diffBlocks.length}\n");
        h.append("function toggleAiBox(){var b=document.getElementById('diffAiBox');")
                .append("var t=document.getElementById('diffAiToggle');")
                .append("if(b.classList.contains('collapsed')){b.classList.remove('collapsed');t.textContent='▾ 收起'}")
                .append("else{b.classList.add('collapsed');t.textContent='▸ 展开'}}\n");
        h.append("function copyColumn(colId,btn){")
                .append("var el=document.getElementById(colId);")
                .append("if(!el)return;")
                .append("var rows=el.querySelectorAll('.diff-row');")
                .append("var lines=[];")
                .append("for(var i=0;i<rows.length;i++){")
                .append("var code=rows[i].querySelector('.code');")
                .append("if(code)lines.push(code.textContent||'')}")
                .append("navigator.clipboard.writeText(lines.join('\\n')).then(function(){")
                .append("btn.textContent='✓';")
                .append("setTimeout(function(){btn.innerHTML='&#128203;'},1500)})}\n");
        h.append("function closeDiff(){var prev=document.querySelectorAll('.diff-row.ai-match');")
                .append("for(var pi=0;pi<prev.length;pi++)prev[pi].classList.remove('ai-match');")
                .append("document.getElementById('diffPanel').classList.remove('show');")
                .append("document.body.style.overflow=''}\n");
        h.append("function scrollToAiFinding(findingIndex){")
                .append("if(!diffBlocks||findingIndex>=diffBlocks.length)return;")
                .append("var block=diffBlocks[findingIndex];")
                .append("currentDiffIdx=findingIndex;")
                .append("document.getElementById('diffPos').textContent='差异 '+(findingIndex+1)+'/'+diffBlocks.length;")
                .append("var els=document.querySelectorAll('.diff-row.highlight');")
                .append("for(var hi=0;hi<els.length;hi++)els[hi].classList.remove('highlight');")
                .append("for(var r=block.start;r<=block.end;r++){")
                .append("var drs=document.querySelectorAll('.diff-row[data-dr=\"'+r+'\"]');")
                .append("for(var di=0;di<drs.length;di++)drs[di].classList.add('highlight','ai-match')}")
                .append("var first=document.querySelector('.diff-row[data-dr=\"'+block.start+'\"]');")
                .append("if(first){var oldCol=document.getElementById('diffOldCol');")
                .append("var newCol=document.getElementById('diffNewCol');")
                .append("var top=first.getBoundingClientRect().top-oldCol.getBoundingClientRect().top+oldCol.scrollTop-oldCol.clientHeight/2;")
                .append("oldCol.scrollTop=top;newCol.scrollTop=top}}\n");
        h.append("function escHtml(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}\n");
        h.append("document.addEventListener('keydown',function(e){")
                .append("if(!document.getElementById('diffPanel').classList.contains('show'))return;")
                .append("if(e.key==='Escape')closeDiff();")
                .append("if(e.key==='ArrowDown'&&e.shiftKey){e.preventDefault();nextDiff()}")
                .append("if(e.key==='ArrowUp'&&e.shiftKey){e.preventDefault();prevDiff()}})\n");
        h.append("applyFilter();\n");
        h.append("</script>\n");
    }

    /**
     * 独立入口：根据已有报告文件重新生成 report.html（不重跑流水线）。
     * 用法：java classcomparator.Reporter [报告目录，默认 report]
     */
    public static void main(String[] args) throws Exception {
        Path reportDir = Paths.get(args.length > 0 ? args[0] : "report");
        if (!Files.exists(reportDir)) {
            System.err.println("[错误] 报告目录不存在: " + reportDir.toAbsolutePath());
            System.exit(1);
        }

        System.out.println("根据已有报告重新生成 HTML ...");
        Config.loadConfig(); // 读取旧/新版目录路径

        // 尝试扫描旧版/新版目录（计算新增/删除数量）
        Path oldDir = Paths.get(Config.OLD_DIR);
        Path newDir = Paths.get(Config.NEW_DIR);
        Map<String, Path> oldMap = Files.exists(oldDir) ? Util.scan(oldDir) : Collections.emptyMap();
        Map<String, Path> newMap = Files.exists(newDir) ? Util.scan(newDir) : Collections.emptyMap();
        Map<String, Path> oldResMap = Files.exists(oldDir) ? Util.scanResources(oldDir) : Collections.emptyMap();
        Map<String, Path> newResMap = Files.exists(newDir) ? Util.scanResources(newDir) : Collections.emptyMap();

        // 从阶段报告文件中重建 ALL_CLASSES
        Set<String> allClasses = new TreeSet<>();
        // 从阶段报告文件重建（主来源）
        allClasses.addAll(readClassNames(reportDir, "02-sha256-match.txt"));
        allClasses.addAll(readClassNames(reportDir, "02-sha256-diff.txt"));
        allClasses.addAll(readClassNames(reportDir, "02-sha256-error.txt"));
        allClasses.addAll(readClassNames(reportDir, "03-asm-match.txt"));
        allClasses.addAll(readClassNames(reportDir, "03-asm-diff.txt"));
        allClasses.addAll(readClassNames(reportDir, "03-asm-error.txt"));
        allClasses.addAll(readClassNames(reportDir, "04-decompiled-match.txt"));
        allClasses.addAll(readClassNames(reportDir, "04-decompiled-diff.txt"));
        allClasses.addAll(readClassNames(reportDir, "04-decompiled-error.txt"));
        allClasses.addAll(readClassNames(reportDir, "05-ai-match.txt"));
        allClasses.addAll(readClassNames(reportDir, "05-ai-diff.txt"));
        // 兜底合并扫描结果，确保不会遗漏报告文件中未出现的类
        allClasses.addAll(oldMap.keySet());
        allClasses.addAll(newMap.keySet());

        // 计算 class 新增/删除
        int missingOld = 0, missingNew = 0;
        for (String cls : allClasses) {
            if (!oldMap.containsKey(cls)) missingOld++;
            else if (!newMap.containsKey(cls)) missingNew++;
        }

        // 计算资源新增/删除
        Set<String> allRes = new TreeSet<>();
        allRes.addAll(oldResMap.keySet());
        allRes.addAll(newResMap.keySet());
        allRes.addAll(readClassNames(reportDir, "01-resource-match.txt"));
        allRes.addAll(readClassNames(reportDir, "01-resource-normalized-match.txt"));
        allRes.addAll(readClassNames(reportDir, "01-resource-diff.txt"));
        int resMissingOld = 0, resMissingNew = 0;
        for (String name : allRes) {
            if (!oldResMap.containsKey(name)) resMissingOld++;
            else if (!newResMap.containsKey(name)) resMissingNew++;
        }

        PhaseResults.ALL_CLASSES = allClasses;
        PhaseResults.oldMap = oldMap;
        PhaseResults.newMap = newMap;
        PhaseResults.oldResMap = oldResMap;
        PhaseResults.newResMap = newResMap;
        PhaseResults.oldCount = oldMap.size();
        PhaseResults.newCount = newMap.size();
        PhaseResults.resMatch = readClassNames(reportDir, "01-resource-match.txt").size()
                + readClassNames(reportDir, "01-resource-normalized-match.txt").size();
        PhaseResults.resDiff = readClassNames(reportDir, "01-resource-diff.txt").size();
        PhaseResults.resMissingOld = resMissingOld;
        PhaseResults.resMissingNew = resMissingNew;
        PhaseResults.missingOld = missingOld;
        PhaseResults.missingNew = missingNew;

        long start = System.currentTimeMillis();
        Reporter.generateHtmlReport(reportDir);
        System.out.println("完成 (" + (System.currentTimeMillis() - start) / 1000 + "s)");

        // 诊断：统计 AI 日志文件
        Path aiDir = reportDir.resolve("ai");
        if (Files.exists(aiDir)) {
            int aiCount = 0;
            try (java.util.stream.Stream<Path> s = Files.list(aiDir)) {
                aiCount = (int) s.filter(p -> p.toString().endsWith(".ai.txt")).count();
            }
            System.out.println("  AI 日志文件: " + aiCount + " 个 (位于 " + aiDir + ")");
            if (aiCount == 0)
                System.out.println("  [提示] 如果完整流水线已跑过，AI 日志应在此目录。"
                        + "文件名格式: 类名或资源路径扁平化后加 .ai.txt");

            // 抽查：列出 AI 目录前 5 个文件名，帮助排查命名匹配问题
            try (java.util.stream.Stream<Path> stream = Files.list(aiDir).limit(5)) {
                System.out.print("  前几个 AI 文件: ");
                stream.forEach(path -> System.out.print(path.getFileName() + "  "));
                System.out.println();
            }
        } else {
            System.out.println("  AI 日志目录不存在: " + aiDir);
        }
    }

    private static Set<String> readClassNames(Path reportDir, String fileName) throws IOException {
        Path file = reportDir.resolve(fileName);
        if (!Files.exists(file))
            return Collections.emptySet();
        Set<String> set = new LinkedHashSet<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty())
                set.add(trimmed);
        }
        return set;
    }
}
