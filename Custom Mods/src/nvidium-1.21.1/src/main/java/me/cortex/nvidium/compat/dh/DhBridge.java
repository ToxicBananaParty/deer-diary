package me.cortex.nvidium.compat.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderPassEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.RenderMode;
import net.minecraft.client.Minecraft;

/**
 * The ONLY file that imports com.seibel.* symbols.
 * All DH API access is isolated here so NvidiumDhCompat can guard by class
 * loading — if DH is absent, this class is never touched.
 */
final class DhBridge {

    /** Cached last radius value written to DH so we only call setValue when it changes. */
    private static float lastRadiusSet = -1f;

    private DhBridge() {}

    /**
     * Register both event handlers via DhApi.events.bind(...).
     * Mirrors the pattern from Iris LodRendererEvents.java exactly.
     */
    static void bind() {
        // (1) After-init handler: DH is ready, Delayed.configs is now safe to use.
        DhApiAfterDhInitEvent initHandler = new DhApiAfterDhInitEvent() {
            @Override
            public void afterDistantHorizonsInit(DhApiEventParam<Void> event) {
                Nvidium.LOGGER.info("DH init complete - applying Nvidium overdraw radius");
                try {
                    applyOverdrawRadius();
                } catch (Throwable t) {
                    Nvidium.LOGGER.warn("Nvidium DH: error in afterDhInit overdraw apply", t);
                    NvidiumDhCompat.ACTIVE = false;
                }
            }
        };
        DhApi.events.bind(DhApiAfterDhInitEvent.class, initHandler);

        // (2) Before-render-pass handler: refresh overdraw radius each frame in case
        //     render distance has changed.
        DhApiBeforeRenderPassEvent renderHandler = new DhApiBeforeRenderPassEvent() {
            @Override
            public void beforeRender(DhApiEventParam<DhApiRenderParam> event) {
                try {
                    applyOverdrawRadius();
                } catch (Throwable t) {
                    Nvidium.LOGGER.warn("Nvidium DH: error in beforeRenderPass overdraw apply", t);
                    NvidiumDhCompat.ACTIVE = false;
                }
            }
        };
        DhApi.events.bind(DhApiBeforeRenderPassEvent.class, renderHandler);
    }

    /**
     * Sets DH's overdrawPreventionRadius so DH skips LOD geometry within
     * Nvidium's rendered chunk radius.  Only calls setValue when the computed
     * value actually changes to avoid redundant API traffic.
     *
     * Only active when Nvidium is in VANILLA mode (no shaders, no force-disable).
     */
    static void applyOverdrawRadius() {
        if (DhApi.Delayed.configs == null) {
            // DH not fully initialised yet — skip silently.
            return;
        }

        if (Nvidium.MODE != RenderMode.VANILLA) {
            // Nvidium isn't rendering right now; let DH use its own default.
            if (lastRadiusSet != -1f) {
                DhApi.Delayed.configs.graphics().overdrawPreventionRadius().clearValue();
                lastRadiusSet = -1f;
            }
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        int renderDistanceChunks = mc.options.getEffectiveRenderDistance();
        // region_keep_distance is in regions (each region = 16 chunks).
        // overdrawPreventionRadius is a fraction [0..1] of DH's render distance.
        float nvidiumRadiusChunks = Nvidium.config.region_keep_distance * 16.0f;
        float radius = (float) Math.min(1.0, nvidiumRadiusChunks / Math.max(1, renderDistanceChunks));

        if (radius != lastRadiusSet) {
            DhApi.Delayed.configs.graphics().overdrawPreventionRadius().setValue(radius);
            lastRadiusSet = radius;
        }
    }
}
