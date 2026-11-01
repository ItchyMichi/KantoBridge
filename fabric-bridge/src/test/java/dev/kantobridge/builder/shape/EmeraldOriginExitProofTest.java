package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.google.gson.*;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.G3Built;
import dev.kantobridge.builder.plan.G3Cells;
import dev.kantobridge.builder.plan.Headroom;
import dev.kantobridge.builder.plan.RaisedRegions;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.CellTag;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Cave exit orientation changes visuals, never the actual north warp route. */
class EmeraldOriginExitProofTest {
    static final Path ROOT=EmeraldSourceStackFollowupsTest.ROOT,OUT=EmeraldSourceStackFollowupsTest.STUDY.resolve("origin-exit-orientation");
    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);}
    @Test void northApproachWarpAndThreeSolidRimCellsKeepExactSourceRouting()throws Exception{
        assumeTrue(Files.exists(OUT.resolve("updates.json")));var source=EmeraldSourceStackFollowupsTest.source();var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));var rooms=Headroom.rooms(source.scene(),source.probe(),9);
        JsonObject baseline=EmeraldSourceStackFollowupsTest.read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),live=EmeraldSourceStackFollowupsTest.read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json")),current=live.deepCopy();for(var e:EmeraldSourceStackFollowupsTest.read(OUT.resolve("updates.json")).getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());
        String snapshot=new GsonBuilder().setPrettyPrinting().create().toJson(current)+"\n";Files.writeString(OUT.resolve("fitted-proof-current.json"),snapshot);JsonArray rows=new JsonArray();
        for(JsonElement v:EmeraldSourceStackFollowupsTest.read(OUT.resolve("source-evidence.json")).getAsJsonArray("groups")){
            JsonObject group=v.getAsJsonObject();String name=group.get("map").getAsString();int x0=group.get("x").getAsInt(),z0=group.get("y").getAsInt();var map=source.scene().map(name).withHeadroom(rooms.get(name));var built=G3Built.map(bundle,name);var levels=RaisedRegions.gen3(G3Cells.model(built,bundle.g3tiles(built.pair()),true,new Warnings()),built,true);var layout=levels.layout();
            byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var swaps=List.copyOf(map.swapSites());var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true,layout);var unchanged=EmeraldSourceStackFollowupsTest.plan(map,source,live,true,layout);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true,layout);
            assertEquals(CellKind.WALK,map.kind(x0+1,z0+1));assertTrue(map.hasTag(x0+1,z0+1,CellTag.WARP));
            for(int x=x0;x<x0+3;x++)for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                int i=EmeraldSourceStackFollowupsTest.column(map,x,z0+2,dx,dz);assertEquals(CellKind.SOLID,map.kind(x,z0+2));assertEquals(2,after.blocks()[i]);assertEquals(ShapeKey.Kind.CELL,after.open()[i][0].kind());
            }
            JsonObject row=EmeraldInteriorSourceProofTest.rectangle(map,x0,z0,3,4,before,after);assertEquals(group.get("layout"),row.get("layout"));assertEquals(group.get("collisions"),row.get("source_collisions"));row.addProperty("id","emerald_origin_exit_native_"+name.toLowerCase());JsonArray floors=new JsonArray();
            for(int z=0;z<8;z++)for(int x=0;x<6;x++){
                int tx=x0*2+x,tz=z0*2+z,i=tz*map.artCols()+tx;assertEquals(unchanged.blocks()[i],after.blocks()[i]);assertEquals(unchanged.decor()[i],after.decor()[i]);assertEquals(unchanged.shapeMask()[i],after.shapeMask()[i]);assertEquals(unchanged.floor()[i],after.floor()[i]);
                assertTrue(map.kind(tx/2,tz/2)==CellKind.WALK||map.kind(tx/2,tz/2)==CellKind.SOLID);JsonObject f=new JsonObject();f.addProperty("x",x);f.addProperty("z",z);f.addProperty("feet",layout==null?0:layout.level(tx/2,tz/2));f.addProperty("before_tile",before.floor()[i]>=0?before.floor()[i]:map.tile(tx,tz));f.addProperty("after_tile",after.floor()[i]>=0?after.floor()[i]:map.tile(tx,tz));floors.add(f);
            }
            row.add("source_floor_columns",floors);assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(swaps,map.swapSites());rows.add(row);
        }
        assertEquals(2,rows.size());JsonObject proof=new JsonObject();proof.addProperty("original_baseline_sha256",EmeraldInteriorSourceProofTest.sha(Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"))));proof.addProperty("current_table_sha256",EmeraldInteriorSourceProofTest.sha(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));proof.addProperty("candidate_updates_sha256",EmeraldInteriorSourceProofTest.sha(Files.readAllBytes(OUT.resolve("updates.json"))));proof.add("occurrences",rows);EmeraldSourceStackFollowupsTest.save("origin-exit-orientation/runtime-proof.json",proof);
    }
}
