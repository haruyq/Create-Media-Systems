package org.haruyq.cmsplantfixture;

import java.util.HashSet;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Test only: exact item IDs checked against Create Plant 98b8349609d31e3c54f17841b111d8dfa6bd6e3a. */
@Mod("create_heavy_indusstry")
public final class PlantMaterialFixture {
    public PlantMaterialFixture(IEventBus bus) {
        var items = DeferredRegister.createItems("create_heavy_indusstry");
        for (String id : new String[] {"mox_pellet", "tungsten_divertor_cassette", "titanium_sheet", "zircaloy_cladding_tube", "fusion_toroidal_field_coil"})
            items.registerSimpleItem(id, new Item.Properties());
        items.register(bus);
    }
}
