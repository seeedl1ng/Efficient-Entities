package com.michaelsebero.efficiententities.renderer;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import com.michaelsebero.efficiententities.EfficientEntities;
import com.michaelsebero.efficiententities.EEConfig;
import com.michaelsebero.efficiententities.opengl.GpuSync;
import com.michaelsebero.efficiententities.opengl.PersistentBuffer;
import com.michaelsebero.efficiententities.opengl.VaoHelper;
import com.michaelsebero.efficiententities.util.CubeGeometry;
import com.michaelsebero.efficiententities.util.MatrixStack;
import com.michaelsebero.efficiententities.util.SimpleStack;

import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL44;

import sun.misc.Unsafe;

/**
 * Core renderer for Efficient Entities.
 *
 * <h3>Vertex layout — 24 bytes per vertex</h3>
 * <pre>
 *  bytes  0–11 : XYZ world position  (3 × GL_FLOAT = 12 bytes)
 *  bytes 12–19 : UV texture coords   (2 × GL_FLOAT =  8 bytes)
 *  bytes 20–22 : XYZ normal          (3 × GL_BYTE  =  3 bytes, packed into a 4-byte int)
 *  byte     23 : padding             (0)
 * </pre>
 *
 * <h3>Coordinate space</h3>
 * Vertices are written in <em>model space</em>: only the entity's bone-hierarchy
 * transforms (accumulated via {@link MatrixStack}) are applied.  The OpenGL
 * modelview matrix, which holds the entity's world→eye transform at the time
 * {@code endBatch()} is called, is left intact so the fixed-function pipeline
 * completes the transform.  No {@code glLoadIdentity} is issued before drawing.
 *
 * <h3>Memory writes</h3>
 * All vertex data is written via {@link Unsafe#putFloat}/{@link Unsafe#putInt},
 * bypassing {@link ByteBuffer} bounds-checks and eliminating JNI overhead on the
 * per-vertex hot path.
 *
 * <h3>Buffer management</h3>
 * Triple-buffered persistent VBOs with explicit-flush / unsynchronised mapping.
 * The driver is never asked to synchronise implicitly (no {@code GL_MAP_COHERENT_BIT});
 * we insert fence objects ourselves via {@link GpuSync} and call
 * {@code glFlushMappedBufferRange} before each draw.  When a VBO slice is too
 * small, it is resized at runtime; the old GL objects are cleaned up lazily after
 * the GPU-sync for that slice completes.
 *
 * <h3>VAOs</h3>
 * Each VBO slice has a pre-configured VAO with vertex attribute pointers recorded
 * at initialisation.  At draw time only a single {@code glBindVertexArray} is
 * needed rather than three separate client-state calls.
 *
 * <h3>Child rendering</h3>
 * Children are dispatched through {@link ModelRenderer#render(float)} so that
 * {@code MixinModelRenderer} fires for every child.  Per-part fallbacks
 * (OptiFine, chest TESRs, cow udder) are therefore honoured for child parts
 * exactly as for top-level parts.
 */
public class EfficientModelRenderer {

    // ------------------------------------------------------------------ Constants

    private static final int  BYTES_PER_VERTEX  = 24;
    private static final int  VERTICES_PER_CUBE = 24;    // 6 faces × 4 verts
    private static final int  INITIAL_CUBES     = 4096;
    private static final long INITIAL_CAPACITY  =
        (long) INITIAL_CUBES * VERTICES_PER_CUBE * BYTES_PER_VERTEX;
    private static final int  BUFFER_SLICES     = 3;

    /**
     * {@code glBufferStorage} flags: the buffer is written by the CPU and is
     * persistently mapped for its lifetime.  Coherent is NOT requested — we
     * issue explicit flushes.
     */
    private static final int STORE_FLAGS =
          GL30.GL_MAP_WRITE_BIT
        | GL44.GL_MAP_PERSISTENT_BIT;

    /**
     * {@code glMapBufferRange} flags: persistent write with explicit-flush and
     * unsynchronised access.  We manage CPU/GPU synchronisation ourselves via
     * {@link GpuSync}, so the driver does not need to stall for us.
     *
     * <p>Notable absence of {@code GL_MAP_COHERENT_BIT}: with coherent mapping the
     * driver must ensure visibility after every write, which can serialise pipeline
     * stages.  Explicit flushes are cheaper and give us control over exactly when
     * visibility is guaranteed.
     */
    private static final int MAP_FLAGS =
          GL30.GL_MAP_WRITE_BIT
        | GL44.GL_MAP_PERSISTENT_BIT
        | GL30.GL_MAP_FLUSH_EXPLICIT_BIT
        | GL30.GL_MAP_UNSYNCHRONIZED_BIT;

    private static final float RAD_TO_DEG      = (float) (180.0 / Math.PI);

    // ------------------------------------------------------------------ Unsafe

    private static final Unsafe UNSAFE;
    /** Offset of the {@code address} field in {@link java.nio.Buffer}. */
    private static final long   BUFFER_ADDRESS_OFFSET;

    static {
        try {
            Field fu = Unsafe.class.getDeclaredField("theUnsafe");
            fu.setAccessible(true);
            UNSAFE = (Unsafe) fu.get(null);

            Field fa = java.nio.Buffer.class.getDeclaredField("address");
            BUFFER_ADDRESS_OFFSET = UNSAFE.objectFieldOffset(fa);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Returns the native base address of a direct {@link ByteBuffer}. */
    private static long nativeAddress(ByteBuffer buf) {
        return UNSAFE.getLong(buf, BUFFER_ADDRESS_OFFSET);
    }

    // ------------------------------------------------------------------ Vanilla renderer registry

    /**
     * Parts that must be drawn by vanilla OpenGL rather than the GPU-batched path.
     * Populated at model construction time by
     * {@link com.michaelsebero.efficiententities.mixin.MixinModelBox}.
     * WeakHashMap backing ensures entries are collected when the model is GC'd.
     */
    private static final Set<ModelRenderer> VANILLA_RENDERERS =
        Collections.newSetFromMap(new WeakHashMap<>());

    public static void registerVanillaRenderer(ModelRenderer renderer) {
        VANILLA_RENDERERS.add(renderer);
    }

    public static boolean isVanillaRenderer(ModelRenderer renderer) {
        return VANILLA_RENDERERS.contains(renderer);
    }

    // ------------------------------------------------------------------ Singleton

    private static EfficientModelRenderer INSTANCE;

    public static EfficientModelRenderer instance() {
        if (INSTANCE == null) INSTANCE = new EfficientModelRenderer();
        return INSTANCE;
    }

    // ------------------------------------------------------------------ State flags

    private boolean initialised;
    /** {@code true} = persistent VBO path; {@code false} = heap fallback. */
    private boolean gpuPath;

    // ------------------------------------------------------------------ Buffer bookkeeping

    /** Capacity in bytes for each individual VBO slice (or the fallback buffer). */
    private long bufferCapacity;

    // GPU path — per-slice resources
    private final int[]    vbos       = new int[BUFFER_SLICES];
    private final int[]    vaos       = new int[BUFFER_SLICES];
    private final long[]   sliceAddrs = new long[BUFFER_SLICES]; // native write addresses
    private final Object[] sliceSyncs = new Object[BUFFER_SLICES];

    /**
     * Deferred GL cleanup tasks — each entry is a lambda that deletes an old
     * VBO+VAO pair.  Tasks are stored per slice and run at the start of the
     * next frame that reuses the same slice, after the GPU sync confirms the
     * previous draw using those resources is complete.
     */
    @SuppressWarnings("unchecked")
    private final SimpleStack<Runnable>[] deferredTasks = new SimpleStack[BUFFER_SLICES];

    // Current-frame hot fields (updated every beginFrame)
    private int  currentSlice;
    private int  currentVbo;
    private int  currentVao;
    private long currentAddr; // native write address for this frame's slice

    // Heap fallback (no persistent buffers)
    private ByteBuffer fallbackBuffer;

    // ------------------------------------------------------------------ Per-batch state

    /** Total vertices written to {@link #currentAddr} this frame. */
    private int     vertexCount;
    /** Value of {@link #vertexCount} when {@link #startBatch()} was called. */
    private int     batchStart;
    /** True between a {@link #startBatch()} / {@link #endBatch()} pair. */
    private boolean inBatch;

    /**
     * Per-entity bone-transform accumulator.  Reset to identity at each
     * {@link #startBatch()}.  Post-multiplied as bones are descended, then
     * popped on the way back up — exactly mirroring what GlStateManager does for
     * the GL matrix, but managed entirely in Java so no {@code glGetFloat} is
     * needed.
     */
    private final MatrixStack matrixStack = new MatrixStack();

    // ------------------------------------------------------------------ Diagnostics

    private int frameCount, batchesThisPeriod, cubesThisPeriod, autoWrapsThisPeriod;

    // ------------------------------------------------------------------ Lifecycle

    private void ensureInitialised() {
        if (initialised) return;

        for (int i = 0; i < BUFFER_SLICES; i++) {
            deferredTasks[i] = new SimpleStack<>();
        }

        if (PersistentBuffer.isAvailable() && GpuSync.isAvailable()) {
            gpuPath        = true;
            bufferCapacity = INITIAL_CAPACITY;
            allocateGpuSlices(INITIAL_CAPACITY);
            EfficientEntities.LOG.info(
                "[EfficientEntities] GPU path: {} KB × {} slices = {} KB total VBO.",
                INITIAL_CAPACITY / 1024, BUFFER_SLICES,
                INITIAL_CAPACITY * BUFFER_SLICES / 1024);
        } else {
            gpuPath        = false;
            bufferCapacity = INITIAL_CAPACITY;
            fallbackBuffer = ByteBuffer.allocateDirect((int) INITIAL_CAPACITY);
            currentAddr    = nativeAddress(fallbackBuffer);
            EfficientEntities.LOG.warn(
                "[EfficientEntities] Persistent buffers / GPU sync unavailable; " +
                "falling back to heap rendering.");
        }

        initialised = true;
    }

    /**
     * Allocates all per-slice GPU resources (VBO, VAO, persistent mapping) at the
     * given per-slice {@code capacity}.  Does NOT touch {@link #deferredTasks}.
     */
    private void allocateGpuSlices(long capacity) {
        for (int i = 0; i < BUFFER_SLICES; i++) {

            // Create buffer with persistent-write storage.
            vbos[i] = PersistentBuffer.createBuffer();
            PersistentBuffer.bindBuffer(GL15.GL_ARRAY_BUFFER, vbos[i], true);
            PersistentBuffer.initStorage(GL15.GL_ARRAY_BUFFER, vbos[i], capacity, STORE_FLAGS);
            ByteBuffer mapped = PersistentBuffer.mapBuffer(
                GL15.GL_ARRAY_BUFFER, vbos[i], 0L, capacity, MAP_FLAGS);
            sliceAddrs[i] = nativeAddress(mapped);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            // Record vertex attribute layout in a VAO so each draw only needs
            // one glBindVertexArray instead of three client-state calls.
            if (VaoHelper.isAvailable()) {
                vaos[i] = VaoHelper.createVao();
                VaoHelper.bindVao(vaos[i]);
                GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbos[i]);
                // Pointers are relative to byte 0 of the VBO.
                // glDrawArrays(first, count) then selects the right vertices by index.
                GL11.glVertexPointer(  3, GL11.GL_FLOAT, BYTES_PER_VERTEX,  0L);
                GL11.glTexCoordPointer(2, GL11.GL_FLOAT, BYTES_PER_VERTEX, 12L);
                GL11.glNormalPointer(     GL11.GL_BYTE,  BYTES_PER_VERTEX, 20L);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
                VaoHelper.bindVao(0);
            }
        }
    }

    // ------------------------------------------------------------------ Frame lifecycle

    public void beginFrame() {
        ensureInitialised();
        if (!initialised) return;

        if (gpuPath) {
            currentSlice = (currentSlice + 1) % BUFFER_SLICES;

            // CPU-wait for GPU to finish consuming this slice before overwriting it.
            Object sync = sliceSyncs[currentSlice];
            if (sync != null) {
                GpuSync.waitSync(sync);
                GpuSync.deleteSync(sync);
                sliceSyncs[currentSlice] = null;
            }

            // Drain deferred GL cleanup (old VBOs/VAOs from previous resizes).
            SimpleStack<Runnable> tasks = deferredTasks[currentSlice];
            while (!tasks.isEmpty()) tasks.pop().run();

            currentVbo  = vbos[currentSlice];
            currentVao  = vaos[currentSlice];
            currentAddr = sliceAddrs[currentSlice];
        }

		vertexCount = 0;
		batchStart  = 0;
		inBatch     = false;

		frameCount++;

		int period = EEConfig.logPeriodFrames;

		if (period > 0 && frameCount % period == 0) {
			EfficientEntities.LOG.info(
				"Last {} frames: {} batches, {} cubes, {} auto-wrapped.",
				period,
				batchesThisPeriod,
				cubesThisPeriod,
				autoWrapsThisPeriod
			);

			batchesThisPeriod = cubesThisPeriod = autoWrapsThisPeriod = 0;
		}
}

    public void finishFrame() {
        if (!initialised || !gpuPath) return;
        if (sliceSyncs[currentSlice] == null) {
            sliceSyncs[currentSlice] = GpuSync.createSync();
        }
    }

    // ------------------------------------------------------------------ Batch lifecycle

    /**
     * Called by the ASM-injected prolog around an entity model's {@code render()}
     * body, and also implicitly when a part is rendered outside any entity model
     * (auto-wrap path).
     */
    public void startBatch() {
        if (!initialised) return;
        // Fallback: each batch gets the whole buffer from the start.
        if (!gpuPath) vertexCount = 0;
        batchStart = vertexCount;
        inBatch    = true;
        matrixStack.reset();
    }

    /** Called by the ASM-injected epilog. Flushes and draws the accumulated batch. */
    public void endBatch() {
        if (!initialised) return;
        inBatch = false;
        drawBatch(batchStart, vertexCount);
    }

    /**
     * Issues the GPU draw for vertices in the index range {@code [from, to)}.
     *
     * <p>For the GPU path the mapped range is explicitly flushed before drawing.
     * No {@code glLoadIdentity} is issued — vertices are in model space and the
     * current GL modelview matrix (entity world→eye transform) must be left intact
     * so the fixed-function pipeline can complete the transform.
     *
     * <p>If VAOs are available the draw path is a single {@code glBindVertexArray}
     * plus {@code glDrawArrays}; otherwise attribute pointers are set up manually.
     */
    private void drawBatch(int from, int to) {
        int count = to - from;
        if (count <= 0) return;

        if (gpuPath) {
            // Notify the driver which bytes we wrote so it can make them visible to
            // the GPU.  Always bind first — non-DSA flush functions require a bound
            // buffer; DSA ones ignore the binding but binding is cheap.
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, currentVbo);
            GL30.glFlushMappedBufferRange(
                GL15.GL_ARRAY_BUFFER,
                (long) from  * BYTES_PER_VERTEX,
                (long) count * BYTES_PER_VERTEX);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            if (VaoHelper.isAvailable()) {
                VaoHelper.bindVao(currentVao);
            } else {
                long base = (long) from * BYTES_PER_VERTEX;
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, currentVbo);
                GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
                GL11.glVertexPointer(  3, GL11.GL_FLOAT, BYTES_PER_VERTEX, base);
                GL11.glTexCoordPointer(2, GL11.GL_FLOAT, BYTES_PER_VERTEX, base + 12L);
                GL11.glNormalPointer(     GL11.GL_BYTE,  BYTES_PER_VERTEX, base + 20L);
            }

            // 'from' is the vertex index (not byte offset).  With the VAO's pointer
            // starting at byte 0 and stride BYTES_PER_VERTEX, GL advances to
            // byte 'from * BYTES_PER_VERTEX' automatically.
            GL11.glDrawArrays(GL11.GL_QUADS, from, count);

            if (VaoHelper.isAvailable()) {
                VaoHelper.bindVao(0);
            } else {
                GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
                GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            }

        } else {
            // Heap fallback: pass the direct ByteBuffer directly to the client-array
            // functions.  Vertex data always starts at byte 0 of fallbackBuffer
            // (batchStart is reset to 0 in startBatch for the fallback path).
            fallbackBuffer.clear();
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
            fallbackBuffer.position(0);
            GL11.glVertexPointer(3, GL11.GL_FLOAT, BYTES_PER_VERTEX, fallbackBuffer);
            fallbackBuffer.position(12);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, BYTES_PER_VERTEX, fallbackBuffer);
            fallbackBuffer.position(20);
            GL11.glNormalPointer(GL11.GL_BYTE, BYTES_PER_VERTEX, fallbackBuffer);
            GL11.glDrawArrays(GL11.GL_QUADS, 0, count);
            GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        }

        batchesThisPeriod++;
    }

    // ------------------------------------------------------------------ Dynamic resize

    /**
     * Grows all VBO slices (or the fallback buffer) to {@code newCapacity} bytes
     * per slice.
     *
     * <p>The current in-flight batch's already-written vertices are copied to the
     * start of the new buffer so rendering continues without a gap.  Old GL objects
     * are destroyed lazily via {@link #deferredTasks} after the GPU sync for each
     * slice completes.
     */
    private void resize(long newCapacity) {
        EfficientEntities.LOG.info(
            "[EfficientEntities] Resizing VBO slice: {} KB → {} KB.",
            bufferCapacity / 1024, newCapacity / 1024);

        int  batchVertexCount = vertexCount - batchStart;
        long batchBytes       = (long) batchVertexCount * BYTES_PER_VERTEX;

        if (gpuPath) {
            // Save current-slice address BEFORE reinitialising the arrays.
            long batchSrcAddr = sliceAddrs[currentSlice] + (long) batchStart * BYTES_PER_VERTEX;

            // Schedule deferred deletion of every old VBO and VAO.
            for (int i = 0; i < BUFFER_SLICES; i++) {
                final int oldVbo = vbos[i];
                final int oldVao = vaos[i];
                deferredTasks[i].push(() -> {
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, oldVbo);
                    PersistentBuffer.unmapBuffer(GL15.GL_ARRAY_BUFFER, oldVbo);
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
                    if (VaoHelper.isAvailable()) VaoHelper.deleteVao(oldVao);
                    PersistentBuffer.deleteBuffer(oldVbo);
                });
            }

            bufferCapacity = newCapacity;
            allocateGpuSlices(newCapacity);

            // Update current-frame hot pointers to the freshly allocated slice.
            currentVbo  = vbos[currentSlice];
            currentVao  = vaos[currentSlice];
            currentAddr = sliceAddrs[currentSlice];

            // Migrate in-flight batch to the new buffer.
            if (batchBytes > 0) {
                UNSAFE.copyMemory(batchSrcAddr, currentAddr, batchBytes);
            }

        } else {
            // Fallback: reallocate a larger direct ByteBuffer.
            long   oldAddr = nativeAddress(fallbackBuffer);
            ByteBuffer newBuf = ByteBuffer.allocateDirect((int) newCapacity);
            if (batchBytes > 0) {
                UNSAFE.copyMemory(oldAddr, nativeAddress(newBuf), batchBytes);
            }
            fallbackBuffer = newBuf;
            bufferCapacity = newCapacity;
            currentAddr    = nativeAddress(fallbackBuffer);
        }

        // Remap vertex tracking: batch now occupies [0, batchVertexCount).
        vertexCount = batchVertexCount;
        batchStart  = 0;
    }

    // ------------------------------------------------------------------ Per-part render

    /**
     * Replaces {@link ModelRenderer#render(float)}.
     *
     * <p>If called outside a batch (e.g. first-person hand, armour renderers) the
     * single-part call is auto-wrapped so it always reaches the screen.
     *
     * <p>GlStateManager transforms are still issued for vanilla-fallback children
     * (chests, OptiFine, cow udder) that dispatch through
     * {@link ModelRenderer#render(float)} and are not cancelled by the mixin.
     * Our {@link MatrixStack} receives the same transforms and is used exclusively
     * for vertex baking, eliminating the old {@code glGetFloat} readback.
     */
    @SuppressWarnings("unchecked")
    public void render(ModelRenderer renderer, float scale) {
        if (!initialised || renderer.isHidden || !renderer.showModel) return;

        boolean autoWrap = !inBatch;
        if (autoWrap) {
            batchStart = vertexCount;
            inBatch    = true;
            matrixStack.reset();
            autoWrapsThisPeriod++;
        }

        // ---- GL state (kept for vanilla-fallback children) ------------------

        boolean hasOffset = renderer.offsetX != 0f
                         || renderer.offsetY != 0f
                         || renderer.offsetZ != 0f;
        if (hasOffset) {
            GlStateManager.translate(renderer.offsetX, renderer.offsetY, renderer.offsetZ);
        }

        boolean hasTransform = renderer.rotationPointX != 0f
                            || renderer.rotationPointY != 0f
                            || renderer.rotationPointZ != 0f
                            || renderer.rotateAngleX   != 0f
                            || renderer.rotateAngleY   != 0f
                            || renderer.rotateAngleZ   != 0f;
        if (hasTransform) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(
                renderer.rotationPointX * scale,
                renderer.rotationPointY * scale,
                renderer.rotationPointZ * scale);
            if (renderer.rotateAngleZ != 0f)
                GlStateManager.rotate(renderer.rotateAngleZ * RAD_TO_DEG, 0f, 0f, 1f);
            if (renderer.rotateAngleY != 0f)
                GlStateManager.rotate(renderer.rotateAngleY * RAD_TO_DEG, 0f, 1f, 0f);
            if (renderer.rotateAngleX != 0f)
                GlStateManager.rotate(renderer.rotateAngleX * RAD_TO_DEG, 1f, 0f, 0f);
        }

        // ---- MatrixStack (for our own vertex baking) ------------------------
        // Mirror the same transforms applied above via GlStateManager.
        // Always push so child parts inherit this part's accumulated transform.

        boolean needsMatrixPush = hasOffset || hasTransform;
        if (needsMatrixPush) {
            matrixStack.push();
            // Combine offset and rotation-point into one translate, matching the
            // net transform that vanilla applies in two separate calls.
            matrixStack.translate(
                renderer.offsetX + renderer.rotationPointX * scale,
                renderer.offsetY + renderer.rotationPointY * scale,
                renderer.offsetZ + renderer.rotationPointZ * scale);
            if (renderer.rotateAngleZ != 0f) matrixStack.rotateZ(renderer.rotateAngleZ);
            if (renderer.rotateAngleY != 0f) matrixStack.rotateY(renderer.rotateAngleY);
            if (renderer.rotateAngleX != 0f) matrixStack.rotateX(renderer.rotateAngleX);
        }

        // ---- Emit this part's cubes -----------------------------------------

        List<ModelBox> cubeList = renderer.cubeList;
        if (cubeList != null && !cubeList.isEmpty()) {

            // Ensure capacity before touching any vertex slots.
            int neededVerts = cubeList.size() * VERTICES_PER_CUBE;
            if (vertexCount + neededVerts > sliceCapacityInVertices()) {
                long newCapacity = Math.max(
                    bufferCapacity + (bufferCapacity >> 1),
                    (long)(vertexCount + neededVerts) * BYTES_PER_VERTEX);
                resize(newCapacity);
            }

            // Snapshot the 12 matrix coefficients once; avoids repeated accessor
            // overhead for multi-cube parts.
            float m00 = matrixStack.m00(), m01 = matrixStack.m01(),
                  m02 = matrixStack.m02(), m03 = matrixStack.m03();
            float m10 = matrixStack.m10(), m11 = matrixStack.m11(),
                  m12 = matrixStack.m12(), m13 = matrixStack.m13();
            float m20 = matrixStack.m20(), m21 = matrixStack.m21(),
                  m22 = matrixStack.m22(), m23 = matrixStack.m23();

            for (int i = 0, n = cubeList.size(); i < n; i++) {
                ModelBox box = cubeList.get(i);
                CubeGeometry geo = ((Supplier<CubeGeometry>) box).get();
                if (geo != null) {
                    emitCube(geo, scale,
                        m00, m01, m02, m03,
                        m10, m11, m12, m13,
                        m20, m21, m22, m23);
                }
            }
        }

        // ---- Dispatch children through ModelRenderer.render() ---------------
        // Re-entering the mixin for each child ensures per-part fallbacks
        // (chest TESR, OptiFine, cow udder at textureOffset 52,0) fire correctly.
        List<ModelRenderer> children = renderer.childModels;
        if (children != null) {
            for (int i = 0, n = children.size(); i < n; i++) {
                children.get(i).render(scale);
            }
        }

        // ---- Undo transforms ------------------------------------------------
        if (needsMatrixPush) matrixStack.pop();
        if (hasTransform) GlStateManager.popMatrix();
        if (hasOffset) {
            GlStateManager.translate(-renderer.offsetX, -renderer.offsetY, -renderer.offsetZ);
        }

        if (autoWrap) {
            inBatch = false;
            drawBatch(batchStart, vertexCount);
        }
    }

    // ------------------------------------------------------------------ Geometry helpers

    /**
     * Transforms all 8 corners of the cube into model space using the factored
     * matrix-vector product, then assembles 6 quads (24 vertices) in vanilla
     * {@link net.minecraft.client.model.TexturedQuad} winding order.
     *
     * <h3>Corner naming</h3>
     * {@code wX_ijk} is the model-space X coordinate of the corner at
     * ({@code xi}, {@code yj}, {@code zk}) where 0 → the lower extent,
     * 1 → the upper extent.
     *
     * <h3>Factored products</h3>
     * For each matrix row r and each axis a, we precompute
     * {@code row_r_a0 = M[r][a] * a0} and {@code row_r_a1 = M[r][a] * a1}.
     * Each corner then costs only 3 additions and a translation offset,
     * rather than 3 full multiply-adds.
     *
     * <h3>Normal packing</h3>
     * The upper-left 3×3 of M contains the rotation only (entity bones have
     * no non-uniform scale), so column j of that sub-matrix is the bone-space
     * direction of world-axis j.  Axis-aligned face normals transform to
     * matrix columns directly.  Each component is quantised to a signed byte
     * via {@code (int)(v * 127) & 0xFF} and the three bytes are packed into an
     * {@code int} for a single {@link Unsafe#putInt} write.
     */
    private void emitCube(CubeGeometry g, float s,
                           float m00, float m01, float m02, float m03,
                           float m10, float m11, float m12, float m13,
                           float m20, float m21, float m22, float m23) {

        float x0 = g.x0 * s, x1 = g.x1 * s;
        float y0 = g.y0 * s, y1 = g.y1 * s;
        float z0 = g.z0 * s, z1 = g.z1 * s;

        // Factored axis contributions for world-X (row 0), world-Y (row 1), world-Z (row 2).
        float rx0 = m00*x0, rx1 = m00*x1,  ry0 = m01*y0, ry1 = m01*y1,  rz0 = m02*z0, rz1 = m02*z1;
        float gx0 = m10*x0, gx1 = m10*x1,  gy0 = m11*y0, gy1 = m11*y1,  gz0 = m12*z0, gz1 = m12*z1;
        float bx0 = m20*x0, bx1 = m20*x1,  by0 = m21*y0, by1 = m21*y1,  bz0 = m22*z0, bz1 = m22*z1;

        // All 8 corners in model space.
        float wX000=rx0+ry0+rz0+m03, wY000=gx0+gy0+gz0+m13, wZ000=bx0+by0+bz0+m23;
        float wX001=rx0+ry0+rz1+m03, wY001=gx0+gy0+gz1+m13, wZ001=bx0+by0+bz1+m23;
        float wX010=rx0+ry1+rz0+m03, wY010=gx0+gy1+gz0+m13, wZ010=bx0+by1+bz0+m23;
        float wX011=rx0+ry1+rz1+m03, wY011=gx0+gy1+gz1+m13, wZ011=bx0+by1+bz1+m23;
        float wX100=rx1+ry0+rz0+m03, wY100=gx1+gy0+gz0+m13, wZ100=bx1+by0+bz0+m23;
        float wX101=rx1+ry0+rz1+m03, wY101=gx1+gy0+gz1+m13, wZ101=bx1+by0+bz1+m23;
        float wX110=rx1+ry1+rz0+m03, wY110=gx1+gy1+gz0+m13, wZ110=bx1+by1+bz0+m23;
        float wX111=rx1+ry1+rz1+m03, wY111=gx1+gy1+gz1+m13, wZ111=bx1+by1+bz1+m23;

        // Bone-space normals: axis direction i transforms to M's column i (upper-left 3x3).
        //   M * (1,0,0) = (m00, m10, m20)  →  +X column
        //   M * (0,1,0) = (m01, m11, m21)  →  +Y column   etc.
        int nPX = packNormal( m00,  m10,  m20);
        int nNX = packNormal(-m00, -m10, -m20);
        int nPY = packNormal( m01,  m11,  m21);
        int nNY = packNormal(-m01, -m11, -m21);
        int nPZ = packNormal( m02,  m12,  m22);
        int nNZ = packNormal(-m02, -m12, -m22);

        // +X face (x1 side)
        putVertex(wX101,wY101,wZ101, g.upx1,g.vpx0, nPX);
        putVertex(wX100,wY100,wZ100, g.upx0,g.vpx0, nPX);
        putVertex(wX110,wY110,wZ110, g.upx0,g.vpx1, nPX);
        putVertex(wX111,wY111,wZ111, g.upx1,g.vpx1, nPX);

        // -X face (x0 side)
        putVertex(wX000,wY000,wZ000, g.unx1,g.vnx0, nNX);
        putVertex(wX001,wY001,wZ001, g.unx0,g.vnx0, nNX);
        putVertex(wX011,wY011,wZ011, g.unx0,g.vnx1, nNX);
        putVertex(wX010,wY010,wZ010, g.unx1,g.vnx1, nNX);

        // +Y face (y1 side — top)
        putVertex(wX011,wY011,wZ011, g.upy0,g.vpy1, nPY);
        putVertex(wX111,wY111,wZ111, g.upy1,g.vpy1, nPY);
        putVertex(wX110,wY110,wZ110, g.upy1,g.vpy0, nPY);
        putVertex(wX010,wY010,wZ010, g.upy0,g.vpy0, nPY);

        // -Y face (y0 side — bottom)
        putVertex(wX000,wY000,wZ000, g.uny0,g.vny1, nNY);
        putVertex(wX100,wY100,wZ100, g.uny1,g.vny1, nNY);
        putVertex(wX101,wY101,wZ101, g.uny1,g.vny0, nNY);
        putVertex(wX001,wY001,wZ001, g.uny0,g.vny0, nNY);

        // +Z face (z1 side)
        putVertex(wX001,wY001,wZ001, g.upz1,g.vpz0, nPZ);
        putVertex(wX101,wY101,wZ101, g.upz0,g.vpz0, nPZ);
        putVertex(wX111,wY111,wZ111, g.upz0,g.vpz1, nPZ);
        putVertex(wX011,wY011,wZ011, g.upz1,g.vpz1, nPZ);

        // -Z face (z0 side)
        putVertex(wX100,wY100,wZ100, g.unz1,g.vnz0, nNZ);
        putVertex(wX000,wY000,wZ000, g.unz0,g.vnz0, nNZ);
        putVertex(wX010,wY010,wZ010, g.unz0,g.vnz1, nNZ);
        putVertex(wX110,wY110,wZ110, g.unz1,g.vnz1, nNZ);

        cubesThisPeriod++;
    }

    /**
     * Writes one 24-byte vertex directly to the current mapped-buffer address via
     * {@link Unsafe}, bypassing {@link ByteBuffer} bounds-checks and avoiding the
     * JNI call overhead that {@code putFloat}/{@code putByte} on a ByteBuffer
     * incurs.
     */
    private void putVertex(float x, float y, float z,
                            float u, float v, int packedNormal) {
        long ptr = currentAddr + (long) vertexCount * BYTES_PER_VERTEX;
        UNSAFE.putFloat(ptr,      x);
        UNSAFE.putFloat(ptr +  4, y);
        UNSAFE.putFloat(ptr +  8, z);
        UNSAFE.putFloat(ptr + 12, u);
        UNSAFE.putFloat(ptr + 16, v);
        UNSAFE.putInt  (ptr + 20, packedNormal);
        vertexCount++;
    }

    /**
     * Packs three normal components into one {@code int} for a single
     * {@link Unsafe#putInt} write.
     *
     * <p>Each component is quantised with {@code (int)(v * 127) & 0xFF}.
     * The {@code & 0xFF} strips sign-extension from the Java {@code int} before
     * ORing — e.g. {@code (int)(-127)} is {@code 0xFFFFFF81}; masking gives
     * {@code 0x81} which {@code GL_BYTE} reads back as {@code -127} (signed). ✓
     *
     * <p>On little-endian (x86) {@code putInt(addr, n)} stores bytes in order
     * {@code [nx, ny, nz, 0]} at {@code addr}, which is exactly what
     * {@code GL_BYTE} reads for a stride-24 normal attribute at offset 20.
     */
    private static int packNormal(float nx, float ny, float nz) {
        return  ((int)(nx * 127f) & 0xFF)
             | (((int)(ny * 127f) & 0xFF) <<  8)
             | (((int)(nz * 127f) & 0xFF) << 16);
    }

    // ------------------------------------------------------------------ Utilities

    private int sliceCapacityInVertices() {
        return (int)(bufferCapacity / BYTES_PER_VERTEX);
    }
}
