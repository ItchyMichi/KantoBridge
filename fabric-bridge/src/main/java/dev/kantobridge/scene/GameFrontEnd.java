package dev.kantobridge.scene;

import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.PlanException;

/**
 * Turns one game family's export bundle into the neutral {@link Scene} (docs/specs/VOXEL_BLOCKS_PLAN.md §5.3). Pure:
 * it reads the bundle's data, never a pixel, so the server and every client get the same scene from the same export.
 */
public interface GameFrontEnd {
	/** The front end's id and version, e.g. {@code kb-frontend-gen1/1}: part of the scene. */
	String id();

	/** The family it reads, e.g. {@code gen1}. */
	String family();

	/** The scene of the whole export: every map, both states of every swap site. */
	Scene describe(ExportBundle bundle) throws BundleException, PlanException;
}
