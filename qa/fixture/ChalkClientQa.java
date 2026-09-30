package chalk.qa;
import java.nio.file.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** No Chalk or shim imports: the no-Chalk client exercises ordinary Minecraft packets. */
public final class ChalkClientQa implements ClientModInitializer {
    final Path control=Path.of(System.getProperty("chalk.qa.control"));
    final BlockPos support=new BlockPos(0,101,0),mark=support.south();
    int phase,index,ticks,craftVariant;boolean done;
    void write(String name,String text)throws Exception {Path temporary=control.resolve(name+".tmp");Files.writeString(temporary,text);Files.move(temporary,control.resolve(name),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    boolean has(String name,String text)throws Exception {Path p=control.resolve(name);return Files.exists(p)&&Files.readString(p).equals(text);}
    void check(boolean value,String text)throws Exception {if(!value)throw new AssertionError(text);Files.writeString(control.resolve("assertions.txt"),text+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    public void onInitializeClient(){ClientTickEvents.END_CLIENT_TICK.register(c->{if(c.gui.screen() instanceof net.minecraft.client.gui.screens.ConfirmScreen screen && screen.getNarrationMessage().getString().contains("resource pack")){for(var child:screen.children())if(child instanceof net.minecraft.client.gui.components.Button button && button.getMessage().getString().equals("Proceed"))button.onPress(null);}if(c.getCurrentServer()!=null){c.getCurrentServer().setResourcePackStatus(net.minecraft.client.multiplayer.ServerData.ServerPackStatus.ENABLED);c.getDownloadedPackSource().allowServerPacks();}if(done||c.player==null||c.level==null||c.gameMode==null)return;try{
        if(++ticks>1800)throw new AssertionError("timeout phase="+phase+" index="+index);
        switch(phase){
            case 0->{if(Boolean.getBoolean("chalk.qa.creative-only")){write("request","creativesetup");phase=15;}else{write("request","setup:"+index);phase=1;}ticks=0;}
            case 1->{if(!has("ready","setup:"+index)||ticks<30)return;
                var stack=c.player.getMainHandItem();check(!stack.isEmpty(),"chalk synchronized index="+index);
                String id=BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();check(id.startsWith("minecraft:")||id.startsWith("chalk:"),"safe client item "+id);
                check(stack.getMaxDamage()==64,"durability synchronized index="+index);
                int region=index%9;double x=(region%3+.5)/3.0,y=1-(region/3+.5)/3.0;
                c.gameMode.useItemOn(c.player,InteractionHand.MAIN_HAND,new BlockHitResult(new Vec3(x,101+y,1),Direction.SOUTH,support,false));phase=2;ticks=0;}
            case 2->{if(ticks<15)return;write("request","inspect:"+index);phase=3;ticks=0;}
            case 3->{if(!has("ack","inspect:"+index))return;check(c.player.getMainHandItem().getDamageValue()==1,"server-confirmed damage synchronized index="+index);
                int displays=0;for(var entity:c.level.entitiesForRendering())if(entity instanceof net.minecraft.world.entity.Display.ItemDisplay display){
                    var rendered=display.itemRenderState();if(rendered==null)continue;var model=rendered.itemStack().get(net.minecraft.core.component.DataComponents.ITEM_MODEL);
                    if(model!=null&&model.getNamespace().equals("chalk_polymer_compat")){displays++;var brightness=net.minecraft.world.entity.Display.class.getDeclaredMethod("getBrightnessOverride");brightness.setAccessible(true);check(index<9?brightness.invoke(display)==null:net.minecraft.util.Brightness.FULL_BRIGHT.equals(brightness.invoke(display)),"normal/glow display brightness index="+index);}
                }check(displays==1,"one synchronized virtual mark index="+index);if(index==4||index==13)net.minecraft.client.Screenshot.grab(c,false);
                int region=(index+1)%9;double x=(region%3+.5)/3.0,y=1-(region/3+.5)/3.0;
                c.gameMode.useItemOn(c.player,InteractionHand.MAIN_HAND,new BlockHitResult(new Vec3(x,101+y,1),Direction.SOUTH,support,false));phase=6;ticks=0;}
            case 6->{if(ticks<15)return;write("request","redraw:"+index);phase=7;ticks=0;}
            case 7->{if(!has("ack","redraw:"+index))return;check(c.player.getMainHandItem().getDamageValue()==2,"redraw damage synchronized index="+index);
                if(index==17){write("request","picksetup");phase=8;ticks=0;}else{c.gameMode.startDestroyBlock(support,Direction.SOUTH);phase=4;ticks=0;}}
            case 8->{if(ticks<20||!has("ack","picksetup"))return;c.player.connection.send(new net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket(support,false));phase=9;ticks=0;}
            case 9->{if(ticks<15)return;write("request","pickinspect");phase=10;ticks=0;}
            case 10->{if(!has("ack","pickinspect"))return;check(!c.player.getMainHandItem().isEmpty(),"creative pick synchronized chalk");c.gameMode.startDestroyBlock(support,Direction.SOUTH);phase=4;ticks=0;}
            case 4->{if(ticks<15)return;write("request","removed:"+index);phase=5;ticks=0;}
            case 11->{if(ticks<20||!has("ack","craftsetup:"+craftVariant))return;
                c.gameMode.handleContainerInput(c.player.inventoryMenu.containerId,9,0,net.minecraft.world.inventory.ContainerInput.PICKUP,c.player);
                c.gameMode.handleContainerInput(c.player.inventoryMenu.containerId,1,1,net.minecraft.world.inventory.ContainerInput.PICKUP,c.player);
                c.gameMode.handleContainerInput(c.player.inventoryMenu.containerId,2,0,net.minecraft.world.inventory.ContainerInput.PICKUP,c.player);
                if(craftVariant==1){c.gameMode.handleContainerInput(c.player.inventoryMenu.containerId,10,0,net.minecraft.world.inventory.ContainerInput.PICKUP,c.player);c.gameMode.handleContainerInput(c.player.inventoryMenu.containerId,3,0,net.minecraft.world.inventory.ContainerInput.PICKUP,c.player);}
                phase=12;ticks=0;}
            case 12->{if(ticks<20)return;check(c.player.inventoryMenu.getSlot(0).getItem().getMaxDamage()==64,"actual crafting output synchronized variant="+craftVariant);
                c.gameMode.handleContainerInput(c.player.inventoryMenu.containerId,0,0,net.minecraft.world.inventory.ContainerInput.QUICK_MOVE,c.player);phase=13;ticks=0;}
            case 13->{if(ticks<20)return;write("request","craftinspect:"+craftVariant);phase=14;ticks=0;}
            case 14->{if(!has("ack","craftinspect:"+craftVariant))return;check(true,"actual client crafting preserves original chalk variant="+craftVariant);
                if(craftVariant==0){craftVariant=1;write("request","craftsetup:1");phase=11;ticks=0;}else if(Boolean.getBoolean("chalk.qa.native")){write("request","creativesetup");phase=15;ticks=0;}else{write("result.txt","PASS 18 real client placements: both chalk types, nine orientations, durability, redraw, virtual display brightness, creative pick, breaking and both client crafting recipes; native="+Boolean.getBoolean("chalk.qa.native")+"\n");done=true;}}
            case 15->{if(ticks<20||!has("ack","creativesetup"))return;
                var item=BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse("chalk:white_chalk"));
                check(item!=null&&item!=net.minecraft.world.item.Items.AIR,"native creative tab supplies original local Chalk registry item");
                c.gameMode.handleCreativeModeItemAdd(new net.minecraft.world.item.ItemStack(item),36);phase=16;ticks=0;}
            case 16->{if(ticks<20)return;write("request","creativeinspect");phase=17;ticks=0;}
            case 17->{if(!has("ack","creativeinspect"))return;check(true,"native raw creative item packet preserves original server chalk");
                write("result.txt",Boolean.getBoolean("chalk.qa.creative-only")?"PASS native raw creative inventory packet\n":"PASS 18 real client placements, redraw, durability, display brightness, breaking, pick, both crafting recipes and native raw creative inventory packet\n");done=true;}
            case 5->{if(!has("ack","removed:"+index))return;check(true,"client placement, server orientation, durability and removal index="+index);index++;
                if(index==18){write("request","craftsetup:0");phase=11;ticks=0;}
                else{phase=0;ticks=0;}}
        }
    }catch(Throwable t){done=true;t.printStackTrace();try{write("failure","FAIL client "+t);}catch(Exception ignored){}}});}
}
