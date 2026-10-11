package com.gtnh.dualoutputhatchme;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

@Mod(
    modid = "dualoutputhatchme",
    name = "Dual Output Hatch ME",
    version = Tags.VERSION,
    dependencies = "required-after:gregtech;required-after:appliedenergistics2;after:ae2fc")
public class DualOutputHatchMod {

    /** NEI 是否在场（GTNH 必带； absence 时 R/U/A 按键集成自动关闭）。 */
    public static final boolean NEI_PRESENT = cpw.mods.fml.common.Loader.isModLoaded("NotEnoughItems");

    @Mod.Instance("dualoutputhatchme")
    public static DualOutputHatchMod instance;

    @SidedProxy(
        clientSide = "com.gtnh.dualoutputhatchme.ClientProxy",
        serverSide = "com.gtnh.dualoutputhatchme.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }
}
