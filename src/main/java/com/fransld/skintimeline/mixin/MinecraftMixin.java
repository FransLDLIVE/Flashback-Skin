package com.fransld.skintimeline.mixin;

import com.fransld.skintimeline.SkinTimelineController;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the skin timeline once per client tick. Flashback's exporter also calls Minecraft.tick() for every
 * exported tick, so the skin switches land on the same tick in the exported video.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void flashbackSkinTimeline$tick(CallbackInfo ci) {
        SkinTimelineController.onClientTick();
    }
}
