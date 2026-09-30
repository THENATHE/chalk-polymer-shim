package com.thenathe.chalkcompat;

import de.dafuqs.chalk.common.ChalkRegistry;
import de.dafuqs.chalk.common.blocks.ChalkMarkBlock;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.item.PolymerItem;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class ChalkCompat {
    private ChalkCompat() {}
    public static void initialize() {
        ChalkResources.initialize();
        ChalkMarks.initialize();
        for (var variant : ChalkRegistry.chalkVariants.values()) {
            for (var item : new Item[]{variant.chalkItem, variant.glowChalkItem}) {
                PolymerItem.registerOverlay(item, new PolymerItem() {
                    public Item getPolymerItem(ItemStack stack, PacketContext context) { return Items.PAPER; }
                    public Identifier getPolymerItemModel(ItemStack stack, PacketContext context, HolderLookup.Provider lookup) {
                        return BuiltInRegistries.ITEM.getKey(item);
                    }
                    public void modifyBasePolymerItemStack(ItemStack original, ItemStack output, PacketContext context, HolderLookup.Provider lookup) {
                        output.set(DataComponents.ITEM_NAME, Component.translatableWithFallback(item.getDescriptionId(), title(BuiltInRegistries.ITEM.getKey(item).getPath())));
                    }
                });
            }
            for (var block : new net.minecraft.world.level.block.Block[]{variant.chalkBlock, variant.glowChalkBlock}) {
                PolymerBlock.registerOverlay(block, new PolymerBlock() {
                    public BlockState getPolymerBlockState(BlockState state, PacketContext context) { return Blocks.AIR.defaultBlockState(); }
                    public boolean handleMiningOnServer(ItemStack stack, BlockState state, BlockPos pos, ServerPlayer player) { return true; }
                });
            }
        }
    }
    private static String title(String id) {
        StringBuilder result = new StringBuilder();
        for (String part : id.split("_")) { if (!result.isEmpty()) result.append(' '); result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)); }
        return result.toString();
    }
    /** Redirect the exact supporting face; all vanilla permission/reach checks still follow. */
    public static BlockPos target(ServerPlayer player, BlockPos support, Direction face) {
        BlockPos candidate = support.relative(face);
        if (!player.level().isLoaded(candidate)) return support;
        BlockState state = player.level().getBlockState(candidate);
        return state.getBlock() instanceof ChalkMarkBlock && state.getValue(ChalkMarkBlock.FACING) == face ? candidate : support;
    }
}
