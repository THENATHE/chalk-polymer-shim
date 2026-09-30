package chalk.protection;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import de.dafuqs.chalk.common.ChalkRegistry;
import de.dafuqs.chalk.common.blocks.ChalkMarkBlock;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/** Calls the actual packet handler and all native permission checks; only output transport is stubbed. */
public final class ChalkProtectionQa implements ModInitializer {
    private int ticks;
    private boolean done;
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (done || ++ticks < 5) return;
            done = true;
            var results = new ArrayList<Object>();
            try {
                server.overworld().setRespawnData(LevelData.RespawnData.of(server.overworld().dimension(), new BlockPos(0, 100, 0), 0, 0));
                server.getPlayerList().op(new NameAndId(UUID.fromString("27a22e00-a2b9-43aa-ae5f-1f1b89e20b2a"), "OtherOperator"));
                results.add(checkCase(server, "nonop_protected_boundary", 16, false, false));
                results.add(checkCase(server, "operator_protected_boundary", 16, true, true));
                // Leave room in front of this mark; Chalk checks that space even
                // when redrawing, so the preceding support must not occupy it.
                results.add(checkCase(server, "nonop_unprotected", 20, false, true));
                Files.writeString(Path.of(System.getProperty("chalk.protection.output")), new GsonBuilder().setPrettyPrinting().create()
                        .toJson(Map.of("passed", true, "cases", results, "scope", "Actual server packet handler; synthetic player and outgoing transport")));
            } catch (Throwable error) {
                error.printStackTrace();
                try { Files.writeString(Path.of(System.getProperty("chalk.protection.output")), new GsonBuilder().setPrettyPrinting().create()
                        .toJson(Map.of("passed", false, "error", error.toString(), "completed_cases", results))); } catch (Exception ignored) {}
            }
        });
    }

    private Object checkCase(MinecraftServer server, String name, int x, boolean operator, boolean allowed) {
        var level = server.overworld();
        var profile = new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "ProtectionQa");
        if (operator) server.getPlayerList().op(new NameAndId(profile));
        var player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        var listener = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), player,
                CommonListenerCookie.createInitial(profile, false)) {
            @Override public boolean hasClientLoaded() { return true; }
            @Override public void send(Packet<?> packet) {}
            @Override public void send(Packet<?> packet, io.netty.channel.ChannelFutureListener callback) {}
        };
        player.connection = listener;
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(x - 1.5, 100, 0.5);
        var variant = ChalkRegistry.chalkVariants.get(DyeColor.WHITE);
        var mark = new BlockPos(x, 100, 0);
        var support = mark.east();
        level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
        var before = variant.chalkBlock.defaultBlockState().setValue(ChalkMarkBlock.FACING, Direction.WEST)
                .setValue(ChalkMarkBlock.ORIENTATION, 0);
        level.setBlock(mark, before, 3);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(variant.chalkItem));
        require(!server.isUnderSpawnProtection(level, support, player), "Test support must be outside protection");
        require(server.isUnderSpawnProtection(level, mark, player) == (!operator && x == 16), "Protection precondition failed");
        require(player.isWithinBlockInteractionRange(mark, 1), "Fixture player too far away");
        // West face centre selects orientation 4; the vanilla packet reports support x+1.
        var hit = new BlockHitResult(new Vec3(x + 1, 100.5, 0.5), Direction.WEST, support, false);
        listener.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, 1));
        var after = level.getBlockState(mark);
        int damage = player.getMainHandItem().getDamageValue();
        require(after.is(variant.chalkBlock), "Redraw changed block identity");
        require(after.getValue(ChalkMarkBlock.ORIENTATION) == (allowed ? 4 : 0), name + " changed unexpected orientation: " + after);
        require(damage == (allowed ? 1 : 0), name + " changed unexpected durability: " + damage);
        return Map.of("case", name, "redraw_allowed", allowed, "orientation", after.getValue(ChalkMarkBlock.ORIENTATION),
                      "damage", damage, "support_x", support.getX(), "mark_x", mark.getX());
    }
}
