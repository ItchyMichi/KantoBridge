package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.google.gson.*;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.Headroom;
import dev.kantobridge.scene.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Actual exported PC and mixed-collision conifer placements, not synthetic guessed topology. */
class EmeraldSourceStackFollowupsTest {
    static final Path ROOT=TestData.gametestResources().toAbsolutePath().resolve("../../../..").normalize();
    static final Path STUDY=ROOT.resolve("local/model-gallery/emerald-pass-study");
    record Source(Scene scene,ArtUnitProbe probe) {}
    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);}
    static JsonObject read(Path path)throws Exception{return JsonParser.parseString(Files.readString(path)).getAsJsonObject();}
    static Source source()throws Exception{
        Path export=ROOT.resolve("local/minecraft/kantobridge/export/emerald");
        assumeTrue(Files.exists(export.resolve("index.json")),"authorized local export absent");
        ExportBundle bundle=ExportBundle.open(export);
        return new Source(FrontEnds.forGame("emerald").describe(bundle),new ArtUnitProbe(FrontEnds.artSource(bundle)));
    }
    static ShapePlanner.MapShapes plan(SceneMap map,Source source,JsonObject table){
        return plan(map,source,table,false);
    }
    static ShapePlanner.MapShapes plan(SceneMap map,Source source,JsonObject table,boolean withBuildings){
        return plan(map,source,table,withBuildings,null);
    }
    static ShapePlanner.MapShapes plan(SceneMap map,Source source,JsonObject table,boolean withBuildings,dev.kantobridge.world.MapLayout layout){
        String text=table.toString();var cells=new TreeMap<String,FittedShapes.Entry>();var stacks=new TreeMap<String,String>();
        var layers=new TreeMap<String,List<FittedShapes.Layer>>();var aliases=new TreeMap<String,Set<String>>();
        var seats=new HashSet<String>();var walks=new HashSet<String>();var counters=new HashSet<String>();var stackFits=new HashSet<String>();
        FittedShapes.parse(text,cells);FittedShapes.parseStacks(text,stacks);FittedShapes.parseLayers(text,layers);
        FittedShapes.parseAlsoStacks(text,aliases);FittedShapes.parseSeats(text,seats);FittedShapes.parseFlagged(text,"walk",walks);FittedShapes.parseCounters(text,counters);
        FittedShapes.parseStackFits(text,stackFits);
        var buildings=new ArrayList<FittedShapes.Building>();if(withBuildings)FittedShapes.parseBuildings(text,buildings);
        FittedShapes.useTablesForTest(Map.of("emerald",new FittedShapes.Table(cells,Map.of(),stacks,seats,layers,buildings,walks,aliases,counters,stackFits)));
        String previous=FittedShapes.enter("emerald");
        try{return ShapePlanner.planMap(map,source.scene().atlas(map.atlas()),0,ShapeParams.current(true),null,source.probe(),new TreeMap<>(),layout);}
        finally{FittedShapes.leave(previous);}
    }
    static int column(SceneMap map,int x,int y,int dx,int dz){return (y*2+dz)*map.artCols()+x*2+dx;}
    static void save(String filename,JsonObject proof)throws Exception{Files.writeString(STUDY.resolve(filename),new GsonBuilder().setPrettyPrinting().create().toJson(proof)+"\n");}
    static JsonArray fragments(ShapePlanner.MapShapes plan,SceneMap map,int x,int topY){
        JsonArray result=new JsonArray();
        for(int row=0;row<2;row++)for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
            ShapeKey[] keys=plan.open()[column(map,x,topY+row,dx,dz)];if(keys==null)continue;
            for(int y=0;y<keys.length;y++){JsonObject f=new JsonObject();f.addProperty("key",keys[y].toString());f.addProperty("palette","BAKED");f.addProperty("x",dx);f.addProperty("y",y);f.addProperty("z",row*2+dz);result.add(f);}
        }
        return result;
    }

    @Test void meteorFallsFormationUsesMeasuredCaveHeadroom()throws Exception{
        Source source=source();SceneMap map=source.scene().map("EM_METEOR_FALLS_1F_1R");
        int headroom=Headroom.rooms(source.scene(),source.probe(),9).get(map.id());
        JsonArray cells=new JsonArray();
        for(int y=10;y<=14;y++)for(int x=19;x<=20;x++){
            assertEquals(CellKind.SOLID,map.kind(x,y));
            JsonObject cell=new JsonObject();cell.addProperty("x",x);cell.addProperty("y",y);
            cell.addProperty("kind",map.kind(x,y).name());cells.add(cell);
        }
        assertTrue(headroom<10,"The five-row mineral cannot be represented as a ten-carrier stack");
        JsonObject proof=new JsonObject();proof.addProperty("map",map.id());proof.addProperty("headroom",headroom);
        proof.addProperty("source_rows",5);proof.addProperty("ordinary_stack_carriers",10);proof.add("cells",cells);
        save("meteor-falls-formation-headroom.json",proof);
    }

    @Test void completeMineralAndFlagTemplatesClaimExactSourceAndKeepWalkColumnsDecorative()throws Exception{
        Path folder=STUDY.resolve("environment-templates");assumeTrue(Files.exists(folder.resolve("additions.json")));
        Source source=source();JsonObject additions=read(folder.resolve("additions.json"));
        JsonArray groups=read(folder.resolve("source-evidence.json")).getAsJsonArray("groups");
        JsonObject table=new JsonObject();table.add("cells",new JsonObject());JsonArray buildings=new JsonArray();
        for(var e:additions.getAsJsonObject("buildings").entrySet())buildings.add(e.getValue());table.add("buildings",buildings);
        Map<String,Integer> rooms=Headroom.rooms(source.scene(),source.probe(),9);
        Map<String,ShapePlanner.MapShapes> plans=new HashMap<>();JsonArray proofRows=new JsonArray();int minerals=0,flags=0;List<Executable> checks=new ArrayList<>();
        for(JsonElement element:groups){JsonObject group=element.getAsJsonObject();String id=group.get("id").getAsString();
            JsonObject spec=additions.getAsJsonObject("buildings").getAsJsonObject(id);
            int high=(spec.get("height").getAsInt()+7)/8;JsonArray layout=group.getAsJsonArray("layout");
            int width=layout.get(0).getAsJsonArray().size(),depth=layout.size();
            for(JsonElement occurrence:group.getAsJsonArray("occurrences")){
                JsonObject row=occurrence.getAsJsonObject();String name=row.get("map").getAsString();
                SceneMap original=source.scene().map(name);SceneMap map=rooms.containsKey(name)?original.withHeadroom(rooms.get(name)):original;
                byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone(),tiles=map.tiles().clone();var swaps=List.copyOf(map.swapSites());
                var planned=plans.computeIfAbsent(name,n->plan(map,source,table,true));
                int x0=row.get("x").getAsInt(),y0=row.get("y").getAsInt();
                var matches=planned.templates().stream().filter(p->p.x0()==x0*2&&p.y0()==y0*2).toList();
                JsonObject proof=row.deepCopy();proof.addProperty("id",id);proof.addProperty("headroom",ShapeHeights.headroom(map));proof.addProperty("template_carriers",high);proof.addProperty("actual_template_count",matches.size());proof.addProperty("outdoors",map.outdoors());proof.addProperty("source_semantics_unchanged",true);proofRows.add(proof);
                if(group.get("family").getAsString().startsWith("mineral"))minerals++;else flags++;
                checks.add(()->assertEquals(1,matches.size(),id+" at "+name+" "+x0+","+y0));
                if(matches.isEmpty())continue;
                JsonObject placed=matches.get(0).entry();
                assertEquals(width*2,placed.get("w").getAsInt());assertEquals(depth*2,placed.get("d").getAsInt());
                assertTrue(high<=ShapeHeights.headroom(map),"template fits measured headroom: "+id);
                for(int y=0;y<depth;y++)for(int x=0;x<width;x++){
                    int raw=group.getAsJsonArray("source_kinds").get(y).getAsJsonArray().get(x).getAsInt();
                    CellKind expected=raw==0?CellKind.WALK:CellKind.SOLID;assertEquals(expected,map.kind(x0+x,y0+y));
                    String key=layout.get(y).getAsJsonArray().get(x).getAsString();String[] numbers=key.substring(4).split("-");
                    for(int z=0;z<2;z++)for(int dx=0;dx<2;dx++){
                        assertEquals(Integer.parseInt(numbers[z*2+dx]),map.tile((x0+x)*2+dx,(y0+y)*2+z));
                        int i=column(map,x0+x,y0+y,dx,z);assertEquals(high,planned.open()[i].length);
                        if(expected==CellKind.WALK){assertEquals(0,planned.blocks()[i]);assertEquals(0,planned.shapeMask()[i]);assertEquals((1<<high)-1,planned.decor()[i]);}
                        else {assertEquals(high,planned.blocks()[i]);assertEquals((1<<high)-1,planned.shapeMask()[i]);assertEquals(0,planned.decor()[i]);}
                    }
                }
                assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertArrayEquals(tiles,map.tiles());assertEquals(swaps,map.swapSites());
            }
        }
        assertEquals(14,minerals);assertEquals(32,flags);JsonObject proof=new JsonObject();proof.addProperty("minerals",minerals);proof.addProperty("flags",flags);proof.add("occurrences",proofRows);
        save("environment-templates/runtime-proof.json",proof);assertAll(checks);
    }

    @Test void weatherRimStacksOnActualInteractivePcAndWalkFootStaysNoncolliding()throws Exception{
        Path patchPath=STUDY.resolve("weather-terminal-correction/updates.json");assumeTrue(Files.exists(patchPath));
        Source source=source();SceneMap map=source.scene().map("EM_ROUTE119_WEATHER_INSTITUTE_1F");
        int headroom=Headroom.rooms(source.scene(),source.probe(),9).get(map.id());map=map.withHeadroom(headroom);
        byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();
        JsonObject beforeTable=read(ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json"));
        JsonObject afterTable=beforeTable.deepCopy();JsonObject patch=read(patchPath).getAsJsonObject("cells");
        for(var e:patch.entrySet())afterTable.getAsJsonObject("cells").add(e.getKey(),e.getValue());
        ShapePlanner.MapShapes before=plan(map,source,beforeTable),after=plan(map,source,afterTable);
        assertEquals(CellKind.SOLID,map.kind(2,0));assertEquals(CellKind.SOLID,map.kind(2,1));assertEquals(CellKind.WALK,map.kind(2,2));
        for(int z=0;z<2;z++)for(int x=0;x<2;x++){
            int top=column(map,2,0,x,z),body=column(map,2,1,x,z),foot=column(map,2,2,x,z);
            assertNotNull(before.open()[top]);assertNotNull(before.open()[body]);assertNull(before.open()[foot]);
            assertNull(after.open()[top]);assertEquals(0,after.blocks()[top]);
            assertEquals(4,after.blocks()[body]);assertEquals(4,after.open()[body].length);
            assertEquals(6557,after.open()[body][2].number(0));
            assertEquals(0,after.blocks()[foot]);assertEquals(0,after.shapeMask()[foot]);assertEquals(1,after.decor()[foot]);assertEquals(1,after.open()[foot].length);
        }
        assertEquals(beforeTable.getAsJsonObject("cells").get("g3q/9-10-11-12"),afterTable.getAsJsonObject("cells").get("g3q/9-10-11-12"));
        assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());
        JsonObject proof=new JsonObject();proof.addProperty("map",map.id());proof.addProperty("headroom",headroom);proof.addProperty("baseline_cap_and_pc_independent",true);proof.addProperty("baseline_walk_foot_absent",true);proof.addProperty("corrected_pc_carriers",4);proof.addProperty("corrected_foot_collision_carriers",0);proof.addProperty("corrected_foot_decor_carriers",1);save("weather-terminal-correction/runtime-proof.json",proof);
    }

    @Test void exactFortreeAliasesRecoverFourWalkCrownsAndJoinFifteenSolidPairs()throws Exception{
        Path evidence=STUDY.resolve("tree-followups/metadata-evidence.json"),patchPath=STUDY.resolve("tree-followups/updates.json");
        assumeTrue(Files.exists(evidence)&&Files.exists(patchPath));Source source=source();
        JsonArray rows=new JsonArray();for(JsonElement row:JsonParser.parseString(Files.readString(evidence)).getAsJsonArray())
            if(row.getAsJsonObject().get("field").getAsString().equals("alsoOn"))rows.add(row);
        JsonObject beforeTable=read(ROOT.resolve("fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json"));
        Path originalPath=ROOT.resolve("local/model-gallery/emerald-full-pass-baseline.json");JsonObject originalTable=read(originalPath);
        JsonObject afterTable=beforeTable.deepCopy();JsonObject patch=read(patchPath).getAsJsonObject("cells");
        for(JsonElement value:rows){JsonObject row=value.getAsJsonObject();String key=row.get("key").getAsString();
            beforeTable.getAsJsonObject("cells").getAsJsonObject(key).add("alsoOn",row.get("old_value"));
            beforeTable.getAsJsonObject("cells").getAsJsonObject(key).remove("stackFit");
            afterTable.getAsJsonObject("cells").add(key,patch.get(key));
        }
        Map<String,ShapePlanner.MapShapes> befores=new HashMap<>(),afters=new HashMap<>(),originals=new HashMap<>();int walk=0,solid=0;
        JsonArray proofRows=new JsonArray();List<Executable> checks=new ArrayList<>();Map<String,Set<Integer>> expectedChangedColumns=new HashMap<>();
        for(JsonElement value:rows)for(JsonElement occurrence:value.getAsJsonObject().getAsJsonArray("occurrences")){
            JsonObject row=occurrence.getAsJsonObject();String name=row.get("map").getAsString();SceneMap map=source.scene().map(name);
            byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();
            var before=befores.computeIfAbsent(name,n->plan(map,source,beforeTable));var after=afters.computeIfAbsent(name,n->plan(map,source,afterTable));
            assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());
            int x=row.get("x").getAsInt(),y=row.get("y").getAsInt();int upper=row.get("upper_collision").getAsInt();
            assertEquals(CellKind.SOLID,map.kind(x,y));assertEquals(upper==0?CellKind.WALK:CellKind.SOLID,map.kind(x,y-1));
            if(upper==0)walk++;else solid++;
            JsonObject proof=row.deepCopy();JsonArray columns=new JsonArray();
            var original=originals.computeIfAbsent(name,n->plan(map,source,originalTable));
            proof.add("original_baseline_fragments",fragments(original,map,x,y-1));proof.add("pre_alias_fragments",fragments(before,map,x,y-1));proof.add("corrected_fragments",fragments(after,map,x,y-1));
            for(int z=0;z<2;z++)for(int dx=0;dx<2;dx++){
                int ti=column(map,x,y-1,dx,z),bi=column(map,x,y,dx,z);
                expectedChangedColumns.computeIfAbsent(name,n->new HashSet<>()).addAll(List.of(ti,bi));
                JsonObject col=new JsonObject();col.addProperty("dx",dx);col.addProperty("dz",z);col.addProperty("before_top",Arrays.toString(before.open()[ti]));col.addProperty("before_base",Arrays.toString(before.open()[bi]));col.addProperty("after_top",Arrays.toString(after.open()[ti]));col.addProperty("after_base",Arrays.toString(after.open()[bi]));col.addProperty("before_top_blocks",before.blocks()[ti]);col.addProperty("after_top_blocks",after.blocks()[ti]);col.addProperty("after_base_blocks",after.blocks()[bi]);columns.add(col);
                checks.add(()->{
                if(upper==0)assertNull(before.open()[ti]);else assertNotNull(before.open()[ti]);
                assertNotNull(before.open()[bi]);assertNull(after.open()[ti]);assertEquals(0,after.blocks()[ti]);
                assertEquals(4,after.open()[bi].length);assertEquals(4,after.blocks()[bi]);
                assertEquals(Integer.parseInt(row.get("upper_key").getAsString().substring(4).split("-")[0]),after.open()[bi][2].number(0));
                });
            }
            proof.addProperty("baseline_walk_upper_omitted",upper==0);proof.addProperty("expected_corrected_base_carriers",4);proof.add("actual_columns",columns);proofRows.add(proof);
        }
        assertEquals(4,walk);assertEquals(15,solid);
        // Existing aliases on these same source keys are checked too: the opt-in must not change other placements.
        for(SceneMap map:source.scene().maps().values()){
            boolean candidate=false;
            for(int y=0;y+1<map.h()&&!candidate;y++)for(int x=0;x<map.w()&&!candidate;x++){
                String top=FittedShapes.key(map.atlas(),new int[]{map.tile(x*2,y*2),map.tile(x*2+1,y*2),map.tile(x*2,y*2+1),map.tile(x*2+1,y*2+1)});
                if(!patch.has(top)||!patch.getAsJsonObject(top).has("alsoOn"))continue;
                String base=FittedShapes.key(map.atlas(),new int[]{map.tile(x*2,y*2+2),map.tile(x*2+1,y*2+2),map.tile(x*2,y*2+3),map.tile(x*2+1,y*2+3)});
                for(JsonElement alias:patch.getAsJsonObject(top).getAsJsonArray("alsoOn"))if(alias.getAsString().equals(base))candidate=true;
            }
            if(!candidate)continue;
            var before=befores.computeIfAbsent(map.id(),n->plan(map,source,beforeTable));var after=afters.computeIfAbsent(map.id(),n->plan(map,source,afterTable));
            Set<Integer> allowed=expectedChangedColumns.getOrDefault(map.id(),Set.of());
            for(int i=0;i<before.open().length;i++)if(!allowed.contains(i)){
                int column=i;checks.add(()->{assertArrayEquals(before.open()[column],after.open()[column],map.id()+" unrelated column "+column);
                    assertEquals(before.blocks()[column],after.blocks()[column]);assertEquals(before.shapeMask()[column],after.shapeMask()[column]);assertEquals(before.decor()[column],after.decor()[column]);assertEquals(before.floor()[column],after.floor()[column]);});
            }
        }
        JsonObject proof=new JsonObject();proof.addProperty("original_baseline_sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(originalPath))));proof.addProperty("walk_upper_pairs",walk);proof.addProperty("solid_upper_pairs",solid);proof.addProperty("unrelated_alias_maps_checked",befores.size());proof.add("occurrences",proofRows);save("tree-followups/runtime-proof.json",proof);assertAll(checks);
    }
}
