package com.fransld.skintimeline.mixin;

import com.fransld.skintimeline.SkinTimelineController;
import com.moulberry.flashback.state.EditorState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps timeline skins out of Flashback's saved editor state and out of the copy Flashback makes for each export.
 * Right before a save/copy the actors' original skins are swapped back in; right after, the timeline skins return.
 */
@Mixin(value = EditorState.class, remap = false)
public class EditorStateMixin {

    @Inject(method = "save", at = @At("HEAD"))
    private void flashbackSkinTimeline$beforeSave(CallbackInfo ci) {
        SkinTimelineController.beforeSaveOrCopy((EditorState) (Object) this);
    }

    @Inject(method = "save", at = @At("RETURN"))
    private void flashbackSkinTimeline$afterSave(CallbackInfo ci) {
        SkinTimelineController.afterSaveOrCopy();
    }

    @Inject(method = {"copy", "copyWithoutKeyframes"}, at = @At("HEAD"))
    private void flashbackSkinTimeline$beforeCopy(CallbackInfoReturnable<EditorState> cir) {
        SkinTimelineController.beforeSaveOrCopy((EditorState) (Object) this);
    }

    @Inject(method = {"copy", "copyWithoutKeyframes"}, at = @At("RETURN"))
    private void flashbackSkinTimeline$afterCopy(CallbackInfoReturnable<EditorState> cir) {
        SkinTimelineController.afterSaveOrCopy();
    }
}
