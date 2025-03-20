package qouteall.dimlib.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.player.LocalPlayer;
import qouteall.dimlib.network.DimSyncPacket;

public class DimLibEntryClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(
                DimSyncPacket.TYPE,
                (DimSyncPacket packet, LocalPlayer player, PacketSender responseSender) -> {
                    packet.handle(player.connection);
                }
        );
    }
}
