package com.gtnh.dualoutputhatchme.mixins;

import java.util.ArrayList;

import net.minecraftforge.fluids.FluidStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnh.dualoutputhatchme.api.IDualOutputHatch;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchOutput;
import gregtech.api.metatileentity.implementations.MTEHatchOutputBus;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.common.tileentities.machines.ItemBusShadowHatch;
import gregtech.common.tileentities.machines.MTEDualOutputHatchME;

/**
 * 输出总成(ME) 的归类挂载、优先级与物品分发兜底。
 *
 * 主仓继承 MTEHatchOutputME（流体原生），被归类进 mOutputHatches；
 * 本 Mixin 把它的影子输出总线(ME) 挂进 mOutputBusses，使机器的物品分发
 * （dumpItem -> storePartial）也作用到主仓的无限物品缓存。
 *
 * 归类侧在四个方法上收口，缺一不可：
 * - addOutputHatchToMachineList / addOutputBusToMachineList：结构件的原子归类方法，
 * StructureLib 的 HatchElement 走这里；
 * - addToMachineList：内联 instanceof 互斥链，直接 mOutputHatches.add，不经过上面两个；
 * - dumpItem：分发前再兜一次，覆盖 GT++/TecTech 等自建归类方法的基类。
 *
 * 另外两处：
 * - canDumpItemToME：主仓物品缓存无限，需要让并行/防溢出逻辑知道「物品总能存下」；
 * - addOutput(FluidStack)：把主仓提到 mOutputHatches 队首，流体优先给本仓。
 *
 * 优先级原理：GT 的分发就是按列表顺序取第一个能接收的仓
 * （dumpItem 遍历 mOutputBusses、dumpFluid 遍历 mOutputHatches），
 * 因此把本仓排到队首即可优先于普通输出总线(ME)/输出仓(ME)。
 */
@Mixin(value = MTEMultiBlockBase.class, remap = false)
public abstract class MixinMTEMultiBlockBase {

    @Shadow(remap = false)
    public ArrayList<MTEHatchOutputBus> mOutputBusses;

    @Shadow(remap = false)
    public ArrayList<MTEHatchOutput> mOutputHatches;

    @Unique
    private boolean dualoutputhatchme$orderReady;
    @Unique
    private boolean dualoutputhatchme$hasDualOutputs;
    @Unique
    private int dualoutputhatchme$busCount;
    @Unique
    private int dualoutputhatchme$hatchCount;
    @Unique
    private long dualoutputhatchme$orderRevision;

    private void dualoutputhatchme$attachShadow(IMetaTileEntity metaTileEntity) {
        if (!(metaTileEntity instanceof IDualOutputHatch)) {
            return;
        }
        MTEHatchOutputBus shadow = ((IDualOutputHatch) metaTileEntity).getItemBusShadow();
        if (shadow == null) {
            return;
        }
        // 幂等：已在列表中则不动位置。
        // 必须「追加」而不是插到队首：mOutputHatches 按归类顺序追加，
        // 影子总线若每次插 0，多台输出总成时 mOutputBusses 会与输出仓列表顺序相反——
        // 同优先级下稳定排序不重排，就会出现一台只收物品、另一台只收流体的分裂。
        // 影子优先 + 按优先级排序由分发前的 sortOutputBusses 统一保证。
        if (this.mOutputBusses.contains(shadow)) {
            return;
        }
        this.mOutputBusses.add(shadow);
        this.dualoutputhatchme$orderReady = false;
    }

    private boolean dualoutputhatchme$hasDualOutputHatch() {
        for (MTEHatchOutput hatch : this.mOutputHatches) {
            if (hatch instanceof IDualOutputHatch && hatch.isValid()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 物品分发顺序：影子总线在前（按主仓优先级降序），其余总线保持原有相对顺序。
     * 仅在结构列表或优先级发生变化时稳定排序。
     * 多个输出总成同机时，物品优先进优先级高的输出总成。
     */
    private void dualoutputhatchme$sortOutputBusses() {
        if (this.mOutputBusses.size() < 2) {
            return;
        }
        this.mOutputBusses.sort((a, b) -> {
            boolean aDual = a instanceof ItemBusShadowHatch;
            boolean bDual = b instanceof ItemBusShadowHatch;
            if (aDual && bDual) {
                return Integer.compare(
                    ((ItemBusShadowHatch) b).getOwner()
                        .getPriority(),
                    ((ItemBusShadowHatch) a).getOwner()
                        .getPriority());
            }
            if (aDual != bDual) {
                return aDual ? -1 : 1;
            }
            return 0;
        });
    }

    /**
     * 流体分发顺序：输出总成在前（按优先级降序），其余输出仓保持原有相对顺序。
     */
    private void dualoutputhatchme$sortOutputHatches() {
        if (this.mOutputHatches.size() < 2) {
            return;
        }
        this.mOutputHatches.sort((a, b) -> {
            boolean aDual = a instanceof IDualOutputHatch;
            boolean bDual = b instanceof IDualOutputHatch;
            if (aDual && bDual) {
                return Integer.compare(((IDualOutputHatch) b).getPriority(), ((IDualOutputHatch) a).getPriority());
            }
            if (aDual != bDual) {
                return aDual ? -1 : 1;
            }
            return 0;
        });
    }

    /**
     * 把当前已被归类为输出仓的主仓的影子输出总线补挂进 mOutputBusses。
     * 用于兜住那些重写了自己的 addToMachineList/归类方法的第三方多方块基类
     * （例如 GT++ 的 GTPPMultiBlockBase、TecTech 的 TTMultiblockBase）。
     */
    private void dualoutputhatchme$attachAllShadows() {
        for (MTEHatchOutput hatch : this.mOutputHatches) {
            if (hatch instanceof IDualOutputHatch) {
                this.dualoutputhatchme$attachShadow(hatch);
            }
        }
    }

    @Unique
    private void dualoutputhatchme$ensureOutputOrder() {
        long revision = MTEDualOutputHatchME.getOutputOrderRevision();
        if (
            this.dualoutputhatchme$orderReady && this.dualoutputhatchme$busCount == this.mOutputBusses.size()
                && this.dualoutputhatchme$hatchCount == this.mOutputHatches.size()
                && (!this.dualoutputhatchme$hasDualOutputs || this.dualoutputhatchme$orderRevision == revision)
        ) {
            return;
        }
        this.dualoutputhatchme$attachAllShadows();
        this.dualoutputhatchme$hasDualOutputs = false;
        for (MTEHatchOutputBus bus : this.mOutputBusses) {
            if (bus instanceof ItemBusShadowHatch) {
                this.dualoutputhatchme$hasDualOutputs = true;
                break;
            }
        }
        if (this.dualoutputhatchme$hasDualOutputs) {
            this.dualoutputhatchme$sortOutputBusses();
            this.dualoutputhatchme$sortOutputHatches();
        }
        this.dualoutputhatchme$busCount = this.mOutputBusses.size();
        this.dualoutputhatchme$hatchCount = this.mOutputHatches.size();
        this.dualoutputhatchme$orderRevision = revision;
        this.dualoutputhatchme$orderReady = true;
    }

    @Inject(method = "clearHatches", at = @At("HEAD"), remap = false)
    private void dualoutputhatchme$invalidateOutputOrder(CallbackInfo ci) {
        this.dualoutputhatchme$orderReady = false;
    }

    /**
     * 流体侧收口：主仓被加入 mOutputHatches 后，把影子输出总线挂进 mOutputBusses。
     */
    @Inject(method = "addOutputHatchToMachineList", at = @At("RETURN"), remap = false)
    private void dualoutputhatchme$attachShadowOnOutputHatch(IGregTechTileEntity aTileEntity, int aBaseCasingIndex,
        CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && aTileEntity != null && aTileEntity.getMetaTileEntity() != null) {
            this.dualoutputhatchme$attachShadow(aTileEntity.getMetaTileEntity());
        }
    }

    /**
     * 物品侧收口：若某条路径把主仓当作输出总线直接归类（理论上不会，因为主仓不是
     * MTEHatchOutputBus），同样补挂一次，保证任何路径下影子仓都在 mOutputBusses 中。
     */
    @Inject(method = "addOutputBusToMachineList", at = @At("RETURN"), remap = false)
    private void dualoutputhatchme$attachShadowOnOutputBus(IGregTechTileEntity aTileEntity, int aBaseCasingIndex,
        CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && aTileEntity != null && aTileEntity.getMetaTileEntity() != null) {
            this.dualoutputhatchme$attachShadow(aTileEntity.getMetaTileEntity());
        }
    }

    /**
     * 兜底收口：addToMachineList 内部是内联的 instanceof 互斥链（直接 mOutputHatches.add），
     * 不经过上面两个原子方法，因此凡是走 addToMachineList 归类输出仓的多方块，
     * 必须在这里补挂影子输出总线。
     */
    @Inject(method = "addToMachineList", at = @At("RETURN"), remap = false)
    private void dualoutputhatchme$attachShadowOnMachineList(IGregTechTileEntity aTileEntity, int aBaseCasingIndex,
        CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && aTileEntity != null && aTileEntity.getMetaTileEntity() != null) {
            this.dualoutputhatchme$attachShadow(aTileEntity.getMetaTileEntity());
        }
    }

    /**
     * 分发兜底：无论归类路径如何，只要机器往 mOutputBusses 分发物品，
     * 就保证影子输出总线在位并按优先级排好序
     * （GT 本体、GT++、TecTech 的物品分发最终都走这里）。
     */
    @Inject(method = "dumpItem", at = @At("HEAD"), remap = false)
    private void dualoutputhatchme$ensureShadowBeforeDump(java.util.List<MTEHatchOutputBus> outputBuses,
        net.minecraft.item.ItemStack itemStack, boolean restrictiveBusesOnly, boolean simulate,
        CallbackInfoReturnable<Boolean> cir) {
        this.dualoutputhatchme$ensureOutputOrder();
    }

    /**
     * 流体分发前把 mOutputHatches 按优先级排好，保证流体优先进优先级高的输出总成。
     */
    @Inject(method = "addOutput(Lnet/minecraftforge/fluids/FluidStack;)Z", at = @At("HEAD"), remap = false)
    private void dualoutputhatchme$prioritizeFluidBeforeDump(FluidStack aLiquid, CallbackInfoReturnable<Boolean> cir) {
        this.dualoutputhatchme$ensureOutputOrder();
    }

    /**
     * 并行/防溢出兜底：主仓物品缓存无限，等同于 ME 输出总线可无限接收。
     * 若不做这一步，某些第三方多方块会在首次分发前因「输出总线槽位为空」把并行算成 0，
     * 机器根本不运行，也就永远等不到上面的分发兜底。
     */
    @Inject(method = "canDumpItemToME", at = @At("RETURN"), cancellable = true, remap = false)
    private void dualoutputhatchme$allowItemDumpToME(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && this.dualoutputhatchme$hasDualOutputHatch()) {
            cir.setReturnValue(true);
        }
    }
}
