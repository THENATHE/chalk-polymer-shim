package com.thenathe.chalkcompat.mixin;
import de.dafuqs.chalk.common.Chalk;
import de.dafuqs.chalk.common.items.ChalkItem;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=ChalkItem.class, remap=false)
public class ChalkParticlesMixin {
    @Inject(method="useOn", at=@At("RETURN"))
    private void chalkcompat$particles(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (cir.getReturnValue().consumesAction() && Chalk.CONFIG.EmitParticles && context.getLevel() instanceof ServerLevel level) {
            var p=context.getClickLocation();
            level.sendParticles(ParticleTypes.POOF,p.x,p.y,p.z,1,0.1,0.1,0.1,0.005);
        }
    }
}
