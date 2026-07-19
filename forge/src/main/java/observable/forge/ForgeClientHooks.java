package observable.forge;

import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import observable.client.MinecraftHelper;
import observable.client.Overlay;

public class ForgeClientHooks {

    @SubscribeEvent
    public void onRender(RenderLevelStageEvent.AfterTranslucentParticles ev) {
        Overlay.INSTANCE.render(
                ev.getPoseStack(),
                MinecraftHelper.getDeltaTracker(Minecraft.getInstance()).getGameTimeDeltaPartialTick(true)
        );
    }

}
