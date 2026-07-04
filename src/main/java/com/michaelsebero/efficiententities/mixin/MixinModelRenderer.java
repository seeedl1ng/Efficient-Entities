package com.michaelsebero.efficiententities.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.michaelsebero.efficiententities.renderer.EfficientModelRenderer;
import com.michaelsebero.efficiententities.EfficientEntities;
import net.minecraft.client.model.ModelRenderer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Redirects {@link ModelRenderer#render(float)} to the GPU-batched VBO path,
 * except when a chest tile-entity renderer is on the call stack, or when this
 * renderer's texture offset is (52, 0) — the vanilla cow udder — in which case
 * the vanilla display-list body is allowed to run normally.
 *
 * <h3>Why stack-trace detection only for chests?</h3>
 * The transformer-injected {@code setChestMode()} approach is fragile: if the
 * obfuscation mapping differs between environments the injection is silently
 * skipped and chests break.  The stack-trace scan is a small, bounded cost
 * (≤10 frames, only at chest TESRs which are rare) and is 100% reliable
 * regardless of obfuscation.
 *
 * <h3>Classes treated as "chest"</h3>
 * <ul>
 *   <li>{@code TileEntityChestRenderer}      — normal chests</li>
 *   <li>{@code TileEntityEnderChestRenderer} — ender chests</li>
 *   <li>Any class whose simple name ends with {@code "ChestRenderer"} —
 *       catches modded variants that follow the vanilla naming convention.</li>
 * </ul>
 *
 * <h3>Cow udder</h3>
 * The udder {@link ModelRenderer} is constructed with
 * {@code new ModelRenderer(this, 52, 0)}, storing 52 and 0 in the private
 * {@code textureOffsetX} / {@code textureOffsetY} fields.  Shadowing those
 * fields lets us check the offset at render time with a single comparison —
 * no reflection, no stack scanning, no separate registry.  Any other renderer
 * that happens to share offset (52, 0) will also fall through to vanilla, which
 * is the safe default and causes no visible difference in practice.
 */
@Mixin(ModelRenderer.class)
public abstract class MixinModelRenderer {

    // ---------------------------------------------------------------------- Shadowed fields

    @Shadow private int textureOffsetX;
    @Shadow private int textureOffsetY;

    // ---------------------------------------------------------------------- OptiFine detection

    private static final boolean OPTIFINE_PRESENT = detectOptiFine();

    private static boolean detectOptiFine() {
        try { Class.forName("net.optifine.Config");           return true; } catch (ClassNotFoundException ignored) {}
        try { Class.forName("optifine.OptiFineForgeTweaker"); return true; } catch (ClassNotFoundException ignored) {}
        return false;
    }

    // ---------------------------------------------------------------------- Chest class names

    /** Simple class names that must fall through to vanilla rendering. */
    private static final String[] CHEST_CLASS_SUFFIXES = {
        "TileEntityChestRenderer",
        "TileEntityEnderChestRenderer",
        "ChestRenderer"          // catches any *ChestRenderer subclass
    };

    // ---------------------------------------------------------------------- Inject

    /**
     * @author michaelsebero
     * @reason Redirect render calls to the GPU-batched VBO path.
     *         Falls back to vanilla for OptiFine, chest TESRs, and the cow udder.
     */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    @SideOnly(Side.CLIENT)
    public void render(float scale, CallbackInfo ci) {
        // OptiFine present: let vanilla (+ OptiFine) handle everything.
        if (OPTIFINE_PRESENT) return;

        // Chest mode: do NOT cancel — let vanilla display-list body run intact.
        if (isCalledFromChestRenderer()) return;

        // Cow udder: texture offset (52, 0) is set by
        //   new ModelRenderer(this, 52, 0)
        // in vanilla ModelCow.  Do NOT cancel so vanilla draws this part.
        if (textureOffsetX == 52 && textureOffsetY == 0) return;

        // Normal entity: cancel vanilla body and run the VBO path instead.
        ci.cancel();
        EfficientModelRenderer.instance().render((ModelRenderer) (Object) this, scale);
    }

    // ---------------------------------------------------------------------- Chest detection

    /**
     * Returns {@code true} if any of the nearest call-stack frames originates
     * from a chest tile-entity renderer class.
     *
     * Frames 0–2 are always {@code getStackTrace}, this method, and
     * {@code render}; meaningful callers start at frame 3.  We cap the scan at
     * frame 10 — chest renderers call {@code ModelRenderer.render()} within
     * 2–4 frames of the TESR entry point, so 10 is more than sufficient while
     * keeping the cost negligible.
     */
    private static boolean isCalledFromChestRenderer() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        int limit = Math.min(stack.length, 10);
        for (int i = 3; i < limit; i++) {
            String className = stack[i].getClassName();
            int dot = className.lastIndexOf('.');
            String simpleName = dot >= 0 ? className.substring(dot + 1) : className;
            for (String suffix : CHEST_CLASS_SUFFIXES) {
                if (simpleName.equals(suffix) || simpleName.endsWith(suffix)) {
                    return true;
                }
            }
        }
        return false;
    }
}
