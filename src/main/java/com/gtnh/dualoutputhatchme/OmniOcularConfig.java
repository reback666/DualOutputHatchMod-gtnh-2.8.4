package com.gtnh.dualoutputhatchme;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 在 OmniOcular 的 GregTech 配置里补上输出总成(ME) 的「数据存储-物品 / 数据存储-流体」两段。
 *
 * 用户看到的「数据存储-物品 / 数据存储-流体」来自 OmniOcular 的 GregTech5U-GTNH.xml，
 * 原版两段分别硬编码 mID==2710（输出总线(ME)）与 mID==2713（输出仓(ME)），
 * 因此本仓（mID 25300）看不到。这里把原版两段原样复制、只改 mID 后插回原位置，
 * 显示格式与位置都与原版完全一致。
 *
 * 只做「不存在才插入」的纯追加修改，并在插入处留标记，避免重复插入。
 */
public final class OmniOcularConfig {

    private static final String GT_CONFIG_FILE = "GregTech5U-GTNH.xml";
    private static final String MARKER = "dualoutputhatchme";
    private static final String ITEM_ANCHOR = "nbt['mID']==2710&&nbt['cachedItems']";
    private static final String FLUID_ANCHOR = "nbt['mID']==2713&&nbt['cachedFluids']";
    private static final String ITEM_MID = "nbt['mID']==2710";
    private static final String FLUID_MID = "nbt['mID']==2713";
    private static final String LINE_TAG = "<line displayname=";
    private static final String LINE_CLOSE = "</line>";

    private OmniOcularConfig() {}

    /** @return 是否已经就位（已插入过或本次插入成功）。 */
    public static boolean patch(File configDirectory, int metaTileId) {
        File file = new File(new File(configDirectory, "OmniOcular"), GT_CONFIG_FILE);
        if (!file.isFile()) {
            return false;
        }
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (text.contains(MARKER)) {
                return true;
            }
            String itemBlock = extractBlock(text, ITEM_ANCHOR);
            String fluidBlock = extractBlock(text, FLUID_ANCHOR);
            if (itemBlock == null || fluidBlock == null) {
                return false;
            }
            String mid = "nbt['mID']==" + metaTileId;
            String myItem = itemBlock.replace(ITEM_MID, mid);
            String myFluid = fluidBlock.replace(FLUID_MID, mid);
            String newline = text.contains("\r\n") ? "\r\n" : "\n";
            int fluidBody = text.indexOf(FLUID_ANCHOR);
            int fluidClose = text.indexOf(LINE_CLOSE, fluidBody) + LINE_CLOSE.length();
            int eol = text.indexOf('\n', fluidClose);
            int insertAt = eol < 0 ? text.length() : eol + 1;
            StringBuilder inserted = new StringBuilder();
            inserted.append("<!-- ")
                .append(MARKER)
                .append(": data-storage lines for MTE ")
                .append(metaTileId)
                .append(", auto-generated, do not edit -->")
                .append(newline)
                .append(myItem)
                .append(newline)
                .append(myFluid)
                .append(newline);
            String updated = text.substring(0, insertAt) + inserted + text.substring(insertAt);
            Files.write(file.toPath(), updated.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String extractBlock(String text, String anchor) {
        int body = text.indexOf(anchor);
        if (body < 0) {
            return null;
        }
        int tagStart = text.lastIndexOf(LINE_TAG, body);
        if (tagStart < 0) {
            return null;
        }
        int lineStart = text.lastIndexOf('\n', tagStart);
        lineStart = lineStart < 0 ? 0 : lineStart + 1;
        int close = text.indexOf(LINE_CLOSE, body);
        if (close < 0) {
            return null;
        }
        return text.substring(lineStart, close + LINE_CLOSE.length());
    }
}
