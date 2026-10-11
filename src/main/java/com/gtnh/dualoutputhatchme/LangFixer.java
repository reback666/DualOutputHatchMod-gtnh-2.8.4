package com.gtnh.dualoutputhatchme;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把方块名称与物品说明直接写进 GT 的语言缓存文件。
 *
 * GT 的语言表是「文件里的值优先」：addStringLocalization 对已经存在于
 * 文件里的键一律返回文件里的旧值，只有键不存在时才用调用方给的文本。
 * GT 又把自己的 preInit 排到所有 mod 之后，所以本 mod 的 preInit 里
 * GTLanguageManager.sEnglishFile 仍是 null，不可能通过它写值。
 * 这里改为在 GT 读文件之前直接改写语言文件本身。
 *
 * 只替换命中键所在行的值，缺失的键插入 LanguageFile 段首，其余内容原样保留。
 */
public final class LangFixer {

    private static final String LANG_RESOURCE_DIR = "/assets/dualoutputhatchme/lang/";
    private static final String NAME_KEY = "gt.blockmachines.hatch.dual_output.me.name";
    private static final String DESC_KEY_PREFIX = "dualoutputhatchme.desc.";
    private static final int DESC_LINES = 6;
    /** GT/Forge 配置的段头，实测为全小写；比对时忽略大小写以防 GT 改格式。 */
    private static final String SECTION_HEADER = "languagefile {";
    /**
     * GT 的英文缓存文件名与其它语言不同：GTPreLoad.initLocalization 对 en_US
     * 直接读 GregTech.lang（GregTech_US.lang 是旧版遗留，现行 GT 不再读写）。
     */
    private static final String EN_US_FILE_NAME = "GregTech.lang";

    private LangFixer() {}

    /**
     * @param gameDirectory .minecraft 目录（GT 把语言文件放在这里，不在 config 下）
     * @param locale        客户端当前语言代码；null 表示无法判定，此时不改写
     * @return 是否写出了改动
     */
    public static boolean apply(File gameDirectory, String locale, int metaTileId) {
        if (gameDirectory == null || locale == null || locale.isEmpty()) {
            return false;
        }
        Map<String, String> updates = collectUpdates(load(locale), metaTileId);
        if (updates.isEmpty()) {
            return false;
        }
        File file = new File(gameDirectory, "en_US".equals(locale) ? EN_US_FILE_NAME : "GregTech_" + locale + ".lang");
        try {
            if (!file.isFile()) {
                // 不能静默放弃：GT 随后会自己建文件，并把 aNameRegional（现在是本地化键）
                // 当英文默认值写进去，物品名会显示原始键名。按 GT/Forge 配置格式新建，
                // 文件里的值同样享受「文件优先」。
                create(file, updates);
                return true;
            }
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            if (!patch(lines, updates)) {
                return false;
            }
            writeAtomically(file, lines);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 目标文件不存在时，按 GT 缓存文件的格式新建一个只含本 mod 键值的文件。 */
    private static void create(File file, Map<String, String> updates) throws IOException {
        List<String> lines = new ArrayList<>(updates.size() + 4);
        lines.add("# Configuration file");
        lines.add("");
        lines.add(SECTION_HEADER);
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            lines.add("    S:" + entry.getKey() + "=" + entry.getValue());
        }
        lines.add("}");
        writeAtomically(file, lines);
    }

    private static Map<String, String> collectUpdates(Map<String, String> lang, int metaTileId) {
        Map<String, String> updates = new LinkedHashMap<>();
        String name = lang.get(NAME_KEY);
        if (name != null) {
            updates.put(NAME_KEY, name);
        }
        for (int i = 0; i < DESC_LINES; i++) {
            String desc = lang.get(DESC_KEY_PREFIX + i);
            if (desc != null) {
                updates.put(String.format("TileEntity_DESCRIPTION_%05d_Index_%02d", metaTileId, i), desc);
            }
        }
        return updates;
    }

    /** @return 是否有行被替换或插入 */
    private static boolean patch(List<String> lines, Map<String, String> updates) {
        Map<String, String> pending = new LinkedHashMap<>(updates);
        boolean changed = false;
        for (int i = 0; i < lines.size() && !pending.isEmpty(); i++) {
            String key = propertyKey(lines.get(i));
            if (key == null || !pending.containsKey(key)) {
                continue;
            }
            String replaced = indentOf(lines.get(i)) + "S:" + key + "=" + pending.remove(key);
            if (!replaced.equals(lines.get(i))) {
                lines.set(i, replaced);
                changed = true;
            }
        }
        if (pending.isEmpty()) {
            return changed;
        }
        for (int i = 0; i < lines.size(); i++) {
            if (
                !SECTION_HEADER.equalsIgnoreCase(
                    lines.get(i)
                        .trim())
            ) {
                continue;
            }
            List<String> inserted = new ArrayList<>(pending.size());
            for (Map.Entry<String, String> entry : pending.entrySet()) {
                inserted.add("    S:" + entry.getKey() + "=" + entry.getValue());
            }
            lines.addAll(i + 1, inserted);
            return true;
        }
        return changed;
    }

    /** 保留原行缩进，避免每次启动都因缩进差异重写整份 5MB 语言文件。 */
    private static String indentOf(String line) {
        int end = 0;
        while (end < line.length() && (line.charAt(end) == ' ' || line.charAt(end) == '\t')) {
            end++;
        }
        return line.substring(0, end);
    }

    /** 取配置文件行的键（去掉类型前缀），非属性行返回 null。 */
    private static String propertyKey(String line) {
        String trimmed = line.trim();
        if (trimmed.length() < 3 || trimmed.charAt(1) != ':') {
            return null;
        }
        int equals = trimmed.indexOf('=');
        if (equals < 0) {
            return null;
        }
        return trimmed.substring(2, equals);
    }

    private static void writeAtomically(File file, List<String> lines) throws IOException {
        Path target = file.toPath();
        Path temp = new File(file.getParentFile(), file.getName() + ".dualoutputhatchme.tmp").toPath();
        Files.write(temp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Map<String, String> load(String lang) {
        Map<String, String> map = new LinkedHashMap<>();
        InputStream stream = LangFixer.class.getResourceAsStream(LANG_RESOURCE_DIR + lang + ".lang");
        if (stream == null) {
            return map;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int split = line.indexOf('=');
                if (split > 0) {
                    map.put(
                        line.substring(0, split)
                            .trim(),
                        line.substring(split + 1)
                            .trim());
                }
            }
        } catch (IOException ignored) {
            // 读不到就保持原样
        }
        return map;
    }
}
