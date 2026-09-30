package com.thenathe.chalkcompat.mixin;
import com.thenathe.chalkcompat.ChalkCompat;
import de.dafuqs.chalk.common.items.ChalkItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(ServerGamePacketListenerImpl.class)
public class ChalkUseMixin {
    @Shadow public ServerPlayer player;
    /** Redirect before Minecraft validates reach, spawn protection and mayInteract. */
    @Redirect(method="handleUseItemOn", at=@At(value="INVOKE", target="Lnet/minecraft/network/protocol/game/ServerboundUseItemOnPacket;getHitResult()Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult chalkcompat$use(ServerboundUseItemOnPacket packet) {
        var hit = packet.getHitResult();
        if (!(player.getItemInHand(packet.getHand()).getItem() instanceof ChalkItem)) return hit;
        BlockPos target = ChalkCompat.target(player, hit.getBlockPos(), hit.getDirection());
        return target.equals(hit.getBlockPos()) ? hit : new BlockHitResult(hit.getLocation(), hit.getDirection(), target, hit.isInside());
    }
}
