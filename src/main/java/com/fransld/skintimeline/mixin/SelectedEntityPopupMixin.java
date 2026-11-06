package com.fransld.skintimeline.mixin;

import com.fransld.skintimeline.SkinTimelineUI;
import com.moulberry.flashback.editor.ui.windows.SelectedEntityPopup;
import com.moulberry.flashback.state.EditorState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the "Skin Timeline" section to the bottom of Flashback's right-click menu for an entity.
 */
@Mixin(value = SelectedEntityPopup.class, remap = false)
public class SelectedEntityPopupMixin {

    @Inject(method = "render", at = @At("RETURN"))
    private static void flashbackSkinTimeline$render(Entity entity, EditorState editorState, CallbackInfo ci) {
        SkinTimelineUI.render(entity, editorState);
    }
}
