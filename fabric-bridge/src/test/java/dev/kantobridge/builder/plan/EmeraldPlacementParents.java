package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.G3Map;
import java.util.*;

/** Uses the actual planner's door graph so filtered source diagnostics retain their required parents. */
public final class EmeraldPlacementParents {
    private EmeraldPlacementParents(){}
    public static List<String> closure(ExportBundle bundle,List<String> requested)throws Exception{
        Map<String,G3Map> all=new TreeMap<>();for(String id:bundle.ids("g3map"))all.put(id,G3Built.map(bundle,id));
        var layout=bundle.g3layout();Set<String> surface=new HashSet<>(layout.maps().keySet());
        var doors=Gen3Planner.doors(all,surface,layout.root(),layout.start());
        LinkedHashSet<String> result=new LinkedHashSet<>(requested);ArrayDeque<String> queue=new ArrayDeque<>(requested);
        while(!queue.isEmpty()){
            String id=queue.removeFirst();if(surface.contains(id))continue;
            var door=doors.get(id);if(door!=null&&result.add(door.parent()))queue.addLast(door.parent());
        }
        return List.copyOf(result);
    }
}
