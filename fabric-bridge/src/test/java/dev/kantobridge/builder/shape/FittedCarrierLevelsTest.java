package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import dev.kantobridge.scene.*;
import dev.kantobridge.world.MapLayout;
import java.util.*;
import org.junit.jupiter.api.Test;

class FittedCarrierLevelsTest {
    static JsonObject box(){
        return JsonParser.parseString("{\"carrierLevels\":[[0,6]],\"parts\":[{\"shape\":\"box\",\"x\":[1,15],\"z\":[1,15],\"y\":[0,12]},{\"shape\":\"cone\",\"center\":[24,8],\"radius\":[7,7],\"y\":[0,20]}]}").getAsJsonObject();
    }
    static Buildings.Claim claim(){var b=FittedCarriersTest.definition("true",true);return FittedCarriersTest.claim(b);}
    @Test void exactRaisedWitnessAndCommonTranslationPreserveFlatCompatibility(){
        var map=ShapeFixtures.surface("LEVELS","AABB","AABB");var claim=claim();var box=box();
        for(int[] levels:new int[][]{{0,6},{3,9},{0,0},{7,7}})assertTrue(FittedCarrierLevels.valid(map,claim,box,MapLayout.flat(2,1,levels,List.of())));
        assertTrue(FittedCarrierLevels.valid(map,claim,box,null));
        assertFalse(FittedCarrierLevels.valid(map,claim,box,MapLayout.flat(2,1,new int[]{6,0},List.of())));
        assertFalse(FittedCarrierLevels.valid(map,claim,box,MapLayout.flat(2,1,new int[]{0,5},List.of())));
        box.remove("carrierLevels");assertFalse(FittedCarrierLevels.valid(map,claim,box,MapLayout.flat(2,1,new int[]{0,6},List.of())));
    }
    @Test void malformedWitnessOrPartsAcrossUnequalLevelsAreRefused(){
        var map=ShapeFixtures.surface("LEVELS","AABB","AABB");var claim=claim();var levels=MapLayout.flat(2,1,new int[]{0,6},List.of());
        for(String defect:List.of("string","fraction","nullClaim","shape","crossing","tooNear","carve","radius","noZero")){
            var box=box();var first=box.getAsJsonArray("parts").get(0).getAsJsonObject();
            switch(defect){case "string"->box.addProperty("carrierLevels","0,6");case "fraction"->box.add("carrierLevels",JsonParser.parseString("[[0,6.5]]"));case "nullClaim"->box.add("carrierLevels",JsonParser.parseString("[[null,6]]"));case "shape"->first.addProperty("shape","gable");case "crossing"->first.add("x",JsonParser.parseString("[1,24]"));case "tooNear"->first.add("x",JsonParser.parseString("[1,15.5]"));case "carve"->first.addProperty("carve",true);case "radius"->box.getAsJsonArray("parts").get(1).getAsJsonObject().add("radius",JsonParser.parseString("[7,7,7]"));case "noZero"->box.add("carrierLevels",JsonParser.parseString("[[1,7]]"));}
            assertFalse(FittedCarrierLevels.valid(map,claim,box,levels),defect);
        }
    }
    @Test void excludedTerrainIsNullAndCannotBeUsedAsAHiddenWitness(){
        var map=ShapeFixtures.surface("LEVELS","AABB","AABB");var claim=claim();for(int z=0;z<2;z++)for(int x=2;x<4;x++)claim.claimed()[z*4+x]=false;
        var box=box();box.add("carrierLevels",JsonParser.parseString("[[0,null]]"));assertTrue(FittedCarrierLevels.valid(map,claim,box,MapLayout.flat(2,1,new int[]{8,14},List.of())));
        box.add("carrierLevels",JsonParser.parseString("[[0,6]]"));assertFalse(FittedCarrierLevels.valid(map,claim,box,null));
    }
}
