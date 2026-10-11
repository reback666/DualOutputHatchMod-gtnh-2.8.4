package com.gtnh.dualoutputhatchme;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

/**
 * GTNH mixin 注册入口（late 路线）。
 *
 * 关键：目标类 MTEMultiBlockBase 是 GregTech 的 late 类。若实现 IEarlyMixinLoader，
 * GTNHMixins 会在早期阶段就注册本配置，此时 GT 类尚未加载，织入被静默跳过
 * （日志只有 "Loading ... MixinMTEMultiBlockBase"，没有 "Mixing ... into MTEMultiBlockBase"）。
 *
 * 因此这里实现 ILateMixinLoader（签名与 IEarlyMixinLoader 完全相同），
 * 让 GTNHMixins 在 mod 类可用后再织入。@LateMixin 注解也因此名副其实。
 */
@IFMLLoadingPlugin.MCVersion("1.7.10")
@com.gtnewhorizon.gtnhmixins.LateMixin
public class LateMixinsLoader implements IFMLLoadingPlugin, ILateMixinLoader {

    @Override
    public String[] getASMTransformerClass() {
        return null;
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {}

    @Override
    public String getAccessTransformerClass() {
        return null;
    }

    @Override
    public String getMixinConfig() {
        return "mixins.dualoutputhatchme.json";
    }

    @Nonnull
    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        return Collections.unmodifiableList(Arrays.asList("MixinMTEMultiBlockBase"));
    }
}
