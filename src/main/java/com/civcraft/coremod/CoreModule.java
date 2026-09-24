package com.civcraft.coremod;

import com.civcraft.CivCraft;
import com.civcraft.Module;
import com.civcraft.core.ui.Holograms;
import com.civcraft.protection.ProtectionListener;

/** Wires the core services: protection, claims guard, residents, culture map. */
public final class CoreModule implements Module {

    private ClaimGuard claimGuard;

    @Override
    public String id() {
        return "core";
    }

    @Override
    public void load(CivCraft civ) {
        civ.messages().include("core");
    }

    @Override
    public void enable(CivCraft civ) {
        Holograms.init(civ.plugin());
        claimGuard = new ClaimGuard(civ.state());
        civ.protection().register(claimGuard);
        civ.listen(new ProtectionListener(civ.protection()));
        ResidentListener residents = new ResidentListener(civ);
        civ.listen(residents);
        civ.clock().everySecond("pvp-protection", residents::tick);
        civ.culture().recompute(false);
        com.civcraft.command.AdminRegistry.register(civ.plugin());
    }

    @Override
    public void disable(CivCraft civ) {
        Holograms.removeAll();
    }

    public ClaimGuard claimGuard() {
        return claimGuard;
    }
}
