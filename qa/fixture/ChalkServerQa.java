package chalk.qa;
import java.nio.file.*;
import java.util.*;
import de.dafuqs.chalk.common.ChalkRegistry;
import de.dafuqs.chalk.common.blocks.ChalkMarkBlock;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** Coordinates assertions only; placements originate in the connected client's game mode. */
public final class ChalkServerQa implements ModInitializer {
    final Path control=Path.of(System.getProperty("chalk.qa.control"));
    final BlockPos support=new BlockPos(0,101,0), mark=support.south();
    String previous="";
    boolean failed;
    static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
    void write(String name,String text)throws Exception {Path temporary=control.resolve(name+".tmp");Files.writeString(temporary,text);Files.move(temporary,control.resolve(name),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    public void onInitialize(){new ChalkChecks().onInitialize();ServerTickEvents.END_SERVER_TICK.register(server->{if(failed)return;try{
        if(server.getPlayerList().getPlayers().isEmpty())return;
        var p=server.getPlayerList().getPlayers().getFirst();var level=server.overworld();
        Path request=control.resolve("request");if(!Files.exists(request))return;
        String command=Files.readString(request).trim();if(command.equals(previous))return;
        var variant=ChalkRegistry.chalkVariants.get(DyeColor.WHITE);
        if(command.startsWith("setup:")){
            int index=Integer.parseInt(command.substring(6));
            p.getInventory().clearContent();
            for(int x=-2;x<3;x++)for(int z=-1;z<5;z++)level.setBlock(new BlockPos(x,100,z),Blocks.STONE.defaultBlockState(),3);
            level.setBlock(support,Blocks.STONE.defaultBlockState(),3);level.removeBlock(mark,false);
            p.connection.teleport(.5,101,3,180,20);
            p.getInventory().setSelectedSlot(0);p.getInventory().setItem(0,new ItemStack(index<9?variant.chalkItem:variant.glowChalkItem));
            p.inventoryMenu.broadcastFullState();write("ready",command);
        }else if(command.startsWith("inspect:")){
            int index=Integer.parseInt(command.substring(8));var state=level.getBlockState(mark);
            check(state.is(index<9?variant.chalkBlock:variant.glowChalkBlock),"server mark identity index="+index+" state="+state);
            check(state.getValue(ChalkMarkBlock.FACING)==Direction.SOUTH,"server facing");
            check(state.getValue(ChalkMarkBlock.ORIENTATION)==index%9,"server orientation index="+index);
            check(p.getMainHandItem().is(index<9?variant.chalkItem:variant.glowChalkItem),"original server item");
            check(p.getMainHandItem().getDamageValue()==1,"actual client placement consumes one durability");
            write("ack",command);
        }else if(command.startsWith("redraw:")){
            int index=Integer.parseInt(command.substring(7));var state=level.getBlockState(mark);
            check(state.getValue(ChalkMarkBlock.ORIENTATION)==(index+1)%9,"redrawn orientation");
            check(p.getMainHandItem().getDamageValue()==2,"redraw consumes durability");write("ack",command);
        }else if(command.startsWith("craftsetup:")){
            int variantIndex=Integer.parseInt(command.substring(11));p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);p.getInventory().clearContent();
            for(int slot=0;slot<5;slot++)p.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
            p.getInventory().setItem(9,new ItemStack(net.minecraft.world.item.Items.CALCITE,2));
            if(variantIndex==1)p.getInventory().setItem(10,new ItemStack(net.minecraft.world.item.Items.GLOW_INK_SAC));
            p.inventoryMenu.broadcastFullState();write("ack",command);
        }else if(command.startsWith("craftinspect:")){
            int variantIndex=Integer.parseInt(command.substring(13));int count=0;
            for(int slot=0;slot<36;slot++){var stack=p.getInventory().getItem(slot);if(stack.is(variantIndex==0?variant.chalkItem:variant.glowChalkItem)){count+=stack.getCount();check(stack.getDamageValue()==0,"crafted chalk starts undamaged");}}
            check(count==1,"client crafted original chalk variant="+variantIndex+" count="+count);write("ack",command);
        }else if(command.equals("creativesetup")){
            p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);p.getInventory().clearContent();p.inventoryMenu.broadcastFullState();write("ack",command);
        }else if(command.equals("creativeinspect")){
            check(p.getInventory().getItem(0).is(variant.chalkItem),"native creative-tab raw item packet preserves white chalk");
            check(p.getInventory().getItem(0).getDamageValue()==0,"native creative raw item starts undamaged");write("ack",command);
        }else if(command.equals("picksetup")){
            p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);p.getInventory().clearContent();p.connection.teleport(.5,101,3,180,29.25f);p.inventoryMenu.broadcastFullState();write("ack",command);
        }else if(command.equals("pickinspect")){
            check(p.getMainHandItem().is(variant.glowChalkItem),"creative pick restores original glow chalk");write("ack",command);
        }else if(command.startsWith("removed:")){
            check(level.isEmptyBlock(mark),"client broke mark");check(level.getBlockState(support).is(Blocks.STONE),"erase preserves support block");write("ack",command);
        }
        previous=command;
    }catch(Throwable t){failed=true;t.printStackTrace();try{write("failure","FAIL "+t);}catch(Exception ignored){}}});}
}
