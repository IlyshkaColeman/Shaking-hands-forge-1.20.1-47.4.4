package com.cooptest.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

/** STAGE 4 STUB — push client logic. Ported in Stage 4 (Push/Catch group). */
@OnlyIn(Dist.CLIENT)
public final class PushClientHandler {

    private PushClientHandler() {}

    public static void register() { }

    public static void cleanup(UUID playerId) { }
}
