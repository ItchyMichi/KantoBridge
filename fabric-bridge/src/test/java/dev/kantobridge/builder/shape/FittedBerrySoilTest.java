package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import dev.kantobridge.scene.*;
import dev.kantobridge.scene.profile.Profile;
import java.util.*;
import org.junit.jupiter.api.Test;

class FittedBerrySoilTest {
    static final int[] SOURCE={11463,11464,11465,11466};
    static SceneMap map(int[] tiles,List<SceneMap.SceneObject> objects){var base=ShapeFixtures.surface("SOIL","AA","AA");return new SceneMap(base.id(),base.index(),base.tileset(),"g3q",base.palette(),1,1,2,true,base.env(),tiles,null,base.kinds(),base.tags(),base.dirs(),null,base.ownBorder(),objects,List.of());}
    static SceneMap.SceneObject berry(){return new SceneMap.SceneObject("O17","OBJ_EVENT_GFX_BERRY_TREE",0,0,true,false,SceneMap.SceneObject.PERSON);}
    static FittedShapes.Building definition(String flag){List<FittedShapes.Building> values=new ArrayList<>();FittedShapes.parseBuildings("{\"buildings\":[{\"atlas\":\"g3q\",\"tiles\":[[11463,11464],[11465,11466]],\"height\":40,\"parts\":[{\"shape\":\"box\",\"x\":[0,16],\"z\":[3,16],\"y\":[0,4]}],\"terrain\":[],\"preserveTerrain\":true,\"preserveCarriers\":true,\"berrySoil\":"+flag+"}]}",values);return values.getFirst();}
    static Buildings.Claim claim(FittedShapes.Building b){return new Buildings.Claim(new Profile.Building(0,b.tiles(),List.of(),false,Set.of(),null),0,0,new boolean[]{true,true,true,true});}

    @Test void exactSourceObjectAndExplicitSoilFlagAreBothRequired(){String previous=FittedShapes.enter("emerald");try{
        var map=map(SOURCE,List.of(berry()));var source=FittedCarriersTest.nativeColumns(map);
        var soil=definition("true");assertNotNull(FittedCarriers.profile(map,claim(soil),soil,source,null,null));
        for(String flag:List.of("false","\"true\"","1","null")){var model=definition(flag);assertFalse(FittedShapes.berrySoilBuilding(model));assertNull(FittedCarriers.profile(map,claim(model),model,source,null,null));}
        for(String game:List.of("blue","crystal")){String old=FittedShapes.enter(game);try{assertFalse(FittedShapes.berrySoilBuilding(soil));assertNull(FittedCarriers.profile(map,claim(soil),soil,source,null,null));}finally{FittedShapes.leave(old);}}
    }finally{FittedShapes.leave(previous);}}

    @Test void changedSourceMissingOrAdditionalObjectAndEveryOtherObjectIdentityRefuse(){
        assertTrue(FittedBerrySoil.matches(map(SOURCE,List.of(berry())),0,0));
        assertFalse(FittedBerrySoil.matches(map(new int[]{11463,11464,11465,11467},List.of(berry())),0,0));
        assertFalse(FittedBerrySoil.matches(map(SOURCE,List.of()),0,0));assertFalse(FittedBerrySoil.matches(map(SOURCE,List.of(berry(),berry())),0,0));
        for(var other:List.of(new SceneMap.SceneObject("berry","OBJ_EVENT_GFX_BERRY_TREE",0,0,true,false,"person"),new SceneMap.SceneObject("O17","OBJ_EVENT_GFX_GIRL_1",0,0,true,false,"person"),new SceneMap.SceneObject("O17","OBJ_EVENT_GFX_BERRY_TREE",0,0,false,false,"person"),new SceneMap.SceneObject("O17","OBJ_EVENT_GFX_BERRY_TREE",0,0,true,true,"person"),new SceneMap.SceneObject("O17","OBJ_EVENT_GFX_BERRY_TREE",0,0,true,false,"item")))assertFalse(FittedBerrySoil.matches(map(SOURCE,List.of(other)),0,0));
    }

    @Test void aBerryOptInCannotMakeAnElevatedOrCarvedModelOrReplaceWalkAndStoryCells(){String old=FittedShapes.enter("emerald");try{
        for(String problem:List.of("tall","floating","carve","cone","walk","warp","story")){
            var model=definition("true");var map=map(SOURCE,List.of(berry()));var part=model.box().getAsJsonArray("parts").get(0).getAsJsonObject();
            if(problem.equals("tall"))part.add("y",JsonParser.parseString("[0,8]"));if(problem.equals("floating"))part.add("y",JsonParser.parseString("[1,4]"));if(problem.equals("carve"))part.addProperty("carve",true);if(problem.equals("cone"))part.addProperty("shape","cone");if(problem.equals("walk"))map.kinds()[0]=(byte)CellKind.WALK.id();if(problem.equals("warp"))map.tags()[0]=CellTag.WARP.bit();if(problem.equals("story"))map=ShapeFixtures.withSite(map,"berry",0,0,"TT","TT");
            assertNull(FittedCarriers.profile(map,claim(model),model,FittedCarriersTest.nativeColumns(map),null,null),problem);
        }
    }finally{FittedShapes.leave(old);}}
}
