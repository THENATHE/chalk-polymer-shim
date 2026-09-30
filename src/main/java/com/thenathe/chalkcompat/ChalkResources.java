package com.thenathe.chalkcompat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.dafuqs.chalk.common.ChalkRegistry;
import de.dafuqs.chalk.common.blocks.ChalkMarkBlock;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Vanilla-readable views of the artwork supplied by the installed Chalk JAR. */
public final class ChalkResources {
    public static final String NAMESPACE = "chalk_polymer_compat";
    private static final Map<BlockState, MarkModel> STATES = new IdentityHashMap<>();
    private static final Map<Identifier, Definition> DEFINITIONS = new LinkedHashMap<>();

    private ChalkResources() {}

    /** Called after Chalk has registered its variants, including the optional color addon. */
    public static void initialize() {
        for (var entry : ChalkRegistry.chalkVariants.entrySet()) {
            int color = ChalkRegistry.dyeColors.get(entry.getKey());
            register(entry.getValue().chalkBlock, color, false);
            register(entry.getValue().glowChalkBlock, color, true);
        }
        PolymerResourcePackUtils.addModAssetsWithoutCopy("chalk");
        PolymerResourcePackUtils.markAsRequired();
        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(ChalkResources::build);
    }

    private static void register(Block block, int color, boolean glow) {
        var blockId = BuiltInRegistries.BLOCK.getKey(block);
        var path = FabricLoader.getInstance().getModContainer("chalk").orElseThrow()
                .findPath("assets/" + blockId.getNamespace() + "/blockstates/" + blockId.getPath() + ".json")
                .orElseThrow(() -> new IllegalStateException("Missing Chalk blockstate: " + blockId));
        JsonObject variants;
        try (var reader = Files.newBufferedReader(path)) {
            variants = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("variants");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        for (Direction facing : Direction.values()) {
            for (int orientation = 0; orientation <= 8; orientation++) {
                String key = "facing=" + facing.getSerializedName() + ",orientation=" + orientation;
                var variant = variants.getAsJsonObject(key);
                if (variant == null) throw new IllegalStateException("Missing Chalk variant " + blockId + " " + key);
                var sourceModel = Identifier.parse(variant.get("model").getAsString());
                var model = Identifier.fromNamespaceAndPath(NAMESPACE,
                        "mark/" + blockId.getPath() + "/" + sourceModel.getPath());
                int x = variant.has("x") ? variant.get("x").getAsInt() : 0;
                int y = variant.has("y") ? variant.get("y").getAsInt() : 0;
                var state = block.defaultBlockState().setValue(ChalkMarkBlock.FACING, facing)
                        .setValue(ChalkMarkBlock.ORIENTATION, orientation);
                STATES.put(state, new MarkModel(model, x, y, glow));
                DEFINITIONS.put(model, new Definition(sourceModel, color));
            }
        }
    }

    private static void build(ResourcePackBuilder builder) {
        builder.addModToCredits("chalk");
        // Repair the source translation before Polymer parses/merges it.
        var assetsRoot = FabricLoader.getInstance().getModContainer("chalk").orElseThrow()
                .findPath("assets/chalk").orElseThrow();
        try (var paths = Files.walk(assetsRoot)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                String relative = assetsRoot.relativize(path).toString().replace('\\', '/');
                byte[] data = Files.readAllBytes(path);
                if (relative.equals("lang/ko_kr.json")) {
                    String korean = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                    korean = korean.replaceAll("(\"block\\.chalk\\.white_glow_chalk_mark\"\\s*:\\s*\"[^\"\\r\\n]*\")\\s*(?=\"item\\.chalk\\.red_chalk\")", "$1,\n");
                    data = korean.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                builder.addData("assets/chalk/" + relative, data);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        try (var license = ChalkResources.class.getResourceAsStream("/licenses/chalk-LICENSE.txt")) {
            if (license == null) throw new IllegalStateException("Missing Chalk artwork license");
            builder.addData("licenses/chalk/LICENSE", license.readAllBytes());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        DEFINITIONS.forEach((id, definition) -> {
            // The block models already contain the exact UVs, mark offsets and glow
            // shading. Item-model constant tints replace Chalk's client-only color provider.
            JsonObject tint = new JsonObject();
            tint.addProperty("type", "minecraft:constant");
            tint.addProperty("value", definition.color() & 0xffffff);
            var tints = new com.google.gson.JsonArray();
            tints.add(tint);
            JsonObject model = new JsonObject();
            model.addProperty("type", "minecraft:model");
            model.addProperty("model", definition.sourceModel().toString());
            model.add("tints", tints);
            JsonObject root = new JsonObject();
            root.add("model", model);
            builder.addStringData("assets/" + id.getNamespace() + "/items/" + id.getPath() + ".json", root.toString());
        });
    }

    /** Chalk's ordinary item definitions need no client code. */
    public static Identifier itemModel(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    public static MarkModel model(BlockState state) {
        var model = STATES.get(state);
        if (model == null) throw new IllegalArgumentException("Unregistered Chalk mark state: " + state);
        return model;
    }

    public static int stateCount() { return STATES.size(); }

    private record Definition(Identifier sourceModel, int color) {}

    public record MarkModel(Identifier model, int xRotation, int yRotation, boolean glow) {
        public ItemStack stack() {
            var stack = new ItemStack(Items.PAPER);
            stack.set(DataComponents.ITEM_MODEL, model);
            return stack;
        }

        public Quaternionf rotation() {
            // Blockstate rotations are clockwise and applied X first, then Y.
            return new Quaternionf().rotationYXZ((float) Math.toRadians(-yRotation),
                    (float) Math.toRadians(-xRotation), 0);
        }
    }
}
