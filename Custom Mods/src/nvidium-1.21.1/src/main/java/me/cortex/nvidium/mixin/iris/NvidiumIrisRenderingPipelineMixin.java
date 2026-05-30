package me.cortex.nvidium.mixin.iris;

import com.google.common.collect.ImmutableSet;
import me.cortex.nvidium.compat.iris.NvidiumIrisRenderingPipelineAccessor;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes private fields of IrisRenderingPipeline that Nvidium's Iris integration layer needs
 * to create custom framebuffers and resolve per-pass program sources.
 */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public abstract class NvidiumIrisRenderingPipelineMixin implements NvidiumIrisRenderingPipelineAccessor {

    @Shadow @Final
    private RenderTargets renderTargets;

    @Shadow @Final
    private ImmutableSet<Integer> flippedAfterPrepare;

    @Shadow @Final
    private ImmutableSet<Integer> flippedAfterTranslucent;

    @Shadow @Final
    private ShadowRenderer shadowRenderer;

    @Shadow @Final
    private ProgramFallbackResolver resolver;

    @Override
    public RenderTargets nvidium$getRenderTargets() {
        return renderTargets;
    }

    @Override
    public ImmutableSet<Integer> nvidium$getFlippedAfterPrepare() {
        return flippedAfterPrepare;
    }

    @Override
    public ImmutableSet<Integer> nvidium$getFlippedAfterTranslucent() {
        return flippedAfterTranslucent;
    }

    @Override
    public ShadowRenderTargets nvidium$getShadowRenderTargets() {
        if (shadowRenderer == null) return null;
        return ((NvidiumShadowRendererMixin) (Object) shadowRenderer).nvidium$getTargets();
    }

    @Override
    public ProgramFallbackResolver nvidium$getResolver() {
        return resolver;
    }
}
