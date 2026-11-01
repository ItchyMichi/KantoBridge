package dev.kantobridge.review;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** A local model issue, its captured context, and an optional proposed repair. Never sent to the server. */
public record ModelReview(String id, Instant createdAt, Instant updatedAt, Status status, Category category, String comment,
		JsonObject context, @Nullable Proposal proposal, boolean screenshot) {
	public static final int VERSION = 1;
	public static final int MAX_COMMENT = 4000;
	public static final int MAX_PROPOSAL = 16000;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Set<String> FIELDS = Set.of("version", "id", "createdAt", "updatedAt", "status", "category", "comment", "context",
			"proposal", "screenshot");
	private static final Set<String> PROPOSAL_FIELDS = Set.of("method", "text", "createdAt");

	public enum Category {
		BACKGROUND, MISSING_PARTS, SHAPE, TEXTURE, OTHER;

		public String wire() {
			return name().toLowerCase(Locale.ROOT);
		}

		public static Category parse(String value) {
			Category category = valueOf(value.toUpperCase(Locale.ROOT));
			if (!category.wire().equals(value)) {
				throw new IllegalArgumentException("unknown review category");
			}
			return category;
		}
	}

	public enum Status {
		OPEN, PROPOSED, RESOLVED;

		public String wire() {
			return name().toLowerCase(Locale.ROOT);
		}

		public static Status parse(String value) {
			Status status = valueOf(value.toUpperCase(Locale.ROOT));
			if (!status.wire().equals(value)) {
				throw new IllegalArgumentException("unknown review status");
			}
			return status;
		}
	}

	public enum Method {
		MASK, DEPTH_RULE, HAND_MODEL, OTHER;

		public String wire() {
			return name().toLowerCase(Locale.ROOT);
		}

		public static Method parse(String value) {
			Method method = valueOf(value.toUpperCase(Locale.ROOT));
			if (!method.wire().equals(value)) {
				throw new IllegalArgumentException("unknown proposal method");
			}
			return method;
		}
	}

	public record Proposal(Method method, String text, Instant createdAt) {
		public Proposal {
			Objects.requireNonNull(method, "method");
			Objects.requireNonNull(createdAt, "createdAt");
			text = checkedText(text, MAX_PROPOSAL, "proposal", false);
		}
	}

	public ModelReview {
		id = checkedId(id);
		Objects.requireNonNull(createdAt, "createdAt");
		Objects.requireNonNull(updatedAt, "updatedAt");
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(category, "category");
		if (updatedAt.isBefore(createdAt)) {
			throw new IllegalArgumentException("updatedAt precedes createdAt");
		}
		if (status == Status.PROPOSED && proposal == null) {
			throw new IllegalArgumentException("a proposed review needs a proposal");
		}
		comment = checkedText(comment, MAX_COMMENT, "comment", true);
		context = Objects.requireNonNull(context, "context").deepCopy();
	}

	/** Context is a snapshot: neither the caller nor a queue reader can mutate the stored record. */
	@Override
	public JsonObject context() {
		return this.context.deepCopy();
	}

	public static ModelReview create(Category category, String comment, JsonObject context) {
		Instant now = Instant.now();
		return new ModelReview(UUID.randomUUID().toString(), now, now, Status.OPEN, category, comment, context, null, false);
	}

	public ModelReview withStatus(Status next) {
		return new ModelReview(this.id, this.createdAt, nextUpdate(), next, this.category, this.comment, this.context, this.proposal,
				this.screenshot);
	}

	public ModelReview withProposal(Method method, String text) {
		Instant now = nextUpdate();
		return new ModelReview(this.id, this.createdAt, now, Status.PROPOSED, this.category, this.comment, this.context,
				new Proposal(method, text, now), this.screenshot);
	}

	public ModelReview withScreenshot(boolean present) {
		return new ModelReview(this.id, this.createdAt, this.updatedAt, this.status, this.category, this.comment, this.context, this.proposal,
				present);
	}

	private Instant nextUpdate() {
		Instant now = Instant.now();
		return now.isAfter(this.updatedAt) ? now : this.updatedAt.plusNanos(1);
	}

	public JsonObject toJsonObject() {
		JsonObject out = new JsonObject();
		out.addProperty("version", VERSION);
		out.addProperty("id", this.id);
		out.addProperty("createdAt", this.createdAt.toString());
		out.addProperty("updatedAt", this.updatedAt.toString());
		out.addProperty("status", this.status.wire());
		out.addProperty("category", this.category.wire());
		out.addProperty("comment", this.comment);
		out.add("context", this.context.deepCopy());
		if (this.proposal != null) {
			JsonObject p = new JsonObject();
			p.addProperty("method", this.proposal.method().wire());
			p.addProperty("text", this.proposal.text());
			p.addProperty("createdAt", this.proposal.createdAt().toString());
			out.add("proposal", p);
		}
		if (this.screenshot) {
			out.addProperty("screenshot", "screenshot.png");
		}
		return out;
	}

	public String toJson() {
		return GSON.toJson(toJsonObject());
	}

	/** Invalid or unsupported records are rejected without changing the file. Context fields are extensible. */
	public static ModelReview fromJson(String text) {
		try {
			JsonElement value = ProtoJson.parse(text);
			if (!value.isJsonObject()) {
				throw new IllegalArgumentException("review must be an object");
			}
			JsonObject root = value.getAsJsonObject();
			if (!FIELDS.containsAll(root.keySet())) {
				throw new IllegalArgumentException("unsupported review field");
			}
			JsonElement version = root.get("version");
			if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
					|| !version.getAsString().equals(Integer.toString(VERSION))) {
				throw new IllegalArgumentException("unsupported review version");
			}
			JsonElement ctx = root.get("context");
			if (ctx == null || !ctx.isJsonObject()) {
				throw new IllegalArgumentException("context must be an object");
			}
			Proposal proposal = null;
			if (root.has("proposal")) {
				JsonObject p = root.getAsJsonObject("proposal");
				if (!PROPOSAL_FIELDS.containsAll(p.keySet())) {
					throw new IllegalArgumentException("unsupported proposal field");
				}
				proposal = new Proposal(p.has("method") ? Method.parse(string(p, "method")) : Method.OTHER, string(p, "text"),
						Instant.parse(string(p, "createdAt")));
			}
			boolean screenshot = root.has("screenshot");
			if (screenshot && !string(root, "screenshot").equals("screenshot.png")) {
				throw new IllegalArgumentException("invalid screenshot filename");
			}
			return new ModelReview(string(root, "id"), Instant.parse(string(root, "createdAt")), Instant.parse(string(root, "updatedAt")),
					Status.parse(string(root, "status")), Category.parse(string(root, "category")), string(root, "comment"),
					ctx.getAsJsonObject(), proposal, screenshot);
		} catch (ProtocolException e) {
			throw new IllegalArgumentException("invalid model review JSON (" + e.code() + ")", e);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("invalid model review: " + e.getMessage(), e);
		}
	}

	static String checkedId(String id) {
		if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
			throw new IllegalArgumentException("invalid review id");
		}
		return id;
	}

	private static String checkedText(String text, int limit, String name, boolean emptyAllowed) {
		if (text == null || text.length() > limit || (!emptyAllowed && text.isBlank())) {
			throw new IllegalArgumentException("invalid " + name);
		}
		return text;
	}

	private static String string(JsonObject root, String field) {
		JsonElement value = root.get(field);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException(field + " must be a string");
		}
		return value.getAsString();
	}
}
