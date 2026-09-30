package com.thenathe.chalkcompat.mixin;
import com.thenathe.chalkcompat.ChalkCompat;
import de.dafuqs.chalk.common.blocks.ChalkMarkBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerPlayerGameMode.class)
public class ChalkTargetingMixin {
    @Shadow protected ServerPlayer player;
    @Unique private BlockPos chalkcompat$lastSupport;
    @Unique private BlockPos chalkcompat$lastMark;
    // The client sees a solid support and can send a follow-up mining packet
    // after the server has already erased the zero-hardness mark.
    @Inject(method="handleBlockBreakAction", at=@At("HEAD"), cancellable=true)
    private void chalkcompat$finishErase(BlockPos pos, ServerboundPlayerActionPacket.Action action, Direction face, int height, int sequence, CallbackInfo ci) {
        if (action != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                && chalkcompat$lastMark != null
                && (pos.equals(chalkcompat$lastSupport) || pos.equals(chalkcompat$lastMark))
                && !(player.level().getBlockState(chalkcompat$lastMark).getBlock() instanceof ChalkMarkBlock)) {
            player.connection.send(new ClientboundBlockUpdatePacket(player.level(), chalkcompat$lastSupport));
            ci.cancel();
        }
    }
    @ModifyVariable(method="handleBlockBreakAction", at=@At("HEAD"), argsOnly=true)
    private BlockPos chalkcompat$attack(BlockPos pos, BlockPos ignored, ServerboundPlayerActionPacket.Action action, Direction face, int height, int sequence) {
        BlockPos target = ChalkCompat.target(player, pos, face);
        if (action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            chalkcompat$lastSupport = target.equals(pos) ? null : pos.immutable();
            chalkcompat$lastMark = target.equals(pos) ? null : target.immutable();
        }
        if (!target.equals(pos)) player.connection.send(new ClientboundBlockUpdatePacket(player.level(), pos));
        return target;
    }
}
