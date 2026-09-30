package com.thenathe.chalkcompat.mixin;
import com.thenathe.chalkcompat.ChalkCompat;
import de.dafuqs.chalk.common.Chalk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=Chalk.class, remap=false)
public class ChalkInitializationMixin {
    @Inject(method="onInitialize", at=@At("TAIL"))
    private void chalkcompat$initialize(CallbackInfo ci) { ChalkCompat.initialize(); }
}
