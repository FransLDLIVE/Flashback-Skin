package com.fransld.skintimeline.mixin;

import com.fransld.skintimeline.SimpleModeWindow;
import com.fransld.skintimeline.SkinTimelineWindow;
import com.moulberry.flashback.editor.ui.windows.MainMenuBar;
import imgui.moulberry90.ImGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds "Simple Mode" and "Skin Timeline" to Flashback's top menu bar (next to Player List, Movement...).
 */
@Mixin(value = MainMenuBar.class, remap = false)
public class MainMenuBarMixin {

    @Inject(method = "renderInner", at = @At("RETURN"))
    private static void flashbackSkinTimeline$menu(CallbackInfo ci) {
        if (ImGui.menuItem("Simple Mode", "", SimpleModeWindow.isOpen())) {
            SimpleModeWindow.toggle();
        }
        if (ImGui.menuItem("Skin Timeline", "", SkinTimelineWindow.isOpen())) {
            SkinTimelineWindow.toggle();
        }
    }
}
