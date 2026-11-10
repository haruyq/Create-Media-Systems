package org.haruyq.createmediasystems.launcher.client;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class ChargerGeometryTest {
    record Face(int axis, double plane, double u0, double v0, double u1, double v1) {}

    @Test void theShippedChargerHasNoCoplanarOverlappingFaces() throws Exception {
        var stream=getClass().getResourceAsStream("/assets/create_media_systems/models/block/disc_cell_charger.json");
        assertNotNull(stream);
        var faces=new ArrayList<Face>();
        try (var reader=new InputStreamReader(stream,StandardCharsets.UTF_8)) {
            var model=JsonParser.parseReader(reader).getAsJsonObject();
            for(var element:model.getAsJsonArray("elements")) {
                var e=element.getAsJsonObject();JsonArray lo=e.getAsJsonArray("from"), hi=e.getAsJsonArray("to");
                for(var name:e.getAsJsonObject("faces").keySet()) {
                    int axis=switch(name){case "east","west"->0;case "up","down"->1;default->2;};
                    int u=axis==0?1:0,v=axis==2?1:2;
                    double plane=(name.equals("east") || name.equals("up") || name.equals("south")?hi:lo).get(axis).getAsDouble();
                    var face=new Face(axis,plane,lo.get(u).getAsDouble(),lo.get(v).getAsDouble(),hi.get(u).getAsDouble(),hi.get(v).getAsDouble());
                    assertTrue(face.u1>face.u0 && face.v1>face.v0,"No degenerate face");faces.add(face);
                }
            }
        }
        assertTrue(faces.size()>50,"Audit the actual complete charger, including its rails and collars");
        for(int i=0;i<faces.size();i++)for(int j=i+1;j<faces.size();j++) {
            var a=faces.get(i);var b=faces.get(j);
            if(a.axis!=b.axis || Math.abs(a.plane-b.plane)>1e-8)continue;
            boolean overlap=Math.min(a.u1,b.u1)-Math.max(a.u0,b.u0)>1e-8
                    && Math.min(a.v1,b.v1)-Math.max(a.v0,b.v0)>1e-8;
            assertFalse(overlap,"Charger depth fighting between faces "+i+" and "+j);
        }
    }
}
