package dev.kantobridge.builder.export;

import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import dev.kantobridge.world.StoryCellTable;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Runtime block-change sites (protocol/export/swaps.schema.json): Cut trees, card-key doors and scripted swaps. The
 * builder places each changing cell in its open (walkable) state and covers it with a story cell, so each player sees
 * and collides with their own state.
 */
public record ExportSwaps(List<Site> sites) {
	/**
	 * One site: Gen 1 block (bx, by) of {@code map} changes from block {@code before} to {@code after}.
	 *
	 * @param source the flag, object or script behind it, when the export names one (an engine symbol)
	 */
	public record Site(String map, int bx, int by, String kind, int before, int after, @Nullable String source) {
		public Site(String map, int bx, int by, String kind, int before, int after) {
			this(map, bx, by, kind, before, after, null);
		}

		public StoryCellTable.Swap toSwap() {
			return new StoryCellTable.Swap(this.bx, this.by, this.kind, this.before, this.after);
		}
	}

	public ExportSwaps {
		sites = List.copyOf(sites);
	}

	public static ExportSwaps empty() {
		return new ExportSwaps(List.of());
	}

	static ExportSwaps parse(DataReader r) throws DataException {
		List<Site> sites = new ArrayList<>();
		for (DataReader s : DataReader.objects(r.array("sites"), r.path("sites"))) {
			String kind = s.string("kind");
			if (!StoryCellTable.SWAP_KINDS.contains(kind)) {
				throw new DataException(s.path("kind"), "unknown swap kind");
			}
			sites.add(new Site(s.string("map", DataReader.MAP_ID), s.integer("bx", 0, 255), s.integer("by", 0, 255), kind,
					s.integer("before", 0, 255), s.integer("after", 0, 255), s.has("source") ? s.string("source", DataReader.SYMBOL) : null));
		}
		return new ExportSwaps(sites);
	}
}
