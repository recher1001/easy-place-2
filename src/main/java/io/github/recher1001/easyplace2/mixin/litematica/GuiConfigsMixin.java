package io.github.recher1001.easyplace2.mixin.litematica;

import java.util.List;
import javax.annotation.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.screens.Screen;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.litematica.gui.GuiConfigs;

import io.github.recher1001.easyplace2.EasyPlace2;
import io.github.recher1001.easyplace2.config.Ep2ConfigTab;
import io.github.recher1001.easyplace2.config.Ep2Configs;

/**
 * Fuegt Litematicas Konfigurationsmenue einen eigenen Reiter "Easy Place 2.0" hinzu.
 */
@Mixin(value = GuiConfigs.class, remap = false)
public abstract class GuiConfigsMixin extends GuiConfigsBase implements Ep2ConfigTab.Access
{
    @Unique private int easyplace2$nextTabX = 10;

    protected GuiConfigsMixin(int listX, int listY, String modId, @Nullable Screen parent, String titleKey, Object... args)
    {
        super(listX, listY, modId, parent, titleKey, args);
    }

    @Inject(method = "initGui", at = @At("HEAD"))
    private void easyplace2$resetTabX(CallbackInfo ci)
    {
        this.easyplace2$nextTabX = 10;
    }

    // Merkt sich, wo der letzte Litematica-Reiter endet
    @Inject(method = "createButton", at = @At("RETURN"))
    private void easyplace2$trackTabX(int x, int y, int width, GuiConfigs.ConfigGuiTab tab, CallbackInfoReturnable<Integer> cir)
    {
        this.easyplace2$nextTabX = x + cir.getReturnValue();
    }

    // Solange unser Reiter offen ist, sind alle Litematica-Reiter anklickbar
    @ModifyArg(method = "createButton", at = @At(value = "INVOKE", target = "Lfi/dy/masa/malilib/gui/button/ButtonGeneric;setEnabled(Z)V"))
    private boolean easyplace2$enableLitematicaTabs(boolean enabled)
    {
        return enabled || Ep2ConfigTab.isActive();
    }

    @Inject(method = "initGui", at = @At("TAIL"))
    private void easyplace2$addTabButton(CallbackInfo ci)
    {
        ButtonGeneric button = new ButtonGeneric(this.easyplace2$nextTabX, 26, -1, 20, StringUtils.translate("easyplace2.gui.button.config_tab"));
        button.setEnabled(!Ep2ConfigTab.isActive());
        this.addButton(button, (btn, mouseButton) -> this.easyplace2$openTab());
    }

    @Override
    public void easyplace2$openTab()
    {
        Ep2ConfigTab.setActive(true);
        this.reCreateListWidget();

        if (this.getListWidget() != null)
        {
            this.getListWidget().resetScrollbarPosition();
        }

        this.initGui();
    }

    @Inject(method = "getConfigs", at = @At("HEAD"), cancellable = true)
    private void easyplace2$getOwnConfigs(CallbackInfoReturnable<List<ConfigOptionWrapper>> cir)
    {
        if (Ep2ConfigTab.isActive())
        {
            cir.setReturnValue(ConfigOptionWrapper.createFor(Ep2Configs.OPTIONS));
        }
    }

    // Im Reiter "Alle" sollen unsere Optionen auch auftauchen
    @Inject(method = "getAllConfigs", at = @At("RETURN"))
    private void easyplace2$addToAllTab(CallbackInfoReturnable<List<ConfigOptionWrapper>> cir)
    {
        cir.getReturnValue().addAll(ConfigOptionWrapper.createFor(Ep2Configs.OPTIONS));
    }

    // Litematica speichert beim Schliessen nur seine eigene Datei, wir speichern unsere mit
    @Inject(method = "onSettingsChanged", at = @At("HEAD"))
    private void easyplace2$saveOwnConfigs(CallbackInfo ci)
    {
        ConfigManager.getInstance().onConfigsChanged(EasyPlace2.MOD_ID);
    }
}
