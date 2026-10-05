package me.cortex.nvidium;

import javax.annotation.Nonnull;

import com.gtnewhorizon.gtnhmixins.builders.IMixins;
import com.gtnewhorizon.gtnhmixins.builders.ITargetMod;
import com.gtnewhorizon.gtnhmixins.builders.MixinBuilder;
import com.gtnewhorizon.gtnhmixins.builders.TargetModBuilder;

import io.github.legacymoddingmc.unimixins.all.repackage.common.abstraction.ComparableVersion;

public enum Mixins implements IMixins {

    MINECRAFT_MIXINS(new MixinBuilder()
        .addClientMixins(
            "minecraft.EntityRendererAccessor",
            "minecraft.MixinEntityRenderer",
            "minecraft.MinecraftAccessor",
            "minecraft.TessellatorAccessor")
        .setPhase(Phase.EARLY)),

    ANGELICA_MIXINS(new MixinBuilder()
        .addClientMixins(
            "angelica.AngelicaRenderSectionManagerAccessor",
            "angelica.CameraAccessor",
            "angelica.MixinAngelicaRenderSectionManager",
            "angelica.MixinCeleritasWorldRenderer",
            "angelica.MixinChunkBuilderMeshingTask",
            "angelica.MixinOptionFlag",
            "angelica.MixinSodiumOptionsGUI",
            "angelica.MixinReeseSodiumVideoOptionsScreen",
            "angelica.CeleritasWorldRendererAccessor")
        .setPhase(Phase.EARLY)
        .addRequiredMod(TargetedMod.ANGELICA)),

    CELERITAS_MIXINS(new MixinBuilder()
        .addClientMixins(
            "celeritas.CompactChunkVertexAccessor",
            "celeritas.MixinChunkBuildOutput",
            "celeritas.MixinRenderRegionManager",
            "celeritas.MixinRenderSectionManager",
            "celeritas.MixinSectionLattice",
            "celeritas.MixinSimpleWorldRenderer")
        .setPhase(Phase.EARLY)
        .addRequiredMod(TargetedMod.ANGELICA)),

    // Textured Distant Horizons LODs with shader packs (port of upstream Iris)
    ANGELICA_DH_MIXINS(new MixinBuilder()
        .addClientMixins("angelica.dh.MixinDHTerrainTransformer", "angelica.dh.MixinIrisLodRenderProgram")
        .setPhase(Phase.EARLY)
        .addRequiredMod(TargetedMod.ANGELICA)
        .addRequiredMod(TargetedMod.DISTANT_HORIZONS)),;

    public enum TargetedMod implements ITargetMod {

        ANGELICA("com.gtnewhorizons.angelica.loading.AngelicaTweaker", "angelica"),
        DISTANT_HORIZONS("com.seibel.distanthorizons.DistantHorizonsTweaker", "distanthorizons");

        private final TargetModBuilder builder;

        TargetedMod(TargetModBuilder builder) {
            this.builder = builder;
        }

        TargetedMod(String modId) {
            this(null, modId, null);
        }

        TargetedMod(String coreModClass, String modId) {
            this(coreModClass, modId, null);
        }

        TargetedMod(String coreModClass, String modId, String targetClass) {
            this.builder = new TargetModBuilder().setCoreModClass(coreModClass)
                .setModId(modId)
                .setTargetClass(targetClass);
        }

        @Nonnull
        @Override
        public TargetModBuilder getBuilder() {
            return builder;
        }

        private static boolean isVersionLessThan(String version, String target) {
            return new ComparableVersion(version).compareTo(new ComparableVersion(target)) < 0;
        }
    }

    private final MixinBuilder builder;

    Mixins(MixinBuilder builder) {
        this.builder = builder;
    }

    @Nonnull
    @Override
    public MixinBuilder getBuilder() {
        return builder;
    }
}
