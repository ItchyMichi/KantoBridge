package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.*;
import dev.kantobridge.scene.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Only explicit, low interior parts counters keep their native one-block collider. */
class EmeraldLowCounterTemplateTest {
    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);}
    static FittedShapes.Table table(SceneMap map,int x0,int y0,int width,int depth,int height,boolean interior){
        JsonObject entry=new JsonObject();entry.addProperty("atlas",map.atlas());entry.addProperty("height",height);entry.addProperty("interior",interior);entry.add("terrain",new JsonArray());JsonArray tiles=new JsonArray();
        for(int y=0;y<depth*2;y++){JsonArray row=new JsonArray();for(int x=0;x<width*2;x++)row.add(map.tile(x0*2+x,y0*2+y));tiles.add(row);}entry.add("tiles",tiles);
        JsonObject part=JsonParser.parseString("{\"shape\":\"box\",\"x\":[0,"+(width*16)+"],\"z\":[0,"+(depth*16)+"],\"y\":[0,"+height+"],\"paint\":{\"front\":[0,0,1,1],\"top\":[0,0,1,1],\"side\":[0,0,1,1],\"back\":[0,0,1,1]}}").getAsJsonObject();JsonArray parts=new JsonArray();parts.add(part);entry.add("parts",parts);JsonObject root=new JsonObject();JsonArray buildings=new JsonArray();buildings.add(entry);root.add("buildings",buildings);List<FittedShapes.Building> parsed=new ArrayList<>();FittedShapes.parseBuildings(root.toString(),parsed);assertEquals(1,parsed.size());return new FittedShapes.Table(Map.of(),Map.of(),Map.of(),Set.of(),Map.of(),parsed);
    }
    static ShapePlanner.MapShapes plan(SceneMap map,String game,int height,boolean interior){
        FittedShapes.useTablesForTest(Map.of(game,table(map,0,0,map.w(),map.h(),height,interior)));String previous=FittedShapes.enter(game);
        try{return ShapePlanner.planMap(map,ShapeFixtures.TIER_B,0,ShapeParams.current(true));}finally{FittedShapes.leave(previous);}
    }
    @Test void lowInteriorPartsKeepCounterAtOneAndSolidEndsAtTwo(){
        SceneMap map=ShapeFixtures.interior("ROOM","AA====","AA====").withHeadroom(9);byte[] kinds=map.kinds().clone();int[] tags=map.tags().clone();var after=plan(map,"emerald",8,true);assertEquals(1,after.templates().size());
        for(int y=0;y<2;y++)for(int x=0;x<6;x++){int i=y*6+x;assertEquals(x<2?2:1,after.blocks()[i]);assertEquals(x<2?3:1,after.shapeMask()[i]);assertEquals(x<2?2:1,after.open()[i].length);assertEquals(0,after.decor()[i]);}
        assertArrayEquals(kinds,map.kinds());assertArrayEquals(tags,map.tags());assertEquals(CellKind.COUNTER,map.kind(1,0));
    }
    @Test void tallerOutdoorUnflaggedAndOtherGamesKeepExistingBehavior(){
        SceneMap room=ShapeFixtures.interior("ROOM","AA====","AA====").withHeadroom(9);
        for(int height:List.of(9,16,24)){var after=plan(room,"emerald",height,true);for(int count:after.blocks())assertEquals(Math.max(2,(height+7)/8),count);}
        var outdoor=plan(ShapeFixtures.surface("OUT","AA====","AA===="),"emerald",8,true);for(int count:outdoor.blocks())assertEquals(2,count);
        for(String game:List.of("emerald","blue","crystal")){var nativePlan=plan(room,game,8,false);var flagged=plan(room,game,8,true);assertEquals(0,nativePlan.templates().size());if(!game.equals("emerald")){assertEquals(0,flagged.templates().size());assertArrayEquals(nativePlan.blocks(),flagged.blocks());assertArrayEquals(nativePlan.decor(),flagged.decor());assertArrayEquals(nativePlan.open(),flagged.open());}}
    }
    @Test void worldPlanWritesOneTemplateColliderAndNoExtraUpperCarrier()throws Exception{
        var bundle=ExportBundle.open(TestData.syntheticGen3Bundle());var options=new WorldPlanner.Options(null,TestData.G3_ORIGIN_X,TestData.G3_ORIGIN_Z,TestData.MIN_BUILD_HEIGHT,true);
        FittedShapes.useTablesForTest(Map.of("emerald",new FittedShapes.Table(Map.of(),Map.of())));WorldPlan before=WorldPlanner.plan(bundle,options);SceneMap map=before.scene().map("EM_TEST_HOUSE");FittedShapes.useTablesForTest(Map.of("emerald",table(map,1,1,1,1,8,true)));WorldPlan after=WorldPlanner.plan(bundle,options);var p=after.index().map(map.id());ChunkCodesView old=new ChunkCodesView(before),now=new ChunkCodesView(after);
        assertArrayEquals(map.kinds(),after.scene().map(map.id()).kinds());assertArrayEquals(map.tags(),after.scene().map(map.id()).tags());assertEquals(MapModel.Kind.COUNTER,after.maps().stream().filter(m->m.id().equals(map.id())).findFirst().orElseThrow().model().kind(1,1));
        for(int z=0;z<2;z++)for(int x=0;x<2;x++){int bx=p.x()+2+x,bz=p.z()+2+z;assertEquals(old.code(bx,p.y()-1,bz),now.code(bx,p.y()-1,bz));assertEquals(BlockCode.KIND_SHAPE,now.kind(bx,p.y(),bz));assertEquals(old.code(bx,p.y()+1,bz),now.code(bx,p.y()+1,bz));assertEquals(ShapeKey.Kind.TMPL,after.shapes().index().key(after.shapes().map().shapeAt(bx,p.y(),bz)).kind());}
    }
}
