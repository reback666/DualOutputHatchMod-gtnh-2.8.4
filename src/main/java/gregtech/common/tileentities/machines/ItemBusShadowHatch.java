package gregtech.common.tileentities.machines;

import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.me.helpers.AENetworkProxy;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;

/**
 * 影子输出总线(ME)：不放在世界里、没有真实方块，只作为 Java 对象被 Mixin 挂进多方块的
 * mOutputBusses，使输出总成(ME)同时被机器识别为输出总线。
 *
 * 为什么继承 ME 输出总线而不是普通输出总线：
 * MTEMultiBlockBase.getItemOutputSlots / canDumpItemToME 对 MTEHatchOutputBusME 走的是
 * 「无限缓存」快路径（不读仓内槽位），既不会因为影子仓没有真实库存算错并行，
 * 也能保证机器不会因防溢出逻辑被卡住。
 *
 * 为什么必须挂 dummy 基座方块实体：
 * 影子仓会被 GT 本体及第三方多方块当作真实仓遍历（刷新贴图、getStoredOutputs、
 * 并行/防溢出计算）。MetaTileEntity 的 (String,...) 构造链不会创建基座方块实体，
 * 而 getBaseMetaTileEntity() 是 final 无法覆盖，所以这里手工挂一个 dummy 基座
 * （与注册 MTE 时 GregTechAPI.constructBaseMetaTileEntity() 的做法一致）。
 */
public class ItemBusShadowHatch extends MTEHatchOutputBusME {

    private final MTEDualOutputHatchME owner;

    public ItemBusShadowHatch(MTEDualOutputHatchME owner) {
        // 影子仓不注册、不放置，名字仅用于日志可读性
        super("hatch.dual_output.me.shadow", 3, new String[0], new ITexture[0][][]);
        this.owner = owner;
        this.setBaseMetaTileEntity(GregTechAPI.constructBaseMetaTileEntity());
        IGregTechTileEntity ownerBase = owner.getBaseMetaTileEntity();
        if (ownerBase != null) {
            this.getBaseMetaTileEntity()
                .setMetaTileID((short) ownerBase.getMetaTileID());
        }
    }

    @Override
    public MetaTileEntity newMetaEntity(IGregTechTileEntity aTileEntity) {
        // 影子仓不放置于世界，理论上不会被调用；返回自身兜底
        return this;
    }

    /** Mixin 排序用：取影子仓所属主仓的输出优先级。 */
    public MTEDualOutputHatchME getOwner() {
        return this.owner;
    }

    // ---------------- 物品分发转发 ----------------

    @Override
    public boolean storePartial(ItemStack aStack, boolean simulate) {
        return this.owner.storePartial(aStack, simulate);
    }

    @Override
    public boolean isLocked() {
        return this.owner.isItemLocked();
    }

    @Override
    public List<ItemStack> getLockedItems() {
        return this.owner.getLockedItemList();
    }

    @Override
    public boolean canAcceptItem() {
        // 主仓物品缓存为无限，永远可接收
        return true;
    }

    // ---------------- AE 代理：与主仓共用，不额外占信道 ----------------

    @Override
    public AENetworkProxy getProxy() {
        return this.owner.getNetworkProxy();
    }

    // ---------------- 有效性：跟随主仓，防止被 filterValidMTEs 清理 ----------------

    @Override
    public boolean isValid() {
        return this.owner.isValid();
    }

    // ---------------- 副作用屏蔽 ----------------

    @Override
    public boolean onRightclick(IGregTechTileEntity aBaseMetaTileEntity, EntityPlayer aPlayer) {
        return false;
    }

    @Override
    public boolean allowPullStack(IGregTechTileEntity aBaseMetaTileEntity, int aIndex, ForgeDirection side,
        ItemStack aStack) {
        return false;
    }

    @Override
    public boolean allowPutStack(IGregTechTileEntity aBaseMetaTileEntity, int aIndex, ForgeDirection side,
        ItemStack aStack) {
        return false;
    }

    @Override
    public boolean pushOutputInventory() {
        return false;
    }

    @Override
    public boolean acceptsItemLock() {
        return false;
    }

    @Override
    public void onPostTick(IGregTechTileEntity aBaseMetaTileEntity, long aTick) {
        // 影子仓自身不 tick：物品上传由主仓负责
    }

    @Override
    public void saveNBTData(NBTTagCompound aNBT) {}

    @Override
    public void loadNBTData(NBTTagCompound aNBT) {}

    @Override
    public boolean isGivingInformation() {
        return false;
    }

    @Override
    public String[] getInfoData() {
        return new String[0];
    }
}
