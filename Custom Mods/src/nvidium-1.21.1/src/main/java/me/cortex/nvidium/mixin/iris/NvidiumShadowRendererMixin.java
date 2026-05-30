package me.cortex.nvidium.mixin.iris;

import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.shadows.ShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the private ShadowRenderTargets field of ShadowRenderer that Nvidium needs
 * to obtain the shadow framebuffer for shadow-pass rendering.
 */
@Mixin(value = ShadowRenderer.class, remap = false)
public interface NvidiumShadowRendererMixin {

    @Accessor("targets")
    ShadowRenderTargets nvidium$getTargets();
}
