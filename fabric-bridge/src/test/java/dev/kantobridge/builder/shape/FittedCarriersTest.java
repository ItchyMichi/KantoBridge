package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import dev.kantobridge.scene.*;
import dev.kantobridge.scene.profile.Profile;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FittedCarriersTest {
    @AfterEach void reset(){FittedShapes.useForTest(null);FittedShapes.useTablesForTest(null);FittedShapes.setEnabled(true);}
    static FittedShapes.Building definition(String flag, boolean terrainFlag){
        List<FittedShapes.Building> values=new ArrayList<>();
        FittedShapes.parseBuildings("{\"buildings\":[{\"atlas\":\"tilesets.fix\",\"tiles\":[[29,29,0,0],[29,29,0,0]],\"height\":40,\"parts\":[],\"terrain\":[],\"preserveTerrain\":"+terrainFlag+",\"preserveCarriers\":"+flag+"}]}",values);
        return values.get(0);
    }
    static Buildings.Claim claim(FittedShapes.Building b){boolean[] held=new boolean[8];Arrays.fill(held,true);return new Buildings.Claim(new Profile.Building(0,b.tiles(),List.of(),false,Set.of(),null),0,0,held);}
    static ShapePlanner.Columns nativeColumns(SceneMap map){
        int n=map.artCols()*map.artRows();ShapeKey[][] keys=new ShapeKey[n][];int[] heights=new int[n],mask=new int[n];
        for(int i=0;i<n;i++)if(map.kindAtTile(i%map.artCols(),i/map.artCols())==CellKind.SOLID){heights[i]=2+i%4;mask[i]=(1<<heights[i])-1;keys[i]=new ShapeKey[heights[i]];Arrays.fill(keys[i],ShapeKey.cube(0,1,1,1,1,1,1));}
        var result=new ShapePlanner.Columns(keys,heights,mask);for(int i=0;i<n;i++)result.floor()[i]=i+80;return result;
    }
    @Test void parserRequiresExactBooleanBothFlagsAndEmerald(){
        for(String flag:List.of("true","false","\"true\"","1","null"))for(boolean terrain:List.of(true,false))for(String game:List.of("emerald","blue","crystal")){
            var b=definition(flag,terrain);String old=FittedShapes.enter(game);try{assertEquals(flag.equals("true")&&terrain&&game.equals("emerald"),FittedShapes.preserveCarriersBuilding(b));}finally{FittedShapes.leave(old);}
        }
    }
    @Test void preservesMixedNativeHeightsZeroWalkAndEveryFloorWithoutTouchingOtherColumns(){
        String old=FittedShapes.enter("emerald");try{
            var map=ShapeFixtures.surface("FOREST","AA..BBBB","AA..BBBB");var b=definition("true",true);var claim=claim(b);var before=nativeColumns(map);
            int[] blocks=before.blocks().clone(),floors=before.floor().clone(),decor=before.decor().clone();ShapeKey[][] keys=before.keys().clone();
            var profile=FittedCarriers.profile(map,claim,b,before,null,null);assertNotNull(profile);
            var result=FittedCarriers.overlay(map,0,before,List.of(claim),Map.of(claim.building(),b),Map.of(claim,profile),new TreeMap<>());
            assertArrayEquals(blocks,result.blocks());assertArrayEquals(floors,result.floor());assertArrayEquals(decor,result.decor());assertEquals(1,result.templates().size());
            for(int z=0;z<2;z++)for(int x=0;x<8;x++){int i=z*8+x;if(x<2)for(var k:result.keys()[i])assertEquals(ShapeKey.Kind.TMPL,k.kind());else assertArrayEquals(keys[i],result.keys()[i]);}
            var hull=result.templates().get(0).entry().getAsJsonObject("hull");assertEquals(2,hull.getAsJsonArray("solid").get(0).getAsInt());assertEquals(3,hull.getAsJsonArray("solid").get(1).getAsInt());assertEquals(0,hull.getAsJsonArray("solid").get(2).getAsInt());
        }finally{FittedShapes.leave(old);}
    }
    @Test void refusesEveryUnsupportedNativeCarrierRatherThanChangingItsCollision(){
        String old=FittedShapes.enter("emerald");try{for(String problem:List.of("counter","warp","story","owned","hung","decor","tooHigh","unknownShape","walkSolid","maskAboveHeight","shortKeys","indoor")){
            var map=problem.equals("indoor")?ShapeFixtures.interior("FOREST","AA..","AA.."):ShapeFixtures.surface("FOREST","AA..","AA..");
            if(problem.equals("counter"))map.kinds()[0]=(byte)CellKind.COUNTER.id();if(problem.equals("warp"))map.tags()[0]=CellTag.WARP.bit();
            if(problem.equals("story"))map=ShapeFixtures.withSite(map,"cut",0,0,"TT","TT");
            var b=definition("true",true);var source=nativeColumns(map);
            switch(problem){case "owned"->source.modelled()[0]=true;case "hung"->source.hungMask()[0]=1;case "decor"->source.decor()[0]=1;case "tooHigh"->source.blocks()[0]=6;case "unknownShape"->source.keys()[0][0]=ShapeKey.slab(0,1,4);case "walkSolid"->source.blocks()[2]=1;case "maskAboveHeight"->source.shapeMask()[0]=255;case "shortKeys"->source.keys()[0]=new ShapeKey[1];default->{}}
            assertNull(FittedCarriers.profile(map,claim(b),b,source,null,null),problem);
        }}finally{FittedShapes.leave(old);}
    }

    @Test void untaggedStationarySourceObjectsCannotBeMistakenForOrdinaryForest(){
        String old=FittedShapes.enter("emerald");try{
            var map=ShapeFixtures.surface("BERRY","AA..","AA..");
            var berry=new SceneMap.SceneObject("berry tree","GFX_BERRY_TREE",0,0,true,false,SceneMap.SceneObject.PERSON);
            var occupied=new SceneMap(map.id(),map.index(),map.tileset(),map.atlas(),map.palette(),map.w(),map.h(),map.perCell(),map.surface(),map.env(),map.tiles(),map.closedTiles(),map.kinds(),map.tags(),map.dirs(),map.border(),map.ownBorder(),List.of(berry),map.swapSites());
            assertEquals(0,occupied.tags(0,0));assertTrue(occupied.swapSites().isEmpty());
            var b=definition("true",true);assertNull(FittedCarriers.profile(occupied,claim(b),b,nativeColumns(occupied),null,null));
        }finally{FittedShapes.leave(old);}
    }
}
