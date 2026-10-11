package com.gtnh.dualoutputhatchme.api;

import java.util.List;

import javax.annotation.Nonnull;

import net.minecraft.item.ItemStack;

import appeng.me.helpers.AENetworkProxy;

/**
 * 标记接口：既当输出仓（流体）又当输出总线（物品）使用的 ME 仓。
 * 实现类继承输出仓(ME)（流体原生工作）；Mixin 通过此接口让
 * MTEMultiBlockBase.addOutput(ItemStack)/addOutputPartial 把物品也喂给它，
 * 并把本仓排到分发队列最前，使物品与流体都优先进入本仓。
 */
public interface IDualOutputHatch {

    /**
     * 接收一个物品输出（语义同 MTEHatchOutputBus.storePartial：成功时清零 stack.stackSize）。
     */
    boolean storePartial(@Nonnull ItemStack stack, boolean simulate);

    /**
     * 是否处于物品过滤（锁定）状态。存储元件槽已移除，恒为 false。
     */
    boolean isItemLocked();

    /**
     * 物品过滤器内容（每个元素数量为 1）。存储元件槽已移除，恒为空表。
     */
    List<ItemStack> getLockedItemList();

    /**
     * 本仓的 AE 网络代理，影子仓与主仓共用同一个代理（不会额外占用信道）。
     */
    AENetworkProxy getNetworkProxy();

    /**
     * 取得影子输出总线：它会被挂进多方块的 mOutputBusses，使本仓同时被识别为输出总线。
     * 影子仓不占方块、不占世界，所有物品操作转发回本仓的无限物品缓存。
     */
    gregtech.api.metatileentity.implementations.MTEHatchOutputBus getItemBusShadow();

    /**
     * 输出优先级：同一多方块里有多个输出总成时，物品与流体优先进优先级高的。
     * 由 MixinMTEMultiBlockBase 在分发前对 mOutputBusses/mOutputHatches 按此值降序稳定排序。
     */
    int getPriority();
}
