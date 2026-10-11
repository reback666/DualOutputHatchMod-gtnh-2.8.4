package com.gtnh.dualoutputhatchme.api;

import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fluids.FluidStack;

import com.gtnewhorizons.modularui.common.internal.network.NetworkUtils;

/**
 * 输出总成(ME) 缓存列表中的一项：要么是一种物品，要么是一种流体。
 *
 * 该对象由服务端构建、经 FakeSyncWidget.ListSyncer 同步到客户端，因此
 * equals/hashCode 只依赖同步字段——内容没变化时不会重复发包。
 */
public final class DualCacheEntry {

    public static final byte KIND_ITEM = 0;
    public static final byte KIND_FLUID = 1;

    public final byte kind;
    /** kind == KIND_ITEM 时非空；数量恒为 1，仅用于画图标。 */
    public final ItemStack item;
    /** kind == KIND_FLUID 时非空；数量恒为 1，仅用于画图标。 */
    public final FluidStack fluid;
    public final String name;
    public final long amount;

    private DualCacheEntry(byte kind, ItemStack item, FluidStack fluid, String name, long amount) {
        this.kind = kind;
        this.item = item;
        this.fluid = fluid;
        this.name = name;
        this.amount = amount;
    }

    public static DualCacheEntry ofItem(ItemStack stack, long amount) {
        ItemStack icon = stack.copy();
        icon.stackSize = 1;
        return new DualCacheEntry(KIND_ITEM, icon, null, stack.getDisplayName(), amount);
    }

    public static DualCacheEntry ofFluid(FluidStack stack, long amount) {
        FluidStack icon = stack.copy();
        icon.amount = 1;
        return new DualCacheEntry(KIND_FLUID, null, icon, stack.getLocalizedName(), amount);
    }

    public boolean isItem() {
        return this.kind == KIND_ITEM;
    }

    public void writeToBuffer(PacketBuffer buffer) {
        buffer.writeByte(this.kind);
        NetworkUtils.writeItemStack(buffer, this.item);
        NetworkUtils.writeFluidStack(buffer, this.fluid);
        NetworkUtils.writeStringSafe(buffer, this.name);
        buffer.writeLong(this.amount);
    }

    public static DualCacheEntry readFromBuffer(PacketBuffer buffer) {
        byte kind = buffer.readByte();
        ItemStack item = NetworkUtils.readItemStack(buffer);
        FluidStack fluid = NetworkUtils.readFluidStack(buffer);
        String name = NetworkUtils.readStringSafe(buffer);
        long amount = buffer.readLong();
        return new DualCacheEntry(kind, item, fluid, name, amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DualCacheEntry)) {
            return false;
        }
        DualCacheEntry other = (DualCacheEntry) o;
        if (this.kind != other.kind || this.amount != other.amount) {
            return false;
        }
        if (!this.name.equals(other.name)) {
            return false;
        }
        if (this.isItem()) {
            return ItemStack.areItemStacksEqual(this.item, other.item);
        }
        return this.fluid.isFluidEqual(other.fluid);
    }

    @Override
    public int hashCode() {
        int result = this.kind * 31 + this.name.hashCode();
        result = result * 31 + Long.hashCode(this.amount);
        return result;
    }
}
