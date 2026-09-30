package chalk.persistence;

import com.google.gson.GsonBuilder;
import com.thenathe.chalkcompat.ChalkMarks;
import de.dafuqs.chalk.common.ChalkRegistry;
import de.dafuqs.chalk.common.blocks.ChalkMarkBlock;
import eu.pb4.polymer.virtualentity.api.attachment.BlockBoundAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Standalone fixture: no player, production worlds, or source JAR modification. */
public final class ChalkPersistenceQa implements ModInitializer {
    private int ticks;
    private boolean done;
    private final boolean write = System.getProperty("chalk.persistence.phase").equals("write");
    private final Path output = Path.of(System.getProperty("chalk.persistence.output"));

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static BlockPos pos(int index) { return new BlockPos(index * 3, 100, 0); }

    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (done) return;
            try {
                ticks++;
                if (ticks != 5 && ticks != 10) return;
                ServerLevel level = server.overworld();
                var variant = ChalkRegistry.chalkVariants.get(DyeColor.WHITE);
                var records = new ArrayList<Object>();
                int index = 0;
                for (boolean glow : new boolean[]{false, true}) {
                    for (Direction facing : Direction.values()) {
                        for (int orientation = 0; orientation <= 8; orientation++) {
                            BlockPos pos = pos(index++);
                            BlockState expected = (glow ? variant.glowChalkBlock : variant.chalkBlock)
                                    .defaultBlockState().setValue(ChalkMarkBlock.FACING, facing)
                                    .setValue(ChalkMarkBlock.ORIENTATION, orientation);
                            if (ticks == 5) {
                                level.getChunkAt(pos);
                                if (write) {
                                    require(level.isEmptyBlock(pos), "Fixture world was not empty at " + pos);
                                    level.setBlock(pos.relative(facing.getOpposite()), Blocks.STONE.defaultBlockState(), 3);
                                    require(level.setBlock(pos, expected, 3), "Placement failed " + pos);
                                }
                            } else {
                                var actual = level.getBlockState(pos);
                                require(actual == expected, "Persisted state mismatch at " + pos + ": " + actual);
                                require(level.getBlockState(pos.relative(facing.getOpposite())).is(Blocks.STONE), "Missing persisted support");
                                var attachment = BlockBoundAttachment.get(level, pos);
                                require(attachment != null && attachment.holder() instanceof ChalkMarks, "Missing reattached holder " + pos);
                                require(attachment.holder().getElements().size() == 1, "Duplicate display holder " + pos);
                                require(attachment.holder().getElements().getFirst() instanceof ItemDisplayElement, "Missing mark display " + pos);
                                var display = (ItemDisplayElement) attachment.holder().getElements().getFirst();
                                require(glow ? Brightness.FULL_BRIGHT.equals(display.getBrightness()) : display.getBrightness() == null,
                                        "Incorrect persisted glow brightness " + pos);
                                var record = new LinkedHashMap<String, Object>();
                                record.put("pos", java.util.List.of(pos.getX(), pos.getY(), pos.getZ()));
                                record.put("block", BuiltInRegistries.BLOCK.getKey(actual.getBlock()).toString());
                                record.put("facing", facing.getSerializedName());
                                record.put("orientation", orientation);
                                record.put("holder_reattached", true);
                                records.add(record);
                            }
                        }
                    }
                }
                if (ticks == 10) {
                    var result = new LinkedHashMap<String, Object>();
                    result.put("passed", true);
                    result.put("phase", write ? "write" : "restart");
                    result.put("mark_count", index);
                    result.put("states", records);
                    Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(result));
                    done = true;
                }
            } catch (Throwable error) {
                done = true;
                error.printStackTrace();
                try {
                    Files.writeString(output, new GsonBuilder().create().toJson(java.util.Map.of("passed", false, "error", error.toString())));
                } catch (Exception ignored) {}
            }
        });
    }
}
