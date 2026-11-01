package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.plan.Headroom;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.FrontEnds;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.scene.SceneMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Uses the authorized local export and immutable pre-pass table, not invented absent-cell geometry. */
class EmeraldSchoolFallbackTest {
	@AfterEach
	void reset() { FittedShapes.useForTest(null); FittedShapes.useTablesForTest(null); FittedShapes.setEnabled(true); }

	private static ShapePlanner.MapShapes plan(SceneMap map, Scene scene, UnitProbe probe, Map<String,FittedShapes.Entry> cells,
			Map<String,String> stacks, Map<String,List<FittedShapes.Layer>> layers, java.util.Set<String> seats, java.util.Set<String> walks) {
		FittedShapes.useTablesForTest(Map.of("emerald",new FittedShapes.Table(cells,Map.of(),stacks,seats,layers,List.of(),walks)));
		String previous=FittedShapes.enter("emerald");
		try { return ShapePlanner.planMap(map,scene.atlas(map.atlas()),0,ShapeParams.current(true),null,probe,new TreeMap<>()); }
		finally { FittedShapes.leave(previous); }
	}

	@Test
	void missingSchoolBoardCapsAlreadyHaveNativeSolidColumnsBeforeExplicitStackModelsAreAdded() throws Exception {
		Path root=TestData.gametestResources().toAbsolutePath().resolve("../../../..").normalize();
		Path export=root.resolve("local/minecraft/kantobridge/export/emerald");
		Path baseline=root.resolve("local/model-gallery/emerald-full-pass-baseline.json");
		assumeTrue(Files.isRegularFile(export.resolve("index.json"))&&Files.isRegularFile(baseline),"local authorized Emerald source not installed");
		ExportBundle bundle=ExportBundle.open(export);Scene scene=FrontEnds.forGame("emerald").describe(bundle);
		ArtUnitProbe probe=new ArtUnitProbe(FrontEnds.artSource(bundle));
		SceneMap map=scene.map("EM_RUSTBORO_CITY_POKEMON_SCHOOL");
		int headroom=Headroom.rooms(scene,probe,9).get(map.id());map=map.withHeadroom(headroom);
		String text=Files.readString(baseline);
		Map<String,FittedShapes.Entry> cells=new TreeMap<>();Map<String,String> stacks=new TreeMap<>();
		Map<String,List<FittedShapes.Layer>> layers=new TreeMap<>();var seats=new HashSet<String>();var walks=new HashSet<String>();
		FittedShapes.parse(text,cells);FittedShapes.parseStacks(text,stacks);FittedShapes.parseLayers(text,layers);
		FittedShapes.parseSeats(text,seats);FittedShapes.parseFlagged(text,"walk",walks);
		String[] tops={"g3q/8656-8657-8658-8659","g3q/8660-8661-8662-8663","g3q/8664-8665-8666-8667","g3q/8657-8668-8669-8670"};
		String[] bases={"g3q/8671-8672-8673-8674","g3q/8672-8672-8674-8674","g3q/8672-8672-8674-8674","g3q/8672-8675-8674-8676"};
		for(int x=0;x<3;x++)assertFalse(cells.containsKey(tops[x]));
		ShapePlanner.MapShapes before=plan(map,scene,probe,cells,stacks,layers,seats,walks);
		byte[] voxels=new byte[512];java.util.Arrays.fill(voxels,(byte)255);
		for(int x=0;x<4;x++){cells.put(tops[x],new FittedShapes.Entry("voxels",List.of(),voxels));stacks.put(tops[x],bases[x]);}
		ShapePlanner.MapShapes after=plan(map,scene,probe,cells,stacks,layers,seats,walks);
		com.google.gson.JsonObject proof=new com.google.gson.JsonObject();
		proof.addProperty("map",map.id());proof.addProperty("headroom",headroom);
		proof.addProperty("baseline_table",baseline.toString());
		proof.addProperty("baseline_sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(baseline))));
		com.google.gson.JsonArray sourceCells=new com.google.gson.JsonArray();
		for(int x=0;x<4;x++) {
			assertEquals(CellKind.SOLID,map.kind(4+x,1));assertEquals(CellKind.SOLID,map.kind(4+x,2));
			int top=2*map.artCols()+(4+x)*2,base=4*map.artCols()+(4+x)*2;
			if(x<3){
				assertEquals(6,headroom);assertEquals(6,before.blocks()[top]);assertEquals(0,before.shapeMask()[top]);
				System.out.println("SCHOOL_NATIVE "+tops[x]+" headroom="+headroom+" blocks="+before.blocks()[top]+" mask="+before.shapeMask()[top]+" keys="+java.util.Arrays.toString(before.open()[top]));
			}
			com.google.gson.JsonObject cell=new com.google.gson.JsonObject();cell.addProperty("key",tops[x]);cell.addProperty("x",4+x);cell.addProperty("y",1);
			com.google.gson.JsonArray columns=new com.google.gson.JsonArray();
			for(int z=0;z<2;z++)for(int dx=0;dx<2;dx++){
				int i=(2+z)*map.artCols()+(4+x)*2+dx;
				com.google.gson.JsonObject column=new com.google.gson.JsonObject();column.addProperty("dx",dx);column.addProperty("dz",z);
				column.addProperty("blocks",before.blocks()[i]);column.addProperty("shape_mask",before.shapeMask()[i]);column.addProperty("decor",before.decor()[i]);
				com.google.gson.JsonArray keys=new com.google.gson.JsonArray();
				for(ShapeKey shape:before.open()[i]){keys.add(shape.toString());if(x<3)assertEquals(ShapeKey.Kind.CUBE,shape.kind());}
				column.add("keys",keys);columns.add(column);
			}
			cell.add("columns",columns);sourceCells.add(cell);
			assertNull(after.open()[top]);assertEquals(0,after.blocks()[top]);
			assertEquals(4,after.blocks()[base]);assertEquals(4,after.open()[base].length);
			assertEquals(Integer.parseInt(tops[x].substring(4).split("-")[0]),after.open()[base][2].number(0));
		}
		proof.add("cells",sourceCells);Path target=root.resolve("local/model-gallery/emerald-pass-study/school-native-fallback.json");
		Files.writeString(target,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(proof)+"\n");
	}
}
