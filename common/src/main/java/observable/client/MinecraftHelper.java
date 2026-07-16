package observable.client;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Helper to access Minecraft getter methods that conflict with private fields of the same name in Kotlin.
 */
public class MinecraftHelper {
    public static Vec3 getCameraPosition(Camera camera) {
        return camera.position();
    }

    public static BlockPos getCameraBlockPosition(Camera camera) {
        return camera.blockPosition();
    }

    public static RenderBuffers getRenderBuffers(Minecraft minecraft) {
        return minecraft.renderBuffers();
    }

    public static DeltaTracker getDeltaTracker(Minecraft minecraft) {
        return minecraft.getDeltaTracker();
    }
}
