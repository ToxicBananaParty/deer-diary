package me.cortex.nvidium.compat.iris;

import com.google.common.collect.ImmutableSet;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.targets.RenderTargets;

/**
 * Duck-type interface implemented by {@code NvidiumIrisRenderingPipelineMixin} so the Iris
 * compat layer can reach the private internals of {@code IrisRenderingPipeline} without a hard
 * compile dependency on the mixin class. An {@code IrisRenderingPipeline} instance is cast to
 * this interface at runtime; the mixin makes that cast valid.
 *
 * <p>Every method is prefixed {@code nvidium$} to avoid clashing with any current or future Iris
 * method name. All Iris symbols referenced here live behind the {@code IrisCheck.IRIS_LOADED}
 * guard at the call sites, so this interface is never loaded when Iris is absent.
 */
public interface NvidiumIrisRenderingPipelineAccessor {
    RenderTargets nvidium$getRenderTargets();

    ImmutableSet<Integer> nvidium$getFlippedAfterPrepare();

    ImmutableSet<Integer> nvidium$getFlippedAfterTranslucent();

    /** May be null when the active pack has no shadow pass. */
    ShadowRenderTargets nvidium$getShadowRenderTargets();

    ProgramFallbackResolver nvidium$getResolver();
}
