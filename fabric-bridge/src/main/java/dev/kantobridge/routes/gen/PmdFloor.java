package dev.kantobridge.routes.gen;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * One dungeon floor (docs/specs/WILD_ROUTES_PLAN.md §4.8): {@link PmdFloorGenerator#WIDTH} × {@link
 * PmdFloorGenerator#HEIGHT} cells, 112×64 Minecraft blocks. x grows east, y grows south.
 *
 * @param seed        the seed it was made from
 * @param width       {@link PmdFloorGenerator#WIDTH}
 * @param height      {@link PmdFloorGenerator#HEIGHT}
 * @param tiles       row-major, {@code y * width + x}; don't modify
 * @param house       a Monster House's cells (row-major flags); all false when there's none
 * @param shop        the shop's inner cells (a Mart stall)
 * @param items       item balls, the Monster House's included
 * @param traps       traps (none by default: Gen 1 has none)
 * @param enemies     where wild Pokémon stand at first
 * @param player      where you arrive
 * @param stairs      the way down (or, on the last floor, into the treasure room)
 * @param fallback    true when no layout passed and the floor is the report's one-room fallback
 * @param layoutTries layouts made, this one included
 * @param spawnTries  times the stairs, items and Pokémon were placed
 */
public record PmdFloor(long seed, int width, int height, Tile[] tiles, boolean[] house, boolean[] shop, List<Cell> items,
		List<Cell> traps, List<Cell> enemies, @Nullable Cell player, @Nullable Cell stairs, boolean fallback, int layoutTries,
		int spawnTries) {
	public enum Tile {
		WALL('#'), FLOOR('.'), WATER('~');

		public final char symbol;

		Tile(char symbol) {
			this.symbol = symbol;
		}
	}

	public Tile at(int x, int y) {
		return this.tiles[y * this.width + x];
	}

	public Tile at(Cell cell) {
		return this.at(cell.x(), cell.y());
	}

	public boolean inHouse(Cell cell) {
		return this.house[cell.y() * this.width + cell.x()];
	}

	public boolean inShop(Cell cell) {
		return this.shop[cell.y() * this.width + cell.x()];
	}

	/** The floor as text, one line per row (synthetic tests and logs only). */
	public String ascii() {
		StringBuilder out = new StringBuilder();
		for (int y = 0; y < this.height; y++) {
			for (int x = 0; x < this.width; x++) {
				Cell c = new Cell(x, y);
				char ch = this.at(x, y).symbol;
				if (ch == '.' && this.inHouse(c)) {
					ch = 'h';
				}
				if (this.inShop(c)) {
					ch = '$';
				}
				if (this.items.contains(c)) {
					ch = 'i';
				}
				if (this.traps.contains(c)) {
					ch = '^';
				}
				if (this.enemies.contains(c)) {
					ch = 'e';
				}
				if (c.equals(this.player)) {
					ch = '@';
				}
				if (c.equals(this.stairs)) {
					ch = '>';
				}
				out.append(ch);
			}
			out.append('\n');
		}
		return out.toString();
	}

	/** SHA-256 over the tiles, the rooms' marks and every placed object. */
	public String fingerprint() {
		StringBuilder s = new StringBuilder("pmd/1;").append(this.width).append('x').append(this.height).append(';');
		for (Tile t : this.tiles) {
			s.append(t.symbol);
		}
		s.append(";house");
		for (int i = 0; i < this.house.length; i++) {
			if (this.house[i]) {
				s.append(' ').append(i);
			}
		}
		s.append(";shop");
		for (int i = 0; i < this.shop.length; i++) {
			if (this.shop[i]) {
				s.append(' ').append(i);
			}
		}
		s.append(";items");
		this.items.forEach(c -> s.append(' ').append(c.x()).append(',').append(c.y()));
		s.append(";traps");
		this.traps.forEach(c -> s.append(' ').append(c.x()).append(',').append(c.y()));
		s.append(";enemies");
		this.enemies.forEach(c -> s.append(' ').append(c.x()).append(',').append(c.y()));
		s.append(";player ").append(this.player == null ? "-" : this.player.x() + "," + this.player.y());
		s.append(";stairs ").append(this.stairs == null ? "-" : this.stairs.x() + "," + this.stairs.y());
		s.append(";fallback ").append(this.fallback);
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(sha.digest(s.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
