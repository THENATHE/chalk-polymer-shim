package com.thenathe.chalkcompat.mixin;
import de.dafuqs.chalk.common.blocks.ChalkMarkBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(ServerGamePacketListenerImpl.class)
public class ChalkPickMixin {
    @Shadow public ServerPlayer player;
    @Redirect(method="handlePickItemFromBlock", at=@At(value="INVOKE", target="Lnet/minecraft/network/protocol/game/ServerboundPickItemFromBlockPacket;pos()Lnet/minecraft/core/BlockPos;"))
    private BlockPos chalkcompat$pick(ServerboundPickItemFromBlockPacket packet) {
        if (player.pick(player.blockInteractionRange(), 0, false) instanceof BlockHitResult hit) {
            var state = player.level().getBlockState(hit.getBlockPos());
            if (state.getBlock() instanceof ChalkMarkBlock && hit.getBlockPos().relative(state.getValue(ChalkMarkBlock.FACING).getOpposite()).equals(packet.pos())) return hit.getBlockPos();
        }
        return packet.pos();
    }
}
