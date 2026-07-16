package observable.forge

import net.minecraft.client.Minecraft
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import observable.client.MinecraftHelper
import observable.client.Overlay

object ForgeClientHooks {
    @SubscribeEvent
    fun onRender(ev: RenderLevelStageEvent.AfterTranslucentParticles) {
        Overlay.render(
            ev.getPoseStack(),
            MinecraftHelper.getDeltaTracker(Minecraft.getInstance()).getGameTimeDeltaPartialTick(true)
        )
    }
}
