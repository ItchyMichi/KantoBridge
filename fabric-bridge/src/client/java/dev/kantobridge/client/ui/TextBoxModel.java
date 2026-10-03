package dev.kantobridge.client.ui;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * One hidden-game text box as our screen shows it (docs/PROTOCOL.md {@code ui.open{kind:text}}, PLAN §2.4 flow 2). Plain
 * Java on an explicit millisecond clock.
 *
 * <p>Like Gen 1's box it types the current line glyph by glyph (at the FAST speed the hidden game is pinned to, one
 * glyph per 60 Hz frame), shows two lines at a time, scrolls on to the next line of a page by itself unless that line
 * is a {@code cont} line, and stops with a ▼ before a {@code cont} line, at the end of a page and at the end of the text.
 *
 * <p>The hidden game is authoritative: {@code ui.update}'s {@code page}/{@code line} move the box forward to where the
 * hidden box is ({@link #apply}), {@code waiting} finishes the line being typed, {@code done} jumps to the end. A press
 * at a stop moves on at once and asks for {@code ui.advance} (gen1-bridge applies it once the hidden box is ready), so
 * the two stay in lockstep: one advance per stop. A press while typing only finishes the visible text, as holding A does
 * in Gen 1. Boxes that close by themselves ({@code auto}), stay under the next screen ({@code stay}) or have a choice
 * open over them when they finish ({@code choice}) show no ▼ and never ask for an advance at their end.
 */
public final class TextBoxModel {
	/** FAST text speed: one glyph per 60 Hz frame (PLAN P21 pins the hidden game to FAST). */
	public static final double GLYPHS_PER_MS = 60.0 / 1000.0;
	/** A final press that got no {@code ui.close} may ask again after this long. */
	public static final long RESEND_FINAL_MS = 1500;

	/** What a press asks of gen1-bridge. */
	public enum Press {
		NONE,
		ADVANCE
	}

	/** Where typing waits for a press. */
	public enum Stop {
		/** Still typing. */
		NONE,
		/** Before a {@code cont} line: A scrolls it in. */
		SCROLL,
		/** At the end of a page that isn't the last. */
		PAGE,
		/** At the end of the text. */
		END
	}

	private final List<List<String>> pages;
	private final boolean[][] cont;
	private final int[][] counts;
	private final boolean auto;
	private final boolean stay;
	private final boolean choice;
	private final boolean instant;

	private int page;
	private int line;
	/** Glyphs typed of the current line (fractional between frames). */
	private double typed;
	private Stop stop = Stop.NONE;
	private long lastMs;
	/** When the final press last asked; far in the past (not MIN_VALUE, so {@code now - finalSentAt} can't overflow). */
	private long finalSentAt = Long.MIN_VALUE / 2;

	/** A box with no choice opening over it at its end. */
	public TextBoxModel(List<List<String>> pages, @Nullable List<List<Boolean>> cont, boolean auto, boolean stay, boolean instant, long now) {
		this(pages, cont, auto, stay, instant, false, now);
	}

	/**
	 * @param pages  pages of lines in the markup of {@link Gen1Text}; empty input becomes one empty line
	 * @param cont   per line, whether it scrolls in after a press ({@code \v}); missing entries are false
	 * @param choice a choice (YES/NO) opens over the box when it finishes ({@code ui.open.choice})
	 */
	public TextBoxModel(List<List<String>> pages, @Nullable List<List<Boolean>> cont, boolean auto, boolean stay, boolean instant,
			boolean choice, long now) {
		List<List<String>> copy = new ArrayList<>();
		for (List<String> page : pages) {
			copy.add(page.isEmpty() ? List.of("") : List.copyOf(page));
		}
		if (copy.isEmpty()) {
			copy.add(List.of(""));
		}
		this.pages = List.copyOf(copy);
		this.cont = new boolean[this.pages.size()][];
		this.counts = new int[this.pages.size()][];
		for (int p = 0; p < this.pages.size(); p++) {
			List<String> lines = this.pages.get(p);
			this.cont[p] = new boolean[lines.size()];
			this.counts[p] = new int[lines.size()];
			List<Boolean> flags = cont != null && p < cont.size() ? cont.get(p) : null;
			for (int l = 0; l < lines.size(); l++) {
				this.cont[p][l] = flags != null && l < flags.size() && Boolean.TRUE.equals(flags.get(l));
				this.counts[p][l] = Gen1Text.count(lines.get(l));
			}
		}
		this.auto = auto;
		this.stay = stay;
		this.choice = choice;
		this.instant = instant;
		this.lastMs = now;
		this.enterPage();
	}

	// ---- time and input ----

	/** Types on by the time passed since the last call. */
	public void tick(long now) {
		long elapsed = Math.max(0, now - this.lastMs);
		this.lastMs = now;
		if (this.stop != Stop.NONE) {
			return;
		}
		this.typed += elapsed * GLYPHS_PER_MS;
		this.settle();
	}

	/** A (or B, or a click). */
	public Press press(long now) {
		this.tick(now);
		switch (this.stop) {
			case NONE -> {
				// Show the rest of the visible text at once, up to the next stop.
				this.fastForward();
				return Press.NONE;
			}
			case SCROLL -> {
				this.line++;
				this.typed = 0;
				this.stop = Stop.NONE;
				this.settle();
				return Press.ADVANCE;
			}
			case PAGE -> {
				this.page++;
				this.line = 0;
				this.typed = 0;
				this.stop = Stop.NONE;
				this.enterPage();
				return Press.ADVANCE;
			}
			default -> {
				if (!this.asksAtEnd() || now - this.finalSentAt < RESEND_FINAL_MS) {
					return Press.NONE;
				}
				this.finalSentAt = now;
				return Press.ADVANCE;
			}
		}
	}

	/**
	 * A {@code ui.update} from the hidden box (each field optional). Positions only ever move forward: the hidden box can
	 * lag behind this one by the press that is on its way.
	 */
	public void apply(@Nullable Integer hiddenPage, @Nullable Integer hiddenLine, @Nullable Boolean waiting, @Nullable Boolean done,
			long now) {
		this.tick(now);
		if (Boolean.TRUE.equals(done)) {
			this.page = this.pages.size() - 1;
			this.line = this.pages.get(this.page).size() - 1;
			this.typed = this.counts[this.page][this.line];
			this.stop = Stop.END;
			return;
		}
		int targetPage = hiddenPage != null ? hiddenPage : this.page;
		int targetLine = hiddenLine != null ? hiddenLine : (hiddenPage != null && hiddenPage != this.page ? 0 : this.line);
		if (targetPage >= 0 && targetPage < this.pages.size()) {
			targetLine = Math.max(0, Math.min(targetLine, this.pages.get(targetPage).size() - 1));
			if (targetPage > this.page || (targetPage == this.page && targetLine > this.line)) {
				boolean newPage = targetPage != this.page;
				this.page = targetPage;
				this.line = targetLine;
				this.typed = 0;
				this.stop = Stop.NONE;
				if (newPage) {
					this.enterPage();
				}
			}
		}
		if (Boolean.TRUE.equals(waiting) && (hiddenPage == null || hiddenPage == this.page) && (hiddenLine == null || hiddenLine == this.line)
				&& this.stop == Stop.NONE) {
			// The hidden box has typed this line and waits: finish it here too.
			this.typed = this.counts[this.page][this.line];
			this.settle();
		}
	}

	// ---- view ----

	/** The lines in the box now (one or two), the last one typed so far. */
	public List<String> visible() {
		List<String> lines = this.pages.get(this.page);
		List<String> out = new ArrayList<>(2);
		if (this.line > 0) {
			out.add(lines.get(this.line - 1));
		}
		out.add(Gen1Text.prefix(lines.get(this.line), (int) Math.floor(this.typed)));
		return out;
	}

	/** The ▼ shows: waiting for a press that does something. */
	public boolean arrow() {
		return switch (this.stop) {
			case SCROLL, PAGE -> true;
			case END -> this.asksAtEnd();
			default -> false;
		};
	}

	public Stop stop() {
		return this.stop;
	}

	public int page() {
		return this.page;
	}

	public int line() {
		return this.line;
	}

	public int pageCount() {
		return this.pages.size();
	}

	/** Glyphs of the current line typed so far. */
	public int typed() {
		return (int) Math.floor(this.typed);
	}

	public boolean auto() {
		return this.auto;
	}

	public boolean stay() {
		return this.stay;
	}

	/** A choice opens over the box when it finishes. */
	public boolean choice() {
		return this.choice;
	}

	/** Whether the box waits for a press at its end (it doesn't when it closes, stays or hands over to a choice). */
	private boolean asksAtEnd() {
		return !this.auto && !this.stay && !this.choice;
	}

	/** The last line of the text, typed or not (a choice's question when it has none of its own). */
	public List<String> lastLines() {
		List<String> lines = this.pages.get(this.pages.size() - 1);
		return lines.size() <= 1 ? lines : lines.subList(lines.size() - 2, lines.size());
	}

	// ---- helpers ----

	private void enterPage() {
		if (this.instant && this.page == this.pages.size() - 1) {
			// An instant box shows its last page already typed (up to any cont stop).
			this.fastForward();
		} else {
			this.settle();
		}
	}

	private void fastForward() {
		while (this.stop == Stop.NONE) {
			this.typed = this.counts[this.page][this.line];
			this.settle();
		}
	}

	/** Moves past finished lines that need no press, and stops where one is needed. */
	private void settle() {
		while (this.stop == Stop.NONE && this.typed >= this.counts[this.page][this.line]) {
			int count = this.counts[this.page][this.line];
			if (this.line + 1 < this.pages.get(this.page).size()) {
				if (this.cont[this.page][this.line + 1]) {
					this.typed = count;
					this.stop = Stop.SCROLL;
				} else {
					this.typed -= count;
					this.line++;
				}
			} else {
				this.typed = count;
				this.stop = this.page + 1 < this.pages.size() ? Stop.PAGE : Stop.END;
			}
		}
	}
}
