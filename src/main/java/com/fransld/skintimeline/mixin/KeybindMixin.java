package com.fransld.skintimeline.mixin;

import com.fransld.skintimeline.SimpleModeWindow;
import com.moulberry.flashback.editor.keybinds.Keybind;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Simple Mode buttons work by "pressing" Flashback's own keybinds once, so Flashback runs exactly the same code
 * as when you press the key (with its undo history, locking and on-screen messages).
 */
@Mixin(value = Keybind.class, remap = false)
public class KeybindMixin {

    @Inject(method = "isPressed", at = @At("HEAD"), cancellable = true)
    private void flashbackSkinTimeline$virtualPress(boolean repeat, CallbackInfoReturnable<Boolean> cir) {
        if (SimpleModeWindow.consumeVirtualPress((Keybind) (Object) this)) {
            cir.setReturnValue(true);
        }
    }
}
