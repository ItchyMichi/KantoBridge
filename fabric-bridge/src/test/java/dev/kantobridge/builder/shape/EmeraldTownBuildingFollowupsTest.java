package dev.kantobridge.builder.shape;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.kantobridge.builder.shape.EmeraldInteriorSourceProofTest.*;
import com.google.gson.*;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.G3Built;
import dev.kantobridge.builder.plan.G3Cells;
import dev.kantobridge.builder.plan.RaisedRegions;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.builder.plan.Headroom;
import dev.kantobridge.builder.plan.G3StoryCells;
import dev.kantobridge.builder.plan.PlacedMap;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.CellTag;
import java.nio.file.*;
import java.util.*;

/** Exact exported-map source proofs for newly identified coastal town buildings. */
class EmeraldTownBuildingFollowupsTest {
    @AfterEach void reset() {
        FittedShapes.useForTest(null);
        FittedShapes.useTablesForTest(null);
        FittedShapes.setEnabled(true);
    }

    @Test void slateportHarborAndSeashoreHousePreserveSourceWalkAndWarps() throws Exception {
        new EmeraldInteriorSourceProofTest().mauvilleBuildings("buildings-slateport-coast",2,5,0,2);
    }

    @Test void slateportBoatAndLighthousePreserveSourceWalkProjection() throws Exception {
        new EmeraldInteriorSourceProofTest().mauvilleBuildings("buildings-slateport-objects",2,4,0,0);
    }

    @Test void abandonedShipPreservesSeaRockAndBoardingWarp() throws Exception {
        new EmeraldInteriorSourceProofTest().mauvilleBuildings("building-abandoned-ship",1,8,3,1);
    }

    @Test void route109BeachFurniturePreservesAllThirtyEightWalkPlacements() throws Exception {
        Path folder=STUDY.resolve("buildings-route109-beach");
        var source=EmeraldSourceStackFollowupsTest.source();
        var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));
        var map=source.scene().map("EM_ROUTE109");var built=G3Built.map(bundle,map.id());
        var levels=RaisedRegions.gen3(G3Cells.model(built,bundle.g3tiles(built.pair()),true,new Warnings()),built,true);
        var baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"));
        Path livePath=ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json");
        var live=read(livePath);var current=live.deepCopy();var patch=read(folder.resolve("updates.json"));
        for(var e:patch.getAsJsonObject("buildings").entrySet())current.getAsJsonArray("buildings").add(e.getValue());
        byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var swaps=List.copyOf(map.swapSites());
        var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true,levels.layout());
        var unchanged=EmeraldSourceStackFollowupsTest.plan(map,source,live,true,levels.layout());
        var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true,levels.layout());
        JsonArray proofs=new JsonArray();Set<Integer> owned=new HashSet<>();Set<String> origins=new HashSet<>();
        for(var item:JsonParser.parseString(Files.readString(folder.resolve("added-ledger.json"))).getAsJsonArray()){
            var row=item.getAsJsonObject();String id=row.get("key").getAsString();
            for(var place:row.getAsJsonArray("occurrences")){
                var loc=place.getAsJsonObject();assertEquals(map.id(),loc.get("map").getAsString());
                int x0=loc.get("x").getAsInt(),y0=loc.get("y").getAsInt(),w=loc.get("width").getAsInt(),d=loc.get("depth").getAsInt();
                assertTrue(origins.add(x0+","+y0));assertEquals(1,after.templates().stream().filter(t->t.x0()==x0*2&&t.y0()==y0*2).count());
                for(var value:loc.getAsJsonArray("cells")){
                    var cell=value.getAsJsonObject();int x=cell.get("x").getAsInt(),y=cell.get("y").getAsInt();
                    assertEquals(0,built.coll()[built.index(x,y)]);assertEquals(CellKind.WALK,map.kind(x,y));assertFalse(cell.get("terrain_excluded").getAsBoolean());
                    for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                        int i=index(map,x,y,dx,dz);assertTrue(owned.add(i),"overlapping beach templates");
                        assertEquals(0,after.blocks()[i]);assertEquals(0,after.shapeMask()[i]);assertTrue(after.decor()[i]>0);
                        assertNotNull(after.open()[i]);assertEquals(ShapeKey.Kind.TMPL,after.open()[i][0].kind());
                        // FittedBuildings deliberately replaces the flattened
                        // prop/shadow art with its neighboring WALK ground.
                        // These exact source scenes are sand; no sprite tile
                        // may be selected as the new ground under the object.
                        assertTrue(Set.of(10728,10729).contains(after.floor()[i]),"beach floor must use source-native plain sand");
                    }
                }
                var proof=rectangle(map,x0,y0,w,d,before,after);proof.addProperty("id",id);proof.addProperty("raised_outcome",levels.outcome());
                proof.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(folder.resolve("updates.json"))));proof.addProperty("loaded_table_sha256",sha(Files.readAllBytes(livePath)));
                proof.add("source_claims",loc.getAsJsonArray("cells").deepCopy());proof.add("source_floor_columns",sourceFloors(map,x0,y0,w,d,before,after,levels.layout(),null));proofs.add(proof);
                proof.addProperty("floor_material_note","Existing FittedBuildings replaces flat furniture/shadow drawing with native neighboring sand10728/10729; zero collision and actual floor height remain unchanged. Original floor tiles are retained in source_floor_columns for the before comparison.");
            }
        }
        assertEquals(38,origins.size());assertEquals(156*4,owned.size());
        for(int i=0;i<after.blocks().length;i++)if(!owned.contains(i)){
            assertEquals(unchanged.blocks()[i],after.blocks()[i]);assertEquals(unchanged.decor()[i],after.decor()[i]);
            assertEquals(unchanged.shapeMask()[i],after.shapeMask()[i]);assertEquals(unchanged.floor()[i],after.floor()[i]);assertArrayEquals(unchanged.open()[i],after.open()[i]);
        }
        assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(swaps,map.swapSites());
        save("buildings-route109-beach",proofs);
    }

    @Test void lilycoveMuseumDescendingStairRecordsNativeWarpFloor() throws Exception {
        Path folder=STUDY.resolve("lilycove-museum-stairs-native");Files.createDirectories(folder);
        var source=EmeraldSourceStackFollowupsTest.source();var rooms=Headroom.rooms(source.scene(),source.probe(),9);
        String name="EM_LILYCOVE_CITY_LILYCOVE_MUSEUM_2F";var map=source.scene().map(name).withHeadroom(rooms.get(name));
        var baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"));
        Path livePath=ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json");byte[] liveBytes=Files.readAllBytes(livePath);var live=JsonParser.parseString(new String(liveBytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var swaps=List.copyOf(map.swapSites());
        var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,live,true);
        assertEquals(CellKind.WALK,map.kind(13,1));assertTrue(map.hasTag(13,1,CellTag.WARP));
        for(int y=0;y<2;y++)for(int x=12;x<15;x++){
            if(x!=13||y!=1)assertEquals(CellKind.SOLID,map.kind(x,y));
            for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                int i=index(map,x,y,dx,dz);assertEquals(before.blocks()[i],after.blocks()[i]);assertEquals(before.shapeMask()[i],after.shapeMask()[i]);
                assertEquals(before.decor()[i],after.decor()[i]);assertEquals(before.floor()[i],after.floor()[i]);assertArrayEquals(before.open()[i],after.open()[i]);
                if(x==13&&y==1){assertEquals(0,after.blocks()[i]);assertEquals(0,after.shapeMask()[i]);}
            }
        }
        var proof=rectangle(map,12,0,3,2,before,after);proof.addProperty("id","emerald_lilycove_museum_stairs2");
        proof.addProperty("current_table_sha256",sha(liveBytes));proof.add("source_floor_columns",sourceFloors(map,12,0,3,2,before,after,null,null));
        proof.addProperty("source_warp_x",13);proof.addProperty("source_warp_y",1);proof.addProperty("source_warp_target","EM_LILYCOVE_CITY_LILYCOVE_MUSEUM_1F");
        JsonArray rows=new JsonArray();rows.add(proof);save("lilycove-museum-stairs-native",rows);
        var report=read(folder.resolve("runtime-proof.json"));report.addProperty("current_table_sha256",sha(liveBytes));
        Files.writeString(folder.resolve("runtime-proof.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");Files.write(folder.resolve("fitted-proof-current.json"),liveBytes);
        assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(swaps,map.swapSites());
    }

    @Test void slateportBlueCanopyVariantsPreserveWalkableDisplaySpace() throws Exception {
        String family="buildings-slateport-market";new EmeraldInteriorSourceProofTest().mauvilleBuildings(family,4,42,0,0);
        Path folder=STUDY.resolve(family);var source=EmeraldSourceStackFollowupsTest.source();
        var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));var map=source.scene().map("EM_SLATEPORT_CITY");var built=G3Built.map(bundle,map.id());
        var levels=RaisedRegions.gen3(G3Cells.model(built,bundle.g3tiles(built.pair()),true,new Warnings()),built,true);
        var baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"));var current=baseline.deepCopy();var patch=read(folder.resolve("updates.json"));
        for(var e:patch.getAsJsonObject("buildings").entrySet())current.getAsJsonArray("buildings").add(e.getValue());
        var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true,levels.layout());var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true,levels.layout());
        var report=read(folder.resolve("runtime-proof.json"));
        for(var value:report.getAsJsonArray("occurrences")){
            var row=value.getAsJsonObject();int x=row.get("x").getAsInt(),y=row.get("y").getAsInt(),w=row.getAsJsonArray("layout").get(0).getAsJsonArray().size();
            row.add("source_floor_columns",sourceFloors(map,x,y,w,3,before,after,levels.layout(),null));
            row.addProperty("floor_material_note","Existing template ground selection replaces flattened roof/shadow art with neighboring native WALK ground. Source floor before/after quarter IDs are explicit; all42 WALK cells remain zero collision and Python geometry proves26voxel low clearance.");
        }
        Files.writeString(folder.resolve("runtime-proof.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
    }

    @Test void museumTreadFacingPreservesExactWarpFloorAndAllOtherColumns() throws Exception {
        Path folder=STUDY.resolve("lilycove-museum-tread-repair");String key="g3q/3656-3656-6946-6947",name="EM_LILYCOVE_CITY_LILYCOVE_MUSEUM_2F";
        var source=EmeraldSourceStackFollowupsTest.source();var map=source.scene().map(name).withHeadroom(Headroom.rooms(source.scene(),source.probe(),9).get(name));
        var baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"));Path livePath=ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json");var live=read(livePath);var current=live.deepCopy();var patch=read(folder.resolve("updates.json"));assertEquals(1,patch.getAsJsonObject("cells").size());var entry=patch.getAsJsonObject("cells").getAsJsonObject(key);assertTrue(entry.get("walk").getAsBoolean());current.getAsJsonObject("cells").add(key,entry);
        var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var unchanged=EmeraldSourceStackFollowupsTest.plan(map,source,live,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true);
        byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone(),tiles=map.tiles().clone();var swaps=List.copyOf(map.swapSites());assertEquals(CellKind.WALK,map.kind(13,1));assertTrue(map.hasTag(13,1,CellTag.WARP));
        assertArrayEquals(unchanged.blocks(),after.blocks());assertArrayEquals(unchanged.decor(),after.decor());assertArrayEquals(unchanged.shapeMask(),after.shapeMask());assertArrayEquals(unchanged.floor(),after.floor());
        Set<Integer> changedQuarters=new HashSet<>();for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++)changedQuarters.add(index(map,13,1,dx,dz));
        for(int i=0;i<after.blocks().length;i++)if(!changedQuarters.contains(i))assertArrayEquals(unchanged.open()[i],after.open()[i]);else{assertEquals(unchanged.open()[i].length,after.open()[i].length);for(int y=0;y<after.open()[i].length;y++){assertEquals(unchanged.open()[i][y].withVariant(null),after.open()[i][y].withVariant(null));assertNotEquals(unchanged.open()[i][y].variant(),after.open()[i][y].variant());}}
        for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=index(map,13,1,dx,dz);assertEquals(0,after.blocks()[i]);assertEquals(0,after.shapeMask()[i]);assertTrue(after.decor()[i]>0);assertNotNull(after.open()[i]);for(var shape:after.open()[i])assertEquals(ShapeKey.Kind.CELL,shape.kind());}
        var proof=rectangle(map,12,0,3,2,before,after);proof.addProperty("id","emerald_lilycove_museum_stairs2");proof.add("source_floor_columns",sourceFloors(map,12,0,3,2,before,after,null,null));proof.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(folder.resolve("updates.json"))));proof.addProperty("loaded_table_sha256",sha(Files.readAllBytes(livePath)));
        JsonArray rows=new JsonArray();rows.add(proof);save("lilycove-museum-tread-repair",rows);String snapshot=new GsonBuilder().setPrettyPrinting().create().toJson(current)+"\n";Files.writeString(folder.resolve("fitted-proof-current.json"),snapshot);var report=read(folder.resolve("runtime-proof.json"));report.addProperty("current_table_sha256",sha(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));report.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(folder.resolve("updates.json"))));Files.writeString(folder.resolve("runtime-proof.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
        assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertArrayEquals(tiles,map.tiles());assertEquals(swaps,map.swapSites());
    }

    @Test void slateportEdgesKeepSourceRoutingAndRecordNativeCrateColumns() throws Exception {
        Path folder=STUDY.resolve("towns-slateport-edges");var source=EmeraldSourceStackFollowupsTest.source();var map=source.scene().map("EM_SLATEPORT_CITY");var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));var built=G3Built.map(bundle,map.id());var levels=RaisedRegions.gen3(G3Cells.model(built,bundle.g3tiles(built.pair()),true,new Warnings()),built,true);
        var baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"));Path livePath=ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json");var live=read(livePath);var current=live.deepCopy();var patch=read(folder.resolve("updates.json"));for(var e:patch.getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());
        var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true,levels.layout());var unchanged=EmeraldSourceStackFollowupsTest.plan(map,source,live,true,levels.layout());var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true,levels.layout());
        byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone(),tiles=map.tiles().clone();var swaps=List.copyOf(map.swapSites());JsonArray rows=new JsonArray();int edges=0,crates=0;
        assertArrayEquals(unchanged.blocks(),after.blocks());assertArrayEquals(unchanged.decor(),after.decor());assertArrayEquals(unchanged.shapeMask(),after.shapeMask());assertArrayEquals(unchanged.floor(),after.floor());Set<Integer> allowed=new HashSet<>();
        for(var value:JsonParser.parseString(Files.readString(folder.resolve("source-cases.json"))).getAsJsonArray()){
            var c=value.getAsJsonObject();int x=c.get("x").getAsInt(),y=c.get("y").getAsInt();String key=c.get("key").getAsString();assertEquals(map.id(),c.get("map").getAsString());var proof=rectangle(map,x,y,1,1,before,after);assertEquals(key,proof.getAsJsonArray("layout").get(0).getAsJsonArray().get(0).getAsString());proof.addProperty("id",key);proof.addProperty("role",c.get("role").getAsString());proof.addProperty("ordinal",c.get("ordinal").getAsInt());proof.add("source_floor_columns",sourceFloors(map,x,y,1,1,before,after,levels.layout(),null));JsonArray audit=new JsonArray();
            for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=index(map,x,y,dx,dz);JsonObject q=new JsonObject();q.addProperty("dx",dx);q.addProperty("dz",dz);q.addProperty("original_blocks",before.blocks()[i]);q.addProperty("current_blocks",after.blocks()[i]);q.addProperty("original_decor",before.decor()[i]);q.addProperty("current_decor",after.decor()[i]);q.add("original_keys",new Gson().toJsonTree(sourceColumn(before,map,x*2+dx,y*2+dz)));q.add("current_keys",new Gson().toJsonTree(sourceColumn(after,map,x*2+dx,y*2+dz)));audit.add(q);if(patch.getAsJsonObject("cells").has(key))allowed.add(i);}
            proof.add("native_column_audit",audit);rows.add(proof);if(c.get("role").getAsString().equals("crate"))crates++;else edges++;
        }
        for(int i=0;i<after.blocks().length;i++)if(!allowed.contains(i))assertArrayEquals(unchanged.open()[i],after.open()[i]);
        assertEquals(125,edges);assertEquals(20,crates);assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertArrayEquals(tiles,map.tiles());assertEquals(swaps,map.swapSites());save("towns-slateport-edges",rows);
        String snapshot=new GsonBuilder().setPrettyPrinting().create().toJson(current)+"\n";Files.writeString(folder.resolve("fitted-proof-current.json"),snapshot);var report=read(folder.resolve("runtime-proof.json"));report.addProperty("current_table_sha256",sha(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));report.addProperty("loaded_table_sha256",sha(Files.readAllBytes(livePath)));report.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(folder.resolve("updates.json"))));Files.writeString(folder.resolve("runtime-proof.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
    }

    @Test void slateportCratesMatchFourPilesBeforeTwelveSinglesAndPreserveTheirNeighbors() throws Exception {
        Path folder=STUDY.resolve("slateport-crates");var source=EmeraldSourceStackFollowupsTest.source();var map=source.scene().map("EM_SLATEPORT_CITY");var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));var built=G3Built.map(bundle,map.id());var levels=RaisedRegions.gen3(G3Cells.model(built,bundle.g3tiles(built.pair()),true,new Warnings()),built,true);
        var baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"));Path livePath=ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json");var live=read(livePath);var current=live.deepCopy();var patch=read(folder.resolve("updates.json"));assertEquals(4,patch.getAsJsonObject("cells").size());assertEquals(4,patch.getAsJsonObject("buildings").size());for(var e:patch.getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());
        var ordered=new ArrayList<>(patch.getAsJsonObject("buildings").keySet());Collections.sort(ordered);assertTrue(ordered.get(0).contains("3stack"));assertTrue(ordered.get(1).contains("3stack"));for(var id:ordered)current.getAsJsonArray("buildings").add(patch.getAsJsonObject("buildings").get(id));
        var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true,levels.layout());var unchanged=EmeraldSourceStackFollowupsTest.plan(map,source,live,true,levels.layout());var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true,levels.layout());
        byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone(),tiles=map.tiles().clone();var swaps=List.copyOf(map.swapSites());Set<Integer> claimed=new HashSet<>();Set<String> pileOrigins=Set.of("4,33","9,55","12,34","8,55");JsonArray rows=new JsonArray();int piles=0,singles=0,nested=0;
        for(var value:JsonParser.parseString(Files.readString(folder.resolve("added-ledger.json"))).getAsJsonArray()){
            var group=value.getAsJsonObject();String id=group.get("key").getAsString();boolean pile=id.contains("3stack");
            for(var item:group.getAsJsonArray("occurrences")){
                var loc=item.getAsJsonObject();int x=loc.get("x").getAsInt(),y=loc.get("y").getAsInt(),d=loc.get("depth").getAsInt();assertEquals(map.id(),loc.get("map").getAsString());
                if(!pile&&pileOrigins.contains(x+","+y)){nested++;continue;}
                var matches=after.templates().stream().filter(t->t.x0()==x*2&&t.y0()==y*2).toList();assertEquals(1,matches.size(),id+" "+x+","+y);assertEquals(2,matches.get(0).entry().get("w").getAsInt());assertEquals(d*2,matches.get(0).entry().get("d").getAsInt());
                for(int z=0;z<d;z++){assertEquals(CellKind.SOLID,map.kind(x,y+z));assertEquals(7,built.coll()[built.index(x,y+z)]);for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=index(map,x,y+z,dx,dz);assertTrue(claimed.add(i));assertEquals(unchanged.blocks()[i],after.blocks()[i],"native crate collision "+x+","+(y+z));assertEquals(unchanged.decor()[i],after.decor()[i]);assertEquals(unchanged.floor()[i],after.floor()[i]);assertEquals(unchanged.shapeMask()[i],after.shapeMask()[i]);assertNotNull(after.open()[i]);for(var key:after.open()[i])assertEquals(ShapeKey.Kind.TMPL,key.kind());}}
                var proof=rectangle(map,x,y,1,d,before,after);proof.addProperty("id",id);proof.add("source_floor_columns",sourceFloors(map,x,y,1,d,before,after,levels.layout(),null));proof.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(folder.resolve("updates.json"))));proof.addProperty("loaded_table_sha256",sha(Files.readAllBytes(livePath)));rows.add(proof);if(pile)piles++;else singles++;
            }
        }
        assertEquals(4,piles);assertEquals(12,singles);assertEquals(4,nested);assertEquals(80,claimed.size());
        JsonArray differences=new JsonArray();for(int i=0;i<after.blocks().length;i++)if(!claimed.contains(i)&&(unchanged.blocks()[i]!=after.blocks()[i]||unchanged.decor()[i]!=after.decor()[i]||unchanged.floor()[i]!=after.floor()[i]||!Arrays.equals(unchanged.open()[i],after.open()[i]))){JsonObject q=new JsonObject();int tx=i%map.artCols(),tz=i/map.artCols();q.addProperty("tile_x",tx);q.addProperty("tile_z",tz);q.addProperty("source_x",tx/2);q.addProperty("source_y",tz/2);q.addProperty("source_kind",map.kindAtTile(tx,tz).name());q.addProperty("before_blocks",unchanged.blocks()[i]);q.addProperty("after_blocks",after.blocks()[i]);q.addProperty("before_decor",unchanged.decor()[i]);q.addProperty("after_decor",after.decor()[i]);q.addProperty("before_floor",unchanged.floor()[i]);q.addProperty("after_floor",after.floor()[i]);q.add("before_keys",new Gson().toJsonTree(sourceColumn(unchanged,map,tx,tz)));q.add("after_keys",new Gson().toJsonTree(sourceColumn(after,map,tx,tz)));differences.add(q);}Files.writeString(folder.resolve("unclaimed-interface-diagnostic.json"),new GsonBuilder().setPrettyPrinting().create().toJson(differences)+"\n");
        for(int i=0;i<after.blocks().length;i++)if(!claimed.contains(i)){assertEquals(unchanged.blocks()[i],after.blocks()[i]);assertEquals(unchanged.decor()[i],after.decor()[i]);assertEquals(unchanged.shapeMask()[i],after.shapeMask()[i]);assertEquals(unchanged.floor()[i],after.floor()[i]);assertArrayEquals(unchanged.open()[i],after.open()[i]);}
        assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertArrayEquals(tiles,map.tiles());assertEquals(swaps,map.swapSites());save("slateport-crates",rows);String snapshot=new GsonBuilder().setPrettyPrinting().create().toJson(current)+"\n";Files.writeString(folder.resolve("fitted-proof-current.json"),snapshot);var report=read(folder.resolve("runtime-proof.json"));report.addProperty("current_table_sha256",sha(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));report.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(folder.resolve("updates.json"))));report.addProperty("loaded_table_sha256",sha(Files.readAllBytes(livePath)));report.addProperty("matched_single_crates",singles);report.addProperty("matched_three_crate_piles",piles);report.addProperty("nested_single_patterns_correctly_suppressed",nested);Files.writeString(folder.resolve("runtime-proof.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
    }

    @Test void newMauvilleSwitchesUseNativeColoredFloorsAndKeepAllTenStoryStates() throws Exception {
        Path folder=STUDY.resolve("new-mauville-switches-native");Files.createDirectories(folder);
        var source=EmeraldSourceStackFollowupsTest.source();String name="EM_NEW_MAUVILLE_INSIDE";
        var map=source.scene().map(name).withHeadroom(Headroom.rooms(source.scene(),source.probe(),9).get(name));
        var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));var built=G3Built.map(bundle,name);var tiles=bundle.g3tiles(built.pair());
        var model=G3Cells.model(built,tiles,true,new Warnings());var placed=new PlacedMap(new MapPlacement(name,0,64,0,built.w(),built.h(),1,MapPlacement.Kind.INTERIOR,"BAKED","minecraft:plains",false,null),model);
        var stories=G3StoryCells.plan(List.of(placed),Map.of(name,built),bundle.g3sites(),(pair,mid)->tiles.quarters(mid),q->q,new Warnings());
        var baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"));
        Path livePath=ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json");byte[] liveBytes=Files.readAllBytes(livePath);var live=JsonParser.parseString(new String(liveBytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,live,true);
        byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone(),sourceTiles=map.tiles().clone();var swaps=List.copyOf(map.swapSites());JsonArray proofs=new JsonArray(),comparisons=new JsonArray();int count=0,objectPriority=0,cellStates=0;
        for(var value:JsonParser.parseString(Files.readString(folder.resolve("source-audit.json"))).getAsJsonArray()){
            var group=value.getAsJsonObject();String key=group.get("key").getAsString();
            for(var item:group.getAsJsonArray("occurrences")){
                var o=item.getAsJsonObject();int x=o.get("x").getAsInt(),y=o.get("y").getAsInt(),mid=o.get("metatile").getAsInt();assertEquals(name,o.get("map").getAsString());
                assertEquals(CellKind.WALK,map.kind(x,y));assertEquals(0,built.coll()[built.index(x,y)]);assertEquals(3,built.elev()[built.index(x,y)]);assertEquals(mid,built.mids()[built.index(x,y)]);
                for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                    int i=index(map,x,y,dx,dz);assertEquals(0,before.blocks()[i]);assertEquals(0,after.blocks()[i]);assertEquals(0,after.shapeMask()[i]);assertEquals(0,before.decor()[i]);assertEquals(0,after.decor()[i]);assertNull(before.open()[i]);assertNull(after.open()[i]);assertEquals(before.floor()[i],after.floor()[i]);
                }
                var story=stories.get(name+":"+x+":"+y);assertNotNull(story);var site=bundle.g3sites().of(name).stream().filter(s->s.x()==x&&s.y()==y).findFirst().orElseThrow();assertEquals(mid,site.own().metatile());assertEquals(1,site.states().size());assertEquals(591,site.states().get(0).metatile());assertEquals(0,site.states().get(0).coll());assertEquals(3,site.states().get(0).elev());assertEquals(mid,G3Built.built(site).metatile());
                var itemAt=built.objects().stream().filter(obj->obj.x()==x&&obj.y()==y).toList();
                if(Set.of("17,10","2,11","25,18","16,22","6,11","13,10").contains(x+","+y)){assertEquals(1,itemAt.size());assertEquals("item",itemAt.get(0).kind());assertEquals(dev.kantobridge.world.StoryCellType.OBJECT,story.type());assertEquals(itemAt.get(0).name(),story.obj());assertNull(story.built());assertTrue(story.states().isEmpty());objectPriority++;}
                else{assertTrue(itemAt.isEmpty());assertEquals(mid,story.start());assertEquals(mid,story.built().metatile());assertEquals(StoryCellTable.Pass.GROUND,story.built().pass());assertEquals(1,story.states().size());var state=story.states().get(0);assertEquals(591,state.metatile());assertEquals(StoryCellTable.Pass.GROUND,state.pass());assertEquals(0,state.height());assertEquals(List.of(13850,13851,13852,13853),state.art());cellStates++;}
                var proof=rectangle(map,x,y,1,1,before,after);assertEquals(key,proof.getAsJsonArray("layout").get(0).getAsJsonArray().get(0).getAsString());proof.addProperty("id",key);proof.addProperty("current_table_sha256",sha(liveBytes));proof.add("source_floor_columns",sourceFloors(map,x,y,1,1,before,after,null,null));proof.add("story_site",new Gson().toJsonTree(story));proof.add("source_story_site",new Gson().toJsonTree(site));proofs.add(proof);count++;
                if(Set.of("18,36","4,26","33,6").contains(x+","+y)){
                    assertEquals(528,built.mids()[built.index(x+1,y)]);assertEquals(CellKind.WALK,map.kind(x+1,y));
                    for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=index(map,x+1,y,dx,dz);assertEquals(0,before.blocks()[i]);assertEquals(0,after.blocks()[i]);assertEquals(0,after.decor()[i]);assertNull(after.open()[i]);assertEquals(before.floor()[i],after.floor()[i]);}
                    var context=rectangle(map,x,y,2,1,before,after);context.addProperty("id",key);context.add("source_floor_columns",sourceFloors(map,x,y,2,1,before,after,null,null));context.add("story_site",new Gson().toJsonTree(story));comparisons.add(context);
                }
            }
        }
        assertEquals(10,count);assertEquals(6,objectPriority);assertEquals(4,cellStates);assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertArrayEquals(sourceTiles,map.tiles());assertEquals(swaps,map.swapSites());save("new-mauville-switches-native",proofs);
        var report=read(folder.resolve("runtime-proof.json"));assertEquals(3,comparisons.size());report.add("comparisons",comparisons);report.addProperty("current_table_sha256",sha(liveBytes));report.addProperty("source_audit_sha256",sha(Files.readAllBytes(folder.resolve("source-audit.json"))));report.addProperty("source_sites_sha256",sha(Files.readAllBytes(ROOT.resolve("local/minecraft/kantobridge/export/emerald/g3sites/all.json"))));
        Files.writeString(folder.resolve("runtime-proof.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");Files.write(folder.resolve("fitted-proof-current.json"),liveBytes);
    }
}
