package com.thenathe.chalkcompat;

import de.dafuqs.chalk.common.ChalkRegistry;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.BlockBoundAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;

/** One packet-only display per mark; Polymer owns chunk tracking and destruction. */
public final class ChalkMarks extends ElementHolder {
    private final ItemDisplayElement display = new ItemDisplayElement();

    public static void initialize() {
        var overlay = new BlockWithElementHolder() {
            @Override
            public ElementHolder createElementHolder(ServerLevel level, BlockPos pos, BlockState state) {
                return create(state);
            }
        };
        for (var variant : ChalkRegistry.chalkVariants.values()) {
            if (!BlockWithElementHolder.registerOverlay(variant.chalkBlock, overlay)
                    || !BlockWithElementHolder.registerOverlay(variant.glowChalkBlock, overlay)) {
                throw new IllegalStateException("Another mod already provides Chalk virtual mark holders");
            }
        }
    }

    public static ChalkMarks create(BlockState state) { return new ChalkMarks(state); }

    private ChalkMarks(BlockState state) {
        // NONE renders block geometry at its original unit scale around the
        // block-centred attachment; FIXED would apply item-frame transforms.
        display.setItemDisplayContext(ItemDisplayContext.NONE);
        // ItemDisplayRenderer adds a 180-degree Y rotation even for NONE.
        // Undo it on the model side of the state rotation so thin one-sided
        // Chalk quads remain on the supporting face, facing the player.
        display.setRightRotation(new Quaternionf().rotationY((float) Math.PI));
        display.setDisplaySize(1.5f, 1.5f);
        display.setViewRange(1);
        display.setShadowRadius(0);
        display.setShadowStrength(0);
        display.setInterpolationDuration(0);
        update(state);
        addElement(display);
    }

    private void update(BlockState state) {
        var model = ChalkResources.model(state);
        display.setItem(model.stack());
        display.setLeftRotation(model.rotation());
        display.setBrightness(model.glow() ? Brightness.FULL_BRIGHT : null);
    }

    @Override
    public void notifyUpdate(HolderAttachment.UpdateType updateType) {
        super.notifyUpdate(updateType);
        if (updateType == BlockBoundAttachment.BLOCK_STATE_UPDATE) {
            var attachment = BlockBoundAttachment.get(this);
            if (attachment != null) {
                update(attachment.getBlockState());
                tick();
            }
        }
    }
}
