package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.google.gson.*;
import dev.kantobridge.builder.plan.Headroom;
import dev.kantobridge.builder.plan.G3Built;
import dev.kantobridge.builder.plan.G3Cells;
import dev.kantobridge.builder.plan.RaisedRegions;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.builder.plan.G3StoryCells;
import dev.kantobridge.builder.plan.PlacedMap;
import dev.kantobridge.builder.plan.HeightRules;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.MapLayout;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.scene.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Original-table source rectangles, including native counters and absent fitted arch cells. */
class EmeraldInteriorSourceProofTest {
    static final Path ROOT=EmeraldSourceStackFollowupsTest.ROOT, STUDY=EmeraldSourceStackFollowupsTest.STUDY;
    static JsonObject read(Path p)throws Exception{return EmeraldSourceStackFollowupsTest.read(p);}
    static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);}
    static int index(SceneMap map,int x,int y,int dx,int dz){return EmeraldSourceStackFollowupsTest.column(map,x,y,dx,dz);}
    static ShapeKey[] sourceColumn(ShapePlanner.MapShapes plan,SceneMap map,int tx,int ty){
        int i=ty*map.artCols()+tx;ShapeKey[] keys=plan.open()[i];if(keys!=null){
            // Native outdoor columns may mix a fitted roof with ordinary lower
            // tile_full carriers. Null shape slots below blocks still draw art.
            keys=Arrays.copyOf(keys,Math.max(keys.length,plan.blocks()[i]));
            for(int y=0;y<plan.blocks()[i];y++)if(keys[y]==null){assertEquals(0,(plan.shapeMask()[i]>>y)&1);int tile=map.tile(tx,ty);keys[y]=ShapeKey.cube(0,tile,tile,tile,tile,tile,tile);}
            return keys;
        }
        int height=plan.blocks()[i];if(height==0)return null;
        // WorldPlan's COUNTER/SOLID branch writes its own full tile for each
        // unshaped carrier when ShapePlanner needs no mixed-face override.
        assertTrue(map.kindAtTile(tx,ty)==CellKind.COUNTER||map.kindAtTile(tx,ty)==CellKind.SOLID);
        assertEquals(0,plan.shapeMask()[i]);assertEquals(0,plan.decor()[i]);
        int tile=map.tile(tx,ty);keys=new ShapeKey[height];Arrays.fill(keys,ShapeKey.cube(0,tile,tile,tile,tile,tile,tile));return keys;
    }
    static JsonArray fragments(ShapePlanner.MapShapes plan,SceneMap map,int x0,int y0,int width,int depth){
        JsonArray result=new JsonArray();
        for(int z=0;z<depth*2;z++)for(int x=0;x<width*2;x++){
            int i=(y0*2+z)*map.artCols()+x0*2+x;ShapeKey[] keys=sourceColumn(plan,map,x0*2+x,y0*2+z);
            if(keys==null){assertEquals(0,plan.blocks()[i],"unrepresented native collision column "+map.id()+" "+x+","+z);continue;}
            for(int y=0;y<keys.length;y++){
                if(keys[y]==null){assertEquals(0,(plan.decor()[i]>>y)&1);continue;}
                JsonObject f=new JsonObject();f.addProperty("key",keys[y].toString());f.addProperty("palette","BAKED");f.addProperty("x",x);f.addProperty("y",y);f.addProperty("z",z);result.add(f);
            }
        }
        return result;
    }
    static JsonObject rectangle(SceneMap map,int x0,int y0,int width,int depth,ShapePlanner.MapShapes before,ShapePlanner.MapShapes after)throws Exception{
        JsonObject raw=read(ROOT.resolve("local/minecraft/kantobridge/export/emerald/g3map/"+map.id()+".json"));byte[] cells=Base64.getDecoder().decode(raw.get("cells").getAsString());
        JsonArray layout=new JsonArray(),collisions=new JsonArray();
        for(int y=0;y<depth;y++){JsonArray row=new JsonArray(),kinds=new JsonArray();for(int x=0;x<width;x++){
            int tx=(x0+x)*2,ty=(y0+y)*2;row.add(FittedShapes.key(map.atlas(),new int[]{map.tile(tx,ty),map.tile(tx+1,ty),map.tile(tx,ty+1),map.tile(tx+1,ty+1)}));
            kinds.add(Byte.toUnsignedInt(cells[((y0+y)*raw.get("w").getAsInt()+x0+x)*4+2]));
        }layout.add(row);collisions.add(kinds);}
        JsonObject proof=new JsonObject();proof.addProperty("map",map.id());proof.addProperty("x",x0);proof.addProperty("y",y0);proof.addProperty("map_cells_sha256",sha(cells));proof.addProperty("headroom",ShapeHeights.headroom(map));
        proof.add("layout",layout);proof.add("source_collisions",collisions);proof.add("original_baseline_fragments",fragments(before,map,x0,y0,width,depth));proof.add("corrected_fragments",fragments(after,map,x0,y0,width,depth));return proof;
    }
    static void save(String folder,JsonArray rows)throws Exception{
        saveAt(folder+"/runtime-proof.json",rows);
    }
    static JsonArray sourceFloors(SceneMap map,int x0,int y0,int w,int d,ShapePlanner.MapShapes before,ShapePlanner.MapShapes after,MapLayout layout,JsonArray mask){
        JsonArray result=new JsonArray();for(int z=0;z<d*2;z++)for(int x=0;x<w*2;x++){
            if(mask!=null&&!mask.get(z/2).getAsJsonArray().get(x/2).getAsBoolean())continue;int tx=x0*2+x,ty=y0*2+z,cx=tx/2,cy=ty/2,i=ty*map.artCols()+tx;CellKind kind=map.kind(cx,cy);
            if(!Set.of(CellKind.WALK,CellKind.SOLID,CellKind.COUNTER,CellKind.LEDGE).contains(kind))continue;
            if(layout!=null)assertNull(layout.ramp(cx,cy),"native floor proof also needs exact ramp geometry");JsonObject f=new JsonObject();f.addProperty("x",x);f.addProperty("z",z);f.addProperty("feet",layout==null?0:layout.level(cx,cy));f.addProperty("before_tile",before.floor()[i]>=0?before.floor()[i]:map.tile(tx,ty));f.addProperty("after_tile",after.floor()[i]>=0?after.floor()[i]:map.tile(tx,ty));result.add(f);
        }return result;
    }
    static void saveAt(String path,JsonArray rows)throws Exception{
        JsonObject proof=new JsonObject();proof.addProperty("original_baseline_sha256",sha(Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")))) ;proof.add("occurrences",rows);
        EmeraldSourceStackFollowupsTest.save(path,proof);
    }
    @Test void environmentTemplatesRetainExactOriginalSourceRectanglesInTheFullTables()throws Exception{
        Path folder=STUDY.resolve("environment-templates");assumeTrue(Files.exists(folder.resolve("additions.json")));
        var source=EmeraldSourceStackFollowupsTest.source();Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);
        JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy();
        JsonObject additions=read(folder.resolve("additions.json")).getAsJsonObject("buildings");
        for(var e:additions.entrySet())current.getAsJsonArray("buildings").add(e.getValue());
        JsonArray rows=new JsonArray();Map<String,ShapePlanner.MapShapes> befores=new HashMap<>(),afters=new HashMap<>();
        for(JsonElement value:read(folder.resolve("source-evidence.json")).getAsJsonArray("groups")){
            JsonObject group=value.getAsJsonObject();int depth=group.getAsJsonArray("layout").size(),width=group.getAsJsonArray("layout").get(0).getAsJsonArray().size();
            for(JsonElement item:group.getAsJsonArray("occurrences")){
                JsonObject occurrence=item.getAsJsonObject();String name=occurrence.get("map").getAsString();SceneMap original=source.scene().map(name);SceneMap map=rooms.containsKey(name)?original.withHeadroom(rooms.get(name)):original;
                var before=befores.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true));var after=afters.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,current,true));
                int x=occurrence.get("x").getAsInt(),y=occurrence.get("y").getAsInt();var templates=after.templates().stream().filter(t->t.x0()==x*2&&t.y0()==y*2).toList();assertEquals(1,templates.size());
                assertEquals(width*2,templates.get(0).entry().get("w").getAsInt());assertEquals(depth*2,templates.get(0).entry().get("d").getAsInt());
                JsonObject proof=rectangle(map,x,y,width,depth,before,after);assertEquals(group.get("layout"),proof.get("layout"));assertEquals(group.get("source_kinds"),proof.get("source_collisions"));proof.addProperty("id",group.get("id").getAsString());rows.add(proof);
            }
        }
        assertEquals(46,rows.size());JsonObject proof=new JsonObject();proof.addProperty("original_baseline_sha256",sha(Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"))));proof.add("occurrences",rows);
        EmeraldSourceStackFollowupsTest.save("environment-templates/baseline-runtime-proof.json",proof);
    }
    @Test void exactTelevisionCabinetsClaimOnlyTheirSolidSourceCell()throws Exception{
        Path folder=STUDY.resolve("generic-tv-templates");assumeTrue(Files.exists(folder.resolve("additions.json")));
        var source=EmeraldSourceStackFollowupsTest.source();Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);
        JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy();
        for(var e:read(folder.resolve("additions.json")).getAsJsonObject("buildings").entrySet())current.getAsJsonArray("buildings").add(e.getValue());
        JsonArray rows=new JsonArray();Map<String,ShapePlanner.MapShapes> befores=new HashMap<>(),afters=new HashMap<>();int total=0;
        for(JsonElement value:read(folder.resolve("source-evidence.json")).getAsJsonArray("groups")){
            JsonObject group=value.getAsJsonObject();for(JsonElement item:group.getAsJsonArray("occurrences")){
                JsonObject occurrence=item.getAsJsonObject();String name=occurrence.get("map").getAsString();SceneMap original=source.scene().map(name);SceneMap map=original.withHeadroom(rooms.get(name));
                byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();
                var before=befores.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true));var after=afters.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,current,true));
                int x=occurrence.get("x").getAsInt(),y=occurrence.get("y").getAsInt();assertTrue(ShapeHeights.headroom(map)>=4);assertEquals(CellKind.SOLID,map.kind(x,y));assertEquals(CellKind.WALK,map.kind(x,y+1));
                assertEquals(1,after.templates().stream().filter(t->t.x0()==x*2&&t.y0()==y*2).count());
                for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                    int top=index(map,x,y,dx,dz),projection=index(map,x,y+1,dx,dz);assertEquals(4,after.blocks()[top]);assertEquals(4,after.open()[top].length);
                    assertEquals(0,after.blocks()[projection]);assertEquals(0,after.shapeMask()[projection]);assertEquals(0,after.decor()[projection]);assertNull(after.open()[projection]);
                }
                assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());JsonObject proof=rectangle(map,x,y,1,2,before,after);assertEquals(group.get("layout"),proof.get("layout"));assertEquals(group.get("source_kinds"),proof.get("source_collisions"));proof.addProperty("id",group.get("id").getAsString());rows.add(proof);total++;
            }
        }
        assertEquals(43,total);save("generic-tv-templates",rows);
    }
    @Test void exportedCliffInterfacesRetainActualNativeAndFittedColumnHeights()throws Exception{
        var source=EmeraldSourceStackFollowupsTest.source();JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"));JsonArray rows=new JsonArray();
        String[] names={"EM_ROUTE113","EM_ROUTE114"};int[][] origins={{50,3},{31,47}};
        for(int n=0;n<names.length;n++){
            SceneMap map=source.scene().map(names[n]);int x0=origins[n][0],y0=origins[n][1];byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();
            var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true);JsonObject proof=rectangle(map,x0,y0,3,3,before,after);JsonArray cells=new JsonArray();
            for(int y=0;y<3;y++)for(int x=0;x<3;x++){
                JsonObject cell=new JsonObject();cell.addProperty("x",x0+x);cell.addProperty("y",y0+y);cell.addProperty("kind",map.kind(x0+x,y0+y).name());String key=proof.getAsJsonArray("layout").get(y).getAsJsonArray().get(x).getAsString();cell.addProperty("key",key);cell.addProperty("original_fitted_entry",baseline.getAsJsonObject("cells").has(key));JsonArray columns=new JsonArray();
                for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                    int i=index(map,x0+x,y0+y,dx,dz);JsonObject column=new JsonObject();column.addProperty("dx",dx);column.addProperty("dz",dz);column.addProperty("original_blocks",before.blocks()[i]);column.addProperty("current_blocks",after.blocks()[i]);column.addProperty("original_decor",before.decor()[i]);column.addProperty("current_decor",after.decor()[i]);column.addProperty("original_keys",Arrays.toString(sourceColumn(before,map,(x0+x)*2+dx,(y0+y)*2+dz)));column.addProperty("current_keys",Arrays.toString(sourceColumn(after,map,(x0+x)*2+dx,(y0+y)*2+dz)));columns.add(column);
                }cell.add("columns",columns);cells.add(cell);
            }
            proof.add("native_column_audit",cells);rows.add(proof);assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());
        }
        assertEquals(2,rows.size());saveAt("cliff-interface-study/runtime-proof.json",rows);
    }
    @Test void exactApplianceTemplatesKeepAllProjectedWalkRowsUnclaimed()throws Exception{
        Path folder=STUDY.resolve("generic-appliance-templates");assumeTrue(Files.exists(folder.resolve("additions.json")));var source=EmeraldSourceStackFollowupsTest.source();Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);
        JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy(),additions=read(folder.resolve("additions.json")).getAsJsonObject("buildings");for(var e:additions.entrySet())current.getAsJsonArray("buildings").add(e.getValue());
        Map<String,ShapePlanner.MapShapes> befores=new HashMap<>(),afters=new HashMap<>();JsonArray rows=new JsonArray();int total=0;
        for(JsonElement element:read(folder.resolve("source-evidence.json")).getAsJsonArray("groups")){
            JsonObject group=element.getAsJsonObject();int depth=group.getAsJsonArray("layout").size(),width=group.getAsJsonArray("layout").get(0).getAsJsonArray().size(),height=(additions.getAsJsonObject(group.get("id").getAsString()).get("height").getAsInt()+7)/8;
            for(JsonElement item:group.getAsJsonArray("occurrences")){
                JsonObject occurrence=item.getAsJsonObject();String name=occurrence.get("map").getAsString();SceneMap original=source.scene().map(name);SceneMap map=original.withHeadroom(rooms.get(name));byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();assertTrue(ShapeHeights.headroom(map)>=height);
                var before=befores.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true));var after=afters.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,current,true));int x0=occurrence.get("x").getAsInt(),y0=occurrence.get("y").getAsInt();
                assertEquals(1,after.templates().stream().filter(t->t.x0()==x0*2&&t.y0()==y0*2).count(),name+" "+x0+","+y0);
                for(int y=0;y<depth;y++)for(int x=0;x<width;x++)for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                    int i=index(map,x0+x,y0+y,dx,dz);if(map.kind(x0+x,y0+y)==CellKind.WALK){assertEquals(0,after.blocks()[i]);assertEquals(0,after.shapeMask()[i]);assertEquals(0,after.decor()[i]);assertNull(after.open()[i]);}
                    else{assertEquals(CellKind.SOLID,map.kind(x0+x,y0+y));assertEquals(height,after.blocks()[i]);assertEquals(height,after.open()[i].length);}
                }
                assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());JsonObject proof=rectangle(map,x0,y0,width,depth,before,after);assertEquals(group.get("layout"),proof.get("layout"));assertEquals(group.get("source_kinds"),proof.get("source_collisions"));proof.addProperty("id",group.get("id").getAsString());rows.add(proof);total++;
            }
        }
        assertEquals(84,total);save("generic-appliance-templates",rows);
    }
    @Test void seashorePairsFitActualWallsAndDrinkCabinetsWithoutChangingKinds()throws Exception{
        Path folder=STUDY.resolve("seashore-house");assumeTrue(Files.exists(folder.resolve("updates.json")));var source=EmeraldSourceStackFollowupsTest.source();SceneMap original=source.scene().map("EM_ROUTE109_SEASHORE_HOUSE");SceneMap map=original.withHeadroom(Headroom.rooms(source.scene(),source.probe(),9).get(original.id()));assertTrue(ShapeHeights.headroom(map)>=4);
        JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy();for(var e:read(folder.resolve("updates.json")).getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current);int count=0;
        for(JsonElement item:JsonParser.parseString(Files.readString(folder.resolve("metadata-evidence.json"))).getAsJsonArray())for(JsonElement place:item.getAsJsonObject().getAsJsonArray("occurrences")){
            JsonObject occurrence=place.getAsJsonObject();int x=occurrence.get("x").getAsInt(),y=occurrence.get("y").getAsInt();assertEquals(CellKind.SOLID,map.kind(x,y));assertEquals(CellKind.SOLID,map.kind(x,y+1));
            for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int top=index(map,x,y,dx,dz),bottom=index(map,x,y+1,dx,dz);assertEquals(0,after.blocks()[top]);assertNull(after.open()[top]);assertEquals(4,after.blocks()[bottom]);assertEquals(4,after.open()[bottom].length);for(ShapeKey key:after.open()[bottom])assertEquals(ShapeKey.Kind.CELL,key.kind());}count++;
        }
        assertEquals(10,count);assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());JsonArray rows=new JsonArray();
        for(JsonElement item:JsonParser.parseString(Files.readString(folder.resolve("assemblies.json"))).getAsJsonArray()){
            JsonObject spec=item.getAsJsonObject();if(!spec.has("baseline_placements"))continue;JsonArray rect=spec.getAsJsonArray("rect");JsonObject proof=rectangle(map,rect.get(0).getAsInt(),rect.get(1).getAsInt(),rect.get(2).getAsInt(),rect.get(3).getAsInt(),before,after);proof.addProperty("id",spec.get("id").getAsString());rows.add(proof);
        }
        assertEquals(4,rows.size());save("seashore-house",rows);
    }
    @Test void bikeBlueWorkbenchRetainsItsExactOriginalFloorAndSolidRows()throws Exception{
        Path folder=STUDY.resolve("bike-shop");assumeTrue(Files.exists(folder.resolve("updates.json")));var source=EmeraldSourceStackFollowupsTest.source();SceneMap original=source.scene().map("EM_NEW_MAUVILLE_INSIDE");SceneMap map=original.withHeadroom(Headroom.rooms(source.scene(),source.probe(),9).get(original.id()));
        JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy();for(var e:read(folder.resolve("updates.json")).getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current);
        for(int x=4;x<6;x++){assertEquals(CellKind.WALK,map.kind(x,18));assertEquals(CellKind.SOLID,map.kind(x,19));for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int upper=index(map,x,18,dx,dz),lower=index(map,x,19,dx,dz);assertEquals(0,before.blocks()[upper]);assertEquals(0,after.blocks()[upper]);assertEquals(2,after.blocks()[lower]);for(ShapeKey key:after.open()[lower])assertEquals(ShapeKey.Kind.CELL,key.kind());}}
        JsonArray rows=new JsonArray();JsonObject proof=rectangle(map,4,18,2,2,before,after);proof.addProperty("id","emerald_bike_shop_blue_workbench");rows.add(proof);saveAt("bike-shop/blue-workbench-runtime-proof.json",rows);
    }
    @Test void ashAndCliffTreeSourceRectanglesReportNativeVersusLayeredDispatch()throws Exception{
        Path folder=STUDY.resolve("towns-fallarbor-trees"),patch=folder.resolve("updates.json");assumeTrue(Files.exists(patch));var source=EmeraldSourceStackFollowupsTest.source();JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),live=read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json")),proposed=live.deepCopy();for(var e:read(patch).getAsJsonObject("cells").entrySet())proposed.getAsJsonObject("cells").add(e.getKey(),e.getValue());
        String[] names={"EM_ROUTE113","EM_FALLARBOR_TOWN"};int[][] origins={{76,3},{4,3}};JsonArray rows=new JsonArray();
        for(int n=0;n<names.length;n++){
            SceneMap map=source.scene().map(names[n]);int x0=origins[n][0],y0=origins[n][1];byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var now=EmeraldSourceStackFollowupsTest.plan(map,source,live,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,proposed,true);JsonObject proof=rectangle(map,x0,y0,2,2,before,after);proof.add("current_live_fragments",fragments(now,map,x0,y0,2,2));JsonArray cells=new JsonArray();
            for(int y=0;y<2;y++)for(int x=0;x<2;x++){
                JsonObject cell=new JsonObject();cell.addProperty("x",x0+x);cell.addProperty("y",y0+y);cell.addProperty("kind",map.kind(x0+x,y0+y).name());String key=proof.getAsJsonArray("layout").get(y).getAsJsonArray().get(x).getAsString();cell.addProperty("key",key);cell.addProperty("proposed_entry",read(patch).getAsJsonObject("cells").has(key));JsonArray columns=new JsonArray();
                for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                    int i=index(map,x0+x,y0+y,dx,dz);JsonObject column=new JsonObject();column.addProperty("dx",dx);column.addProperty("dz",dz);column.addProperty("original_blocks",before.blocks()[i]);column.addProperty("live_blocks",now.blocks()[i]);column.addProperty("proposed_blocks",after.blocks()[i]);column.addProperty("proposed_decor",after.decor()[i]);column.addProperty("original_keys",Arrays.toString(sourceColumn(before,map,(x0+x)*2+dx,(y0+y)*2+dz)));column.addProperty("live_keys",Arrays.toString(sourceColumn(now,map,(x0+x)*2+dx,(y0+y)*2+dz)));column.addProperty("proposed_keys",Arrays.toString(sourceColumn(after,map,(x0+x)*2+dx,(y0+y)*2+dz)));columns.add(column);
                }cell.add("columns",columns);cells.add(cell);
            }
            proof.add("routing_audit",cells);rows.add(proof);assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());
        }
        JsonObject report=new JsonObject();report.addProperty("original_baseline_sha256",sha(Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"))));report.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(patch)));report.addProperty("current_live_table_sha256",sha(Files.readAllBytes(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"))));report.add("occurrences",rows);EmeraldSourceStackFollowupsTest.save("towns-fallarbor-trees/runtime-routing-proposal.json",report);
    }
    @Test void mauvilleGameCornerPreservesProjectedHoodsSeatsAndCounterCollisions()throws Exception{
        Path folder=STUDY.resolve("mauville-game-corner");assumeTrue(Files.exists(folder.resolve("updates.json")));var source=EmeraldSourceStackFollowupsTest.source();SceneMap original=source.scene().map("EM_MAUVILLE_CITY_GAME_CORNER");SceneMap map=original.withHeadroom(Headroom.rooms(source.scene(),source.probe(),9).get(original.id()));assertTrue(ShapeHeights.headroom(map)>=4);
        JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy();for(var e:read(folder.resolve("updates.json")).getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current);
        for(int x=0;x<2;x++)for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int top=index(map,x,1,dx,dz),base=index(map,x,2,dx,dz);assertEquals(CellKind.SOLID,map.kind(x,1));assertEquals(CellKind.SOLID,map.kind(x,2));assertEquals(0,after.blocks()[top]);assertNull(after.open()[top]);assertEquals(4,after.blocks()[base]);assertEquals(4,after.open()[base].length);for(ShapeKey key:after.open()[base])assertEquals(ShapeKey.Kind.CELL,key.kind());}
        for(int x:new int[]{2,3,7,8})for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=index(map,x,5,dx,dz);assertEquals(CellKind.WALK,map.kind(x,5));assertEquals(0,before.blocks()[i]);assertEquals(0,after.blocks()[i]);assertEquals(0,after.decor()[i]);assertNull(before.open()[i]);assertNull(after.open()[i]);}
        int counters=0,seats=0;
        for(JsonElement item:JsonParser.parseString(Files.readString(STUDY.resolve("small-facilities-study/mauville_game_corner/source-evidence.json"))).getAsJsonArray()){
            JsonObject cell=item.getAsJsonObject();int ordinal=cell.get("position").getAsInt();boolean counter=Set.of(27,28,30,31).contains(ordinal),seat=Set.of(4,5,8,9).contains(ordinal);if(!counter&&!seat)continue;
            for(JsonElement value:cell.getAsJsonArray("occurrences")){
                JsonObject o=value.getAsJsonObject();int x=o.get("x").getAsInt(),y=o.get("y").getAsInt();assertEquals(counter?CellKind.COUNTER:CellKind.WALK,map.kind(x,y));
                for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=index(map,x,y,dx,dz);assertEquals(counter?1:0,after.blocks()[i]);assertNotNull(after.open()[i]);for(ShapeKey key:after.open()[i])assertEquals(ShapeKey.Kind.CELL,key.kind());
                    if(counter){assertEquals(1,before.blocks()[i]);assertEquals(2,after.decor()[i]);assertEquals(ShapeKey.Kind.CUBE,sourceColumn(before,map,x*2+dx,y*2+dz)[0].kind());}else assertTrue((after.decor()[i]&ShapePlanner.SEAT)!=0);
                }if(counter)counters++;else seats++;
            }
        }
        assertEquals(7,counters);assertEquals(20,seats);assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());JsonArray rows=new JsonArray();
        for(JsonElement item:JsonParser.parseString(Files.readString(folder.resolve("assemblies.json"))).getAsJsonArray()){
            JsonObject spec=item.getAsJsonObject();String id=spec.get("id").getAsString();if(!(id.endsWith("vending_pair")||id.endsWith("counter")||id.endsWith("slots")))continue;JsonArray r=spec.getAsJsonArray("rect");JsonObject proof=rectangle(map,r.get(0).getAsInt(),r.get(1).getAsInt(),r.get(2).getAsInt(),r.get(3).getAsInt(),before,after);assertEquals(spec.get("source_layout"),proof.get("layout"));JsonArray mask=new JsonArray();
            for(JsonElement line:spec.getAsJsonArray("model_layout")){JsonArray m=new JsonArray();for(JsonElement key:line.getAsJsonArray())m.add(!key.isJsonNull());mask.add(m);}proof.add("source_mask",mask);
            for(String field:List.of("original_baseline_fragments","corrected_fragments")){JsonArray filtered=new JsonArray();for(JsonElement f:proof.getAsJsonArray(field)){JsonObject fragment=f.getAsJsonObject();if(mask.get(fragment.get("z").getAsInt()/2).getAsJsonArray().get(fragment.get("x").getAsInt()/2).getAsBoolean())filtered.add(f);}proof.add(field,filtered);}proof.addProperty("id",id);rows.add(proof);
        }
        assertEquals(4,rows.size());save("mauville-game-corner",rows);
    }
    @Test void genericArchitectureReportsBuiltStorySourceAndMeasuredTrunkHeadroom()throws Exception{
        Path folder=STUDY.resolve("generic-architecture"),patterns=folder.resolve("pattern-candidates.json");assumeTrue(Files.exists(patterns));var source=EmeraldSourceStackFollowupsTest.source();Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"));JsonArray rows=new JsonArray();int total=0,trunks=0,changed=0;
        for(JsonElement item:JsonParser.parseString(Files.readString(patterns)).getAsJsonArray()){
            JsonObject group=item.getAsJsonObject();int width=group.getAsJsonArray("layout").get(0).getAsJsonArray().size(),depth=group.getAsJsonArray("layout").size();String family=group.get("family").getAsString();
            for(JsonElement value:group.getAsJsonArray("occurrences")){
                JsonObject occurrence=value.getAsJsonObject();String name=occurrence.get("map").getAsString();int x0=occurrence.get("x").getAsInt(),y0=occurrence.get("y").getAsInt();SceneMap original=source.scene().map(name);SceneMap map=original.withHeadroom(rooms.get(name));var built=G3Built.map(bundle,name);var tiles=bundle.g3tiles(built.pair());var model=G3Cells.model(built,tiles,true,new Warnings());var placed=new PlacedMap(new MapPlacement(map.id(),0,64,0,built.w(),built.h(),1,MapPlacement.Kind.INTERIOR,"BAKED","minecraft:plains",false,null),model);
                var stories=G3StoryCells.plan(List.of(placed),Map.of(map.id(),built),bundle.g3sites(),(pair,mid)->tiles.quarters(mid),q->q,new Warnings());var plan=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);JsonObject proof=rectangle(map,x0,y0,width,depth,plan,plan);proof.addProperty("family",family);proof.add("raw_source_layout",group.get("layout"));assertEquals(group.get("source_kinds"),proof.get("source_collisions"));JsonArray collisions=new JsonArray(),kinds=new JsonArray(),sites=new JsonArray();
                for(int y=0;y<depth;y++){JsonArray line=new JsonArray(),kindLine=new JsonArray();for(int x=0;x<width;x++){line.add(built.coll()[built.index(x0+x,y0+y)]);kindLine.add(map.kind(x0+x,y0+y).name());}collisions.add(line);kinds.add(kindLine);}proof.add("built_source_collisions",collisions);proof.add("built_scene_kinds",kinds);
                for(var site:bundle.g3sites().of(name))if(site.x()>=x0&&site.x()<x0+width&&site.y()>=y0&&site.y()<y0+depth){
                    var story=stories.get(name+":"+site.x()+":"+site.y());assertNotNull(story);JsonObject state=new JsonObject();state.addProperty("x",site.x());state.addProperty("y",site.y());state.addProperty("own_metatile",site.own().metatile());state.addProperty("built_metatile",story.built().metatile());state.addProperty("built_pass",story.built().pass().name());state.add("states",new Gson().toJsonTree(story.states()));sites.add(state);
                    if(name.equals("EM_ROUTE110_TRICK_HOUSE_ENTRANCE")){assertEquals(539,site.own().metatile());assertEquals(537,story.built().metatile());assertEquals(StoryCellTable.Pass.GROUND,story.built().pass());assertEquals(CellKind.WALK,map.kind(site.x(),site.y()));state.addProperty("built_warp_tag",map.hasTag(site.x(),site.y(),CellTag.WARP));state.addProperty("built_collision",built.coll()[built.index(site.x(),site.y())]);assertTrue(story.states().stream().anyMatch(s->s.metatile()==539&&s.pass()==StoryCellTable.Pass.SOLID&&s.height()==HeightRules.INTERIOR_WALL),"closed539 must retain its native interior wall state");changed++;}
                }
                proof.add("native_story_sites",sites);if(family.equals("trunk")){proof.addProperty("supports_64_voxel_trunk",ShapeHeights.headroom(map)>=8);trunks++;}rows.add(proof);total++;
            }
        }
        assertEquals(17,total);assertEquals(6,trunks);assertEquals(1,changed);JsonObject report=new JsonObject();report.addProperty("pattern_candidates_sha256",sha(Files.readAllBytes(patterns)));report.addProperty("sites_sha256",sha(Files.readAllBytes(ROOT.resolve("local/minecraft/kantobridge/export/emerald/g3sites/all.json"))));report.add("occurrences",rows);EmeraldSourceStackFollowupsTest.save("generic-architecture/built-source-diagnostic.json",report);
    }
    @Test void brokenShipDoorTemplatesPreserveWarpAndOnlyDecorateTheSouthernCell()throws Exception{
        Path folder=STUDY.resolve("ship-qa-corrections");assumeTrue(Files.exists(folder.resolve("additions.json")));var source=EmeraldSourceStackFollowupsTest.source();Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy();for(var e:read(folder.resolve("additions.json")).getAsJsonObject("buildings").entrySet())current.getAsJsonArray("buildings").add(e.getValue());JsonArray rows=new JsonArray();int count=0;
        for(JsonElement item:read(folder.resolve("source-evidence.json")).getAsJsonArray("groups")){
            JsonObject group=item.getAsJsonObject();for(JsonElement value:group.getAsJsonArray("occurrences")){
                JsonObject occurrence=value.getAsJsonObject();String name=occurrence.get("map").getAsString();int x=occurrence.get("x").getAsInt(),y=occurrence.get("y").getAsInt();SceneMap original=source.scene().map(name);SceneMap map=original.withHeadroom(rooms.get(name));byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var swaps=List.copyOf(map.swapSites());assertTrue(ShapeHeights.headroom(map)>=4);assertEquals(CellKind.WALK,map.kind(x,y));assertEquals(CellKind.WALK,map.kind(x,y+1));assertTrue(map.hasTag(x,y+1,CellTag.WARP));
                var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true);assertEquals(1,after.templates().stream().filter(t->t.x0()==x*2&&t.y0()==y*2).count());
                for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int top=index(map,x,y,dx,dz),base=index(map,x,y+1,dx,dz);assertEquals(0,after.blocks()[top]);assertEquals(0,after.decor()[top]);assertNull(after.open()[top]);assertEquals(0,after.blocks()[base]);assertEquals(0,after.shapeMask()[base]);assertEquals(15,after.decor()[base]);assertEquals(4,after.open()[base].length);}
                assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(swaps,map.swapSites());JsonObject proof=rectangle(map,x,y,1,2,before,after);assertEquals(group.get("layout"),proof.get("layout"));assertEquals(group.get("source_kinds"),proof.get("source_collisions"));proof.addProperty("id",group.get("id").getAsString());rows.add(proof);count++;
            }
        }
        assertEquals(5,count);saveAt("ship-qa-corrections/baseline-runtime-proof.json",rows);
    }
    @Test void genericCompleteArchitectureRetainsNativeKindsAndCounterTalkThrough()throws Exception{
        Path folder=STUDY.resolve("generic-architecture-templates");assumeTrue(Files.exists(folder.resolve("additions.json")));var source=EmeraldSourceStackFollowupsTest.source();Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy(),additions=read(folder.resolve("additions.json")).getAsJsonObject("buildings");for(var e:additions.entrySet())current.getAsJsonArray("buildings").add(e.getValue());JsonArray rows=new JsonArray();List<Executable> checks=new ArrayList<>();int total=0,counterCells=0;
        for(JsonElement item:read(folder.resolve("source-evidence.json")).getAsJsonArray("groups")){
            JsonObject group=item.getAsJsonObject(),template=additions.getAsJsonObject(group.get("id").getAsString());int width=group.getAsJsonArray("layout").get(0).getAsJsonArray().size(),depth=group.getAsJsonArray("layout").size(),height=Math.max(2,(template.get("height").getAsInt()+7)/8);Set<String> terrain=new HashSet<>();for(JsonElement c:template.getAsJsonArray("terrain"))terrain.add(c.getAsJsonArray().get(0).getAsInt()+","+c.getAsJsonArray().get(1).getAsInt());
            for(JsonElement value:group.getAsJsonArray("occurrences")){
                JsonObject occurrence=value.getAsJsonObject();String name=occurrence.get("map").getAsString();int x0=occurrence.get("x").getAsInt(),y0=occurrence.get("y").getAsInt();SceneMap original=source.scene().map(name);SceneMap map=original.withHeadroom(rooms.get(name));byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var swaps=List.copyOf(map.swapSites());assertTrue(ShapeHeights.headroom(map)>=height);var built=G3Built.map(bundle,name);
                var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true);assertEquals(1,after.templates().stream().filter(t->t.x0()==x0*2&&t.y0()==y0*2).count(),name+" "+x0+","+y0);
                for(int y=0;y<depth;y++)for(int x=0;x<width;x++){
                    boolean excluded=terrain.contains(x+","+y);CellKind kind=map.kind(x0+x,y0+y);if(!excluded&&kind==CellKind.COUNTER)counterCells++;
                    for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                        int i=index(map,x0+x,y0+y,dx,dz);if(excluded){assertEquals(before.blocks()[i],after.blocks()[i]);assertEquals(before.decor()[i],after.decor()[i]);continue;}
                        if(kind==CellKind.COUNTER){assertEquals(1,before.blocks()[i]);checks.add(()->{assertEquals(1,after.blocks()[i],"explicit low interior counter must preserve one-block collision "+name);assertEquals(1,after.open()[i].length);assertEquals(0,after.decor()[i]);});}
                        else if(kind==CellKind.WALK){assertEquals(0,after.blocks()[i]);assertEquals(0,after.shapeMask()[i]);assertEquals((1<<height)-1,after.decor()[i]);assertEquals(height,after.open()[i].length);}
                        else{assertEquals(CellKind.SOLID,kind);assertEquals(height,after.blocks()[i]);assertEquals(height,after.open()[i].length);}
                    }
                }
                assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(swaps,map.swapSites());JsonObject proof=rectangle(map,x0,y0,width,depth,before,after);assertEquals(group.get("layout"),proof.get("layout"));proof.addProperty("id",group.get("id").getAsString());
                if(occurrence.has("built_source")){
                    proof.add("built_source",occurrence.get("built_source"));proof.add("raw_source_layout",occurrence.get("raw_source_layout"));proof.add("raw_source_collisions",proof.get("source_collisions"));JsonArray normalized=new JsonArray();JsonObject raw=read(ROOT.resolve("local/minecraft/kantobridge/export/emerald/g3map/"+name+".json"));byte[] cooked=Base64.getDecoder().decode(raw.get("cells").getAsString());
                    for(int y=0;y<depth;y++){JsonArray line=new JsonArray();for(int x=0;x<width;x++){int ci=built.index(x0+x,y0+y),offset=ci*4;line.add(built.coll()[ci]);cooked[offset]=(byte)built.mids()[ci];cooked[offset+1]=(byte)(built.mids()[ci]>>8);cooked[offset+2]=(byte)built.coll()[ci];cooked[offset+3]=(byte)built.elev()[ci];}normalized.add(line);}proof.add("source_collisions",normalized);proof.addProperty("built_cells_sha256",sha(cooked));
                    var tiles=bundle.g3tiles(built.pair());var model=G3Cells.model(built,tiles,true,new Warnings());var placed=new PlacedMap(new MapPlacement(name,0,64,0,built.w(),built.h(),1,MapPlacement.Kind.INTERIOR,"BAKED","minecraft:plains",false,null),model);var stories=G3StoryCells.plan(List.of(placed),Map.of(name,built),bundle.g3sites(),(pair,mid)->tiles.quarters(mid),q->q,new Warnings());var story=stories.get(name+":5:1");assertNotNull(story);assertEquals(537,story.built().metatile());var closed=story.states().stream().filter(s->s.metatile()==539).findFirst().orElseThrow();assertEquals(StoryCellTable.Pass.SOLID,closed.pass());assertEquals(2,closed.height());assertEquals(Arrays.stream(tiles.quarters(539)).boxed().toList(),closed.art());proof.add("native_closed_state",new Gson().toJsonTree(closed));proof.addProperty("actual_warp_tag",map.hasTag(5,1,CellTag.WARP));assertFalse(map.hasTag(5,1,CellTag.WARP));
                }
                assertEquals(group.get("source_kinds"),proof.get("source_collisions"));rows.add(proof);total++;
            }
        }
        assertEquals(24,total);assertEquals(4,counterCells);saveAt("generic-architecture-templates/runtime-proof-diagnostic.json",rows);assertAll(checks);save("generic-architecture-templates",rows);
    }
    @Test void route104ExteriorClaimsPreserveNativeWalkAndDoorSource()throws Exception{
        Path folder=STUDY.resolve("buildings-route104");assumeTrue(Files.exists(folder.resolve("updates.json")));var source=EmeraldSourceStackFollowupsTest.source();SceneMap map=source.scene().map("EM_ROUTE104");JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy(),additions=read(folder.resolve("updates.json")).getAsJsonObject("buildings");for(var e:additions.entrySet())current.getAsJsonArray("buildings").add(e.getValue());byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var swaps=List.copyOf(map.swapSites());var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true);JsonArray rows=new JsonArray();int count=0,walks=0;
        for(JsonElement item:JsonParser.parseString(Files.readString(folder.resolve("added-ledger.json"))).getAsJsonArray()){
            JsonObject row=item.getAsJsonObject(),occurrence=row.getAsJsonArray("occurrences").get(0).getAsJsonObject();String id=row.get("key").getAsString();int x0=occurrence.get("x").getAsInt(),y0=occurrence.get("y").getAsInt(),width=occurrence.get("width").getAsInt(),depth=occurrence.get("depth").getAsInt();assertEquals(1,after.templates().stream().filter(t->t.x0()==x0*2&&t.y0()==y0*2).count());
            for(int y=0;y<depth;y++)for(int x=0;x<width;x++)if(map.kind(x0+x,y0+y)==CellKind.WALK){walks++;for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=index(map,x0+x,y0+y,dx,dz);assertEquals(0,after.blocks()[i]);assertEquals(0,after.shapeMask()[i]);assertTrue(after.decor()[i]>0);assertNotNull(after.open()[i]);}}
            JsonObject proof=rectangle(map,x0,y0,width,depth,before,after);proof.addProperty("id",id);rows.add(proof);count++;
        }
        assertEquals(2,count);assertTrue(walks>=8);assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(swaps,map.swapSites());save("buildings-route104",rows);
    }
    @Test void mauvilleGateBuildingsPreserveRaisedSourceWaterWalkAndDoorClaims()throws Exception{mauvilleBuildings("buildings-mauville-gates",3,5,6,5);}
    @Test void mauvilleHouseBuildingsPreserveNativeWaterWalkAndDoorClaims()throws Exception{mauvilleBuildings("buildings-mauville-houses",2,4,2,1);}
    @Test void mirageRawTowerTemplateDoesNotMatchFiveNormalizedGroundCells()throws Exception{
        Path folder=STUDY.resolve("building-mirage-tower");assumeTrue(Files.exists(folder.resolve("updates.json")));var source=EmeraldSourceStackFollowupsTest.source();var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));SceneMap map=source.scene().map("EM_ROUTE111");var built=G3Built.map(bundle,map.id());JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy(),patch=read(folder.resolve("updates.json"));for(var e:patch.getAsJsonObject("buildings").entrySet())current.getAsJsonArray("buildings").add(e.getValue());for(var e:patch.getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true);assertEquals(0,after.templates().stream().filter(t->t.x0()==36&&t.y0()==106).count());
        for(int y=53;y<=58;y++)for(int x=18;x<=20;x++){assertEquals(CellKind.WALK,map.kind(x,y));if(y==57)assertEquals(601,built.mids()[built.index(x,y)]);if(y==58&&x!=19)assertEquals(289,built.mids()[built.index(x,y)]);for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=index(map,x,y,dx,dz);assertEquals(0,after.blocks()[i]);assertEquals(before.decor()[i],after.decor()[i]);assertEquals(before.floor()[i],after.floor()[i]);assertArrayEquals(before.open()[i],after.open()[i]);}}
        assertTrue(map.hasTag(19,58,CellTag.WARP));assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());JsonObject proof=rectangle(map,18,53,3,6,before,after);proof.addProperty("candidate_matching_templates",0);proof.addProperty("normalized_ground_cells",5);proof.addProperty("raw_template_not_runtime_ready",true);JsonArray rows=new JsonArray();rows.add(proof);saveAt("building-mirage-tower/runtime-normalization-proof.json",rows);
    }
    void mauvilleBuildings(String family,int expected,int minWalks,int expectedWater,int expectedDoors)throws Exception{
        Path folder=STUDY.resolve(family);assumeTrue(Files.exists(folder.resolve("updates.json")));var source=EmeraldSourceStackFollowupsTest.source();var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));
        JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy(),patch=read(folder.resolve("updates.json")),additions=patch.getAsJsonObject("buildings");for(var e:additions.entrySet())current.getAsJsonArray("buildings").add(e.getValue());for(var e:patch.getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());JsonArray rows=new JsonArray();int count=0,walks=0,waters=0,doors=0;
        for(JsonElement item:JsonParser.parseString(Files.readString(folder.resolve("added-ledger.json"))).getAsJsonArray()){
            JsonObject row=item.getAsJsonObject(),o=row.getAsJsonArray("occurrences").get(0).getAsJsonObject(),spec=additions.getAsJsonObject(row.get("key").getAsString());String name=o.get("map").getAsString();SceneMap map=source.scene().map(name);var built=G3Built.map(bundle,name);var model=G3Cells.model(built,bundle.g3tiles(built.pair()),true,new Warnings());var levels=RaisedRegions.gen3(model,built,true);var layout=levels.layout();byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var swaps=List.copyOf(map.swapSites());var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true,layout);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true,layout);
            int x0=o.get("x").getAsInt(),y0=o.get("y").getAsInt(),w=o.get("width").getAsInt(),d=o.get("depth").getAsInt();long matching=after.templates().stream().filter(t->t.x0()==x0*2&&t.y0()==y0*2).count();if(matching!=1){JsonObject diag=rectangle(map,x0,y0,w,d,before,after);diag.addProperty("id",row.get("key").getAsString());diag.add("raw_evidence",o);diag.addProperty("matching_templates",matching);diag.add("story_sites",new Gson().toJsonTree(bundle.g3sites().of(name)));JsonArray diagnostics=new JsonArray();diagnostics.add(diag);saveAt(family+"/matching-diagnostic.json",diagnostics);}assertEquals(1,matching);JsonArray claims=new JsonArray();
            for(JsonElement value:o.getAsJsonArray("cells")){
                JsonObject cell=value.getAsJsonObject();int x=cell.get("x").getAsInt(),y=cell.get("y").getAsInt(),raw=cell.get("collision").getAsInt();assertEquals(raw,built.coll()[built.index(x,y)]);boolean terrain=cell.get("terrain_excluded").getAsBoolean();CellKind kind=map.kind(x,y);if(kind==CellKind.WALK)walks++;if(kind==CellKind.WATER)waters++;if(raw==113){doors++;assertTrue(map.hasTag(x,y,CellTag.WARP));}
                for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                    int i=index(map,x,y,dx,dz);if(terrain){assertEquals(before.blocks()[i],after.blocks()[i]);assertEquals(before.decor()[i],after.decor()[i]);assertEquals(before.floor()[i],after.floor()[i]);assertArrayEquals(before.open()[i],after.open()[i]);}
                    else{assertNotNull(after.open()[i]);assertEquals(ShapeKey.Kind.TMPL,after.open()[i][0].kind());if(kind==CellKind.WALK){assertEquals(0,after.blocks()[i]);assertEquals(0,after.shapeMask()[i]);assertTrue(after.decor()[i]>0);}else assertEquals(CellKind.SOLID,kind);}
                }
                JsonObject claim=cell.deepCopy();claim.addProperty("built_kind",kind.name());claim.addProperty("warp_tag",map.hasTag(x,y,CellTag.WARP));claims.add(claim);
            }
            JsonObject proof=rectangle(map,x0,y0,w,d,before,after);for(JsonElement v:o.getAsJsonArray("cells")){JsonObject c=v.getAsJsonObject();assertEquals(c.get("key"),proof.getAsJsonArray("layout").get(c.get("y").getAsInt()-y0).getAsJsonArray().get(c.get("x").getAsInt()-x0));}proof.addProperty("id",row.get("key").getAsString());proof.addProperty("raised_outcome",levels.outcome());proof.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(folder.resolve("updates.json"))));proof.add("source_claims",claims);rows.add(proof);count++;assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(swaps,map.swapSites());
        }
        assertEquals(expected,count);assertTrue(walks>=minWalks);assertEquals(expectedWater,waters);assertEquals(expectedDoors,doors);save(family,rows);
    }
    @Test void trainerHillExactTemplateKeepsTerrainAndSourceWarpCarriers()throws Exception{mauvilleBuildings("building-trainer-hill",1,3,0,1);}
    @Test void trainerHillRecordsEveryExcludedNativeInterfaceDifference()throws Exception{
        Path folder=STUDY.resolve("building-trainer-hill");var source=EmeraldSourceStackFollowupsTest.source();var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));SceneMap map=source.scene().map("EM_ROUTE111");var built=G3Built.map(bundle,map.id());var layout=RaisedRegions.gen3(G3Cells.model(built,bundle.g3tiles(built.pair()),true,new Warnings()),built,true).layout();JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy(),patch=read(folder.resolve("updates.json"));for(var entry:patch.getAsJsonObject("buildings").entrySet())current.getAsJsonArray("buildings").add(entry.getValue());for(var entry:patch.getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(entry.getKey(),entry.getValue());var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true,layout);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true,layout);assertEquals(1,after.templates().stream().filter(t->t.x0()==58&&t.y0()==210).count());JsonArray differences=new JsonArray();List<Executable> strictChecks=new ArrayList<>();JsonObject spec=patch.getAsJsonObject("buildings").getAsJsonObject("emerald_object_route111_trainer_hill");
        for(JsonElement cell:spec.getAsJsonArray("terrain")){JsonArray p=cell.getAsJsonArray();int x=29+p.get(0).getAsInt(),y=105+p.get(1).getAsInt();for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=index(map,x,y,dx,dz);strictChecks.add(()->{assertEquals(before.blocks()[i],after.blocks()[i]);assertEquals(before.decor()[i],after.decor()[i]);assertEquals(before.floor()[i],after.floor()[i]);});ShapeKey[] old=sourceColumn(before,map,x*2+dx,y*2+dz),now=sourceColumn(after,map,x*2+dx,y*2+dz);if(Arrays.equals(old,now))continue;JsonObject d=new JsonObject();d.addProperty("x",x);d.addProperty("y",y);d.addProperty("dx",dx);d.addProperty("dz",dz);d.addProperty("before_blocks",before.blocks()[i]);d.addProperty("after_blocks",after.blocks()[i]);d.addProperty("before_decor",before.decor()[i]);d.addProperty("after_decor",after.decor()[i]);d.addProperty("before_floor",before.floor()[i]);d.addProperty("after_floor",after.floor()[i]);d.add("before",new Gson().toJsonTree(Arrays.stream(old).map(ShapeKey::toString).toList()));d.add("after",new Gson().toJsonTree(Arrays.stream(now).map(ShapeKey::toString).toList()));differences.add(d);}}
        JsonArray rows=new JsonArray();JsonObject own=rectangle(map,29,105,5,9,before,after);own.addProperty("id","emerald_object_route111_trainer_hill");own.add("excluded_native_interface_differences",differences);rows.add(own);JsonObject context=rectangle(map,28,104,7,11,before,after);context.addProperty("id","emerald_trainer_hill_native_context");JsonArray templates=new JsonArray();for(var placed:after.templates())if(placed.x0()==58&&placed.y0()==210){JsonObject t=new JsonObject();t.addProperty("sig",placed.sig());t.addProperty("x0",placed.x0());t.addProperty("y0",placed.y0());t.add("entry",placed.entry());templates.add(t);}context.add("current_templates",templates);rows.add(context);saveAt("building-trainer-hill/runtime-interface-preserved.json",rows);assertEquals(0,differences.size(),"Every excluded native shape, including roof hips, must remain exact");assertAll(strictChecks);
    }
    @Test void trainerHillExteriorRecordsExistingNativeCliffAndBuildingColumns()throws Exception{
        var source=EmeraldSourceStackFollowupsTest.source();var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));String name="EM_ROUTE111";SceneMap map=source.scene().map(name);var built=G3Built.map(bundle,name);var model=G3Cells.model(built,bundle.g3tiles(built.pair()),true,new Warnings());var levels=RaisedRegions.gen3(model,built,true);var baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"));var current=read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"));byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true,levels.layout());var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true,levels.layout());JsonObject proof=rectangle(map,28,104,7,11,before,after);proof.addProperty("raised_outcome",levels.outcome());JsonArray cells=new JsonArray();
        for(int y=104;y<115;y++)for(int x=28;x<35;x++){JsonObject cell=new JsonObject();cell.addProperty("x",x);cell.addProperty("y",y);cell.addProperty("kind",map.kind(x,y).name());cell.addProperty("built_collision",built.coll()[built.index(x,y)]);cell.addProperty("elevation",built.elev()[built.index(x,y)]);cell.addProperty("warp_tag",map.hasTag(x,y,CellTag.WARP));JsonArray carriers=new JsonArray();for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=index(map,x,y,dx,dz);JsonObject c=new JsonObject();c.addProperty("dx",dx);c.addProperty("dz",dz);c.addProperty("before_blocks",before.blocks()[i]);c.addProperty("after_blocks",after.blocks()[i]);c.addProperty("before_decor",before.decor()[i]);c.addProperty("after_decor",after.decor()[i]);c.addProperty("before_keys",Arrays.toString(sourceColumn(before,map,x*2+dx,y*2+dz)));c.addProperty("after_keys",Arrays.toString(sourceColumn(after,map,x*2+dx,y*2+dz)));carriers.add(c);}cell.add("columns",carriers);cells.add(cell);}proof.add("source_column_audit",cells);assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());JsonArray rows=new JsonArray();rows.add(proof);saveAt("towns-mauville/trainer-hill-runtime-proof.json",rows);
    }
    @Test void prettyPetalCompleteFixturesKeepSourceKindsAndExportAllOriginalAssemblies()throws Exception{
        Path folder=STUDY.resolve("pretty-petal");assumeTrue(Files.exists(folder.resolve("updates.json")));var source=EmeraldSourceStackFollowupsTest.source();Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy();for(var e:read(folder.resolve("updates.json")).getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());JsonArray proofs=new JsonArray();int counters=0,pairs=0,walks=0;
        for(String name:List.of("EM_ROUTE104_PRETTY_PETAL_FLOWER_SHOP","EM_ROUTE104_PROTOTYPE_PRETTY_PETAL_FLOWER_SHOP")){
            SceneMap map=source.scene().map(name).withHeadroom(rooms.get(name));byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var swaps=List.copyOf(map.swapSites());assertTrue(ShapeHeights.headroom(map)>=4);var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current);
            for(int[] pair:new int[][]{{1,0},{4,0},{7,1},{8,1}}){pairs++;for(int z=0;z<2;z++)for(int x=0;x<2;x++){int top=index(map,pair[0],pair[1],x,z),base=index(map,pair[0],pair[1]+1,x,z);assertEquals(0,after.blocks()[top],"paired top "+name);assertNull(after.open()[top]);assertEquals(4,after.blocks()[base]);assertEquals(4,after.open()[base].length);assertEquals(ShapeKey.Kind.CELL,after.open()[base][0].kind());}}
            for(int[] vase:new int[][]{{6,1},{2,1},{9,1}})for(int z=0;z<2;z++)for(int x=0;x<2;x++){int top=index(map,vase[0],vase[1],x,z);assertEquals(4,after.blocks()[top]);assertEquals(ShapeKey.Kind.CELL,after.open()[top][0].kind());int floor=index(map,vase[0],vase[1]+1,x,z);assertEquals(0,after.blocks()[floor]);assertEquals(0,after.shapeMask()[floor]);assertEquals(1,after.decor()[floor]);walks++;}
            for(int y=2;y<=4;y++){assertEquals(CellKind.COUNTER,map.kind(1,y));counters++;for(int z=0;z<2;z++)for(int x=0;x<2;x++){int i=index(map,1,y,x,z);assertEquals(1,before.blocks()[i]);assertEquals(1,after.blocks()[i]);assertEquals(ShapeKey.Kind.CELL,after.open()[i][0].kind());assertEquals(2,after.decor()[i]);}}
            for(int[] p:new int[][]{{12,5},{12,6},{12,7},{13,5},{13,6},{13,7}})for(int z=0;z<2;z++)for(int x=0;x<2;x++){int i=index(map,p[0],p[1],x,z);assertEquals(2,after.blocks()[i]);assertEquals(ShapeKey.Kind.CELL,after.open()[i][0].kind());}
            assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(swaps,map.swapSites());
            if(name.equals("EM_ROUTE104_PRETTY_PETAL_FLOWER_SHOP"))for(JsonElement e:JsonParser.parseString(Files.readString(folder.resolve("assemblies.json"))).getAsJsonArray()){
                JsonObject s=e.getAsJsonObject();JsonArray rect=s.getAsJsonArray("rect");JsonObject proof=rectangle(map,rect.get(0).getAsInt(),rect.get(1).getAsInt(),rect.get(2).getAsInt(),rect.get(3).getAsInt(),before,after);proof.addProperty("id",s.get("id").getAsString());JsonArray mask=new JsonArray();JsonArray physical=s.getAsJsonArray("model_layout");boolean anyNull=false;for(JsonElement line:physical){JsonArray m=new JsonArray();for(JsonElement key:line.getAsJsonArray()){m.add(!key.isJsonNull());anyNull|=key.isJsonNull();}mask.add(m);}if(anyNull){proof.add("source_mask",mask);for(String field:List.of("original_baseline_fragments","corrected_fragments")){JsonArray kept=new JsonArray();for(JsonElement f:proof.getAsJsonArray(field)){JsonObject q=f.getAsJsonObject();if(mask.get(q.get("z").getAsInt()/2).getAsJsonArray().get(q.get("x").getAsInt()/2).getAsBoolean())kept.add(f);}proof.add(field,kept);}}proofs.add(proof);
            }
        }
        assertEquals(6,counters);assertEquals(8,pairs);assertEquals(24,walks);assertEquals(16,proofs.size());save("pretty-petal",proofs);
    }
    @Test void mysteryHouseConcealedStairDiagnosticsPreserveNativeStoryStates()throws Exception{
        var source=EmeraldSourceStackFollowupsTest.source();var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"));JsonArray rows=new JsonArray();
        for(String name:List.of("EM_SOOTOPOLIS_CITY_MYSTERY_EVENTS_HOUSE_1F","EM_SOOTOPOLIS_CITY_MYSTERY_EVENTS_HOUSE_B1F")){
            SceneMap map=source.scene().map(name).withHeadroom(rooms.get(name));var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);JsonObject proof=rectangle(map,1,0,6,3,before,before);var built=G3Built.map(bundle,name);var tiles=bundle.g3tiles(built.pair());var model=G3Cells.model(built,tiles,true,new Warnings());var placed=new PlacedMap(new MapPlacement(name,0,64,0,built.w(),built.h(),1,MapPlacement.Kind.INTERIOR,"BAKED","minecraft:plains",false,null),model);var stories=G3StoryCells.plan(List.of(placed),Map.of(name,built),bundle.g3sites(),(pair,mid)->tiles.quarters(mid),q->q,new Warnings());proof.add("story_sites",new Gson().toJsonTree(stories));JsonArray cells=new JsonArray();
            for(int y=0;y<3;y++)for(int x=1;x<7;x++){int i=built.index(x,y);JsonObject cell=new JsonObject();cell.addProperty("x",x);cell.addProperty("y",y);cell.addProperty("built_mid",built.mids()[i]);cell.addProperty("built_collision",built.coll()[i]);cell.addProperty("kind",map.kind(x,y).name());cell.addProperty("warp_tag",map.hasTag(x,y,CellTag.WARP));JsonArray blocks=new JsonArray(),decor=new JsonArray();for(int z=0;z<2;z++)for(int dx=0;dx<2;dx++){int col=index(map,x,y,dx,z);blocks.add(before.blocks()[col]);decor.add(before.decor()[col]);}cell.add("blocks",blocks);cell.add("decor",decor);cells.add(cell);}
            proof.add("built_cells",cells);rows.add(proof);
        }
        saveAt("small-facilities-study/mystery_events_house/story-runtime-diagnostic.json",rows);
    }
    @Test void exactLadderPairsRecordNativeAndFittedRoutesWithoutAssumingSolidBaseStacks()throws Exception{
        Path pairs=STUDY.resolve("environment-repairs/ladder-pairs.json"),live=ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json");assumeTrue(Files.exists(pairs));var source=EmeraldSourceStackFollowupsTest.source();Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=read(live);JsonArray rows=new JsonArray();int count=0;
        for(JsonElement group:JsonParser.parseString(Files.readString(pairs)).getAsJsonArray())for(JsonElement item:group.getAsJsonObject().getAsJsonArray("pairs")){
            JsonArray pair=item.getAsJsonArray();String name=pair.get(0).getAsString();int x=pair.get(1).getAsInt(),y=pair.get(2).getAsInt();SceneMap original=source.scene().map(name),map=rooms.containsKey(name)?original.withHeadroom(rooms.get(name)):original;assertEquals(CellKind.SOLID,map.kind(x,y));assertEquals(CellKind.WALK,map.kind(x,y+1));byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true);JsonObject proof=rectangle(map,x,y,1,2,before,after);assertEquals(group.getAsJsonObject().get("key"),proof.getAsJsonArray("layout").get(0).getAsJsonArray().get(0));assertEquals(pair.get(4),proof.getAsJsonArray("layout").get(1).getAsJsonArray().get(0));proof.addProperty("upper_key",group.getAsJsonObject().get("key").getAsString());proof.addProperty("lower_warp_tag",map.hasTag(x,y+1,CellTag.WARP));JsonArray carriers=new JsonArray();
            for(int row=0;row<2;row++)for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=index(map,x,y+row,dx,dz);JsonObject carrier=new JsonObject();carrier.addProperty("source_row",row);carrier.addProperty("dx",dx);carrier.addProperty("dz",dz);carrier.addProperty("before_blocks",before.blocks()[i]);carrier.addProperty("after_blocks",after.blocks()[i]);carrier.addProperty("before_decor",before.decor()[i]);carrier.addProperty("after_decor",after.decor()[i]);carriers.add(carrier);if(row==0)assertTrue(after.blocks()[i]>0,"SOLID upper must not relocate onto a WALK ladder foot");else{assertEquals(0,after.blocks()[i]);assertEquals(0,after.shapeMask()[i]);}}
            proof.add("carrier_routing",carriers);JsonArray units=new JsonArray();for(var unit:after.units())if(unit.cx()==x&&(unit.cy()==y||unit.cy()==y+1)){JsonObject u=new JsonObject();u.addProperty("x",unit.cx());u.addProperty("y",unit.cy());u.addProperty("source",unit.source());units.add(u);}proof.add("current_units",units);assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());rows.add(proof);count++;
        }
        assertEquals(21,count);JsonObject report=new JsonObject();report.addProperty("original_baseline_sha256",sha(Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"))));report.addProperty("current_table_sha256",sha(Files.readAllBytes(live)));report.addProperty("pair_evidence_sha256",sha(Files.readAllBytes(pairs)));report.add("occurrences",rows);EmeraldSourceStackFollowupsTest.save("environment-repairs/ladder-runtime-proof.json",report);
    }
    @Test void completeLadderTemplatesUseMeasuredHeadroomAndLeaveWarpFeetNoncolliding()throws Exception{
        Path folder=STUDY.resolve("ladder-templates");assumeTrue(Files.exists(folder.resolve("additions.json")));var source=EmeraldSourceStackFollowupsTest.source();Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy();for(var e:read(folder.resolve("additions.json")).getAsJsonObject("buildings").entrySet())current.getAsJsonArray("buildings").add(e.getValue());JsonArray rows=new JsonArray();int count=0;
        for(JsonElement group:read(folder.resolve("source-evidence.json")).getAsJsonArray("groups"))for(JsonElement o:group.getAsJsonObject().getAsJsonArray("occurrences")){
            JsonObject occurrence=o.getAsJsonObject();String name=occurrence.get("map").getAsString();int x=occurrence.get("x").getAsInt(),y=occurrence.get("y").getAsInt();SceneMap map=source.scene().map(name).withHeadroom(rooms.get(name));byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var swaps=List.copyOf(map.swapSites());assertTrue(ShapeHeights.headroom(map)>=4);assertEquals(CellKind.SOLID,map.kind(x,y));assertEquals(CellKind.WALK,map.kind(x,y+1));var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true);assertEquals(1,after.templates().stream().filter(t->t.x0()==x*2&&t.y0()==y*2).count());JsonArray floorChanges=new JsonArray();
            for(int z=0;z<2;z++)for(int dx=0;dx<2;dx++){int top=index(map,x,y,dx,z),foot=index(map,x,y+1,dx,z);assertEquals(4,after.blocks()[top]);assertEquals(15,after.shapeMask()[top]);assertEquals(0,after.blocks()[foot]);assertEquals(0,after.shapeMask()[foot]);assertEquals(15,after.decor()[foot]);assertEquals(ShapeKey.Kind.TMPL,after.open()[foot][0].kind());if(before.floor()[foot]!=after.floor()[foot]){JsonObject f=new JsonObject();f.addProperty("dx",dx);f.addProperty("dz",z);f.addProperty("before",before.floor()[foot]);f.addProperty("after",after.floor()[foot]);floorChanges.add(f);}}
            assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(swaps,map.swapSites());JsonObject proof=rectangle(map,x,y,1,2,before,after);assertEquals(group.getAsJsonObject().get("layout"),proof.get("layout"));proof.add("floor_material_changes",floorChanges);proof.addProperty("floor_material_review_required",!floorChanges.isEmpty());proof.addProperty("id",group.getAsJsonObject().get("id").getAsString());rows.add(proof);count++;
        }
        assertEquals(21,count);save("ladder-templates",rows);
    }
    @Test void route110BridgeContextsRecordActualRaisedFloorAndFittedRouting()throws Exception{
        var source=EmeraldSourceStackFollowupsTest.source();var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));String name="EM_ROUTE110";SceneMap map=source.scene().map(name);var built=G3Built.map(bundle,name);var model=G3Cells.model(built,bundle.g3tiles(built.pair()),true,new Warnings());var levels=RaisedRegions.gen3(model,built,true);var layout=levels.layout();JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"));byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true,layout);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true,layout);JsonArray rows=new JsonArray();
        for(int[] rect:new int[][]{{18,5,16,9},{24,20,7,5},{8,28,24,7}}){int x0=rect[0],y0=rect[1],w=rect[2],d=rect[3];JsonObject proof=rectangle(map,x0,y0,w,d,before,after);proof.addProperty("raised_outcome",levels.outcome());JsonArray cells=new JsonArray();
            for(int y=y0;y<y0+d;y++)for(int x=x0;x<x0+w;x++){JsonObject cell=new JsonObject();cell.addProperty("x",x);cell.addProperty("y",y);cell.addProperty("kind",map.kind(x,y).name());cell.addProperty("tags",map.tags()[y*map.w()+x]);cell.addProperty("built_collision",built.coll()[built.index(x,y)]);cell.addProperty("elevation",built.elev()[built.index(x,y)]);JsonArray carriers=new JsonArray();for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=index(map,x,y,dx,dz);JsonObject c=new JsonObject();c.addProperty("dx",dx);c.addProperty("dz",dz);c.addProperty("before_blocks",before.blocks()[i]);c.addProperty("after_blocks",after.blocks()[i]);c.addProperty("before_decor",before.decor()[i]);c.addProperty("after_decor",after.decor()[i]);c.addProperty("before_floor",before.floor()[i]);c.addProperty("after_floor",after.floor()[i]);c.addProperty("before_keys",Arrays.toString(sourceColumn(before,map,x*2+dx,y*2+dz)));c.addProperty("after_keys",Arrays.toString(sourceColumn(after,map,x*2+dx,y*2+dz)));carriers.add(c);}cell.add("columns",carriers);cells.add(cell);}proof.add("source_column_audit",cells);rows.add(proof);
        }
        assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());saveAt("towns-mauville/bridge-runtime-proof.json",rows);
    }
    @Test void mysteryFurnitureKeepsNativeStorySidesAndExistingPlantPair()throws Exception{
        Path folder=STUDY.resolve("mystery-house");assumeTrue(Files.exists(folder.resolve("updates.json")));var source=EmeraldSourceStackFollowupsTest.source();Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),live=read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json")),current=live.deepCopy();for(var e:read(folder.resolve("updates.json")).getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());JsonArray rows=new JsonArray();
        for(String name:List.of("EM_SOOTOPOLIS_CITY_MYSTERY_EVENTS_HOUSE_1F","EM_SOOTOPOLIS_CITY_MYSTERY_EVENTS_HOUSE_B1F")){
            SceneMap map=source.scene().map(name).withHeadroom(rooms.get(name));byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var swaps=List.copyOf(map.swapSites());var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var unchanged=EmeraldSourceStackFollowupsTest.plan(map,source,live,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true);
            for(int x:new int[]{2,4})for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=index(map,x,1,dx,dz);assertEquals(CellKind.COUNTER,map.kind(x,1));assertEquals(1,after.blocks()[i]);assertArrayEquals(unchanged.open()[i],after.open()[i]);assertEquals(unchanged.decor()[i],after.decor()[i]);}
            if(name.endsWith("B1F")){for(int z=0;z<2;z++)for(int x=0;x<2;x++){int upper=index(map,11,7,x,z),base=index(map,11,8,x,z);assertNull(after.open()[upper]);assertEquals(0,after.blocks()[upper]);assertEquals(4,after.blocks()[base]);assertEquals(ShapeKey.Kind.CELL,after.open()[base][0].kind());}JsonObject proof=rectangle(map,11,7,1,2,before,after);proof.addProperty("id","emerald_mystery_house_basement_plant");rows.add(proof);}
            else{for(int x:new int[]{6,9})for(int y:new int[]{4,5})for(int z=0;z<2;z++)for(int dx=0;dx<2;dx++){int i=index(map,x,y,dx,z);assertEquals(0,after.blocks()[i]);assertTrue(after.decor()[i]>0);}JsonObject proof=rectangle(map,6,4,4,2,before,after);proof.addProperty("id","emerald_mystery_house_dining");rows.add(proof);}
            assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(swaps,map.swapSites());
        }
        assertEquals(2,rows.size());save("mystery-house",rows);
    }
    @Test void qa10WholeContextsExportActualOriginalAndCurrentNativeRouting()throws Exception{
        Path folder=STUDY.resolve("qa10-followups/whole");assumeTrue(Files.exists(folder.resolve("runtime-source-cases.json")));var source=EmeraldSourceStackFollowupsTest.source();Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"));JsonArray rows=new JsonArray();int count=0;
        for(JsonElement g:read(folder.resolve("runtime-source-cases.json")).getAsJsonArray("groups"))for(JsonElement o:g.getAsJsonObject().getAsJsonArray("occurrences")){
            JsonObject group=g.getAsJsonObject(),occurrence=o.getAsJsonObject();String name=occurrence.get("map").getAsString();int x0=occurrence.get("x").getAsInt(),y0=occurrence.get("y").getAsInt(),w=group.getAsJsonArray("layout").get(0).getAsJsonArray().size(),d=group.getAsJsonArray("layout").size();SceneMap map=source.scene().map(name).withHeadroom(rooms.get(name));byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true);JsonObject proof=rectangle(map,x0,y0,w,d,before,after);assertEquals(group.get("layout"),proof.get("layout"));proof.addProperty("id",group.get("id").getAsString());JsonArray carriers=new JsonArray();
            for(int y=0;y<d;y++)for(int x=0;x<w;x++){JsonObject cell=new JsonObject();cell.addProperty("x",x0+x);cell.addProperty("y",y0+y);cell.addProperty("kind",map.kind(x0+x,y0+y).name());cell.addProperty("warp_tag",map.hasTag(x0+x,y0+y,CellTag.WARP));JsonArray columns=new JsonArray();for(int z=0;z<2;z++)for(int dx=0;dx<2;dx++){int i=index(map,x0+x,y0+y,dx,z);JsonObject c=new JsonObject();c.addProperty("dx",dx);c.addProperty("dz",z);c.addProperty("before_blocks",before.blocks()[i]);c.addProperty("after_blocks",after.blocks()[i]);c.addProperty("before_decor",before.decor()[i]);c.addProperty("after_decor",after.decor()[i]);columns.add(c);}cell.add("columns",columns);carriers.add(cell);}proof.add("carrier_routing",carriers);assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());rows.add(proof);count++;
        }
        assertEquals(9,count);JsonObject result=new JsonObject();result.addProperty("original_baseline_sha256",sha(Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"))));result.addProperty("current_table_sha256",sha(Files.readAllBytes(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"))));result.addProperty("source_cases_sha256",sha(Files.readAllBytes(folder.resolve("runtime-source-cases.json"))));result.add("occurrences",rows);EmeraldSourceStackFollowupsTest.save("qa10-followups/whole/runtime-proof.json",result);
    }
    @Test void stairTreadRepairsPreserveAllActualWarpCarriersAndTemplateHeadroom()throws Exception{
        Path folder=STUDY.resolve("interior-stair-repairs");assumeTrue(Files.exists(folder.resolve("updates.json")));var source=EmeraldSourceStackFollowupsTest.source();Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);JsonObject live=read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json")),current=live.deepCopy(),patch=read(folder.resolve("updates.json")).getAsJsonObject("buildings");JsonArray buildings=new JsonArray();int replaced=0;for(JsonElement e:current.getAsJsonArray("buildings")){String id=e.getAsJsonObject().get("id").getAsString();if(patch.has(id)){buildings.add(patch.get(id));replaced++;}else buildings.add(e);}assertEquals(4,replaced);current.add("buildings",buildings);JsonArray rows=new JsonArray();int occurrences=0,warps=0;
        for(JsonElement g:read(folder.resolve("source-evidence.json")).getAsJsonArray("groups"))for(JsonElement o:g.getAsJsonObject().getAsJsonArray("occurrences")){
            JsonObject group=g.getAsJsonObject(),place=o.getAsJsonObject();String name=place.get("map").getAsString();SceneMap map=source.scene().map(name).withHeadroom(rooms.get(name));int x0=place.get("x").getAsInt(),y0=place.get("y").getAsInt(),w=group.getAsJsonArray("layout").get(0).getAsJsonArray().size(),d=group.getAsJsonArray("layout").size();byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var swaps=List.copyOf(map.swapSites());var before=EmeraldSourceStackFollowupsTest.plan(map,source,live,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true);assertEquals(1,after.templates().stream().filter(t->t.x0()==x0*2&&t.y0()==y0*2).count());
            for(int y=0;y<d;y++)for(int x=0;x<w;x++){if(map.hasTag(x0+x,y0+y,CellTag.WARP))warps++;for(int z=0;z<2;z++)for(int dx=0;dx<2;dx++){int i=index(map,x0+x,y0+y,dx,z);assertEquals(before.blocks()[i],after.blocks()[i]);assertEquals(before.decor()[i],after.decor()[i]);assertEquals(before.shapeMask()[i],after.shapeMask()[i]);assertEquals(before.floor()[i],after.floor()[i]);assertNotNull(after.open()[i]);assertTrue(after.open()[i].length<=ShapeHeights.headroom(map));assertEquals(ShapeKey.Kind.TMPL,after.open()[i][0].kind());if(map.kind(x0+x,y0+y)==CellKind.WALK){assertEquals(0,after.blocks()[i]);assertEquals(0,after.shapeMask()[i]);assertTrue(after.decor()[i]>0);}}}
            JsonObject proof=rectangle(map,x0,y0,w,d,before,after);assertEquals(group.get("layout"),proof.get("layout"));proof.addProperty("id",group.get("id").getAsString());proof.remove("original_baseline_fragments");proof.addProperty("comparison","current merged template versus four tread repairs; original source baseline remains separately frozen");rows.add(proof);occurrences++;assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(swaps,map.swapSites());
        }
        assertEquals(5,occurrences);assertTrue(warps>=5);JsonObject proof=new JsonObject();proof.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(folder.resolve("updates.json"))));proof.add("occurrences",rows);EmeraldSourceStackFollowupsTest.save("interior-stair-repairs/runtime-proof.json",proof);
    }
    @Test void rusturfPlateauRecordsNativeLedgeAndWallColumnInterfaces()throws Exception{
        var source=EmeraldSourceStackFollowupsTest.source();var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));String name="EM_RUSTURF_TUNNEL";SceneMap map=source.scene().map(name).withHeadroom(Headroom.rooms(source.scene(),source.probe(),9).get(name));var built=G3Built.map(bundle,name);var model=G3Cells.model(built,bundle.g3tiles(built.pair()),true,new Warnings());var levels=RaisedRegions.gen3(model,built,true);JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),live=read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"));var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true,levels.layout());var after=EmeraldSourceStackFollowupsTest.plan(map,source,live,true,levels.layout());JsonObject proof=rectangle(map,12,0,5,5,before,after);proof.addProperty("raised_outcome",levels.outcome());proof.add("layout_levels",new Gson().toJsonTree(levels.layout()));JsonArray cells=new JsonArray();
        for(int y=0;y<5;y++)for(int x=12;x<17;x++){JsonObject cell=new JsonObject();cell.addProperty("x",x);cell.addProperty("y",y);cell.addProperty("kind",map.kind(x,y).name());cell.addProperty("raw_collision",built.coll()[built.index(x,y)]);JsonArray columns=new JsonArray();for(int z=0;z<2;z++)for(int dx=0;dx<2;dx++){int i=index(map,x,y,dx,z);JsonObject col=new JsonObject();col.addProperty("dx",dx);col.addProperty("dz",z);col.addProperty("before_blocks",before.blocks()[i]);col.addProperty("after_blocks",after.blocks()[i]);col.addProperty("before_decor",before.decor()[i]);col.addProperty("after_decor",after.decor()[i]);col.addProperty("before_keys",Arrays.toString(sourceColumn(before,map,x*2+dx,y*2+z)));col.addProperty("after_keys",Arrays.toString(sourceColumn(after,map,x*2+dx,y*2+z)));columns.add(col);}cell.add("columns",columns);cells.add(cell);}proof.add("source_column_audit",cells);JsonArray rows=new JsonArray();rows.add(proof);saveAt("environment-repairs/rusturf-plateau-runtime-proof.json",rows);
    }
    @Test void berrySoilGeometryHeightDoesNotAlterSourceCollisionOrNativeStoryStates()throws Exception{
        var source=EmeraldSourceStackFollowupsTest.source();var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));JsonObject live=read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json")),current=live.deepCopy();String key="g3q/11463-11464-11465-11466";JsonObject entry=current.getAsJsonObject("cells").getAsJsonObject(key);assertNotNull(entry);assertFalse(entry.has("up"));byte[] plane=new byte[512];Arrays.fill(plane,0,32,(byte)255);entry.addProperty("v",Base64.getEncoder().encodeToString(plane));JsonArray rows=new JsonArray();
        for(Object[] place:new Object[][]{{"EM_ROUTE102",24,2},{"EM_ROUTE103",58,5}}){String name=(String)place[0];int x=(Integer)place[1],y=(Integer)place[2];SceneMap map=source.scene().map(name);byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var built=G3Built.map(bundle,name);var tiles=bundle.g3tiles(built.pair());var model=G3Cells.model(built,tiles,true,new Warnings());var levels=RaisedRegions.gen3(model,built,true);var placed=new PlacedMap(new MapPlacement(name,0,64,0,built.w(),built.h(),1,MapPlacement.Kind.SURFACE,"BAKED","minecraft:plains",false,null),model);var storiesBefore=G3StoryCells.plan(List.of(placed),Map.of(name,built),bundle.g3sites(),(pair,mid)->tiles.quarters(mid),q->q,new Warnings());var before=EmeraldSourceStackFollowupsTest.plan(map,source,live,true,levels.layout());var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true,levels.layout());var storiesAfter=G3StoryCells.plan(List.of(placed),Map.of(name,built),bundle.g3sites(),(pair,mid)->tiles.quarters(mid),q->q,new Warnings());assertEquals(storiesBefore.toJson(),storiesAfter.toJson());JsonObject proof=rectangle(map,x,y,1,1,before,after);assertEquals(key,proof.getAsJsonArray("layout").get(0).getAsJsonArray().get(0).getAsString());proof.remove("original_baseline_fragments");proof.addProperty("comparison","merged current versus one-voxel soil occupancy diagnostic, not an authored candidate");proof.addProperty("kind",map.kind(x,y).name());proof.addProperty("built_collision",built.coll()[built.index(x,y)]);JsonArray columns=new JsonArray();
            for(int z=0;z<2;z++)for(int dx=0;dx<2;dx++){int i=index(map,x,y,dx,z);assertEquals(before.blocks()[i],after.blocks()[i]);assertEquals(before.decor()[i],after.decor()[i]);assertEquals(before.shapeMask()[i],after.shapeMask()[i]);assertEquals(before.floor()[i],after.floor()[i]);JsonObject col=new JsonObject();col.addProperty("blocks",after.blocks()[i]);col.addProperty("decor",after.decor()[i]);col.addProperty("before_keys",Arrays.toString(sourceColumn(before,map,x*2+dx,y*2+z)));col.addProperty("after_keys",Arrays.toString(sourceColumn(after,map,x*2+dx,y*2+z)));columns.add(col);}proof.add("columns",columns);proof.addProperty("native_story_cells_unchanged",true);assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());rows.add(proof);
        }
        JsonObject proof=new JsonObject();proof.addProperty("current_table_sha256",sha(Files.readAllBytes(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"))));proof.add("occurrences",rows);EmeraldSourceStackFollowupsTest.save("environment-repairs/berry-soil-runtime-proof.json",proof);
    }
    @Test void fortreeNineRegisteredAssembliesExportActualOriginalAndCurrentSourcePlacement()throws Exception{
        Path folder=STUDY.resolve("fortree-assembly-diagnostics");assumeTrue(Files.exists(folder.resolve("source-cases.json")));var source=EmeraldSourceStackFollowupsTest.source();var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"));JsonArray rows=new JsonArray();Map<String,SceneMap> maps=new HashMap<>();Map<String,ShapePlanner.MapShapes> befores=new HashMap<>(),afters=new HashMap<>();int count=0;
        for(JsonElement g:read(folder.resolve("source-cases.json")).getAsJsonArray("groups")){
            JsonObject group=g.getAsJsonObject();for(JsonElement o:group.getAsJsonArray("occurrences")){JsonObject place=o.getAsJsonObject();String name=place.get("map").getAsString();SceneMap map=maps.computeIfAbsent(name,n->{SceneMap m=source.scene().map(n);return rooms.containsKey(n)?m.withHeadroom(rooms.get(n)):m;});var built=G3Built.map(bundle,name);var model=G3Cells.model(built,bundle.g3tiles(built.pair()),true,new Warnings());var levels=RaisedRegions.gen3(model,built,true);byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var before=befores.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true,levels.layout()));var after=afters.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,current,true,levels.layout()));int x0=place.get("x").getAsInt(),y0=place.get("y").getAsInt(),d=group.getAsJsonArray("layout").size(),w=group.getAsJsonArray("layout").get(0).getAsJsonArray().size();JsonObject proof=rectangle(map,x0,y0,w,d,before,after);assertEquals(group.get("layout"),proof.get("layout"));proof.addProperty("id",group.get("id").getAsString());proof.addProperty("raised_outcome",levels.outcome());proof.add("existing_model_layout",group.get("model_layout"));proof.add("existing_placements",group.get("existing_placements"));JsonArray cells=new JsonArray();
                for(int y=0;y<d;y++)for(int x=0;x<w;x++){JsonObject cell=new JsonObject();cell.addProperty("x",x0+x);cell.addProperty("y",y0+y);cell.addProperty("kind",map.kind(x0+x,y0+y).name());cell.addProperty("built_collision",built.coll()[built.index(x0+x,y0+y)]);JsonArray columns=new JsonArray();for(int z=0;z<2;z++)for(int dx=0;dx<2;dx++){int i=index(map,x0+x,y0+y,dx,z);JsonObject col=new JsonObject();col.addProperty("dx",dx);col.addProperty("dz",z);col.addProperty("before_blocks",before.blocks()[i]);col.addProperty("after_blocks",after.blocks()[i]);col.addProperty("before_decor",before.decor()[i]);col.addProperty("after_decor",after.decor()[i]);col.addProperty("before_keys",Arrays.toString(sourceColumn(before,map,(x0+x)*2+dx,(y0+y)*2+z)));col.addProperty("after_keys",Arrays.toString(sourceColumn(after,map,(x0+x)*2+dx,(y0+y)*2+z)));columns.add(col);}cell.add("columns",columns);cells.add(cell);}proof.add("source_column_audit",cells);rows.add(proof);count++;assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());
            }
        }
        assertEquals(9,count);JsonObject proof=new JsonObject();proof.addProperty("original_baseline_sha256",sha(Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"))));proof.addProperty("current_table_sha256",sha(Files.readAllBytes(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"))));proof.addProperty("source_cases_sha256",sha(Files.readAllBytes(folder.resolve("source-cases.json"))));proof.add("occurrences",rows);EmeraldSourceStackFollowupsTest.save("fortree-assembly-diagnostics/runtime-proof.json",proof);
    }
    @Test void fivePublishedBuildingsReceiveExactOriginalNativeCompanions()throws Exception{
        Path folder=STUDY.resolve("island-interior-optins");assumeTrue(Files.exists(folder.resolve("updates.json")));
        var source=EmeraldSourceStackFollowupsTest.source();var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);
        JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),live=read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json")),current=live.deepCopy(),patch=read(folder.resolve("updates.json")).getAsJsonObject("buildings");JsonArray buildings=new JsonArray();int replaced=0;
        for(JsonElement e:current.getAsJsonArray("buildings")){String id=e.getAsJsonObject().get("id").getAsString();if(patch.has(id)){JsonObject proposed=patch.getAsJsonObject(id),without=proposed.deepCopy();without.remove("interior");JsonObject previous=e.getAsJsonObject().deepCopy();previous.remove("interior");assertEquals(previous,without);assertTrue(proposed.get("interior").getAsBoolean());buildings.add(proposed);replaced++;}else buildings.add(e);}assertEquals(4,replaced);current.add("buildings",buildings);
        String snapshot=new GsonBuilder().setPrettyPrinting().create().toJson(current)+"\n";Files.writeString(folder.resolve("fitted-proof-current.json"),snapshot);JsonArray rows=new JsonArray();int count=0;
        for(JsonElement g:read(folder.resolve("source-cases.json")).getAsJsonArray("groups"))for(JsonElement o:g.getAsJsonObject().getAsJsonArray("occurrences")){
            JsonObject group=g.getAsJsonObject(),place=o.getAsJsonObject();String name=place.get("map").getAsString();SceneMap original=source.scene().map(name),map=rooms.containsKey(name)?original.withHeadroom(rooms.get(name)):original;var built=G3Built.map(bundle,name);var model=G3Cells.model(built,bundle.g3tiles(built.pair()),true,new Warnings());var levels=RaisedRegions.gen3(model,built,true);byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var swaps=List.copyOf(map.swapSites());var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true,levels.layout());var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true,levels.layout());
            int x=place.get("x").getAsInt(),y=place.get("y").getAsInt(),d=group.getAsJsonArray("layout").size(),w=group.getAsJsonArray("layout").get(0).getAsJsonArray().size();assertEquals(1,after.templates().stream().filter(t->t.x0()==x*2&&t.y0()==y*2).count(),group.get("id").getAsString());
            if(patch.has(group.get("id").getAsString())){assertFalse(map.outdoors());int needed=(patch.getAsJsonObject(group.get("id").getAsString()).get("height").getAsInt()+7)/8;assertTrue(ShapeHeights.headroom(map)>=needed);}
            for(int yy=0;yy<d;yy++)for(int xx=0;xx<w;xx++)if(map.kind(x+xx,y+yy)==CellKind.WALK||map.kind(x+xx,y+yy)==CellKind.WATER)for(int z=0;z<2;z++)for(int dx=0;dx<2;dx++){int i=index(map,x+xx,y+yy,dx,z);assertEquals(0,after.blocks()[i]);assertEquals(0,after.shapeMask()[i]);}
            JsonObject proof=rectangle(map,x,y,w,d,before,after);assertEquals(group.get("layout"),proof.get("layout"));proof.addProperty("id",group.get("id").getAsString());proof.addProperty("outdoors",map.outdoors());proof.addProperty("matching_templates",1);rows.add(proof);assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(swaps,map.swapSites());count++;
        }
        assertEquals(6,count);JsonObject report=new JsonObject();report.addProperty("original_baseline_sha256",sha(Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"))));report.addProperty("current_table_sha256",sha(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));report.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(folder.resolve("updates.json"))));report.add("occurrences",rows);EmeraldSourceStackFollowupsTest.save("building-native-followups/runtime-proof.json",report);
    }
    @Test void mauvillePlanterRemovalPreservesSourceKindsAndReportsActualSeparateRoutes()throws Exception{
        Path folder=STUDY.resolve("towns-mauville-planters");assumeTrue(Files.exists(folder.resolve("updates.json")));var source=EmeraldSourceStackFollowupsTest.source();SceneMap map=source.scene().map("EM_VERDANTURF_TOWN");JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"));for(var e:read(folder.resolve("updates.json")).getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true);JsonArray rows=new JsonArray();List<Executable> checks=new ArrayList<>();
        for(int[] p:new int[][]{{0,15,1,2},{15,18,3,1}}){JsonObject proof=rectangle(map,p[0],p[1],p[2],p[3],before,after);proof.addProperty("id",p[0]==0?"emerald_verdanturf_long_planter":"emerald_verdanturf_short_planters");for(int y=0;y<p[3];y++)for(int x=0;x<p[2];x++)for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=index(map,p[0]+x,p[1]+y,dx,dz);assertEquals(CellKind.SOLID,map.kind(p[0]+x,p[1]+y));checks.add(()->{assertNotNull(after.open()[i]);assertEquals(2,after.blocks()[i]);assertEquals(ShapeKey.Kind.CELL,after.open()[i][0].kind());});}rows.add(proof);}
        assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());saveAt("towns-mauville-planters/runtime-diagnostic.json",rows);assertAll(checks);save("towns-mauville-planters",rows);
    }
    @Test void trickHouseKitKeepsStoryStatesAndExportsNativeMissingWallBefore()throws Exception{
        Path folder=STUDY.resolve("trick-house");assumeTrue(Files.exists(folder.resolve("updates.json")));var source=EmeraldSourceStackFollowupsTest.source();var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),live=read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json")),current=live.deepCopy();for(var e:read(folder.resolve("updates.json")).getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());String snapshot=new GsonBuilder().setPrettyPrinting().create().toJson(current)+"\n";Files.writeString(folder.resolve("fitted-proof-current.json"),snapshot);JsonArray rows=new JsonArray(),audit=new JsonArray();JsonObject updated=read(folder.resolve("updates.json")).getAsJsonObject("cells");int added=0,openStory=0;
        for(int number=1;number<=8;number++){
            String name="EM_ROUTE110_TRICK_HOUSE_PUZZLE"+number;SceneMap map=source.scene().map(name).withHeadroom(rooms.get(name));var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var unchanged=EmeraldSourceStackFollowupsTest.plan(map,source,live,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true);byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var swaps=List.copyOf(map.swapSites());
            for(int y=0;y<map.h();y++)for(int x=0;x<map.w();x++){
                String key=FittedShapes.key(map.atlas(),new int[]{map.tile(x*2,y*2),map.tile(x*2+1,y*2),map.tile(x*2,y*2+1),map.tile(x*2+1,y*2+1)});if(!updated.has(key))continue;
                boolean newKey=!baseline.getAsJsonObject("cells").has(key);if(newKey)added++;if(key.equals("g3q/10326-10203-10327-10205")||key.equals("g3q/10202-10328-10204-10329")){assertEquals(CellKind.WALK,map.kind(x,y));openStory++;}
                JsonObject cell=new JsonObject();cell.addProperty("map",name);cell.addProperty("x",x);cell.addProperty("y",y);cell.addProperty("key",key);cell.addProperty("kind",map.kind(x,y).name());cell.addProperty("baseline_absent",newKey);JsonArray carriers=new JsonArray();
                for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=index(map,x,y,dx,dz);JsonObject carrier=new JsonObject();carrier.addProperty("before_blocks",unchanged.blocks()[i]);carrier.addProperty("after_blocks",after.blocks()[i]);carrier.addProperty("before_decor",unchanged.decor()[i]);carrier.addProperty("after_decor",after.decor()[i]);carriers.add(carrier);if(newKey){assertEquals(CellKind.SOLID,map.kind(x,y));assertEquals(2,after.blocks()[i]);assertEquals(ShapeKey.Kind.CELL,after.open()[i][0].kind());}else{assertEquals(unchanged.blocks()[i],after.blocks()[i]);assertEquals(unchanged.decor()[i],after.decor()[i]);assertEquals(unchanged.floor()[i],after.floor()[i]);}if(map.kind(x,y)==CellKind.WALK){assertEquals(0,after.blocks()[i]);assertNull(after.open()[i]);}}
                cell.add("carriers",carriers);audit.add(cell);
            }
            for(JsonElement value:JsonParser.parseString(Files.readString(folder.resolve("assembly-source-contexts.json"))).getAsJsonArray()){
                JsonObject spec=value.getAsJsonObject();if(!spec.get("map").getAsString().equals(name))continue;JsonArray rect=spec.getAsJsonArray("rect");JsonObject row=rectangle(map,rect.get(0).getAsInt(),rect.get(1).getAsInt(),rect.get(2).getAsInt(),rect.get(3).getAsInt(),before,after);assertEquals(spec.get("source_layout"),row.get("layout"));row.addProperty("id",spec.get("id").getAsString());JsonArray mask=new JsonArray();for(JsonElement line:spec.getAsJsonArray("model_layout")){JsonArray m=new JsonArray();for(JsonElement k:line.getAsJsonArray())m.add(!k.isJsonNull());mask.add(m);}row.add("source_mask",mask);for(String field:List.of("original_baseline_fragments","corrected_fragments")){JsonArray kept=new JsonArray();for(JsonElement item:row.getAsJsonArray(field)){JsonObject f=item.getAsJsonObject();if(mask.get(f.get("z").getAsInt()/2).getAsJsonArray().get(f.get("x").getAsInt()/2).getAsBoolean())kept.add(f);}row.add(field,kept);}row.add("source_floor_columns",sourceFloors(map,rect.get(0).getAsInt(),rect.get(1).getAsInt(),rect.get(2).getAsInt(),rect.get(3).getAsInt(),before,after,null,mask));rows.add(row);
            }
            assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(swaps,map.swapSites());
        }
        assertEquals(20,openStory);assertTrue(added>=12);assertEquals(8,rows.size());JsonObject proof=new JsonObject();proof.addProperty("original_baseline_sha256",sha(Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"))));proof.addProperty("current_table_sha256",sha(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));proof.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(folder.resolve("updates.json"))));proof.addProperty("sites_sha256",sha(Files.readAllBytes(ROOT.resolve("local/minecraft/kantobridge/export/emerald/g3sites/all.json"))));proof.add("occurrences",rows);proof.add("actual_source_routes",audit);EmeraldSourceStackFollowupsTest.save("trick-house/runtime-proof.json",proof);
    }
    @Test void rusturfGroundedRepairsPreserveNativeLedgeAndAllSourceCarrierRoutes()throws Exception{
        Path folder=STUDY.resolve("rusturf-plateau-repairs");assumeTrue(Files.exists(folder.resolve("updates.json")));var source=EmeraldSourceStackFollowupsTest.source();var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),live=read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json")),current=live.deepCopy();for(var e:read(folder.resolve("updates.json")).getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());String snapshot=new GsonBuilder().setPrettyPrinting().create().toJson(current)+"\n";Files.writeString(folder.resolve("fitted-proof-current.json"),snapshot);JsonArray rows=new JsonArray(),audit=new JsonArray();Map<String,ShapePlanner.MapShapes> oldPlans=new HashMap<>(),newPlans=new HashMap<>();
        for(JsonElement item:read(folder.resolve("source-evidence.json")).getAsJsonArray("sources"))for(JsonElement place:item.getAsJsonObject().getAsJsonArray("occurrences")){
            JsonObject p=place.getAsJsonObject();String name=p.get("map").getAsString();int x=p.get("x").getAsInt(),y=p.get("y").getAsInt();SceneMap original=source.scene().map(name),map=rooms.containsKey(name)?original.withHeadroom(rooms.get(name)):original;var built=G3Built.map(bundle,name);var model=G3Cells.model(built,bundle.g3tiles(built.pair()),true,new Warnings());var layout=RaisedRegions.gen3(model,built,true).layout();byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var before=oldPlans.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,live,true,layout));var after=newPlans.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,current,true,layout));JsonObject row=rectangle(map,x,y,1,1,before,after);assertEquals(item.getAsJsonObject().get("key"),row.getAsJsonArray("layout").get(0).getAsJsonArray().get(0));assertEquals(CellKind.SOLID,map.kind(x,y));
            for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=index(map,x,y,dx,dz);assertEquals(before.blocks()[i],after.blocks()[i]);assertEquals(before.decor()[i],after.decor()[i]);assertEquals(before.shapeMask()[i],after.shapeMask()[i]);assertEquals(before.floor()[i],after.floor()[i]);assertEquals(2,after.blocks()[i]);assertEquals(ShapeKey.Kind.CELL,after.open()[i][0].kind());}assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());audit.add(row);
        }
        SceneMap map=source.scene().map("EM_RUSTURF_TUNNEL").withHeadroom(rooms.get("EM_RUSTURF_TUNNEL"));var built=G3Built.map(bundle,map.id());var layout=RaisedRegions.gen3(G3Cells.model(built,bundle.g3tiles(built.pair()),true,new Warnings()),built,true).layout();var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true,layout);var after=newPlans.get(map.id());var unchanged=oldPlans.get(map.id());
        for(int[] rect:new int[][]{{12,0,5,5},{17,0,5,5}}){JsonObject row=rectangle(map,rect[0],rect[1],rect[2],rect[3],before,after);row.addProperty("id","emerald_rusturf_plateau_context_"+rect[0]);row.add("layout_levels",new Gson().toJsonTree(layout));rows.add(row);}
        for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=index(map,14,1,dx,dz);assertArrayEquals(unchanged.open()[i],after.open()[i]);assertEquals(0,after.blocks()[i]);assertEquals(ShapeKey.Kind.SLAB,after.open()[i][0].kind());}
        assertEquals(158,audit.size());JsonObject proof=new JsonObject();proof.addProperty("original_baseline_sha256",sha(Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"))));proof.addProperty("current_table_sha256",sha(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));proof.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(folder.resolve("updates.json"))));proof.add("occurrences",rows);proof.add("all_source_routes",audit);EmeraldSourceStackFollowupsTest.save("rusturf-plateau-repairs/runtime-proof.json",proof);
    }
    @Test void mysteryStairRepairPreservesNativeWarpAndExportsOnlyTheNewWholeRevision()throws Exception{
        Path folder=STUDY.resolve("mystery-stair-correction");assumeTrue(Files.exists(folder.resolve("updates.json")));var source=EmeraldSourceStackFollowupsTest.source();var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));String name="EM_SOOTOPOLIS_CITY_MYSTERY_EVENTS_HOUSE_1F",key="g3q/7625-7626-7627-7628";SceneMap map=source.scene().map(name).withHeadroom(Headroom.rooms(source.scene(),source.probe(),9).get(name));JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),live=read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json")),current=live.deepCopy();JsonObject entry=read(folder.resolve("updates.json")).getAsJsonObject("cells").getAsJsonObject(key);for(String field:List.of("class","walk","from"))assertEquals(live.getAsJsonObject("cells").getAsJsonObject(key).get(field),entry.get(field));current.getAsJsonObject("cells").add(key,entry);String snapshot=new GsonBuilder().setPrettyPrinting().create().toJson(current)+"\n";Files.writeString(folder.resolve("fitted-proof-current.json"),snapshot);byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var swaps=List.copyOf(map.swapSites());var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var unchanged=EmeraldSourceStackFollowupsTest.plan(map,source,live,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true);
        assertEquals(CellKind.WALK,map.kind(3,1));assertTrue(map.hasTag(3,1,CellTag.WARP));for(int y=0;y<2;y++)for(int x=2;x<5;x++)for(int z=0;z<2;z++)for(int dx=0;dx<2;dx++){int i=index(map,x,y,dx,z);assertEquals(unchanged.blocks()[i],after.blocks()[i]);assertEquals(unchanged.decor()[i],after.decor()[i]);assertEquals(unchanged.shapeMask()[i],after.shapeMask()[i]);assertEquals(unchanged.floor()[i],after.floor()[i]);if(x==3&&y==1){assertEquals(0,after.blocks()[i]);assertEquals(3,after.decor()[i]);assertEquals(ShapeKey.Kind.CELL,after.open()[i][0].kind());}else assertArrayEquals(unchanged.open()[i],after.open()[i]);}
        var built=G3Built.map(bundle,name);var tiles=bundle.g3tiles(built.pair());var model=G3Cells.model(built,tiles,true,new Warnings());var placed=new PlacedMap(new MapPlacement(name,0,64,0,built.w(),built.h(),1,MapPlacement.Kind.INTERIOR,"BAKED","minecraft:plains",false,null),model);var stories=G3StoryCells.plan(List.of(placed),Map.of(name,built),bundle.g3sites(),(pair,mid)->tiles.quarters(mid),q->q,new Warnings());assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(swaps,map.swapSites());JsonObject row=rectangle(map,2,0,3,2,before,after);row.addProperty("id","emerald_qa10_mystery_stairs_1f");row.add("native_story_sites_unchanged",new Gson().toJsonTree(stories));JsonArray rows=new JsonArray();rows.add(row);JsonObject proof=new JsonObject();proof.addProperty("original_baseline_sha256",sha(Files.readAllBytes(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"))));proof.addProperty("current_table_sha256",sha(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));proof.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(folder.resolve("updates.json"))));proof.add("occurrences",rows);EmeraldSourceStackFollowupsTest.save("mystery-stair-correction/runtime-proof.json",proof);
    }
    @Test void recordConsoleCountersKeepOneCollisionBlockAndExportTheirTrueOriginalCubes()throws Exception{
        Path patch=STUDY.resolve("cable-palace/updates.json");assumeTrue(Files.exists(patch));
        var source=EmeraldSourceStackFollowupsTest.source();SceneMap original=source.scene().map("EM_RECORD_CORNER");
        SceneMap map=original.withHeadroom(Headroom.rooms(source.scene(),source.probe(),9).get(original.id()));
        byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();
        JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy();
        for(var e:read(patch).getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());
        var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current);
        for(int[] p:new int[][]{{8,5},{11,5},{9,6},{10,6}}){assertEquals(CellKind.COUNTER,map.kind(p[0],p[1]));
            for(int z=0;z<2;z++)for(int x=0;x<2;x++){int i=index(map,p[0],p[1],x,z);assertEquals(1,before.blocks()[i]);assertEquals(1,after.blocks()[i]);
                assertNotNull(before.open()[i]);assertEquals(ShapeKey.Kind.CUBE,before.open()[i][0].kind());assertEquals(ShapeKey.Kind.CELL,after.open()[i][0].kind());}
        }
        assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());JsonArray rows=new JsonArray();rows.add(rectangle(map,7,3,6,4,before,after));save("cable-palace",rows);
    }
    @Test void mossdeepFurnitureKeepsCounterCollisionAndExportsMaskedOriginalBanks()throws Exception{
        Path folder=STUDY.resolve("mossdeep-corner");assumeTrue(Files.exists(folder.resolve("updates.json")));
        var source=EmeraldSourceStackFollowupsTest.source();SceneMap original=source.scene().map("EM_MOSSDEEP_CITY_GAME_CORNER_1F");
        SceneMap map=original.withHeadroom(Headroom.rooms(source.scene(),source.probe(),9).get(original.id()));
        JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy();
        for(var e:read(folder.resolve("updates.json")).getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());
        byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();
        var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current);
        for(int[] p:new int[][]{{3,3},{4,3},{6,3},{7,3}})for(int z=0;z<2;z++)for(int x=0;x<2;x++){
            int i=index(map,p[0],p[1],x,z);assertEquals(CellKind.COUNTER,map.kind(p[0],p[1]));assertEquals(1,before.blocks()[i]);assertEquals(1,after.blocks()[i]);
            assertEquals(ShapeKey.Kind.CUBE,before.open()[i][0].kind());assertEquals(ShapeKey.Kind.CELL,after.open()[i][0].kind());assertEquals(1,after.open()[i].length);
        }
        JsonArray rows=new JsonArray();
        for(JsonElement e:JsonParser.parseString(Files.readString(folder.resolve("assemblies.json"))).getAsJsonArray()){
            JsonObject spec=e.getAsJsonObject();if(!spec.get("id").getAsString().contains("counter"))continue;
            JsonArray rect=spec.getAsJsonArray("rect");JsonObject proof=rectangle(map,rect.get(0).getAsInt(),rect.get(1).getAsInt(),3,3,before,after);
            assertEquals(spec.get("source_layout"),proof.get("layout"));JsonArray mask=new JsonArray();
            for(JsonElement line:spec.getAsJsonArray("model_layout")){JsonArray m=new JsonArray();for(JsonElement cell:line.getAsJsonArray())m.add(!cell.isJsonNull());mask.add(m);}
            proof.add("source_mask",mask);
            for(String field:List.of("original_baseline_fragments","corrected_fragments")){
                JsonArray filtered=new JsonArray();for(JsonElement f:proof.getAsJsonArray(field)){JsonObject fragment=f.getAsJsonObject();
                    if(mask.get(fragment.get("z").getAsInt()/2).getAsJsonArray().get(fragment.get("x").getAsInt()/2).getAsBoolean())filtered.add(f);}
                proof.add(field,filtered);
            }
            proof.addProperty("id",spec.get("id").getAsString());rows.add(proof);
        }
        assertEquals(2,rows.size());assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());save("mossdeep-corner",rows);
    }
    @Test void newMauvilleMachinesFitMeasuredHeadroomAndKeepSourceCrateCarriers()throws Exception{
        Path folder=STUDY.resolve("bike-shop");assumeTrue(Files.exists(folder.resolve("updates.json")));
        var source=EmeraldSourceStackFollowupsTest.source();SceneMap original=source.scene().map("EM_NEW_MAUVILLE_INSIDE");
        SceneMap map=original.withHeadroom(Headroom.rooms(source.scene(),source.probe(),9).get(original.id()));assertTrue(ShapeHeights.headroom(map)>=6);
        JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy();
        for(var e:read(folder.resolve("updates.json")).getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());
        byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current);
        JsonArray rows=new JsonArray();int cells=0;
        for(int[] rect:new int[][]{{32,1,4,4},{29,1,2,3},{38,1,2,3},{1,13,2,3},{22,14,2,3}}){
            JsonObject proof=rectangle(map,rect[0],rect[1],rect[2],rect[3],before,after);proof.addProperty("id",rect[2]==4?"emerald_bike_shop_generator":"emerald_bike_shop_compressor");rows.add(proof);
            for(int y=0;y<rect[3];y++)for(int x=0;x<rect[2];x++){
                assertEquals(CellKind.SOLID,map.kind(rect[0]+x,rect[1]+y));String key=proof.getAsJsonArray("layout").get(y).getAsJsonArray().get(x).getAsString();JsonObject entry=current.getAsJsonObject("cells").getAsJsonObject(key);assertNotNull(entry);
                int carriers=2*(1+(entry.has("up")?entry.getAsJsonArray("up").size():0));assertTrue(carriers<=ShapeHeights.headroom(map));
                for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                    int i=index(map,rect[0]+x,rect[1]+y,dx,dz);assertNotNull(after.open()[i]);assertEquals(carriers,after.open()[i].length);assertEquals(carriers,after.blocks()[i]);
                    for(ShapeKey shape:after.open()[i])assertEquals(ShapeKey.Kind.CELL,shape.kind());
                }cells++;
            }
        }
        int crates=0;
        for(JsonElement e:JsonParser.parseString(Files.readString(folder.resolve("metadata-evidence.json"))).getAsJsonArray()){
            JsonObject change=e.getAsJsonObject();if(!change.getAsJsonObject("before").has("on")||change.getAsJsonObject("after").has("on"))continue;
            for(JsonElement o:change.getAsJsonArray("occurrences")){JsonObject place=o.getAsJsonObject();assertEquals(map.id(),place.get("map").getAsString());int x=place.get("x").getAsInt(),y=place.get("y").getAsInt();
                assertEquals(CellKind.SOLID,map.kind(x,y));assertEquals(CellKind.WALK,map.kind(x,y+1));
                for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){int i=index(map,x,y,dx,dz);assertNotNull(before.open()[i]);assertNotNull(after.open()[i]);assertEquals(ShapeKey.Kind.CELL,after.open()[i][0].kind());assertTrue(after.blocks()[i]>0);}crates++;
            }
        }
        assertEquals(40,cells);assertEquals(12,crates);assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());save("bike-shop",rows);
    }
    @Test void bikeShopDeskPairsRetainTrueOriginalMissingCellFallback()throws Exception{
        Path folder=STUDY.resolve("bike-shop");assumeTrue(Files.exists(folder.resolve("updates.json")));
        var source=EmeraldSourceStackFollowupsTest.source();SceneMap original=source.scene().map("EM_MAUVILLE_CITY_BIKE_SHOP");
        SceneMap map=original.withHeadroom(Headroom.rooms(source.scene(),source.probe(),9).get(original.id()));
        JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy();
        for(String file:List.of("updates.json","additions.json"))if(Files.exists(folder.resolve(file)))for(var e:read(folder.resolve(file)).getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());
        var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current);JsonArray rows=new JsonArray();int missing=0;
        for(int x0:new int[]{7,10}){
            JsonObject proof=rectangle(map,x0,1,2,1,before,after);proof.addProperty("id",x0==7?"emerald_bike_shop_first_book_desk":"emerald_bike_shop_second_book_desk");rows.add(proof);
            for(int x=0;x<2;x++){
                String key=proof.getAsJsonArray("layout").get(0).getAsJsonArray().get(x).getAsString();assertEquals(CellKind.SOLID,map.kind(x0+x,1));JsonObject entry=current.getAsJsonObject("cells").getAsJsonObject(key);int carriers=2*(1+(entry.has("up")?entry.getAsJsonArray("up").size():0));assertTrue(carriers<=ShapeHeights.headroom(map));
                boolean absent=!baseline.getAsJsonObject("cells").has(key);if(absent)missing++;
                for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                    int i=index(map,x0+x,1,dx,dz);assertEquals(carriers,after.blocks()[i]);assertEquals(carriers,after.open()[i].length);for(ShapeKey s:after.open()[i])assertEquals(ShapeKey.Kind.CELL,s.kind());
                    if(absent){assertEquals(ShapeKey.Kind.CUBE,sourceColumn(before,map,(x0+x)*2+dx,2+dz)[0].kind());assertTrue(before.blocks()[i]>0);}
                }
            }
        }
        assertEquals(1,missing);saveAt("bike-shop/desk-runtime-proof.json",rows);
    }
    @Test void genericWindowsAndFurnitureUseSourceBoundStacksAndNoncollidingDecor()throws Exception{
        var source=EmeraldSourceStackFollowupsTest.source();Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);
        JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"));int windows=0,walkKeys=0,removedKeys=0;
        for(String family:List.of("generic-soft-furnishings","generic-cabinets")){
            Path folder=STUDY.resolve(family);assumeTrue(Files.exists(folder.resolve("updates.json")));JsonObject current=baseline.deepCopy();
            for(String file:List.of("updates.json","additions.json"))if(Files.exists(folder.resolve(file)))for(var e:read(folder.resolve(file)).getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());
            Map<String,SceneMap> maps=new HashMap<>();Map<String,ShapePlanner.MapShapes> befores=new HashMap<>(),afters=new HashMap<>();JsonArray routing=new JsonArray(),proofs=new JsonArray();
            for(JsonElement element:JsonParser.parseString(Files.readString(folder.resolve("metadata-evidence.json"))).getAsJsonArray()){
                JsonObject change=element.getAsJsonObject();String field=change.get("field").getAsString();boolean walk=field.equals("walk"),removed=field.equals("on")&&change.get("new_value").isJsonNull();if(!walk&&!removed)continue;
                if(walk)walkKeys++;else removedKeys++;
                for(JsonElement item:change.getAsJsonArray("occurrences")){
                    JsonObject place=item.getAsJsonObject();String name=place.get("map").getAsString();SceneMap map=maps.computeIfAbsent(name,n->{SceneMap m=source.scene().map(n);return rooms.containsKey(n)?m.withHeadroom(rooms.get(n)):m;});
                    var before=befores.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,baseline));var after=afters.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,current));int x=place.get("x").getAsInt(),y=place.get("y").getAsInt();
                    assertEquals(walk?CellKind.WALK:CellKind.SOLID,map.kind(x,y));JsonObject record=place.deepCopy();record.addProperty("key",change.get("key").getAsString());record.addProperty("headroom",ShapeHeights.headroom(map));record.addProperty("route",walk?"decor":"standalone-solid");
                    for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                        int i=index(map,x,y,dx,dz);assertNotNull(after.open()[i],name+" "+x+","+y);for(ShapeKey s:after.open()[i])assertEquals(ShapeKey.Kind.CELL,s.kind());
                        if(walk){assertEquals(0,before.blocks()[i]);assertEquals(0,after.blocks()[i]);assertEquals(0,after.shapeMask()[i]);assertTrue(after.decor()[i]>0);}
                        else{assertTrue(after.blocks()[i]>0);assertEquals(2,after.open()[i].length);}
                    }routing.add(record);
                }
            }
            if(family.equals("generic-soft-furnishings"))for(JsonElement element:JsonParser.parseString(Files.readString(folder.resolve("entities.json"))).getAsJsonArray()){
                JsonObject entity=element.getAsJsonObject();if(!entity.has("baseline_native_required"))continue;
                for(JsonElement item:entity.getAsJsonArray("occurrences")){
                    JsonObject place=item.getAsJsonObject();String name=place.get("map").getAsString();SceneMap map=maps.computeIfAbsent(name,n->{SceneMap m=source.scene().map(n);return rooms.containsKey(n)?m.withHeadroom(rooms.get(n)):m;});
                    var before=befores.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,baseline));var after=afters.computeIfAbsent(name,n->EmeraldSourceStackFollowupsTest.plan(map,source,current));int x=place.get("x").getAsInt(),y=place.get("y").getAsInt();assertTrue(ShapeHeights.headroom(map)>=4);
                    assertEquals(CellKind.SOLID,map.kind(x,y));assertEquals(CellKind.SOLID,map.kind(x,y+1));JsonObject proof=rectangle(map,x,y,1,2,before,after);assertEquals(entity.get("layout"),proof.get("layout"));proof.addProperty("id",entity.get("id").getAsString());proofs.add(proof);
                    for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                        int top=index(map,x,y,dx,dz),base=index(map,x,y+1,dx,dz);assertEquals(ShapeKey.Kind.CUBE,before.open()[base][0].kind());assertNull(after.open()[top]);assertEquals(0,after.blocks()[top]);assertEquals(4,after.blocks()[base]);assertEquals(4,after.open()[base].length);for(ShapeKey s:after.open()[base])assertEquals(ShapeKey.Kind.CELL,s.kind());
                    }windows++;
                }
            }
            if(!proofs.isEmpty())save(family,proofs);JsonObject report=new JsonObject();report.addProperty("candidate_updates_sha256",sha(Files.readAllBytes(folder.resolve("updates.json"))));report.add("occurrences",routing);EmeraldSourceStackFollowupsTest.save(family+"/runtime-routing-proof.json",report);
        }
        assertEquals(19,windows);assertEquals(37,walkKeys);assertEquals(9,removedKeys);
    }
    @Test void dayCareCounterPlantPenAndNewTerminalCapPreserveActualSourceKinds()throws Exception{
        Path folder=STUDY.resolve("day-care");assumeTrue(Files.exists(folder.resolve("additions.json")));
        var source=EmeraldSourceStackFollowupsTest.source();SceneMap original=source.scene().map("EM_ROUTE117_POKEMON_DAY_CARE");SceneMap map=original.withHeadroom(Headroom.rooms(source.scene(),source.probe(),9).get(original.id()));
        JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy();for(String file:List.of("updates.json","additions.json"))for(var e:read(folder.resolve(file)).getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());
        byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current);
        for(int[] p:new int[][]{{4,2},{0,3},{1,3},{2,3},{3,3}})for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
            int i=index(map,p[0],p[1],dx,dz);assertEquals(CellKind.COUNTER,map.kind(p[0],p[1]));assertEquals(1,before.blocks()[i]);assertEquals(1,after.blocks()[i]);assertEquals(ShapeKey.Kind.CUBE,sourceColumn(before,map,p[0]*2+dx,p[1]*2+dz)[0].kind());assertEquals(ShapeKey.Kind.CELL,after.open()[i][0].kind());
        }
        for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
            int top=index(map,10,0,dx,dz),pc=index(map,10,1,dx,dz),foot=index(map,10,2,dx,dz);assertEquals(ShapeKey.Kind.CUBE,sourceColumn(before,map,20+dx,dz)[0].kind());assertNull(before.open()[foot]);assertNull(after.open()[top]);assertEquals(4,after.open()[pc].length);assertEquals(4,after.blocks()[pc]);assertEquals(0,after.blocks()[foot]);assertTrue(after.decor()[foot]>0);
            for(int y:new int[]{5,7}){assertNull(after.open()[index(map,0,y,dx,dz)]);assertEquals(4,after.blocks()[index(map,0,y+1,dx,dz)]);}
            for(int y:new int[]{7,8})assertEquals(2,after.blocks()[index(map,6,y,dx,dz)]);
        }
        JsonArray rows=new JsonArray();for(JsonElement e:JsonParser.parseString(Files.readString(folder.resolve("assemblies.json"))).getAsJsonArray()){
            JsonObject spec=e.getAsJsonObject();String id=spec.get("id").getAsString();if(!id.endsWith("counter")&&!id.endsWith("terminal"))continue;JsonArray rect=spec.getAsJsonArray("rect");int x=rect.get(0).getAsInt(),y=rect.get(1).getAsInt(),w=rect.get(2).getAsInt(),d=rect.get(3).getAsInt();JsonObject proof=rectangle(map,x,y,w,d,before,after);assertEquals(spec.get("source_layout"),proof.get("layout"));proof.addProperty("id",id);
            if(spec.has("model_layout")){JsonArray mask=new JsonArray();for(JsonElement line:spec.getAsJsonArray("model_layout")){JsonArray a=new JsonArray();for(JsonElement cell:line.getAsJsonArray())a.add(!cell.isJsonNull());mask.add(a);}proof.add("source_mask",mask);for(String field:List.of("original_baseline_fragments","corrected_fragments")){JsonArray filtered=new JsonArray();for(JsonElement f:proof.getAsJsonArray(field)){var r=f.getAsJsonObject();if(mask.get(r.get("z").getAsInt()/2).getAsJsonArray().get(r.get("x").getAsInt()/2).getAsBoolean())filtered.add(f);}proof.add(field,filtered);}}
            rows.add(proof);
        }
        assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(2,rows.size());save("day-care",rows);
    }
    @Test void route113GlassWorkshopClaimsExactFootprintAndKeepsWalkColumnsNoncolliding()throws Exception{
        Path folder=STUDY.resolve("building-glass-workshop");assumeTrue(Files.exists(folder.resolve("updates.json")));
        var source=EmeraldSourceStackFollowupsTest.source();SceneMap map=source.scene().map("EM_ROUTE113");JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy();
        JsonObject spec=read(folder.resolve("updates.json")).getAsJsonObject("buildings").getAsJsonObject("emerald_object_route113_glass_workshop");assertNotNull(spec);current.getAsJsonArray("buildings").add(spec);
        byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true);
        var placed=after.templates().stream().filter(t->t.x0()==64&&t.y0()==4).toList();assertEquals(1,placed.size());assertEquals(8,placed.get(0).entry().get("w").getAsInt());assertEquals(8,placed.get(0).entry().get("d").getAsInt());
        for(int y=0;y<4;y++)for(int x=0;x<4;x++)for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
            int i=index(map,32+x,2+y,dx,dz);assertNotNull(after.open()[i]);assertEquals(7,after.open()[i].length);
            if(map.kind(32+x,2+y)==CellKind.WALK){assertEquals(0,after.blocks()[i]);assertEquals(0,after.shapeMask()[i]);assertEquals(127,after.decor()[i]);}
            else{assertEquals(CellKind.SOLID,map.kind(32+x,2+y));assertEquals(7,after.blocks()[i]);}
        }
        assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());JsonObject proof=rectangle(map,32,2,4,4,before,after);proof.addProperty("id",spec.get("id").getAsString());JsonArray rows=new JsonArray();rows.add(proof);save("building-glass-workshop",rows);
    }
    @Test void dewfordPalmAliasRecoversOnlySourceWalkCrownsOnTheirActualSolidBases()throws Exception{
        Path folder=STUDY.resolve("towns-dewford");assumeTrue(Files.exists(folder.resolve("metadata-corrections.json")));
        JsonArray metadata=JsonParser.parseString(Files.readString(folder.resolve("metadata-corrections.json"))).getAsJsonArray();
        JsonObject evidence=metadata.get(0).getAsJsonObject();var source=EmeraldSourceStackFollowupsTest.source();
        JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"));
        for(var e:read(folder.resolve("updates.json")).getAsJsonObject("cells").entrySet())current.getAsJsonObject("cells").add(e.getKey(),e.getValue());
        current.getAsJsonObject("cells").getAsJsonObject("g3q/15876-15877-15878-15879").addProperty("stackFit",true);
        JsonArray rows=new JsonArray();List<Executable> checks=new ArrayList<>();
        for(JsonElement item:evidence.getAsJsonArray("witnesses")){
            JsonObject row=item.getAsJsonObject();SceneMap map=source.scene().map(row.get("map").getAsString());int x=row.get("x").getAsInt(),y=row.get("y").getAsInt();
            byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();assertEquals(CellKind.WALK,map.kind(x,y));assertEquals(CellKind.SOLID,map.kind(x,y+1));
            var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true);
            JsonObject proof=rectangle(map,x,y,1,2,before,after);rows.add(proof);
            for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                int top=index(map,x,y,dx,dz),base=index(map,x,y+1,dx,dz);
                checks.add(()->{assertNull(before.open()[top]);assertNull(after.open()[top]);assertEquals(0,after.blocks()[top]);assertEquals(4,after.blocks()[base],map.id()+" "+x+","+y);assertEquals(4,after.open()[base].length);assertEquals(15876,after.open()[base][2].number(0));});
            }
            assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());
        }
        assertEquals(9,rows.size());save("towns-dewford",rows);assertAll(checks);
    }
    @Test void eliteWalkStairAndLandingCellsReportTheirActualRaisedRegionDispatch()throws Exception{
        Path stats=STUDY.resolve("generic-elite/source-stats.json");assumeTrue(Files.exists(stats));
        var source=EmeraldSourceStackFollowupsTest.source();var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));
        JsonObject table=read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"));JsonArray rows=new JsonArray();
        Set<Integer> selected=Set.of(64,65,74,75,76,77,78,79,80,81,85);int entries=0;
        for(JsonElement element:JsonParser.parseString(Files.readString(stats)).getAsJsonArray()){
            JsonObject item=element.getAsJsonObject();if(!item.get("group").getAsString().equals("building__elite_four")||!selected.contains(item.get("source_ordinal").getAsInt()))continue;entries++;
            for(JsonElement occurrence:item.getAsJsonArray("occurrences")){
                JsonObject place=occurrence.getAsJsonObject();String name=place.get("map").getAsString();int x=place.get("x").getAsInt(),y=place.get("y").getAsInt();
                var built=G3Built.map(bundle,name);var model=G3Cells.model(built,bundle.g3tiles(built.pair()),true,new Warnings());var levels=RaisedRegions.gen3(model,built,true);var layout=levels.layout();
                SceneMap map=source.scene().map(name);map=map.withHeadroom(Headroom.rooms(source.scene(),source.probe(),9).get(name));var plan=EmeraldSourceStackFollowupsTest.plan(map,source,table,true,layout);
                assertEquals(CellKind.WALK,map.kind(x,y));JsonObject proof=place.deepCopy();proof.addProperty("key",item.get("key").getAsString());proof.addProperty("ordinal",item.get("source_ordinal").getAsInt());proof.addProperty("raised_outcome",levels.outcome());
                var ramp=layout==null?null:layout.ramp(x,y);proof.addProperty("ramp",ramp!=null);if(ramp!=null){proof.addProperty("ramp_rise",ramp.rise());proof.addProperty("ramp_direction",ramp.dir().name());}
                int i=index(map,x,y,0,0);proof.addProperty("blocks",plan.blocks()[i]);proof.addProperty("decor_mask",plan.decor()[i]);proof.addProperty("keys",Arrays.toString(plan.open()[i]));rows.add(proof);
            }
        }
        assertEquals(11,entries);JsonObject proof=new JsonObject();proof.addProperty("selected_entries",entries);proof.add("occurrences",rows);EmeraldSourceStackFollowupsTest.save("generic-elite/stair-runtime-proof.json",proof);
    }
    @Test void exactInteriorArchesFitBuiltRoomsAndPreserveNativeClosedStoryLeaves()throws Exception{
        Path folder=STUDY.resolve("elite-arches");assumeTrue(Files.exists(folder.resolve("additions.json")));
        var source=EmeraldSourceStackFollowupsTest.source();Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);
        var bundle=ExportBundle.open(ROOT.resolve("local/minecraft/kantobridge/export/emerald"));
        JsonObject baseline=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json")),current=baseline.deepCopy();
        for(var e:read(folder.resolve("additions.json")).getAsJsonObject("buildings").entrySet())current.getAsJsonArray("buildings").add(e.getValue());
        JsonArray rows=new JsonArray();int total=0,closedLeaves=0;
        for(JsonElement value:read(folder.resolve("source-evidence.json")).getAsJsonArray("groups")){
            JsonObject group=value.getAsJsonObject();for(JsonElement item:group.getAsJsonArray("occurrences")){
                JsonObject occurrence=item.getAsJsonObject();SceneMap original=source.scene().map(occurrence.get("map").getAsString());SceneMap map=original.withHeadroom(rooms.get(original.id()));
                var built=G3Built.map(bundle,map.id());var tiles=bundle.g3tiles(built.pair());
                var model=G3Cells.model(built,tiles,true,new Warnings());
                var placed=new PlacedMap(new MapPlacement(map.id(),0,64,0,built.w(),built.h(),1,MapPlacement.Kind.INTERIOR,"BAKED","minecraft:plains",false,null),model);
                byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var swaps=List.copyOf(map.swapSites());
                var before=EmeraldSourceStackFollowupsTest.plan(map,source,baseline,true);
                var storiesBefore=G3StoryCells.plan(List.of(placed),Map.of(map.id(),built),bundle.g3sites(),(pair,mid)->tiles.quarters(mid),q->q,new Warnings());
                var after=EmeraldSourceStackFollowupsTest.plan(map,source,current,true);
                var storiesAfter=G3StoryCells.plan(List.of(placed),Map.of(map.id(),built),bundle.g3sites(),(pair,mid)->tiles.quarters(mid),q->q,new Warnings());
                assertEquals(storiesBefore.toJson(),storiesAfter.toJson());
                int x0=occurrence.get("x").getAsInt(),y0=occurrence.get("y").getAsInt();assertTrue(ShapeHeights.headroom(map)>=4);
                assertEquals(1,after.templates().stream().filter(p->p.x0()==x0*2&&p.y0()==y0*2).count());
                for(int y=0;y<2;y++)for(int x=0;x<3;x++)for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                    int i=index(map,x0+x,y0+y,dx,dz);assertEquals(4,after.open()[i].length);
                    if(map.kind(x0+x,y0+y)==CellKind.WALK){assertEquals(0,after.blocks()[i]);assertEquals(0,after.shapeMask()[i]);assertEquals(15,after.decor()[i]);}
                    else{assertEquals(CellKind.SOLID,map.kind(x0+x,y0+y));assertEquals(4,after.blocks()[i]);}
                }
                assertEquals(CellKind.WALK,map.kind(x0+1,y0+1));assertTrue(map.hasTag(x0+1,y0+1,CellTag.WARP));
                assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(swaps,map.swapSites());
                JsonObject proof=rectangle(map,x0,y0,3,2,before,after);assertEquals(group.get("layout"),proof.get("layout"));
                JsonArray collisions=new JsonArray();for(int y=0;y<2;y++){JsonArray line=new JsonArray();for(int x=0;x<3;x++)line.add(built.coll()[built.index(x0+x,y0+y)]);collisions.add(line);}
                assertEquals(occurrence.has("source_kinds")?occurrence.get("source_kinds"):group.get("source_kinds"),collisions);
                proof.add("source_collisions",collisions);JsonObject state=new JsonObject();state.addProperty("mode","elite-open-arch-v1");state.addProperty("sites_sha256",sha(Files.readAllBytes(ROOT.resolve("local/minecraft/kantobridge/export/emerald/g3sites/all.json"))));proof.add("built_source",state);
                JsonArray closed=new JsonArray();
                for(var site:bundle.g3sites().of(map.id()))if(site.x()==x0+1&&site.y()>=y0&&site.y()<y0+2){
                    var story=storiesAfter.get(map.id()+":"+site.x()+":"+site.y());assertNotNull(story);assertEquals(StoryCellTable.Pass.GROUND,story.built().pass());
                    var own=story.states().stream().filter(s->s.metatile()==site.own().metatile()).findFirst().orElseThrow();
                    assertEquals(StoryCellTable.Pass.SOLID,own.pass());assertEquals(HeightRules.INTERIOR_WALL,own.height());assertEquals(Arrays.stream(tiles.quarters(site.own().metatile())).boxed().toList(),own.art());
                    JsonObject leaf=new JsonObject();leaf.addProperty("x",site.x());leaf.addProperty("y",site.y());leaf.addProperty("metatile",own.metatile());leaf.addProperty("pass",own.pass().name());leaf.addProperty("height",own.height());leaf.add("native_quarter_art",new Gson().toJsonTree(own.art()));closed.add(leaf);closedLeaves++;
                }
                proof.add("native_closed_leaves_unchanged",closed);proof.addProperty("id",group.get("id").getAsString());rows.add(proof);total++;
            }
        }
        assertEquals(10,total);assertEquals(10,closedLeaves);save("elite-arches",rows);
    }
}
