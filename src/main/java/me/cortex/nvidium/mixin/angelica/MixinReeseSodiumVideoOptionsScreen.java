package me.cortex.nvidium.mixin.angelica;

import java.util.EnumSet;
import java.util.HashSet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import me.cortex.nvidium.config.ConfigGuiBuilder;
import me.flashyreese.mods.reeses_sodium_options.client.gui.ReeseSodiumVideoOptionsScreen;
import me.jellysquid.mods.sodium.client.gui.options.OptionFlag;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;

/**
 * Angelica opens the Reese's Sodium Options screen by default. It inherits the page list from
 * {@link me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI} but has its own private applyChanges.
 */
@Mixin(value = ReeseSodiumVideoOptionsScreen.class, remap = false)
public class MixinReeseSodiumVideoOptionsScreen {

    @Inject(method = "applyChanges", at = @At("RETURN"), locals = LocalCapture.CAPTURE_FAILSOFT)
    private void nvidium$applyShaderReload(CallbackInfo ci, HashSet<OptionStorage<?>> dirtyStorages,
        EnumSet<OptionFlag> flags) {
        ConfigGuiBuilder.onOptionsApplied(flags);
    }
}
