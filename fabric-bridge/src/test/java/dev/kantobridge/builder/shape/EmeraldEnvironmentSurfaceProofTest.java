package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.google.gson.*;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.*;
import dev.kantobridge.world.MapLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Full native WorldPlan evidence for remaining source projection/soil appearance repairs. */
class EmeraldEnvironmentSurfaceProofTest {
    static final Path ROOT=EmeraldSourceStackFollowupsTest.ROOT,STUDY=EmeraldSourceStackFollowupsTest.STUDY;
    final String priorOutdoor=System.getProperty(OutdoorLevels.PROPERTY),priorChain=System.getProperty(MapChain.PROPERTY);
    record Case(String id,String map,int x,int z,int w,int d){}
    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);
        if(priorOutdoor==null)System.clearProperty(OutdoorLevels.PROPERTY);else System.setProperty(OutdoorLevels.PROPERTY,priorOutdoor);
        if(priorChain==null)System.clearProperty(MapChain.PROPERTY);else System.setProperty(MapChain.PROPERTY,priorChain);}

    @Test void acroBeamSupportHeightsAndAllSeventyFiveSourcePositionsAreMeasuredOutdoors()throws Exception{
        List<Case> cases=List.of(new Case("acro_north_network","EM_ROUTE119",3,3,18,19),
            new Case("acro_south_zigzag","EM_ROUTE119",17,90,9,7),new Case("acro_safari_zigzag","EM_SAFARI_ZONE_SOUTH",16,2,11,8));
        export("acro-source-study",cases,false);
    }

    @Test void allSoilContextsExposeActualBerryStoryCarriersAndNativeFloors()throws Exception{
        Path p=STUDY.resolve("soil-perimeter-completion/runtime-proof.json");assumeTrue(Files.exists(p));List<Case> cases=new ArrayList<>();
        for(JsonElement v:EmeraldSourceStackFollowupsTest.read(p).getAsJsonArray("occurrences")){
            JsonObject r=v.getAsJsonObject();JsonArray grid=r.getAsJsonArray("layout");cases.add(new Case(r.get("id").getAsString(),r.get("map").getAsString(),r.get("x").getAsInt(),r.get("y").getAsInt(),grid.get(0).getAsJsonArray().size(),grid.size()));
        }
        assertEquals(22,cases.size());export("soil-native-appearance",cases,false);
    }

    @Test void shorelineCandidateRetainsActualWaterStoryFloorAndCollisionCarriers()throws Exception{
        export("shore-bank-repairs",List.of(new Case("shore_birth_island_corner","EM_BIRTH_ISLAND_EXTERIOR",26,18,4,5),
            new Case("shore_route121_returns","EM_ROUTE121",72,12,8,6),new Case("shore_frontier_bank","EM_BATTLE_FRONTIER_OUTSIDE_WEST",36,13,12,5)),true);
    }

    void export(String folder,List<Case> cases,boolean candidate)throws Exception{
        Path export=ROOT.resolve("local/minecraft/kantobridge/export/emerald");assumeTrue(Files.exists(export.resolve("index.json")));
        Path out=STUDY.resolve(folder);Files.createDirectories(out);System.setProperty(OutdoorLevels.PROPERTY,"all");System.setProperty(MapChain.PROPERTY,"false");
        byte[] oldBytes=Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),liveBytes=Files.readAllBytes(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"));
        JsonObject old=JsonParser.parseString(new String(oldBytes,StandardCharsets.UTF_8)).getAsJsonObject(),live=JsonParser.parseString(new String(liveBytes,StandardCharsets.UTF_8)).getAsJsonObject(),now=live.deepCopy();
        if(candidate){
            JsonObject patch=EmeraldSourceStackFollowupsTest.read(out.resolve("updates.json")).getAsJsonObject("cells"),pre=EmeraldSourceStackFollowupsTest.read(out.resolve("preconditions.json")).getAsJsonObject("cells");
            for(var e:patch.entrySet()){assertEquals(pre.get(e.getKey()),live.getAsJsonObject("cells").get(e.getKey()),"live geometry precondition changed");now.getAsJsonObject("cells").add(e.getKey(),e.getValue());}
        }
        ExportBundle bundle=ExportBundle.open(export);List<String> maps=EmeraldPlacementParents.closure(bundle,cases.stream().map(Case::map).distinct().toList());
        var before=EmeraldBridgeSurfaceProofTest.Render.of(EmeraldBridgeSurfaceProofTest.plan(bundle,old,maps));
        var after=EmeraldBridgeSurfaceProofTest.Render.of(EmeraldBridgeSurfaceProofTest.plan(bundle,now,maps));
        JsonArray rows=new JsonArray();int quarters=0;Map<String,String> rawHashes=new TreeMap<>();
        for(Case c:cases){
            var map=after.plan().scene().map(c.map());var built=G3Built.map(bundle,c.map());var placed=after.plan().map(c.map());var oldPlaced=before.plan().map(c.map());
            assertNotNull(placed,c.map()+" has its actual parent chain");assertNotNull(oldPlaced);assertEquals(new Gson().toJson(oldPlaced.layout()),new Gson().toJson(placed.layout()));
            assertArrayEquals(before.plan().scene().map(c.map()).kinds(),map.kinds());assertArrayEquals(before.plan().scene().map(c.map()).tags(),map.tags());
            var oldShapes=before.plan().shapes().shapes(c.map());var newShapes=after.plan().shapes().shapes(c.map());
            byte[] rawBytes=Files.readAllBytes(export.resolve("g3map/"+c.map()+".json"));rawHashes.put(c.map(),EmeraldInteriorSourceProofTest.sha(rawBytes));var raw=JsonParser.parseString(new String(rawBytes,StandardCharsets.UTF_8)).getAsJsonObject();byte[] rawCells=Base64.getDecoder().decode(raw.get("cells").getAsString());
            JsonObject row=EmeraldInteriorSourceProofTest.rectangle(map,c.x(),c.z(),c.w(),c.d(),oldShapes,newShapes);row.addProperty("id",c.id());row.addProperty("width",c.w());row.addProperty("depth",c.d());row.addProperty("surface_evidence_mode","worldplan-environment-v1");row.add("actual_map_layout",new Gson().toJsonTree(placed.layout()));
            JsonArray columns=new JsonArray(),objects=new JsonArray(),stories=new JsonArray();
            for(var object:built.objects())if(object.x()>=c.x()&&object.x()<c.x()+c.w()&&object.y()>=c.z()&&object.y()<c.z()+c.d())objects.add(new Gson().toJsonTree(object));
            for(var story:after.plan().storyCells().entries())if(story.cell().map().equals(c.map())&&story.cell().x()>=c.x()&&story.cell().x()<c.x()+c.w()&&story.cell().y()>=c.z()&&story.cell().y()<c.z()+c.d())stories.add(new Gson().toJsonTree(story));
            for(int z=0;z<c.d()*2;z++)for(int x=0;x<c.w()*2;x++){
                int cx=c.x()+x/2,cz=c.z()+z/2,tx=c.x()*2+x,tz=c.z()*2+z,i=tz*map.artCols()+tx;MapLayout layout=placed.layout();
                if(layout!=null){var piece=layout.pieceAt(cx,cz);assertNotNull(piece);assertEquals(0,piece.dx());assertEquals(0,piece.dy());assertEquals(0,piece.dz());}
                JsonObject q=new JsonObject();q.addProperty("x",x);q.addProperty("z",z);q.addProperty("feet",placed.level(cx,cz));q.addProperty("source_tile",map.tile(tx,tz));q.addProperty("kind",map.kind(cx,cz).name());q.addProperty("model_kind",placed.model().kind(cx,cz).name());
                q.addProperty("raw_collision",Byte.toUnsignedInt(rawCells[(cz*raw.get("w").getAsInt()+cx)*4+2]));q.addProperty("built_collision",built.coll()[built.index(cx,cz)]);q.addProperty("source_elevation",built.elev()[built.index(cx,cz)]);q.addProperty("source_tags",map.tags()[cz*map.w()+cx]);
                q.add("ramp",new Gson().toJsonTree(layout==null?null:layout.ramp(cx,cz)));q.add("deck",new Gson().toJsonTree(layout==null?null:layout.deck(cx,cz)));
                q.addProperty("before_effective_floor",oldShapes.floor()[i]>=0?oldShapes.floor()[i]:map.tile(tx,tz));q.addProperty("after_effective_floor",newShapes.floor()[i]>=0?newShapes.floor()[i]:map.tile(tx,tz));
                q.add("before",EmeraldBridgeSurfaceProofTest.column(before,c.map(),cx,cz,x%2,z%2));q.add("after",EmeraldBridgeSurfaceProofTest.column(after,c.map(),cx,cz,x%2,z%2));columns.add(q);quarters++;
            }
            JsonArray sceneObjects=new JsonArray();for(var object:map.objects())if(object.x()>=c.x()&&object.x()<c.x()+c.w()&&object.y()>=c.z()&&object.y()<c.z()+c.d())sceneObjects.add(new Gson().toJsonTree(object));
            row.add("source_surface_columns",columns);row.add("source_objects",objects);row.add("scene_objects",sceneObjects);row.add("actual_story_entries",stories);rows.add(row);
        }
        byte[] nowBytes=candidate?(new GsonBuilder().setPrettyPrinting().create().toJson(now)+"\n").getBytes(StandardCharsets.UTF_8):liveBytes;
        JsonObject proof=new JsonObject();proof.addProperty("purpose","Actual outdoor WorldPlan surface/object/story diagnostic; geometry authoring and visual approval are separate");proof.addProperty("original_baseline_sha256",EmeraldInteriorSourceProofTest.sha(oldBytes));proof.addProperty("current_table_sha256",EmeraldInteriorSourceProofTest.sha(nowBytes));proof.addProperty("live_before_sha256",EmeraldInteriorSourceProofTest.sha(liveBytes));
        proof.addProperty("outdoor_levels","all");proof.addProperty("chain_levels",false);proof.addProperty("landscape_override",false);proof.addProperty("datum","Map-local base in the documented local planner configuration, not remote rollout landscape v15");proof.addProperty("source_quarters",quarters);proof.add("requested_maps_with_parents",new Gson().toJsonTree(maps));proof.add("source_map_hashes",new Gson().toJsonTree(rawHashes));proof.add("occurrences",rows);
        if(candidate)proof.addProperty("candidate_updates_sha256",EmeraldInteriorSourceProofTest.sha(Files.readAllBytes(out.resolve("updates.json"))));
        Files.write(out.resolve("fitted-surface-current.json"),nowBytes);Files.write(out.resolve("fitted-surface-baseline.json"),oldBytes);EmeraldSourceStackFollowupsTest.save(folder+"/surface-runtime-proof.json",proof);
    }
}
