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
import dev.kantobridge.scene.SceneMap;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Read-only source routing evidence, never geometry approval or policy changes. */
class EmeraldShoreAcroDiagnosticTest {
    static final Path ROOT=EmeraldSourceStackFollowupsTest.ROOT,STUDY=EmeraldSourceStackFollowupsTest.STUDY;
    static final Set<String> ACRO=Set.of("g3q/10701-10990-10991-10992","g3q/10957-10966-10975-10984","g3q/10957-10966-11097-11106","g3q/11001-11010-11019-11028","g3q/11032-10698-11033-11034","g3q/11107-11108-10682-10703","g3q/11117-11126-11135-11144","g3q/11147-11148-11149-10700","g3q/11158-11167-11176-11185");
    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);}
    @Test void everySolidShoreAndRepresentativeNativeWaterKeepExactSourceEvidence()throws Exception{diagnose("shoreline-source-study","sources",true);}
    @Test void allNineAcroProjectionKeysExposeActualNativeFloorAndSupportRouting()throws Exception{diagnose("bridge-repairs","source_keys",false);}

    void diagnose(String folder,String listName,boolean shore)throws Exception{
        Path directory=STUDY.resolve(folder),evidence=directory.resolve("source-evidence.json");assumeTrue(Files.exists(evidence));
        var source=EmeraldSourceStackFollowupsTest.source();var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));var rooms=Headroom.rooms(source.scene(),source.probe(),9);
        byte[] originalBytes=Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),currentBytes=Files.readAllBytes(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"));
        JsonObject baseline=JsonParser.parseString(new String(originalBytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject(),current=JsonParser.parseString(new String(currentBytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();Files.write(directory.resolve(shore?"fitted-runtime-current.json":"fitted-acro-runtime-current.json"),currentBytes);
        Map<String,ShapePlanner.MapShapes> befores=new HashMap<>(),afters=new HashMap<>();JsonArray rows=new JsonArray();int solids=0,waters=0,total=0,rawSolids=0;
        for(JsonElement item:EmeraldSourceStackFollowupsTest.read(evidence).getAsJsonArray(listName)){
            JsonObject definition=item.getAsJsonObject();String key=definition.get("key").getAsString();if(!shore&&!ACRO.contains(key))continue;Set<Boolean> waterKinds=new HashSet<>();
            for(JsonElement value:definition.getAsJsonArray("occurrences")){
                JsonObject p=value.getAsJsonObject();String name=p.get("map").getAsString();int x=p.get("x").getAsInt(),z=p.get("y").getAsInt();SceneMap original=source.scene().map(name);var map=rooms.containsKey(name)?original.withHeadroom(rooms.get(name)):original;
                if(shore&&map.kind(x,z)==CellKind.WATER&&!waterKinds.add(map.outdoors()))continue;
                if(shore)assertTrue(map.kind(x,z)==CellKind.SOLID||map.kind(x,z)==CellKind.WATER);
                var built=G3Built.map(bundle,name);var levels=RaisedRegions.gen3(G3Cells.model(built,bundle.g3tiles(built.pair()),true,new Warnings()),built,true);var layout=levels.layout();byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var swaps=List.copyOf(map.swapSites());
                var before=befores.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true,layout));var after=afters.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,current,true,layout));
                JsonObject row=EmeraldInteriorSourceProofTest.rectangle(map,x,z,1,1,before,after);assertEquals(key,row.getAsJsonArray("layout").get(0).getAsJsonArray().get(0).getAsString());row.addProperty("key",key);row.addProperty("kind",map.kind(x,z).name());row.addProperty("raw_collision",built.coll()[built.index(x,z)]);row.addProperty("source_elevation",built.elev()[built.index(x,z)]);row.addProperty("outdoors",map.outdoors());row.addProperty("raised_outcome",levels.outcome());row.addProperty("feet",layout==null?0:layout.level(x,z));row.addProperty("source_tags",map.tags()[z*map.w()+x]);row.addProperty("sample",shore&&map.kind(x,z)==CellKind.WATER?"one representative per key and indoor/outdoor source kind":"all exact source occurrences");
                if(layout!=null){row.add("raised_layout",new Gson().toJsonTree(layout));}
                JsonArray columns=new JsonArray();
                for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                    int tx=x*2+dx,tz=z*2+dz,i=tz*map.artCols()+tx;JsonObject c=new JsonObject();c.addProperty("dx",dx);c.addProperty("dz",dz);c.addProperty("source_tile",map.tile(tx,tz));
                    c.addProperty("before_blocks",before.blocks()[i]);c.addProperty("after_blocks",after.blocks()[i]);c.addProperty("before_shape_mask",before.shapeMask()[i]);c.addProperty("after_shape_mask",after.shapeMask()[i]);c.addProperty("before_decor",before.decor()[i]);c.addProperty("after_decor",after.decor()[i]);c.addProperty("before_floor",before.floor()[i]);c.addProperty("after_floor",after.floor()[i]);c.addProperty("before_effective_floor",before.floor()[i]>=0?before.floor()[i]:map.tile(tx,tz));c.addProperty("after_effective_floor",after.floor()[i]>=0?after.floor()[i]:map.tile(tx,tz));c.addProperty("before_keys",Arrays.toString(EmeraldInteriorSourceProofTest.sourceColumn(before,map,tx,tz)));c.addProperty("after_keys",Arrays.toString(EmeraldInteriorSourceProofTest.sourceColumn(after,map,tx,tz)));columns.add(c);
                }
                int rawSource=p.has("collision")?p.get("collision").getAsInt():built.coll()[built.index(x,z)];row.addProperty("original_source_collision",rawSource);if(rawSource==7)rawSolids++;
                row.add("source_column_audit",columns);assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(swaps,map.swapSites());rows.add(row);total++;if(map.kind(x,z)==CellKind.SOLID)solids++;if(map.kind(x,z)==CellKind.WATER)waters++;
            }
        }
        if(shore){assertEquals(125,rawSolids);assertEquals(6,waters);}else assertEquals(75,total);
        JsonObject proof=new JsonObject();proof.addProperty("purpose","Read-only original/current actual-source diagnostic; no candidate geometry or automatic semantic approval");proof.addProperty("original_baseline_sha256",EmeraldInteriorSourceProofTest.sha(originalBytes));proof.addProperty("current_table_sha256",EmeraldInteriorSourceProofTest.sha(currentBytes));proof.addProperty("source_evidence_sha256",EmeraldInteriorSourceProofTest.sha(Files.readAllBytes(evidence)));proof.addProperty("total_occurrences",total);proof.addProperty("raw_solid_occurrences",rawSolids);proof.addProperty("solid_occurrences",solids);proof.addProperty("water_samples",waters);proof.add("occurrences",rows);EmeraldSourceStackFollowupsTest.save(folder+"/"+(shore?"runtime-proof.json":"acro-runtime-proof.json"),proof);
    }
}
