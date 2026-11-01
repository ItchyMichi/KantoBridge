package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.google.gson.*;
import dev.kantobridge.builder.plan.Headroom;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.CellTag;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Lowering source stair stripes must retain the walk-through warp contract. */
class EmeraldResidentialThresholdProofTest {
    static final Path ROOT=EmeraldSourceStackFollowupsTest.ROOT,OUT=EmeraldSourceStackFollowupsTest.STUDY.resolve("residential-threshold-repair");
    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);}
    @Test void shallowTreadsPreserveBothWalkWarpsAndSurroundingJambs()throws Exception{
        assumeTrue(Files.exists(OUT.resolve("updates.json")));var source=EmeraldSourceStackFollowupsTest.source();var rooms=Headroom.rooms(source.scene(),source.probe(),9);
        JsonObject baseline=EmeraldSourceStackFollowupsTest.read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),live=EmeraldSourceStackFollowupsTest.read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json")),current=live.deepCopy();
        for(var e:EmeraldSourceStackFollowupsTest.read(OUT.resolve("updates.json")).getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());JsonArray rows=new JsonArray();
        for(JsonElement v:EmeraldSourceStackFollowupsTest.read(OUT.resolve("source-evidence.json")).getAsJsonArray("sources").get(0).getAsJsonObject().getAsJsonArray("occurrences")){
            JsonObject p=v.getAsJsonObject();String name=p.get("map").getAsString();int x=p.get("x").getAsInt(),z=p.get("y").getAsInt();var map=source.scene().map(name).withHeadroom(rooms.get(name));byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var swaps=List.copyOf(map.swapSites());
            var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var unchanged=EmeraldSourceStackFollowupsTest.plan(map,source,live,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true);
            assertEquals(CellKind.WALK,map.kind(x,z));assertTrue(map.hasTag(x,z,CellTag.WARP));
            Set<Integer> corrected=new HashSet<>();
            for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=EmeraldSourceStackFollowupsTest.column(map,x,z,dx,dz);corrected.add(i);assertEquals(0,after.blocks()[i]);assertEquals(0,after.shapeMask()[i]);assertEquals(3,unchanged.decor()[i]);assertEquals(1,after.decor()[i]);assertEquals(1,after.open()[i].length);assertEquals(ShapeKey.Kind.CELL,after.open()[i][0].kind());}
            assertArrayEquals(unchanged.blocks(),after.blocks());assertArrayEquals(unchanged.shapeMask(),after.shapeMask());for(int i=0;i<after.decor().length;i++)if(!corrected.contains(i)){assertEquals(unchanged.decor()[i],after.decor()[i]);assertArrayEquals(unchanged.open()[i],after.open()[i]);}assertArrayEquals(unchanged.floor(),after.floor());assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(swaps,map.swapSites());
            JsonObject row=EmeraldInteriorSourceProofTest.rectangle(map,x-1,z-1,3,2,before,after);rows.add(row);
        }
        assertEquals(2,rows.size());JsonObject proof=new JsonObject();proof.addProperty("original_baseline_sha256",EmeraldInteriorSourceProofTest.sha(Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"))));proof.addProperty("previous_table_sha256",EmeraldInteriorSourceProofTest.sha(Files.readAllBytes(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"))));proof.addProperty("candidate_updates_sha256",EmeraldInteriorSourceProofTest.sha(Files.readAllBytes(OUT.resolve("updates.json"))));proof.add("occurrences",rows);EmeraldSourceStackFollowupsTest.save("residential-threshold-repair/runtime-proof.json",proof);
    }
}
