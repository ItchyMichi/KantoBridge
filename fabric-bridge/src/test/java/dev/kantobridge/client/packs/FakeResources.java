package dev.kantobridge.client.packs;

import dev.kantobridge.client.shape.vox.VoxFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * A resource manager over made-up packs for the texture-pack tests: packs in load order (lowest priority first), each
 * with its files; a lookup finds the highest pack that has the file, as Minecraft's does.
 */
final class FakeResources implements ResourceManager {
	private final Map<String, Map<ResourceLocation, byte[]>> packs = new LinkedHashMap<>();
	private final Map<String, PackResources> sources = new LinkedHashMap<>();

	/** A pack above the ones added before it. */
	FakeResources pack(String id) {
		this.packs.computeIfAbsent(id, k -> new TreeMap<>());
		this.sources.computeIfAbsent(id, k -> (PackResources) Proxy.newProxyInstance(PackResources.class.getClassLoader(),
				new Class<?>[] {PackResources.class}, (proxy, method, args) -> switch (method.getName()) {
					case "packId" -> k;
					case "toString" -> "pack " + k;
					case "hashCode" -> k.hashCode();
					case "equals" -> proxy == args[0];
					default -> null;
				}));
		return this;
	}

	/** A file in the last pack added. */
	FakeResources file(String id, byte[] bytes) {
		String last = null;
		for (String pack : this.packs.keySet()) {
			last = pack;
		}
		this.packs.get(last).put(ResourceLocation.parse(id), bytes);
		return this;
	}

	FakeResources file(String id, String text) {
		return this.file(id, text.getBytes(StandardCharsets.UTF_8));
	}

	/** A PNG's signature and header, enough for a size check (the pixels are never read). */
	static byte[] png(int width, int height) {
		ByteBuffer out = ByteBuffer.allocate(33);
		out.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
		out.putInt(13).put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(width).putInt(height);
		out.put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0).putInt(0);
		return out.array();
	}

	/** A solid box of one colour, {@code sx × sy × sz} voxels (vox axes). */
	static VoxFile.Model box(int sx, int sy, int sz, int colour) {
		byte[] voxels = new byte[sx * sy * sz * 4];
		int i = 0;
		for (int z = 0; z < sz; z++) {
			for (int y = 0; y < sy; y++) {
				for (int x = 0; x < sx; x++) {
					voxels[i++] = (byte) x;
					voxels[i++] = (byte) y;
					voxels[i++] = (byte) z;
					voxels[i++] = (byte) colour;
				}
			}
		}
		return new VoxFile.Model(sx, sy, sz, voxels, palette(), 1);
	}

	/** A 3D checkerboard: no two filled voxels share a face, so nothing merges. */
	static VoxFile.Model checkerboard(int size) {
		java.io.ByteArrayOutputStream voxels = new java.io.ByteArrayOutputStream();
		for (int z = 0; z < size; z++) {
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					if ((x + y + z) % 2 == 0) {
						voxels.writeBytes(new byte[] {(byte) x, (byte) y, (byte) z, 1});
					}
				}
			}
		}
		return new VoxFile.Model(size, size, size, voxels.toByteArray(), palette(), 1);
	}

	static int[] palette() {
		int[] palette = new int[256];
		palette[1] = 0xFF336699;
		palette[2] = 0xFFCC0000;
		palette[3] = 0xFF00CC00;
		palette[4] = 0xFF0000CC;
		return palette;
	}

	/** A key index of made-up keys, read back from the file it is written to. */
	static KeyIndex keys(Path dir) throws IOException {
		String json = """
				{"keys": 1, "game": "red", "exportHash": "%1$s", "shapeIndexHash": "%1$s",
				 "tiles": {"t/4": {"palettes": {"P": {"texture": "x"}}}, "t/5": {"palettes": {"P": {"texture": "x"}}},
				           "t/9": {"palettes": {"P": {"texture": "x"}}}, "t/10": {"palettes": {"P": {"texture": "x"}}}},
				 "sprites": {"S": {"frames": 6}},
				 "cells": {"t/1-2-3-4": {"kind": "hull"}, "t/1-2-3-5": {"kind": "hull"}, "t/1-2-3-6": {"kind": "object"},
				           "t/1-2-3-7": {"kind": "columns"}, "t/1-2-3-8": {"kind": "columns"}},
				 "structs": {}, "maps": ["M"], "classes": []}
				""".formatted("0".repeat(64));
		Path file = dir.resolve("keys.json");
		Files.writeString(file, json, StandardCharsets.UTF_8);
		KeyIndex keys = KeyIndex.read(file);
		if (keys == null) {
			throw new IOException("the test key index doesn't read back");
		}
		return keys;
	}

	private Resource resource(String pack, byte[] bytes) {
		return new Resource(this.sources.get(pack), () -> new ByteArrayInputStream(bytes));
	}

	@Override
	public Set<String> getNamespaces() {
		Set<String> out = new TreeSet<>();
		this.packs.values().forEach(files -> files.keySet().forEach(id -> out.add(id.getNamespace())));
		return out;
	}

	@Override
	public List<Resource> getResourceStack(ResourceLocation id) {
		List<Resource> out = new ArrayList<>();
		this.packs.forEach((pack, files) -> {
			byte[] bytes = files.get(id);
			if (bytes != null) {
				out.add(this.resource(pack, bytes));
			}
		});
		return out;
	}

	@Override
	public Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> filter) {
		Map<ResourceLocation, Resource> out = new TreeMap<>();
		this.listResourceStacks(path, filter).forEach((id, stack) -> out.put(id, stack.get(stack.size() - 1)));
		return out;
	}

	@Override
	public Map<ResourceLocation, List<Resource>> listResourceStacks(String path, Predicate<ResourceLocation> filter) {
		Map<ResourceLocation, List<Resource>> out = new TreeMap<>();
		this.packs.forEach((pack, files) -> files.forEach((id, bytes) -> {
			if (id.getPath().startsWith(path + "/") && filter.test(id)) {
				out.computeIfAbsent(id, k -> new ArrayList<>()).add(this.resource(pack, bytes));
			}
		}));
		return out;
	}

	@Override
	public Stream<PackResources> listPacks() {
		return this.sources.values().stream();
	}

	@Override
	public Optional<Resource> getResource(ResourceLocation id) {
		List<Resource> stack = this.getResourceStack(id);
		return stack.isEmpty() ? Optional.empty() : Optional.of(stack.get(stack.size() - 1));
	}
}
