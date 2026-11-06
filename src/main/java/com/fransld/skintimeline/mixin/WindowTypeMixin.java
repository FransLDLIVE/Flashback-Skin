package com.fransld.skintimeline.mixin;

import com.fransld.skintimeline.SimpleModeWindow;
import com.fransld.skintimeline.SkinTimelineWindow;
import com.moulberry.flashback.editor.ui.windows.WindowType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the Simple Mode and Skin Timeline windows together with Flashback's other windows.
 */
@Mixin(value = WindowType.class, remap = false)
public class WindowTypeMixin {

    @Inject(method = "renderAll", at = @At("RETURN"))
    private static void flashbackSkinTimeline$render(CallbackInfo ci) {
        SimpleModeWindow.render();
        SkinTimelineWindow.render();
    }
}
