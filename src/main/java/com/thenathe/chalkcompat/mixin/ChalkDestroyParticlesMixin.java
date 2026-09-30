package com.thenathe.chalkcompat.mixin;

import de.dafuqs.chalk.common.Chalk;
import de.dafuqs.chalk.common.blocks.ChalkMarkBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sends the original cloud effect because an AIR client block has no break particles. */
@Mixin(value = ChalkMarkBlock.class, remap = false)
public class ChalkDestroyParticlesMixin {
    @Group(name = "chalkDestroy", min = 1, max = 1)
    @Inject(method = "spawnDestroyByEntityParticles", at = @At("TAIL"), require = 0)
    private void chalkcompat$destroyParticles(Level level, Entity entity, BlockPos pos, BlockState state,
                                              CallbackInfo ci) {
        chalkcompat$sendCloud(level, pos);
    }

    @Group(name = "chalkDestroy", min = 1, max = 1)
    @Inject(method = "spawnDestroyParticles", at = @At("TAIL"), require = 0)
    private void chalkcompat$destroyParticles26_2(Level level, net.minecraft.world.entity.player.Player player,
                                                 BlockPos pos, BlockState state, CallbackInfo ci) {
        chalkcompat$sendCloud(level, pos);
    }

    @org.spongepowered.asm.mixin.Unique
    private static void chalkcompat$sendCloud(Level level, BlockPos pos) {
        if (Chalk.CONFIG.EmitParticles && level instanceof ServerLevel serverLevel) {
            var random = level.getRandom();
            serverLevel.sendParticles(ParticleTypes.CLOUD,
                    pos.getX() + 0.5 * (random.nextFloat() + 0.15), pos.getY() + 0.3,
                    pos.getZ() + 0.5 * (random.nextFloat() + 0.15), 1, 0, 0, 0, 0);
        }
    }
}
