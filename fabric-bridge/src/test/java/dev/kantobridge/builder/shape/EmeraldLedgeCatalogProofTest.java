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
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Fitted catalog ledge correction must not alter its actual native route. */
class EmeraldLedgeCatalogProofTest {
    static final Path ROOT=EmeraldSourceStackFollowupsTest.ROOT,OUT=EmeraldSourceStackFollowupsTest.STUDY.resolve("rusturf-ledge-catalog");
    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);}
    @Test void catalogSlabKeepsAllElevenNativeLedgesAndExactTerrainCompanions()throws Exception{
        assumeTrue(Files.exists(OUT.resolve("updates.json")));var source=EmeraldSourceStackFollowupsTest.source();var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));String name="EM_RUSTURF_TUNNEL";
        var map=source.scene().map(name).withHeadroom(Headroom.rooms(source.scene(),source.probe(),9).get(name));var built=G3Built.map(bundle,name);var layout=RaisedRegions.gen3(G3Cells.model(built,bundle.g3tiles(built.pair()),true,new Warnings()),built,true).layout();
        JsonObject baseline=EmeraldSourceStackFollowupsTest.read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),live=EmeraldSourceStackFollowupsTest.read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json")),current=live.deepCopy();
        for(var e:EmeraldSourceStackFollowupsTest.read(OUT.resolve("updates.json")).getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());
        String snapshot=new GsonBuilder().setPrettyPrinting().create().toJson(current)+"\n";Files.writeString(OUT.resolve("fitted-proof-current.json"),snapshot);
        byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var swaps=List.copyOf(map.swapSites());
        var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true,layout);var unchanged=EmeraldSourceStackFollowupsTest.plan(map,source,live,true,layout);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true,layout);
        JsonArray routes=new JsonArray();
        for(JsonElement v:EmeraldSourceStackFollowupsTest.read(OUT.resolve("source-evidence.json")).getAsJsonArray("sources").get(0).getAsJsonObject().getAsJsonArray("occurrences")){
            JsonObject p=v.getAsJsonObject();int x=p.get("x").getAsInt(),z=p.get("y").getAsInt();assertEquals(CellKind.LEDGE,map.kind(x,z));assertEquals(163,built.coll()[built.index(x,z)]);
            for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                int i=EmeraldSourceStackFollowupsTest.column(map,x,z,dx,dz);assertEquals(0,after.blocks()[i]);assertEquals(0,after.decor()[i]);assertEquals(0,after.shapeMask()[i]);assertArrayEquals(unchanged.open()[i],after.open()[i]);assertEquals(unchanged.floor()[i],after.floor()[i]);assertEquals("slab:0|"+map.tile(x*2+dx,z*2+dz)+"|4",after.open()[i][0].toString());
            }
            routes.add(EmeraldInteriorSourceProofTest.rectangle(map,x,z,1,1,before,after));
        }
        assertEquals(11,routes.size());JsonArray contexts=new JsonArray();
        for(int x:new int[]{12,17}){JsonObject row=EmeraldInteriorSourceProofTest.rectangle(map,x,0,5,5,before,after);row.addProperty("id","emerald_rusturf_plateau_context_"+x);contexts.add(row);}
        assertArrayEquals(unchanged.blocks(),after.blocks());assertArrayEquals(unchanged.decor(),after.decor());assertArrayEquals(unchanged.shapeMask(),after.shapeMask());assertArrayEquals(unchanged.floor(),after.floor());assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(swaps,map.swapSites());
        JsonObject proof=new JsonObject();proof.addProperty("original_baseline_sha256",EmeraldInteriorSourceProofTest.sha(Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"))));proof.addProperty("current_table_sha256",EmeraldInteriorSourceProofTest.sha(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));proof.addProperty("candidate_updates_sha256",EmeraldInteriorSourceProofTest.sha(Files.readAllBytes(OUT.resolve("updates.json"))));proof.add("native_ledge_occurrences",routes);proof.add("occurrences",contexts);EmeraldSourceStackFollowupsTest.save("rusturf-ledge-catalog/runtime-proof.json",proof);
    }
}
