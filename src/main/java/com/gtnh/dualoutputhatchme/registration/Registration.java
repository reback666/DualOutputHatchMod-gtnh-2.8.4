package com.gtnh.dualoutputhatchme.registration;

import net.minecraft.item.ItemStack;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.GTValues;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Mods;
import gregtech.api.enums.TierEU;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.util.GTModHandler;
import gregtech.api.util.GTUtility;
import gregtech.common.tileentities.machines.MTEDualOutputHatchME;

public class Registration {

    /**
     * GTNH 第三方 MTE ID 段（17000-29999）中的一个空闲位置。
     * 若与其他 mod 冲突，改这个常量即可。
     */
    public static final int MTE_ID = 25300;

    public static ItemStack DUAL_OUTPUT_HATCH_ME;

    public static void registerMetaTileEntities() {
        // 防撞车自检：GT 的 MTE 段是共享的，若已被别的 mod 占用，注册会静默失败或行为错乱。
        if (MTE_ID < GregTechAPI.METATILEENTITIES.length && GregTechAPI.METATILEENTITIES[MTE_ID] != null) {
            gregtech.GTMod.GT_FML_LOGGER.error(
                "MTE ID {} is already occupied by {}! Dual Output Hatch (ME) registration will conflict.",
                MTE_ID,
                GregTechAPI.METATILEENTITIES[MTE_ID].getClass()
                    .getName());
        }
        // 第三参数是 aNameRegional，GT 约定它同时充当本地化键
        // (gt.blockmachines.<aNameRegional>.name)，不能直接传英文显示文本。
        DUAL_OUTPUT_HATCH_ME = new MTEDualOutputHatchME(MTE_ID, "hatch.dual_output.me", "hatch.dual_output.me")
            .getStackForm(1L);
    }

    public static void registerRecipes() {
        // 组装机(HV)：输出总线(ME) + 输出仓(ME) + AE2 印刷电路类材料x4 + 集成电路(1) -> 输出总成(ME)
        GTValues.RA.stdBuilder()
            .itemInputs(
                ItemList.Hatch_Output_Bus_ME.get(1L, new Object[0]),
                ItemList.Hatch_Output_ME.get(1L, new Object[0]),
                GTModHandler.getModItem(Mods.AppliedEnergistics2.ID, "item.ItemMultiMaterial", 4L, 30),
                GTUtility.getIntegratedCircuit(1))
            .itemOutputs(DUAL_OUTPUT_HATCH_ME.copy())
            .duration(300)
            .eut(TierEU.RECIPE_HV)
            .addTo(RecipeMaps.assemblerRecipes);
    }
}
