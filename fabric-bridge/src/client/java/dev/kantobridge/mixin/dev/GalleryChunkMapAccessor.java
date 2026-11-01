package dev.kantobridge.mixin.dev;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.List;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Only applied with the explicit gallery property; it never changes chunk state. */
@Mixin(ChunkMap.class)
public interface GalleryChunkMapAccessor {
	@Accessor("updatingChunkMap") Long2ObjectLinkedOpenHashMap<ChunkHolder> kantobridge$galleryUpdatingChunks();
	@Accessor("pendingUnloads") Long2ObjectLinkedOpenHashMap<ChunkHolder> kantobridge$galleryPendingUnloads();
	@Accessor("pendingGenerationTasks") List<?> kantobridge$galleryPendingGeneration();
}
