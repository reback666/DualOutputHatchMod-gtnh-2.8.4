package com.gtnh.dualoutputhatchme;

import java.io.File;

import net.minecraft.client.Minecraft;

import com.gtnh.dualoutputhatchme.registration.Registration;

import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {

    private File configDirectory;
    private boolean omniOcularPatched;

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        this.configDirectory = event.getModConfigurationDirectory();
        // 语言文件在 .minecraft 根目录；必须在 MTE 注册前改好，GT 才会读到新文案
        LangFixer.apply(this.configDirectory.getParentFile(), currentLanguageCode(), Registration.MTE_ID);
        super.preInit(event);
        this.omniOcularPatched = OmniOcularConfig.patch(this.configDirectory, Registration.MTE_ID);
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
        if (!this.omniOcularPatched && this.configDirectory != null) {
            // OmniOcular 的预置配置文件是在它的 init 阶段释放的，首次安装时要在这里再补一次。
            this.omniOcularPatched = OmniOcularConfig.patch(this.configDirectory, Registration.MTE_ID);
            if (this.omniOcularPatched) {
                FMLLog.info(
                    "[dualoutputhatchme] OmniOcular config patched. Run /oor once if the data-storage lines are not shown yet.");
            }
        }
    }

    /** 客户端当前语言代码；取不到时返回 null，语言文件就不改。 */
    private static String currentLanguageCode() {
        try {
            return Minecraft.getMinecraft()
                .getLanguageManager()
                .getCurrentLanguage()
                .getLanguageCode();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
