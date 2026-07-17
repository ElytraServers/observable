package observable.client

import com.mojang.blaze3d.vertex.*
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.Font.DisplayMode
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.Vec3
import observable.Observable
import kotlin.math.pow
import kotlin.math.roundToInt

object Overlay {
    data class Color(val r: Int, val g: Int, val b: Int, val a: Int) {
        companion object {
            fun fromNanos(rateNanos: Double): Color {
                val micros = rateNanos / 1000.0
                return Color(micros)
            }
        }

        constructor(
            rateMicros: Double
        ) : this(
            (rateMicros / 100.0 * 255).roundToInt().coerceIn(0, 255),
            ((100.0 - rateMicros) / 100.0 * 255).roundToInt().coerceIn(0, 255),
            0,
            (rateMicros / 100.0 * 255).roundToInt().coerceIn(20, 100)
        )

        val hex: Int =
            with(this) {
                val red = if (r > g) 0xFFu else (255 * r / g).toUInt()
                val green = if (g > r) 0xFFu else (255 * g / r).toUInt()
                (red shl 16) or (green shl 8) or (0xFFu shl 24)
            }
                .toInt()
    }

    sealed class Entry(val color: Color) {
        data class EntityEntry(val entityId: Int, val rate: Double) : Entry(Color.fromNanos(rate)) {
            val entity
                get() = Minecraft.getInstance().level?.getEntity(entityId)
        }

        data class BlockEntry(val pos: BlockPos, val rate: Double) : Entry(Color.fromNanos(rate))
    }

    var enabled = true
    var entities: List<Entry.EntityEntry> = ArrayList()
    var blocks: List<Entry.BlockEntry> = ArrayList()
    var blockMap = mapOf<ChunkPos, List<Entry.BlockEntry>>()
    lateinit var loc: Vec3
    var meshPosition: Vec3 = Vec3.ZERO

    val DIST_FAC = 1.0 / (2 * 16.0.pow(2)).pow(.5)

    val font: Font by lazy { Minecraft.getInstance().font }

    private val renderType: RenderType by lazy {
        val setup = RenderSetup.builder(RenderPipelines.DEBUG_QUADS)
            .setOutputTarget(OutputTarget.MAIN_TARGET)
            .createRenderSetup()
        RenderType.create("observable_overlay", setup)
    }

    fun load(lvl: ClientLevel? = null) {
        val data = Observable.RESULTS ?: return
        val level = lvl ?: Minecraft.getInstance().level ?: return
        val levelLocation = level.dimension().identifier()
        val ticks = data.ticks
        val norm = ClientSettings.normalized
        entities =
            data.entities[levelLocation]
                ?.map {
                    Entry.EntityEntry(
                        it.entityId!!,
                        it.rate * (if (norm) it.ticks.toDouble() / ticks else 1.0)
                    )
                }
                .orEmpty()
                .filter { it.rate >= ClientSettings.minRate }
                .sortedByDescending { it.rate }

        blocks =
            data.blocks[levelLocation]
                ?.map {
                    Entry.BlockEntry(
                        it.position,
                        it.rate * (if (norm) it.ticks.toDouble() / ticks else 1.0)
                    )
                }
                ?.filter { it.rate >= ClientSettings.minRate }
                .orEmpty()
        blockMap = blocks.groupBy { ChunkPos.containing(it.pos) }
    }

    inline fun loadSync(lvl: ClientLevel? = null) = synchronized(this) { this.load(lvl) }

    fun render(poseStack: PoseStack, partialTicks: Float) {
        if (!enabled || Observable.RESULTS == null) return

        val camera = Minecraft.getInstance().gameRenderer.mainCamera
        val bufSrc = MinecraftHelper.getRenderBuffers(Minecraft.getInstance()).bufferSource()

        poseStack.pushPose()

        synchronized(this) {
            val cpos = ChunkPos.containing(Minecraft.getInstance().player!!.blockPosition())
            val dist = (ClientSettings.maxBlockDist / 16).coerceAtLeast(2)
            for (x in (cpos.x - dist)..(cpos.x + dist)) {
                for (y in (cpos.z - dist)..(cpos.z + dist)) {
                    blockMap[ChunkPos(x, y)]?.forEach { entry ->
                        val maxDist = ClientSettings.maxBlockDist * ClientSettings.maxBlockDist
                        if (MinecraftHelper.getCameraBlockPosition(camera).distSqr(entry.pos) < maxDist) {
                            drawBlock(entry, poseStack, camera, bufSrc)
                        }
                    }
                }
            }

            val maxEntityIndex = ClientSettings.maxEntityCount - 1
            for ((i, entry) in entities.withIndex()) {
                if (i > maxEntityIndex) break
                drawEntity(entry, poseStack, partialTicks, camera, bufSrc)
            }

            // Build and draw block outline mesh — rebuilt every frame.
            // ByteBufferBuilder must be closed AFTER MeshData because the Result
            // backing the mesh holds a reference to the builder's native memory.
            val camPos = MinecraftHelper.getCameraPosition(camera)
            val nearbyBlocks = blocks.filter { block ->
                block.pos.distSqr(MinecraftHelper.getCameraBlockPosition(camera)) < 1_440_000
            }
            if (nearbyBlocks.isNotEmpty()) {
                ByteBufferBuilder(renderType.bufferSize() * nearbyBlocks.size).use { builder ->
                    val buf = BufferBuilder(builder, renderType.mode(), renderType.format())
                    val outlineStack = PoseStack()

                    for (entry in nearbyBlocks) {
                        drawBlockOutline(entry, outlineStack, camera, buf)
                    }

                    buf.build()?.use { mesh ->
                        poseStack.pushPose()
                        poseStack.mulPose(camera.rotation().invert())
                        meshPosition.subtract(camPos).apply {
                            poseStack.translate(x, y, z)
                        }
                        renderType.draw(mesh)
                        poseStack.popPose()
                    }
                }
            }
        }

        poseStack.popPose()
        bufSrc.endBatch()
    }

    inline fun drawEntity(
        entry: Entry.EntityEntry,
        poseStack: PoseStack,
        partialTicks: Float,
        camera: Camera,
        bufSrc: MultiBufferSource
    ) {
        val rate = entry.rate
        val entity = entry.entity ?: return
        if (entity.isRemoved) return

        val pos = entity.getPosition(partialTicks)
        if (MinecraftHelper.getCameraPosition(camera).distanceTo(pos) > ClientSettings.maxEntityDist) return

        poseStack.pushPose()
        var text = "${(rate / 1000).roundToInt()} μs/t"
        if (!entity.isAlive) {
            text += " [X]"
        }

        poseStack.translate(
            pos.x - MinecraftHelper.getCameraPosition(camera).x,
            pos.y + entity.bbHeight + 0.33 - MinecraftHelper.getCameraPosition(camera).y,
            pos.z - MinecraftHelper.getCameraPosition(camera).z
        )
        poseStack.mulPose(camera.rotation())
        poseStack.scale(0.025F, -0.025F, 0.025F)
        font.drawInBatch(
            text,
            -font.width(text).toFloat() / 2,
            0F,
            entry.color.hex,
            false,
            poseStack.last().pose(),
            bufSrc,
            DisplayMode.SEE_THROUGH,
            0,
            0xF000F0
        )

        poseStack.popPose()
    }

    private inline fun drawBlockOutline(
        entry: Entry.BlockEntry,
        poseStack: PoseStack,
        camera: Camera,
        buf: VertexConsumer
    ) {
        poseStack.pushPose()

        Vec3.atLowerCornerOf(entry.pos).subtract(MinecraftHelper.getCameraPosition(camera))
            .apply { poseStack.translate(x, y, z) }
        val mat = poseStack.last().pose()
        entry.color.apply {
            buf.addVertex(mat, 0F, 1F, 0F).setColor(r, g, b, a)
            buf.addVertex(mat, 0F, 1F, 1F).setColor(r, g, b, a)
            buf.addVertex(mat, 1F, 1F, 1F).setColor(r, g, b, a)
            buf.addVertex(mat, 1F, 1F, 0F).setColor(r, g, b, a)

            buf.addVertex(mat, 0F, 1F, 0F).setColor(r, g, b, a)
            buf.addVertex(mat, 1F, 1F, 0F).setColor(r, g, b, a)
            buf.addVertex(mat, 1F, 0F, 0F).setColor(r, g, b, a)
            buf.addVertex(mat, 0F, 0F, 0F).setColor(r, g, b, a)

            buf.addVertex(mat, 1F, 1F, 1F).setColor(r, g, b, a)
            buf.addVertex(mat, 0F, 1F, 1F).setColor(r, g, b, a)
            buf.addVertex(mat, 0F, 0F, 1F).setColor(r, g, b, a)
            buf.addVertex(mat, 1F, 0F, 1F).setColor(r, g, b, a)

            buf.addVertex(mat, 0F, 1F, 1F).setColor(r, g, b, a)
            buf.addVertex(mat, 0F, 1F, 0F).setColor(r, g, b, a)
            buf.addVertex(mat, 0F, 0F, 0F).setColor(r, g, b, a)
            buf.addVertex(mat, 0F, 0F, 1F).setColor(r, g, b, a)

            buf.addVertex(mat, 1F, 0F, 1F).setColor(r, g, b, a)
            buf.addVertex(mat, 1F, 0F, 0F).setColor(r, g, b, a)
            buf.addVertex(mat, 1F, 1F, 0F).setColor(r, g, b, a)
            buf.addVertex(mat, 1F, 1F, 1F).setColor(r, g, b, a)

            buf.addVertex(mat, 1F, 0F, 0F).setColor(r, g, b, a)
            buf.addVertex(mat, 1F, 0F, 1F).setColor(r, g, b, a)
            buf.addVertex(mat, 0F, 0F, 1F).setColor(r, g, b, a)
            buf.addVertex(mat, 0F, 0F, 0F).setColor(r, g, b, a)
        }

        poseStack.popPose()
    }

    private inline fun drawBlock(
        entry: Entry.BlockEntry,
        poseStack: PoseStack,
        camera: Camera,
        bufSrc: MultiBufferSource
    ) {
        poseStack.pushPose()

        val (pos, rate) = entry
        val text = "${(rate / 1000).roundToInt()} μs/t"

        val col: Int = -0x1
        Vec3.atCenterOf(pos).subtract(MinecraftHelper.getCameraPosition(camera)).apply {
            poseStack.translate(x, y, z)
            poseStack.mulPose(camera.rotation())
            poseStack.scale(0.025F, -0.025F, 0.025F)
            font.drawInBatch(
                text,
                -font.width(text).toFloat() / 2,
                0F,
                col,
                false,
                poseStack.last().pose(),
                bufSrc,
                DisplayMode.SEE_THROUGH,
                0,
                0xF000F0
            )
        }

        poseStack.popPose()
    }
}
