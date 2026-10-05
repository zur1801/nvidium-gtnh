package me.cortex.nvidium.mixin.celeritas;

import org.embeddedt.embeddium.impl.render.chunk.occlusion.SectionLattice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * The occlusion lattice refuses windows of 2^22 cells or more, which with 18 section layers caps the render distance
 * at 235 chunks. Each cell costs roughly 40 bytes, so 2^24 (render distance ~477, still under the 1024 per-axis
 * limit) costs at most ~670 MB and only when such a distance is actually selected.
 */
@Mixin(value = SectionLattice.class, remap = false)
public class MixinSectionLattice {

    @ModifyConstant(method = "allocate", constant = @Constant(longValue = 1L << 22), require = 1)
    private long nvidium$raiseLatticeSlotLimit(long original) {
        return 1L << 24;
    }
}
