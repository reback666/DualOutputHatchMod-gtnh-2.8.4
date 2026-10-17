package gregtech.common.tileentities.machines;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidContainerItem;
import net.minecraftforge.oredict.OreDictionary;

import org.lwjgl.input.Keyboard;

import com.gtnewhorizons.modularui.api.drawable.FluidDrawable;
import com.gtnewhorizons.modularui.api.drawable.IDrawable;
import com.gtnewhorizons.modularui.api.drawable.ItemDrawable;
import com.gtnewhorizons.modularui.api.drawable.shapes.Rectangle;
import com.gtnewhorizons.modularui.api.math.Alignment;
import com.gtnewhorizons.modularui.api.math.Color;
import com.gtnewhorizons.modularui.api.math.Pos2d;
import com.gtnewhorizons.modularui.api.screen.Cursor;
import com.gtnewhorizons.modularui.api.screen.ModularWindow;
import com.gtnewhorizons.modularui.api.screen.UIBuildContext;
import com.gtnewhorizons.modularui.api.widget.IHasStackUnderMouse;
import com.gtnewhorizons.modularui.api.widget.Interactable;
import com.gtnewhorizons.modularui.api.widget.Widget;
import com.gtnewhorizons.modularui.common.widget.ButtonWidget;
import com.gtnewhorizons.modularui.common.widget.DrawableWidget;
import com.gtnewhorizons.modularui.common.widget.FakeSyncWidget;
import com.gtnewhorizons.modularui.common.widget.TextWidget;
import com.gtnewhorizons.modularui.common.widget.textfield.NumericWidget;
import com.gtnewhorizons.modularui.common.widget.textfield.TextFieldWidget;
import com.gtnh.dualoutputhatchme.DualOutputHatchMod;
import com.gtnh.dualoutputhatchme.api.CacheFlushQueue;
import com.gtnh.dualoutputhatchme.api.DualCacheEntry;
import com.gtnh.dualoutputhatchme.api.IDualOutputHatch;
import com.gtnh.dualoutputhatchme.registration.Registration;

import appeng.api.AEApi;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.MachineSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import appeng.core.localization.WailaText;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.util.Platform;
import codechicken.nei.ItemPanels;
import codechicken.nei.NEIClientConfig;
import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.GuiUsageRecipe;
import codechicken.nei.util.NEIKeyboardUtils;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregtech.api.gui.modularui.GTUITextures;
import gregtech.api.interfaces.modularui.IAddGregtechLogo;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.util.GTUtility;
import mcp.mobius.waila.api.IWailaConfigHandler;
import mcp.mobius.waila.api.IWailaDataAccessor;

/**
 * 输出总成(ME)：同时被多方块识别为输出总线与输出仓的 ME 舱室。
 *
 * 流体输出原生工作（本类继承输出仓(ME)，进多方块的 mOutputHatches，fill/dumpFluid 原生走通），
 * 物品缓存移植自输出总线(ME)（itemCache + storePartial），接收由 MixinMTEMultiBlockBase
 * 在 addOutput(ItemStack)/addOutputPartial 中注入的影子总线完成。
 *
 * 与普通输出总线(ME)/输出仓(ME) 同用时本仓优先：影子总线被放在 mOutputBusses 队首、
 * 主仓被放在 mOutputHatches 队首（见 MixinMTEMultiBlockBase）。
 *
 * 本类放在 gregtech 包下，以便直接读写父类 MTEHatchOutputME 的包私有字段
 * （fluidCache / lastOutputTick / tickCounter 等）。
 */
public class MTEDualOutputHatchME extends MTEHatchOutputME implements IDualOutputHatch, IAddGregtechLogo {

    public static final String COPIED_DATA_IDENTIFIER = "dualOutputHatchME";

    /** 缓存冲入 ME 网络的默认间隔（tick）。 */
    public static final int DEFAULT_FLUSH_INTERVAL = 10;
    public static final int MIN_FLUSH_INTERVAL = 2;
    public static final int MAX_FLUSH_INTERVAL = 72000;

    private static final int MAX_FLUSH_OPERATIONS = 64;
    private static long outputOrderRevision = 0L;

    private static final String NBT_FLUSH_INTERVAL = "dualFlushInterval";

    private static final int GUI_WIDTH = 176;
    // 172（主界面）+ 83（玩家背包 76px + bindPlayerInventoryUI 的 7px 下边距），背包画在 y=172..248
    private static final int GUI_HEIGHT = 255;
    private static final int LIST_X = 7;
    private static final int LIST_Y = 26;
    private static final int LIST_WIDTH = 162;
    private static final int LIST_HEIGHT = 138;
    private static final int ROW_HEIGHT = 18;

    /** 界面列表的服务端快照重建间隔（tick），避免每 tick 重算整份缓存。 */
    private static final int SNAPSHOT_INTERVAL = 2;
    /** 界面列表最多同步多少项，防止极端缓存把同步包撑爆。 */
    private static final int SNAPSHOT_LIMIT = 400;

    /** 明细最多列出多少条，其余折叠为"还有 N 种未显示"。 */
    private static final int WAILA_MAX_STACKS = 10;

    /**
     * 缓存上限：物品与流体缓存都用 long 计数（AE2 的 IAEStack#setStackSize(long)），
     * 所以单种物品/流体的实际上限就是 long 最大值。
     */
    public static final long MAX_CACHE_AMOUNT = Long.MAX_VALUE;

    // ---------------- 物品侧（移植自 MTEHatchOutputBusME） ----------------

    final IItemList<IAEItemStack> itemCache = AEApi.instance()
        .storage()
        .createItemList();
    private BaseActionSource itemRequestSource = null;
    private ItemBusShadowHatch itemBusShadow = null;

    // ---------------- 冲缓存节奏 ----------------

    private long dualTickCounter = 0L;
    private long lastDualFlushTick = 0L;
    private int flushIntervalTicks = DEFAULT_FLUSH_INTERVAL;

    // ---------------- 优先级与过滤（设置页可调） ----------------

    private static final String NBT_PRIORITY = "dualPriority";
    private static final String NBT_ITEM_FILTER = "dualItemFilter";
    private static final String NBT_FLUID_FILTER = "dualFluidFilter";
    private static final String NBT_FILTER_MODE = "dualFilterMode";
    private static final String NBT_FILTER_ITEMS = "dualFilterItemMarks";
    private static final String NBT_FILTER_FLUIDS = "dualFilterFluidMarks";
    private static final int MAX_PRIORITY = 1000000;
    /** 过滤模式：标签（OreDictionary 文本）/ 具体（幻影槽标记物品与流体）。 */
    public static final int FILTER_TAG = 0;
    public static final int FILTER_SPECIFIC = 1;
    /** 具体过滤的标记槽位：物品 9 格、流体 4 格。 */
    public static final int FILTER_ITEM_SLOTS = 18;
    public static final int FILTER_FLUID_SLOTS = 18;

    /** 输出优先级：同一多方块里多个输出总成时，高的优先接收。 */
    private int outputPriority = 0;
    /** 过滤模式：0=标签过滤，1=具体过滤。 */
    private int filterMode = FILTER_TAG;
    /** 物品过滤原文：逗号分隔的 OreDictionary 词典名；空 = 接受全部物品（标签模式用）。 */
    private String itemFilterRaw = "";
    /** 流体过滤原文：逗号分隔的流体名；空 = 接受全部流体（标签模式用）。 */
    private String fluidFilterRaw = "";
    private List<String> itemFilterTags = Collections.emptyList();
    private List<String> fluidFilterNames = Collections.emptyList();
    /** 具体过滤标记：物品（item+meta，count 恒 1）与流体（按流体种类）。 */
    private final ItemStack[] filterItemMarks = new ItemStack[FILTER_ITEM_SLOTS];
    private final FluidStack[] filterFluidMarks = new FluidStack[FILTER_FLUID_SLOTS];

    private final CacheFlushQueue<IAEItemStack> itemFlushQueue = new CacheFlushQueue<>();
    private final CacheFlushQueue<IAEFluidStack> fluidFlushQueue = new CacheFlushQueue<>();

    // ---------------- 界面数据 ----------------

    // 悬停 tooltip 与按键集成要跨方法访问列表控件
    private CacheListWidget cacheList;

    /** 只有缓存变化且到达更新间隔时才重建，未打开窗口时不会求值。 */
    private List<DualCacheEntry> syncSnapshot = Collections.emptyList();
    private long syncSnapshotTick = -SNAPSHOT_INTERVAL;
    private boolean syncSnapshotDirty = true;
    /** 客户端收到的界面列表。 */
    private List<DualCacheEntry> clientCacheEntries = Collections.emptyList();

    public MTEDualOutputHatchME(int aID, String aName, String aNameRegional) {
        super(aID, aName, aNameRegional);
    }

    public MTEDualOutputHatchME(String aName, int aTier, String[] aDescription,
        gregtech.api.interfaces.ITexture[][][] aTextures) {
        super(aName, aTier, aDescription, aTextures);
    }

    @Override
    public MetaTileEntity newMetaEntity(IGregTechTileEntity aTileEntity) {
        return new MTEDualOutputHatchME(this.mName, this.mTier, this.mDescriptionArray, this.mTextures);
    }

    /**
     * 影子输出总线：被 Mixin 挂进多方块的 mOutputBusses，使本仓同时被识别为输出总线。
     */
    public ItemBusShadowHatch getItemBusShadow() {
        if (this.itemBusShadow == null) {
            this.itemBusShadow = new ItemBusShadowHatch(this);
        }
        return this.itemBusShadow;
    }

    // ---------------- 流体侧：容量恒可接受（无限缓存） ----------------

    @Override
    public boolean canAcceptFluid() {
        return true;
    }

    /** 流体过滤门：设置了流体过滤时，不匹配的流体一律拒收（返回 0 = 一滴不进）。 */
    @Override
    public int fill(FluidStack aFluid, boolean doFill) {
        if (!this.matchesFluidFilter(aFluid)) {
            return 0;
        }
        return super.fill(aFluid, doFill);
    }

    @Override
    public boolean canStoreFluid(FluidStack fluidStack) {
        if (!this.matchesFluidFilter(fluidStack)) {
            return false;
        }
        return super.canStoreFluid(fluidStack);
    }

    /** 父类 fill 经此接收流体，并将实际缓存条目加入上传队列。 */
    @Override
    public int tryFillAE(FluidStack aFluid) {
        if (!this.matchesFluidFilter(aFluid)) {
            return 0;
        }
        if (aFluid == null || aFluid.amount <= 0) {
            return 0;
        }
        IAEFluidStack accepted = AEApi.instance()
            .storage()
            .createFluidStack(aFluid);
        this.fluidCache.add(accepted);
        this.fluidFlushQueue.add(this.fluidCache.findPrecise(accepted));
        this.lastInputTick = this.tickCounter;
        this.syncSnapshotDirty = true;
        return aFluid.amount;
    }

    // ---------------- 物品接收（语义同 MTEHatchOutputBusME.storePartial） ----------------

    @Override
    public boolean storePartial(ItemStack stack, boolean simulate) {
        if (stack == null || stack.stackSize <= 0) {
            return false;
        }
        // 过滤不匹配时拒绝接收：多方块会把物品转给下一个输出总线，全被拒时物品留在机器里
        if (!this.matchesItemFilter(stack)) {
            return false;
        }
        if (!simulate) {
            IAEItemStack accepted = AEApi.instance()
                .storage()
                .createItemStack(stack);
            this.itemCache.add(accepted);
            this.itemFlushQueue.add(this.itemCache.findPrecise(accepted));
            this.syncSnapshotDirty = true;
        }
        stack.stackSize = 0;
        return true;
    }

    /** 存储元件槽已移除，物品过滤功能随之取消。 */
    @Override
    public boolean isItemLocked() {
        return false;
    }

    @Override
    public List<ItemStack> getLockedItemList() {
        return Collections.emptyList();
    }

    @Override
    public appeng.me.helpers.AENetworkProxy getNetworkProxy() {
        return this.getProxy();
    }

    private BaseActionSource getItemRequest() {
        if (this.itemRequestSource == null) {
            this.itemRequestSource = new MachineSource(
                (appeng.api.networking.security.IActionHost) this.getBaseMetaTileEntity());
        }
        return this.itemRequestSource;
    }

    // ---------------- Tick：物品锁定逻辑已移除，只保留统一冲缓存 ----------------

    @Override
    public void onPostTick(IGregTechTileEntity aBaseMetaTileEntity, long aTick) {
        if (aBaseMetaTileEntity.isServerSide()) {
            this.dualTickCounter = aTick;
            if (aTick >= this.lastDualFlushTick + this.flushIntervalTicks) {
                this.lastDualFlushTick = aTick;
                this.flushCache();
            }
            // 抑制父类自己的 40 tick 流体 flush：物品与流体已在本方法内一起冲，
            // 不重复取 storage、不重复遍历缓存。
            this.lastOutputTick = aTick;
        }
        super.onPostTick(aBaseMetaTileEntity, aTick);
    }

    @Override
    public boolean onRightclick(IGregTechTileEntity aBaseMetaTileEntity, EntityPlayer aPlayer) {
        return super.onRightclick(aBaseMetaTileEntity, aPlayer);
    }

    /**
     * 共用网络查询与总预算，未完成的条目留在轮转队列中，不依赖新产出来唤醒。
     */
    private void flushCache() {
        boolean hasItems = !this.itemFlushQueue.isEmpty();
        boolean hasFluids = !this.fluidFlushQueue.isEmpty();
        if (!hasItems && !hasFluids) {
            return;
        }
        AENetworkProxy proxy = this.getProxy();
        if (proxy == null || !proxy.isActive()) {
            return;
        }
        try {
            BaseActionSource request = this.getItemRequest();
            IStorageGrid storage = proxy.getStorage();
            IEnergyGrid energy = proxy.getEnergy();
            Runnable onChanged = () -> this.syncSnapshotDirty = true;
            int used = 0;
            if (hasItems) {
                IMEMonitor<IAEItemStack> inventory = storage.getItemInventory();
                used = this.itemFlushQueue.flush(
                    hasFluids ? MAX_FLUSH_OPERATIONS / 2 : MAX_FLUSH_OPERATIONS,
                    stack -> Platform.poweredInsert(energy, inventory, stack, request),
                    onChanged);
            }
            if (hasFluids) {
                IMEMonitor<IAEFluidStack> inventory = storage.getFluidInventory();
                this.fluidFlushQueue.flush(
                    MAX_FLUSH_OPERATIONS - used,
                    stack -> MTEHatchOutputME.fluidAEInsert(energy, inventory, stack, request),
                    onChanged);
            }
        } catch (GridAccessException e) {
            // ME 网络不可达，缓存保留
        }
    }

    // ---------------- 冲缓存间隔（界面可调） ----------------

    public int getFlushIntervalTicks() {
        return this.flushIntervalTicks;
    }

    public void setFlushIntervalTicks(int ticks) {
        int clamped = Math.max(MIN_FLUSH_INTERVAL, Math.min(MAX_FLUSH_INTERVAL, ticks));
        if (clamped != this.flushIntervalTicks) {
            this.flushIntervalTicks = clamped;
            this.markDirty();
        }
    }

    // ---------------- 优先级与过滤（设置页可调） ----------------

    @Override
    public int getPriority() {
        return this.outputPriority;
    }

    public static long getOutputOrderRevision() {
        return outputOrderRevision;
    }

    public void setOutputPriority(int priority) {
        int clamped = Math.max(0, Math.min(MAX_PRIORITY, priority));
        if (clamped != this.outputPriority) {
            this.outputPriority = clamped;
            outputOrderRevision++;
            this.markDirty();
        }
    }

    public String getItemFilterRaw() {
        return this.itemFilterRaw;
    }

    public void setItemFilterRaw(String raw) {
        String cleaned = raw == null ? "" : raw.trim();
        if (!cleaned.equals(this.itemFilterRaw)) {
            this.itemFilterRaw = cleaned;
            this.itemFilterTags = parseFilterList(cleaned);
            this.syncSnapshotDirty = true;
            this.markDirty();
        }
    }

    public String getFluidFilterRaw() {
        return this.fluidFilterRaw;
    }

    public void setFluidFilterRaw(String raw) {
        String cleaned = raw == null ? "" : raw.trim();
        if (!cleaned.equals(this.fluidFilterRaw)) {
            this.fluidFilterRaw = cleaned;
            this.fluidFilterNames = parseFilterList(cleaned);
            this.syncSnapshotDirty = true;
            this.markDirty();
        }
    }

    public int getFilterMode() {
        return this.filterMode;
    }

    public void setFilterMode(int mode) {
        int clamped = mode == FILTER_SPECIFIC ? FILTER_SPECIFIC : FILTER_TAG;
        if (clamped != this.filterMode) {
            this.filterMode = clamped;
            this.markDirty();
        }
    }

    /** 具体过滤：标记/清除一个物品槽（左键放光标物品副本，右键清空）。 */
    public void setFilterItemMark(int slot, ItemStack mark) {
        if (slot < 0 || slot >= FILTER_ITEM_SLOTS) {
            return;
        }
        this.filterItemMarks[slot] = mark == null || mark.getItem() == null ? null : mark.copy();
        this.markDirty();
    }

    /** 具体过滤：标记/清除一个流体槽（按流体种类，不记数量）。 */
    public void setFilterFluidMark(int slot, FluidStack fluid) {
        if (slot < 0 || slot >= FILTER_FLUID_SLOTS) {
            return;
        }
        this.filterFluidMarks[slot] = fluid == null || fluid.getFluid() == null ? null : fluid.copy();
        this.markDirty();
    }

    /** 逗号分隔 -> 去重去空白的标签列表。 */
    private static List<String> parseFilterList(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> tags = new ArrayList<>();
        for (String part : raw.split(",")) {
            String tag = part.trim();
            if (!tag.isEmpty() && !tags.contains(tag)) {
                tags.add(tag);
            }
        }
        return tags;
    }

    /** 物品过滤：未设置 = 全部接受；设置了则物品必须带其中一个词典名。 */
    private boolean matchesItemFilter(ItemStack stack) {
        if (this.filterMode == FILTER_SPECIFIC) {
            // 具体过滤：标记全空 = 接受全部；否则须与某个标记物品同 item+meta
            boolean any = false;
            for (ItemStack mark : this.filterItemMarks) {
                if (mark == null) {
                    continue;
                }
                any = true;
                if (mark.isItemEqual(stack)) {
                    return true;
                }
            }
            return !any;
        }
        if (this.itemFilterTags.isEmpty()) {
            return true;
        }
        if (stack == null || stack.getItem() == null) {
            return false;
        }
        int[] stackOreIds = OreDictionary.getOreIDs(stack);
        if (stackOreIds.length == 0) {
            // 物品没有任何词典名时无法命中任何标签
            return false;
        }
        for (String tag : this.itemFilterTags) {
            int tagId = OreDictionary.getOreID(tag);
            if (tagId < 0) {
                continue;
            }
            for (int stackOreId : stackOreIds) {
                if (stackOreId == tagId) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 流体过滤：未设置 = 全部接受；设置了则流体名必须在列表里。 */
    private boolean matchesFluidFilter(FluidStack fluid) {
        if (this.filterMode == FILTER_SPECIFIC) {
            // 具体过滤：标记全空 = 接受全部；否则流体种类须在标记里
            boolean any = false;
            for (FluidStack mark : this.filterFluidMarks) {
                if (mark == null || mark.getFluid() == null) {
                    continue;
                }
                any = true;
                if (fluid != null && fluid.getFluid() != null && mark.getFluid() == fluid.getFluid()) {
                    return true;
                }
            }
            return !any;
        }
        if (this.fluidFilterNames.isEmpty()) {
            return true;
        }
        if (fluid == null || fluid.getFluid() == null) {
            return false;
        }
        return this.fluidFilterNames.contains(
            fluid.getFluid()
                .getName());
    }

    // ---------------- 界面 ----------------

    @Override
    public int getGUIWidth() {
        return GUI_WIDTH;
    }

    @Override
    public int getGUIHeight() {
        return GUI_HEIGHT;
    }

    /** 绑定玩家背包：createWindow 会在窗口底部（y=172..248）补上 3 行背包 + 快捷栏。 */
    @Override
    public boolean doesBindPlayerInventory() {
        return true;
    }

    @Override
    public void addGregTechLogo(ModularWindow.Builder builder) {
        // 面板占满整屏，不画 GT logo，避免与缓存列表重叠
    }

    @Override
    public void addUIWidgets(ModularWindow.Builder builder, UIBuildContext buildContext) {
        // 双页结构：主页（缓存视图）与设置页在同一个窗口里，页签点击只对两组控件
        // 做 setEnabled 切换（纯本地操作，两端各自执行，不涉及 synced window 网络 API）。
        List<Widget> mainPageWidgets = new ArrayList<>();
        List<Widget> settingsPageWidgets = new ArrayList<>();
        boolean[] settingsPageActive = { false };

        // 主页：网络状态（绿=在线 / 红=离线），跟随服务端同步过来的 active 状态
        Widget statusText = TextWidget.dynamicString(this::getNetworkStatusText)
            .setPos(7, 8);
        builder.widget(statusText);
        mainPageWidgets.add(statusText);

        // 右上角：-1 / 数字框 / +1，数字是缓存冲入 ME 网络的间隔 tick 数
        ButtonWidget minus = new ButtonWidget();
        // setOnClick 在两端都会执行（本地先走一遍再同步给服务端），两端各自 -1
        minus.setOnClick((clickData, widget) -> this.setFlushIntervalTicks(this.flushIntervalTicks - 1));
        minus.setBackground(new IDrawable[] { GTUITextures.BUTTON_STANDARD, GTUITextures.OVERLAY_BUTTON_MINUS_SMALL });
        minus.setSize(18, 18);
        minus.setPos(96, 5);
        minus.addTooltip(StatCollector.translateToLocal("dualoutputhatchme.gui.interval.tooltip"));
        builder.widget(minus);
        mainPageWidgets.add(minus);

        NumericWidget interval = new NumericWidget();
        interval.setBounds(MIN_FLUSH_INTERVAL, MAX_FLUSH_INTERVAL);
        interval.setDefaultValue(DEFAULT_FLUSH_INTERVAL);
        interval.setIntegerOnly(true);
        interval.setGetter(() -> (double) this.flushIntervalTicks);
        interval.setSetter(value -> this.setFlushIntervalTicks((int) value));
        interval.setScrollValues(1.0, 10.0, 100.0);
        interval.setTextColor(Color.WHITE.normal);
        interval.setTextAlignment(Alignment.Center);
        interval.setBackground(new IDrawable[] { GTUITextures.BACKGROUND_TEXT_FIELD });
        interval.setSize(36, 18);
        interval.setPos(116, 5);
        interval.addTooltip(StatCollector.translateToLocal("dualoutputhatchme.gui.interval.tooltip"));
        builder.widget(interval);
        mainPageWidgets.add(interval);

        // 间隔的服务端 -> 客户端同步：数字框读的是本侧字段，
        // 不同步的话点 +-1 或服务端裁切后显示的数字不会跟着变。
        builder.widget(new FakeSyncWidget.IntegerSyncer(this::getFlushIntervalTicks, this::setFlushIntervalTicks));

        ButtonWidget plus = new ButtonWidget();
        plus.setOnClick((clickData, widget) -> this.setFlushIntervalTicks(this.flushIntervalTicks + 1));
        plus.setBackground(new IDrawable[] { GTUITextures.BUTTON_STANDARD, GTUITextures.OVERLAY_BUTTON_PLUS_SMALL });
        plus.setSize(18, 18);
        plus.setPos(154, 5);
        plus.addTooltip(StatCollector.translateToLocal("dualoutputhatchme.gui.interval.tooltip"));
        builder.widget(plus);
        mainPageWidgets.add(plus);

        // 缓存列表数据：服务端限频打包，客户端接收后由下面的列表控件绘制
        builder.widget(
            new FakeSyncWidget.ListSyncer<DualCacheEntry>(
                this::getCacheSnapshot,
                list -> this.clientCacheEntries = list,
                (buffer, entry) -> entry.writeToBuffer(buffer),
                DualCacheEntry::readFromBuffer));

        this.cacheList = new CacheListWidget(this);
        CacheListWidget list = this.cacheList;
        list.setBackground(GTUITextures.PICTURE_SCREEN_BLACK);
        list.setSize(LIST_WIDTH, LIST_HEIGHT);
        list.setPos(LIST_X, LIST_Y);
        builder.widget(list);
        mainPageWidgets.add(list);
        list.dynamicTooltip(() -> this.getHoveredTooltip());
        // dynamicTooltip 默认缓存（首帧求值后不再刷新），必须开每 tick 更新
        list.setUpdateTooltipEveryTick(true);

        // 左上角页签（与 GT 封面页签同款：左缘 x=-16 的按钮列）
        // 第 1 页 = 缓存视图，第 2 页 = 设置；点击在同一窗口内切换页面（纯本地 setEnabled）。
        // 页签背景用 getBackground() 动态返回，选中页高亮。
        ButtonWidget tabMain = new ButtonWidget() {

            @Override
            public IDrawable[] getBackground() {
                return settingsPageActive[0] ? new IDrawable[] { GTUITextures.TAB_COVER_NORMAL }
                    : new IDrawable[] { GTUITextures.TAB_COVER_HIGHLIGHT };
            }
        };
        tabMain.setSize(18, 20);
        tabMain.setPos(-16, 1);
        tabMain.addTooltip(StatCollector.translateToLocal("dualoutputhatchme.gui.tab.main"));
        builder.widget(tabMain);
        builder.widget(
            new ItemDrawable(Registration.DUAL_OUTPUT_HATCH_ME).asWidget()
                .setPos(-15, 3)
                .setSize(16, 16));

        ButtonWidget tabSettings = new ButtonWidget() {

            @Override
            public IDrawable[] getBackground() {
                return settingsPageActive[0] ? new IDrawable[] { GTUITextures.TAB_COVER_HIGHLIGHT }
                    : new IDrawable[] { GTUITextures.TAB_COVER_NORMAL };
            }
        };
        tabSettings.setSize(18, 20);
        tabSettings.setPos(-16, 23);
        tabSettings.addTooltip(StatCollector.translateToLocal("dualoutputhatchme.gui.tab.settings"));
        builder.widget(tabSettings);
        builder.widget(
            new DrawableWidget().setBackground(GTUITextures.OVERLAY_SLOT_FILTER)
                .setPos(-15, 25)
                .setSize(16, 16));

        Runnable showMainPage = () -> {
            settingsPageActive[0] = false;
            for (Widget widget : mainPageWidgets) {
                widget.setEnabled(true);
            }
            for (Widget widget : settingsPageWidgets) {
                widget.setEnabled(false);
            }
        };
        Runnable showSettingsPage = () -> {
            settingsPageActive[0] = true;
            for (Widget widget : mainPageWidgets) {
                widget.setEnabled(false);
            }
            for (Widget widget : settingsPageWidgets) {
                widget.setEnabled(true);
            }
            // 列表停用后不再 draw，悬停下标会冻结在旧值——清掉，防止 NEI 拿到过期物品
            list.hoveredIndex = -1;
        };
        tabMain.setOnClick((clickData, widget) -> showMainPage.run());
        tabSettings.setOnClick((clickData, widget) -> showSettingsPage.run());

        // ---- 设置页控件（与主页同窗，切换时整组启用/停用） ----

        // 输出优先级：-1 / 数值 / +1（两端同步写法与主页冲缓存间隔一致）
        Widget priorityLabel = TextWidget.dynamicString(this::getPriorityText)
            .setPos(7, 8);
        builder.widget(priorityLabel);
        settingsPageWidgets.add(priorityLabel);

        ButtonWidget priorityMinus = new ButtonWidget();
        priorityMinus.setOnClick((clickData, widget) -> this.setOutputPriority(this.getPriority() - 1));
        priorityMinus
            .setBackground(new IDrawable[] { GTUITextures.BUTTON_STANDARD, GTUITextures.OVERLAY_BUTTON_MINUS_SMALL });
        priorityMinus.setSize(18, 18);
        priorityMinus.setPos(96, 5);
        priorityMinus.addTooltip(StatCollector.translateToLocal("dualoutputhatchme.gui.priority.tooltip"));
        builder.widget(priorityMinus);
        settingsPageWidgets.add(priorityMinus);

        TextWidget priorityValue = TextWidget.dynamicString(() -> Integer.toString(this.getPriority()));
        priorityValue.setTextAlignment(Alignment.Center);
        priorityValue.setBackground(new IDrawable[] { GTUITextures.BACKGROUND_TEXT_FIELD });
        priorityValue.setSize(36, 18);
        priorityValue.setPos(116, 5);
        builder.widget(priorityValue);
        settingsPageWidgets.add(priorityValue);

        ButtonWidget priorityPlus = new ButtonWidget();
        priorityPlus.setOnClick((clickData, widget) -> this.setOutputPriority(this.getPriority() + 1));
        priorityPlus
            .setBackground(new IDrawable[] { GTUITextures.BUTTON_STANDARD, GTUITextures.OVERLAY_BUTTON_PLUS_SMALL });
        priorityPlus.setSize(18, 18);
        priorityPlus.setPos(154, 5);
        priorityPlus.addTooltip(StatCollector.translateToLocal("dualoutputhatchme.gui.priority.tooltip"));
        builder.widget(priorityPlus);
        settingsPageWidgets.add(priorityPlus);

        builder.widget(new FakeSyncWidget.IntegerSyncer(this::getPriority, this::setOutputPriority));

        // ---- 过滤：模式切换（标签 / 具体），两组控件按模式动态启停 ----
        // 模式组控件不进 settingsPageWidgets：它们的启用状态由
        // 「在设置页 && 模式匹配」的动态谓词逐帧决定（isEnabled 每次实时求值）。
        java.util.function.Function<Widget, Boolean> tagMode = w -> settingsPageActive[0]
            && this.filterMode == FILTER_TAG;
        java.util.function.Function<Widget, Boolean> specMode = w -> settingsPageActive[0]
            && this.filterMode == FILTER_SPECIFIC;

        ButtonWidget modeButton = new ButtonWidget();
        modeButton.setOnClick(
            (clickData, widget) -> this.setFilterMode(this.filterMode == FILTER_TAG ? FILTER_SPECIFIC : FILTER_TAG));
        modeButton.setBackground(new IDrawable[] { GTUITextures.BUTTON_STANDARD });
        modeButton.setSize(162, 16);
        modeButton.setPos(7, 26);
        modeButton.addTooltip(StatCollector.translateToLocal("dualoutputhatchme.gui.filter.mode.tooltip"));
        builder.widget(modeButton);
        settingsPageWidgets.add(modeButton);

        Widget modeText = TextWidget
            .dynamicString(
                () -> StatCollector.translateToLocal("dualoutputhatchme.gui.filter.mode") + ": "
                    + EnumChatFormatting.GOLD
                    + StatCollector.translateToLocal(
                        this.filterMode == FILTER_TAG ? "dualoutputhatchme.gui.filter.mode.tag"
                            : "dualoutputhatchme.gui.filter.mode.specific"))
            .setTextAlignment(Alignment.Center)
            .setSize(162, 16)
            .setPos(7, 26);
        builder.widget(modeText);
        settingsPageWidgets.add(modeText);

        builder.widget(new FakeSyncWidget.IntegerSyncer(this::getFilterMode, this::setFilterMode));

        // 标签过滤组（文本输入，按 OreDictionary 词典名 / 流体名）
        Widget tagItemLabel = TextWidget.localised("dualoutputhatchme.gui.filter.items")
            .setPos(7, 48);
        builder.widget(tagItemLabel);
        tagItemLabel.setEnabled(tagMode);

        TextFieldWidget tagItemField = new TextFieldWidget();
        tagItemField.setGetter(this::getItemFilterRaw)
            .setSetter(this::setItemFilterRaw)
            .setSynced(true, true);
        tagItemField.setBackground(GTUITextures.BACKGROUND_TEXT_FIELD);
        tagItemField.setSize(162, 14);
        tagItemField.setPos(7, 60);
        tagItemField.addTooltip(StatCollector.translateToLocal("dualoutputhatchme.gui.filter.items.tooltip"));
        builder.widget(tagItemField);
        tagItemField.setEnabled(tagMode);

        Widget tagFluidLabel = TextWidget.localised("dualoutputhatchme.gui.filter.fluids")
            .setPos(7, 86);
        builder.widget(tagFluidLabel);
        tagFluidLabel.setEnabled(tagMode);

        TextFieldWidget tagFluidField = new TextFieldWidget();
        tagFluidField.setGetter(this::getFluidFilterRaw)
            .setSetter(this::setFluidFilterRaw)
            .setSynced(true, true);
        tagFluidField.setBackground(GTUITextures.BACKGROUND_TEXT_FIELD);
        tagFluidField.setSize(162, 14);
        tagFluidField.setPos(7, 98);
        tagFluidField.addTooltip(StatCollector.translateToLocal("dualoutputhatchme.gui.filter.fluids.tooltip"));
        builder.widget(tagFluidField);
        tagFluidField.setEnabled(tagMode);

        // 具体过滤组（自绘标记格：真槽位不受控件启停控制，会永远显示，故不能用）
        Widget markItemLabel = TextWidget.localised("dualoutputhatchme.gui.filter.items.specific")
            .setPos(7, 46);
        markItemLabel.addTooltip(StatCollector.translateToLocal("dualoutputhatchme.gui.filter.items.specific.tooltip"));
        builder.widget(markItemLabel);
        markItemLabel.setEnabled(specMode);

        FilterMarkGrid markGrid = new FilterMarkGrid();
        markGrid.setSize(162, 86);
        markGrid.setPos(7, 58);
        builder.widget(markGrid);
        markGrid.setEnabled(specMode);
        // 悬停标记的原生 tooltip（物品/流体信息行），每 tick 刷新跟随格子
        markGrid.dynamicTooltip(markGrid::getHoveredMarkTooltip);
        markGrid.setUpdateTooltipEveryTick(true);

        // 隐形点击层：ButtonWidget 的 onClick 两端各执行一次（本地+服务端重放），
        // 标记因此能落到服务端并持久化——自绘控件自己的点击只在客户端跑，不会持久化。
        for (int i = 0; i < FILTER_ITEM_SLOTS; i++) {
            final int slot = i;
            builder.widget(new ButtonWidget() {

                @Override
                public IDrawable[] getBackground() {
                    return new IDrawable[0];
                }
            }.setOnClick((clickData, widget) -> {
                ItemStack held = widget.getContext()
                    .getPlayer().inventory.getItemStack();
                setFilterItemMark(slot, clickData.mouseButton == 0 && held != null ? held.copy() : null);
            })
                .setSize(18, 18)
                .setPos(7 + slot % 9 * 18, 58 + slot / 9 * 18)
                .setEnabled(specMode));
        }
        for (int i = 0; i < FILTER_FLUID_SLOTS; i++) {
            final int slot = i;
            builder.widget(new ButtonWidget() {

                @Override
                public IDrawable[] getBackground() {
                    return new IDrawable[0];
                }
            }.setOnClick((clickData, widget) -> {
                ItemStack held = widget.getContext()
                    .getPlayer().inventory.getItemStack();
                FluidStack heldFluid = null;
                if (clickData.mouseButton == 0 && held != null) {
                    heldFluid = FluidContainerRegistry.getFluidForFilledItem(held);
                    if (heldFluid == null && held.getItem() instanceof IFluidContainerItem) {
                        heldFluid = ((IFluidContainerItem) held.getItem()).getFluid(held);
                    }
                }
                setFilterFluidMark(slot, heldFluid);
            })
                .setSize(18, 18)
                .setPos(7 + slot % 9 * 18, 58 + 50 + slot / 9 * 18)
                .setEnabled(specMode));
        }

        for (int i = 0; i < FILTER_ITEM_SLOTS; i++) {
            final int slot = i;
            builder.widget(
                new FakeSyncWidget.ItemStackSyncer(
                    () -> this.filterItemMarks[slot],
                    stack -> this.filterItemMarks[slot] = stack));
        }
        for (int i = 0; i < FILTER_FLUID_SLOTS; i++) {
            final int slot = i;
            builder.widget(
                new FakeSyncWidget.FluidStackSyncer(
                    () -> this.filterFluidMarks[slot],
                    stack -> this.filterFluidMarks[slot] = stack));
        }

        // 初始显示主页（必须在设置页控件全部就位后调用，才能把设置页整体停用）
        showMainPage.run();
    }

    /** 悬停条目的 tooltip：与 1.5.0 槽位一致的原生物品/流体信息行。 */
    private List<String> getHoveredTooltip() {
        List<DualCacheEntry> entries = this.clientCacheEntries;
        CacheListWidget widget = this.cacheList;
        if (widget == null || widget.hoveredIndex < 0 || entries == null || widget.hoveredIndex >= entries.size()) {
            return Collections.emptyList();
        }
        DualCacheEntry entry = entries.get(widget.hoveredIndex);
        ItemStack display;
        if (entry.isItem()) {
            display = entry.item.copy();
            display.stackSize = (int) Math.min(entry.amount, (long) Integer.MAX_VALUE);
        } else if (entry.fluid != null && entry.fluid.getFluid() != null) {
            display = GTUtility.getFluidDisplayStack(entry.fluid, false);
        } else {
            return Collections.emptyList();
        }
        if (display == null) {
            return Collections.emptyList();
        }
        return applyVanillaTooltipColors(display, display.getTooltip(Minecraft.getMinecraft().thePlayer, false));
    }

    /**
     * 复刻原版 renderToolTip 的着色：getTooltip 返回的原始字符串不带颜色，
     * 首行由渲染器按稀有度上色、其余行统一灰色——ModularUI 的 tooltip 不会替我们做这一步。
     */
    private static List<String> applyVanillaTooltipColors(ItemStack display, List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (i == 0) {
                lines.set(i, display.getRarity().rarityColor + lines.get(i));
            } else {
                lines.set(i, EnumChatFormatting.GRAY + lines.get(i));
            }
        }
        return lines;
    }

    private String getPriorityText() {
        return StatCollector.translateToLocal("dualoutputhatchme.gui.priority") + ": "
            + EnumChatFormatting.GOLD
            + this.getPriority()
            + EnumChatFormatting.RESET;
    }

    /**
     * 在两端都可调用（addUIWidgets 服务端也跑），不能标 @SideOnly(CLIENT)，
     * 否则服务端被剥离后方法引用会崩。
     */
    private String getNetworkStatusText() {
        boolean online = this.getBaseMetaTileEntity() != null && this.getBaseMetaTileEntity()
            .isActive();
        String status = StatCollector
            .translateToLocal(online ? "dualoutputhatchme.gui.online" : "dualoutputhatchme.gui.offline");
        // 字段名不着色，只给结果上色（与参考图一致）
        return StatCollector.translateToLocalFormatted(
            "dualoutputhatchme.gui.network",
            (online ? EnumChatFormatting.GREEN : EnumChatFormatting.RED) + status + EnumChatFormatting.RESET);
    }

    /**
     * 服务端限频快照：缓存变化也必须等待 SNAPSHOT_INTERVAL tick，
     * 返回值未变化时 ListSyncer 不会重复发包。
     */
    private List<DualCacheEntry> getCacheSnapshot() {
        long now = this.dualTickCounter;
        if (!this.syncSnapshotDirty || now - this.syncSnapshotTick < SNAPSHOT_INTERVAL) {
            return this.syncSnapshot;
        }
        this.syncSnapshotTick = now;
        this.syncSnapshotDirty = false;
        List<DualCacheEntry> snapshot = new ArrayList<>();
        for (IAEItemStack s : this.itemCache) {
            if (s == null || s.getStackSize() <= 0L) {
                continue;
            }
            ItemStack display = s.getItemStack();
            if (display == null) {
                continue;
            }
            snapshot.add(DualCacheEntry.ofItem(display, s.getStackSize()));
            if (snapshot.size() >= SNAPSHOT_LIMIT) {
                break;
            }
        }
        if (snapshot.size() < SNAPSHOT_LIMIT) {
            for (IAEFluidStack s : this.fluidCache) {
                if (s == null || s.getStackSize() <= 0L) {
                    continue;
                }
                snapshot.add(DualCacheEntry.ofFluid(s.getFluidStack(), s.getStackSize()));
                if (snapshot.size() >= SNAPSHOT_LIMIT) {
                    break;
                }
            }
        }
        this.syncSnapshot = snapshot;
        return snapshot;
    }

    /**
     * 缓存列表控件：自绘行（图标 + 名称 + 数量），只画完全可见的行，
     * 不建子控件，条目多时也不会拖慢界面。
     *
     * 实现 IHasStackUnderMouse 把悬停行的物品报给 NEI：悬停行显示 NEI tooltip，
     * 并可直接用 NEI 快捷键（R 查配方、U 查用途、A 收藏）。
     * 流体行用流体桶作代理——NEI 1.7.10 的配方/书签体系只有物品类型。
     */
    private static class CacheListWidget extends Widget implements Interactable, IHasStackUnderMouse {

        private static final Rectangle ROW_ODD = new Rectangle().setColor(0x20FFFFFF);
        private static final Rectangle ROW_EVEN = new Rectangle().setColor(0x10FFFFFF);

        private final MTEDualOutputHatchME hatch;
        private int scrollRow = 0;
        /** 当前鼠标悬停的缓存条目下标（-1 = 无），每次 draw 刷新，供 NEI 查询。 */
        private int hoveredIndex = -1;

        private List<DualCacheEntry> iconSource = null;
        private IDrawable[] icons = null;

        CacheListWidget(MTEDualOutputHatchME hatch) {
            this.hatch = hatch;
        }

        private int visibleRows() {
            return Math.max(1, this.getSize().height / ROW_HEIGHT);
        }

        private void refreshIcons(List<DualCacheEntry> entries) {
            if (entries == this.iconSource) {
                return;
            }
            this.iconSource = entries;
            IDrawable[] newIcons = new IDrawable[entries.size()];
            for (int i = 0; i < entries.size(); i++) {
                DualCacheEntry entry = entries.get(i);
                newIcons[i] = entry.isItem() ? new ItemDrawable(entry.item) : new FluidDrawable().setFluid(entry.fluid);
            }
            this.icons = newIcons;
        }

        @Override
        public void draw(float partialTicks) {
            this.updateHoveredIndex();
            List<DualCacheEntry> entries = this.hatch.clientCacheEntries;
            FontRenderer font = Minecraft.getMinecraft().fontRenderer;
            int width = this.getSize().width;
            int height = this.getSize().height;
            int rows = this.visibleRows();
            int total = entries == null ? 0 : entries.size();
            int maxScroll = Math.max(0, total - rows);
            if (this.scrollRow > maxScroll) {
                this.scrollRow = maxScroll;
            }
            if (total == 0) {
                return;
            }
            this.refreshIcons(entries);
            for (int row = 0; row < rows; row++) {
                int index = this.scrollRow + row;
                if (index >= total) {
                    break;
                }
                DualCacheEntry entry = entries.get(index);
                int y = row * ROW_HEIGHT;
                (row % 2 == 0 ? ROW_ODD : ROW_EVEN).draw(0, y, width, ROW_HEIGHT, partialTicks);
                IDrawable icon = this.icons[index];
                if (icon != null) {
                    icon.draw(1, y + 1, 16, 16, partialTicks);
                }
                String amount = GTUtility.formatNumbers(entry.amount) + (entry.isItem() ? "" : " L");
                int amountWidth = font.getStringWidth(amount);
                int nameSpace = width - 22 - amountWidth - 4;
                if (nameSpace < 0) {
                    nameSpace = 0;
                }
                font.drawString(font.trimStringToWidth(entry.name, nameSpace), 20, y + 5, 0xD0D0D0);
                font.drawString(amount, width - 4 - amountWidth, y + 5, 0xFFCC33);
            }
        }

        /**
         * 换算鼠标在列表内的行号：
         * Cursor.getPos() 是屏幕坐标，Widget.getPos() 是窗口内坐标，
         * 列表的屏幕位置 = 窗口位置 + 控件位置（窗口拖动后 windowPos 变化，实测验证此式成立）。
         * 不依赖 Cursor.isAbove——它有边缘漏判（鼠标已在列表内仍返回 false）。
         */
        private void updateHoveredIndex() {
            try {
                this.hoveredIndex = -1;
                Cursor cursor = this.getContext()
                    .getCursor();
                if (cursor == null) {
                    return;
                }
                Pos2d widgetPos = this.getPos();
                Pos2d windowPos = this.getWindow() == null ? null
                    : this.getWindow()
                        .getPos();
                if (windowPos == null) {
                    return;
                }
                int localX = (int) (cursor.getX() - windowPos.getX() - widgetPos.getX());
                int localY = (int) (cursor.getY() - windowPos.getY() - widgetPos.getY());
                int width = this.getSize().width;
                int height = this.getSize().height;
                List<DualCacheEntry> entries = this.hatch.clientCacheEntries;
                int total = entries == null ? 0 : entries.size();
                if (localX >= 0 && localX < width && localY >= 0 && localY < height) {
                    int index = this.scrollRow + localY / ROW_HEIGHT;
                    if (index < total) {
                        this.hoveredIndex = index;
                    }
                }
            } catch (Throwable t) {
                // 窗口切换时查询失败则取消悬停，避免向 NEI 提供过期条目。
                this.hoveredIndex = -1;
            }
        }

        /** NEI 悬停查询：物品行返回缓存物品，流体行返回 GT 流体显示栈（通用流体单元）。 */
        @Override
        public ItemStack getStackUnderMouse() {
            if (this.hoveredIndex < 0) {
                return null;
            }
            List<DualCacheEntry> entries = this.hatch.clientCacheEntries;
            if (entries == null || this.hoveredIndex >= entries.size()) {
                return null;
            }
            DualCacheEntry entry = entries.get(this.hoveredIndex);
            if (entry.isItem()) {
                ItemStack stack = entry.item.copy();
                // 缓存数量是 long，钳到 int 让 NEI tooltip 显示真实数量
                stack.stackSize = (int) Math.min(entry.amount, (long) Integer.MAX_VALUE);
                return stack;
            }
            if (entry.fluid != null && entry.fluid.getFluid() != null) {
                // GT 标准流体显示栈（通用流体单元）：GT 自己的流体槽对 NEI 就是这么暴露的，
                // 悬停 R/U 能查到该流体的配方与用途，对无桶的 GT 气体同样有效
                return GTUtility.getFluidDisplayStack(entry.fluid, false);
            }
            return null;
        }

        @Override
        public boolean onMouseScroll(int direction) {
            List<DualCacheEntry> entries = this.hatch.clientCacheEntries;
            int total = entries == null ? 0 : entries.size();
            int maxScroll = Math.max(0, total - this.visibleRows());
            int next = Math.max(0, Math.min(maxScroll, this.scrollRow - direction));
            if (next == this.scrollRow) {
                return false;
            }
            this.scrollRow = next;
            return true;
        }

        /**
         * R 查配方 / U 查用途 / A 收藏：ModularGui.keyTyped 会把按键派发给悬停的
         * Interactable 控件（包内字节码），这里直接调 NEI 公开 API，
         * 绕开 NEI 输入链（其 lastKeyTyped 在本机实测被上游吞掉，永不触达）。
         * 按键码读 NEI 的按键绑定（尊重改键），读不到时回落默认 R/U/A。
         */
        @Override
        public boolean onKeyPressed(char character, int key) {
            // 按键瞬间即时重算悬停，不依赖上一帧 draw 的结果，杜绝时序错位
            this.updateHoveredIndex();
            int mode;
            if (key == neiKey("gui.recipe", Keyboard.KEY_R)) {
                mode = 0;
            } else if (key == neiKey("gui.usage", Keyboard.KEY_U)) {
                mode = 1;
            } else if (key == neiKey("gui.bookmark", Keyboard.KEY_A)) {
                mode = 2;
            } else {
                return false;
            }
            return this.handleNeiAction(mode);
        }

        /**
         * 点击行的冗余触发路径：左键 = 查配方，右键 = 查用途。
         * 与按键路径互为备份——点击派发由 ModularUI 自己完成，不经过 NEI 输入链。
         */
        @Override
        public Interactable.ClickResult onClick(int button, boolean isShiftPressed) {
            // 与按键一致：点击瞬间重算悬停
            this.updateHoveredIndex();
            boolean handled = false;
            if (button == 0) {
                handled = this.handleNeiAction(0);
            } else if (button == 1) {
                handled = this.handleNeiAction(1);
            }
            return handled ? Interactable.ClickResult.SUCCESS : Interactable.ClickResult.IGNORE;
        }

        /**
         * 统一的 NEI 动作入口。mode：0=查配方(R/左键)，1=查用途(U/右键)，2=收藏(A)。
         * 物品行直接用 NEI 的 ItemStack API；流体行优先用桶代理，
         * 无桶的流体（GT 气体等）退化为把流体名填进 NEI 搜索框——与手动搜索等价。
         */
        private boolean handleNeiAction(int mode) {
            if (!DualOutputHatchMod.NEI_PRESENT) {
                return false;
            }
            List<DualCacheEntry> entries = this.hatch.clientCacheEntries;
            if (this.hoveredIndex < 0 || entries == null || this.hoveredIndex >= entries.size()) {
                return false;
            }
            DualCacheEntry entry = entries.get(this.hoveredIndex);
            ItemStack stack = null;
            if (entry.isItem()) {
                stack = entry.item.copy();
                stack.stackSize = (int) Math.min(entry.amount, (long) Integer.MAX_VALUE);
            } else if (entry.fluid != null && entry.fluid.getFluid() != null) {
                // GT 标准流体显示栈（通用流体单元），对无桶的 GT 气体同样有效
                stack = GTUtility.getFluidDisplayStack(entry.fluid, false);
            }
            boolean handled;
            if (stack != null) {
                switch (mode) {
                    case 0:
                        handled = GuiCraftingRecipe.openRecipeGui("item", stack);
                        break;
                    case 1:
                        handled = GuiUsageRecipe.openRecipeGui("item", stack);
                        break;
                    default:
                        handled = ItemPanels.bookmarkPanel.addItem(stack);
                        break;
                }
            } else if (!entry.isItem() && mode <= 1) {
                // 无桶流体：NEI 1.7.10 没有流体配方入口，填搜索框与手动搜索等价
                NEIClientConfig.setSearchExpression(entry.name);
                handled = true;
            } else {
                handled = false;
            }
            return handled;
        }

        /** 读 NEI 的按键绑定；未配置（<=0）时用默认键。 */
        private static int neiKey(String bindName, int fallback) {
            try {
                int bound = NEIKeyboardUtils.unhash(NEIClientConfig.getKeyBinding(bindName));
                return bound > 0 ? bound : fallback;
            } catch (Throwable ignored) {
                return fallback;
            }
        }
    }

    /**
     * 具体过滤的标记格（自绘）：上排 9 格物品、下排 4 格流体。
     * 只负责绘制；点击由叠在上面的隐形 ButtonWidget 处理——
     * ButtonWidget 的 onClick 在两端各执行一次（与优先级按钮同机制），标记才能持久化。
     */
    private class FilterMarkGrid extends Widget {

        private static final int CELL = 18;
        private static final int FLUID_ROW_Y = 50;

        @Override
        public void draw(float partialTicks) {
            // 第一遍：全部槽位背景（物品渲染会改 GL 状态，混画会让后面的格子变色）
            for (int i = 0; i < FILTER_ITEM_SLOTS; i++) {
                GTUITextures.SLOT_DARK_GRAY.draw(i % 9 * CELL, i / 9 * CELL, CELL, CELL, partialTicks);
            }
            for (int i = 0; i < FILTER_FLUID_SLOTS; i++) {
                GTUITextures.SLOT_DARK_GRAY.draw(i % 9 * CELL, FLUID_ROW_Y + i / 9 * CELL, CELL, CELL, partialTicks);
            }
            // 第二遍：图标
            for (int i = 0; i < FILTER_ITEM_SLOTS; i++) {
                ItemStack mark = filterItemMarks[i];
                if (mark != null) {
                    new ItemDrawable(mark).draw(i % 9 * CELL + 1, i / 9 * CELL + 1, 16, 16, partialTicks);
                }
            }
            for (int i = 0; i < FILTER_FLUID_SLOTS; i++) {
                FluidStack mark = filterFluidMarks[i];
                if (mark != null) {
                    new FluidDrawable().setFluid(mark)
                        .draw(i % 9 * CELL + 1, FLUID_ROW_Y + i / 9 * CELL + 1, 16, 16, partialTicks);
                }
            }
        }

        /** 悬停格子的 tooltip：有标记时显示该物品/流体的原生信息行。 */
        private java.util.List<String> getHoveredMarkTooltip() {
            Cursor cursor = this.getContext()
                .getCursor();
            Pos2d widgetPos = this.getPos();
            Pos2d windowPos = this.getWindow() == null ? null
                : this.getWindow()
                    .getPos();
            if (cursor == null || windowPos == null) {
                return Collections.emptyList();
            }
            int localX = (int) (cursor.getX() - windowPos.getX() - widgetPos.getX());
            int localY = (int) (cursor.getY() - windowPos.getY() - widgetPos.getY());
            ItemStack display = null;
            if (localX >= 0 && localX < 9 * CELL && localY >= 0 && localY < 2 * CELL) {
                ItemStack mark = filterItemMarks[localY / CELL * 9 + localX / CELL];
                display = mark == null ? null : mark.copy();
            } else if (localX >= 0 && localX < 9 * CELL && localY >= FLUID_ROW_Y && localY < FLUID_ROW_Y + 2 * CELL) {
                FluidStack mark = filterFluidMarks[(localY - FLUID_ROW_Y) / CELL * 9 + localX / CELL];
                display = mark == null ? null : GTUtility.getFluidDisplayStack(mark, false);
            }
            if (display == null) {
                return Collections.emptyList();
            }
            return applyVanillaTooltipColors(display, display.getTooltip(Minecraft.getMinecraft().thePlayer, false));
        }

    }

    // ---------------- 存储元件槽位已移除 ----------------

    @Override
    public boolean isValidSlot(int aIndex) {
        return false;
    }

    // ---------------- NBT ----------------

    @Override
    public void saveNBTData(NBTTagCompound aNBT) {
        super.saveNBTData(aNBT);
        aNBT.setInteger(NBT_FLUSH_INTERVAL, this.flushIntervalTicks);
        aNBT.setInteger(NBT_PRIORITY, this.outputPriority);
        aNBT.setString(NBT_ITEM_FILTER, this.itemFilterRaw);
        aNBT.setString(NBT_FLUID_FILTER, this.fluidFilterRaw);
        aNBT.setInteger(NBT_FILTER_MODE, this.filterMode);
        NBTTagList markItems = new NBTTagList();
        for (ItemStack mark : this.filterItemMarks) {
            if (mark == null) {
                continue;
            }
            markItems.appendTag(GTUtility.saveItem(mark));
        }
        aNBT.setTag(NBT_FILTER_ITEMS, markItems);
        NBTTagList markFluids = new NBTTagList();
        for (FluidStack mark : this.filterFluidMarks) {
            if (mark != null && mark.getFluid() != null) {
                markFluids.appendTag(mark.writeToNBT(new NBTTagCompound()));
            }
        }
        aNBT.setTag(NBT_FILTER_FLUIDS, markFluids);
        NBTTagList items = new NBTTagList();
        for (IAEItemStack s : this.itemCache) {
            if (s.getStackSize() == 0L) {
                continue;
            }
            NBTTagCompound tag = new NBTTagCompound();
            // 必须用 GTUtility.saveItem：ItemStack.writeToNBT 的 Count 是 byte，
            // 缓存里单格数量可能远超 127，直接写会溢出丢数量。
            tag.setTag("itemStack", GTUtility.saveItem(s.getItemStack()));
            tag.setLong("size", s.getStackSize());
            items.appendTag(tag);
        }
        // 与 GT 总线和 OmniOcular 共用一个字段；现有存档已经包含 cachedItems。
        aNBT.setTag("cachedItems", items);
    }

    @Override
    public void loadNBTData(NBTTagCompound aNBT) {
        super.loadNBTData(aNBT);
        this.flushIntervalTicks = Math.max(
            MIN_FLUSH_INTERVAL,
            Math.min(
                MAX_FLUSH_INTERVAL,
                aNBT.getInteger(NBT_FLUSH_INTERVAL) == 0 ? DEFAULT_FLUSH_INTERVAL
                    : aNBT.getInteger(NBT_FLUSH_INTERVAL)));
        this.setOutputPriority(aNBT.getInteger(NBT_PRIORITY));
        this.setItemFilterRaw(aNBT.getString(NBT_ITEM_FILTER));
        this.setFluidFilterRaw(aNBT.getString(NBT_FLUID_FILTER));
        this.filterMode = aNBT.getInteger(NBT_FILTER_MODE) == FILTER_SPECIFIC ? FILTER_SPECIFIC : FILTER_TAG;
        for (int i = 0; i < FILTER_ITEM_SLOTS; i++) {
            this.filterItemMarks[i] = null;
        }
        NBTTagList markItems = aNBT.getTagList(NBT_FILTER_ITEMS, 10);
        int mi = 0;
        for (int i = 0; i < markItems.tagCount() && mi < FILTER_ITEM_SLOTS; i++) {
            ItemStack mark = GTUtility.loadItem(markItems.getCompoundTagAt(i));
            if (mark != null && mark.getItem() != null) {
                mark.stackSize = 1;
                this.filterItemMarks[mi++] = mark;
            }
        }
        for (int i = 0; i < FILTER_FLUID_SLOTS; i++) {
            this.filterFluidMarks[i] = null;
        }
        NBTTagList markFluids = aNBT.getTagList(NBT_FILTER_FLUIDS, 10);
        int fi = 0;
        for (int i = 0; i < markFluids.tagCount() && fi < FILTER_FLUID_SLOTS; i++) {
            FluidStack mark = FluidStack.loadFluidStackFromNBT(markFluids.getCompoundTagAt(i));
            if (mark != null && mark.getFluid() != null) {
                this.filterFluidMarks[fi++] = mark;
            }
        }
        NBTBase itemsTag = aNBT.getTag("cachedItems");
        if (itemsTag instanceof NBTTagList) {
            NBTTagList l = (NBTTagList) itemsTag;
            for (int i = 0; i < l.tagCount(); ++i) {
                NBTTagCompound tag = l.getCompoundTagAt(i);
                IAEItemStack s = AEApi.instance()
                    .storage()
                    .createItemStack(GTUtility.loadItem(tag.getCompoundTag("itemStack")));
                if (s != null) {
                    s.setStackSize(tag.getLong("size"));
                    this.itemCache.add(s);
                    this.itemFlushQueue.add(this.itemCache.findPrecise(s));
                } else {
                    System.out.println(
                        "[DualOutputHatchME] Failed to load cached item, it has been voided: "
                            + tag.getCompoundTag("itemStack"));
                }
            }
        }
        // 存档里的缓存下一轮要冲进网络，否则没有新输出时会一直留在缓存里
        for (IAEFluidStack fluid : this.fluidCache) {
            this.fluidFlushQueue.add(fluid);
        }
        this.syncSnapshotDirty = true;
    }

    // ---------------- 信息展示（物品 + 流体） ----------------

    @Override
    public boolean isGivingInformation() {
        return true;
    }

    @Override
    public String[] getInfoData() {
        ArrayList<String> ss = new ArrayList<>();
        boolean online = this.getProxy() != null && this.getProxy()
            .isActive();
        ss.add(
            online ? StatCollector.translateToLocal("GT5U.infodata.hatch.crafting_input_me.bus.online")
                : StatCollector.translateToLocalFormatted(
                    "GT5U.infodata.hatch.crafting_input_me.bus.offline",
                    this.getAEDiagnostics()));
        ss.add(StatCollector.translateToLocalFormatted("dualoutputhatchme.waila.items", this.itemCache.size()));
        int counter = 0;
        for (IAEItemStack s : this.itemCache) {
            if (s.getStackSize() <= 0L) {
                continue;
            }
            ss.add(
                s.getItemStack()
                    .getDisplayName() + ": "
                    + EnumChatFormatting.GOLD
                    + GTUtility.formatNumbers(s.getStackSize())
                    + EnumChatFormatting.RESET);
            if (++counter > 100) {
                break;
            }
        }
        ss.add(StatCollector.translateToLocalFormatted("dualoutputhatchme.waila.fluids", this.fluidCache.size()));
        counter = 0;
        for (IAEFluidStack s : this.fluidCache) {
            if (s.getStackSize() <= 0L) {
                continue;
            }
            ss.add(
                s.getFluidStack()
                    .getLocalizedName() + ": "
                    + EnumChatFormatting.GOLD
                    + GTUtility.formatNumbers(s.getStackSize())
                    + " L"
                    + EnumChatFormatting.RESET);
            if (++counter > 100) {
                break;
            }
        }
        return ss.toArray(new String[0]);
    }

    // ---------------- 方块说明文字 ----------------

    /**
     * 说明文字实时取语言表，不再依赖 GT 的 TileEntity_DESCRIPTION_%05d_Index_%02d 机制
     * （该机制在部分环境下取不到本 mod 的语言键，会退回 MTE 构造时传入的英文默认文案）。
     */
    @Override
    public String[] getDescription() {
        String[] description = new String[6];
        for (int i = 0; i < description.length; i++) {
            description[i] = StatCollector.translateToLocal("dualoutputhatchme.desc." + i);
        }
        return description;
    }

    // ---------------- WAILA / Jade：物品在前，流体在后 ----------------

    @Override
    public void getWailaNBTData(EntityPlayerMP player, TileEntity tile, NBTTagCompound tag, World world, int x, int y,
        int z) {
        // MetaTileEntity 的通用 AE 状态；不调用 ME 父类，避免它先全量排序流体缓存。
        tag.setBoolean("isActive", this.isActive());
        tag.setBoolean("isPowered", this.isPowered());
        tag.setBoolean("isBooting", this.isBooting());

        int itemCount = 0;
        List<IAEItemStack> topItems = new ArrayList<>(WAILA_MAX_STACKS);
        for (IAEItemStack s : this.itemCache) {
            if (s == null || s.getStackSize() <= 0L) {
                continue;
            }
            itemCount++;
            insertTopStack(topItems, s, WAILA_MAX_STACKS);
        }
        tag.setInteger("dualItemCount", itemCount);
        NBTTagList itemList = new NBTTagList();
        for (IAEItemStack stack : topItems) {
            NBTTagCompound entry = new NBTTagCompound();
            ItemStack display = stack.getItemStack();
            entry.setString("name", display == null ? "" : display.getDisplayName());
            entry.setLong("size", stack.getStackSize());
            itemList.appendTag(entry);
        }
        tag.setTag("dualItemStacks", itemList);

        int fluidCount = 0;
        List<IAEFluidStack> topFluids = new ArrayList<>(WAILA_MAX_STACKS);
        for (IAEFluidStack s : this.fluidCache) {
            if (s == null || s.getStackSize() <= 0L) {
                continue;
            }
            fluidCount++;
            insertTopStack(topFluids, s, WAILA_MAX_STACKS);
        }
        tag.setInteger("dualFluidCount", fluidCount);
        NBTTagList fluidList = new NBTTagList();
        for (IAEFluidStack stack : topFluids) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString(
                "name",
                stack.getFluidStack()
                    .getLocalizedName());
            entry.setLong("size", stack.getStackSize());
            fluidList.appendTag(entry);
        }
        tag.setTag("dualFluidStacks", fluidList);
    }

    /** 有界 Top-N：只维护 max 个元素的降序列表，避免对整份缓存排序。 */
    private static <T extends IAEStack<?>> void insertTopStack(List<T> top, T stack, int max) {
        long size = stack.getStackSize();
        int index = 0;
        while (
            index < top.size() && top.get(index)
                .getStackSize() >= size
        ) {
            index++;
        }
        if (index >= max) {
            return;
        }
        top.add(index, stack);
        if (top.size() > max) {
            top.remove(top.size() - 1);
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void getWailaBody(ItemStack itemStack, List<String> ss, IWailaDataAccessor accessor,
        IWailaConfigHandler config) {
        NBTTagCompound tag = accessor.getNBTData();

        // GT 的通用信息（朝向 + ME 通道状态），与普通输出总线(ME)/输出仓(ME) 的显示保持一致
        ss.add(
            StatCollector.translateToLocalFormatted(
                "GT5U.waila.facing",
                getFacingNameLocalized(
                    this.getBaseMetaTileEntity()
                        .getFrontFacing()
                        .ordinal())));
        ss.add(
            WailaText
                .getPowerState(tag.getBoolean("isActive"), tag.getBoolean("isPowered"), tag.getBoolean("isBooting")));

        // 容量行：文案与写法和原版一致，数字是缓存真实上限（long 最大值）
        ss.add(
            String.format(
                "Item cache capacity: %s%s%s",
                EnumChatFormatting.GOLD,
                GTUtility.formatNumbers(MAX_CACHE_AMOUNT),
                EnumChatFormatting.RESET));
        ss.add(
            String.format(
                "Fluid cache capacity: %s%s L%s",
                EnumChatFormatting.GOLD,
                GTUtility.formatNumbers(MAX_CACHE_AMOUNT),
                EnumChatFormatting.RESET));

        if (!GuiScreen.isShiftKeyDown()) {
            ss.add("Hold Shift for more info");
            return;
        }

        // 明细：分别照抄原版输出总线(ME) 与输出仓(ME) 的文案，物品在前、流体在后
        int itemCount = tag.getInteger("dualItemCount");
        NBTTagList items = tag.getTagList("dualItemStacks", 10);
        if (itemCount == 0) {
            ss.add("This bus has no cached stacks");
        } else {
            ss.add(
                String.format(
                    "The bus contains %s%d%s cached stack%s: ",
                    EnumChatFormatting.GOLD,
                    itemCount,
                    EnumChatFormatting.RESET,
                    itemCount > 1 ? "s" : ""));
            for (int i = 0; i < items.tagCount(); i++) {
                NBTTagCompound entry = items.getCompoundTagAt(i);
                ss.add(
                    String.format(
                        "%s: %s%s%s",
                        entry.getString("name"),
                        EnumChatFormatting.GOLD,
                        GTUtility.formatNumbers(entry.getLong("size")),
                        EnumChatFormatting.RESET));
            }
            if (itemCount > items.tagCount()) {
                ss.add(EnumChatFormatting.ITALIC + "And " + (itemCount - items.tagCount()) + " more...");
            }
        }

        int fluidCount = tag.getInteger("dualFluidCount");
        NBTTagList fluids = tag.getTagList("dualFluidStacks", 10);
        if (fluidCount == 0) {
            ss.add("This hatch has no cached fluids");
        } else {
            ss.add(
                String.format(
                    "The hatch contains %s%d%s cached fluid%s: ",
                    EnumChatFormatting.GOLD,
                    fluidCount,
                    EnumChatFormatting.RESET,
                    fluidCount > 1 ? "s" : ""));
            for (int i = 0; i < fluids.tagCount(); i++) {
                NBTTagCompound entry = fluids.getCompoundTagAt(i);
                ss.add(
                    String.format(
                        "%s: %s%s L%s",
                        entry.getString("name"),
                        EnumChatFormatting.GOLD,
                        GTUtility.formatNumbers(entry.getLong("size")),
                        EnumChatFormatting.RESET));
            }
            if (fluidCount > fluids.tagCount()) {
                ss.add(EnumChatFormatting.ITALIC + "And " + (fluidCount - fluids.tagCount()) + " more...");
            }
        }
    }
}
