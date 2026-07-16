package observable.fabric

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import observable.Observable
import observable.client.MinecraftHelper
import observable.client.Overlay

class Client : ClientModInitializer {
    override fun onInitializeClient() {
        Observable.clientInit()

        LevelRenderEvents.END_MAIN.register { ctx ->
            Overlay.render(
                ctx.poseStack(),
                MinecraftHelper.getDeltaTracker(Minecraft.getInstance()).getGameTimeDeltaPartialTick(true)
            )
        }
    }
}
