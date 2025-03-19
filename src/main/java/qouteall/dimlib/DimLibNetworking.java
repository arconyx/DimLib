package qouteall.dimlib;

import com.google.common.collect.ImmutableMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.dimlib.api.DimensionAPI;
import qouteall.dimlib.mixin.client.IClientPacketListener;

import static qouteall.dimlib.DimLibEntry.MODID;

public class DimLibNetworking {
    public static final Logger LOGGER = LoggerFactory.getLogger(DimLibNetworking.class);

    @Environment(EnvType.CLIENT)
    public static void initClient() {
        ClientPlayNetworking.registerGlobalReceiver(
                DimSyncPacket.TYPE.getId(),
                (client, handler, buf, responseSender) -> {
                    // directly handle in networking thread
                    // it does not touch world state, so it's safe
                    DimSyncPacket dimSyncPacket = DimSyncPacket.TYPE.read(buf);
                    dimSyncPacket.handleOnNetworkingThread(handler);
                }
        );
    }

    public record DimSyncPacket(
            CompoundTag dimIdToTypeIdTag
    ) implements FabricPacket {
        public static final ResourceLocation DIM_SYNC_CHANNEL = new ResourceLocation(MODID, "dim_sync");
        public static final PacketType<DimSyncPacket> TYPE = PacketType.create(
                DIM_SYNC_CHANNEL,
                DimSyncPacket::new
        );

        public DimSyncPacket(FriendlyByteBuf buf) {
            this(buf.readNbt());
        }

        public static DimSyncPacket createPacket(MinecraftServer server) {
            RegistryAccess registryManager = server.registryAccess();
            Registry<DimensionType> dimensionTypes = registryManager.registryOrThrow(Registries.DIMENSION_TYPE);

            CompoundTag dimIdToDimTypeId = new CompoundTag();
            for (ServerLevel world : server.getAllLevels()) {
                ResourceKey<Level> dimId = world.dimension();

                DimensionType dimType = world.dimensionType();
                ResourceLocation dimTypeId = dimensionTypes.getKey(dimType);

                if (dimTypeId == null) {
                    LOGGER.error("Cannot find dimension type for {}", dimId.location());
                    LOGGER.error(
                            "Registered dimension types {}", dimensionTypes.keySet()
                    );
                    dimTypeId = BuiltinDimensionTypes.OVERWORLD.location();
                }

                dimIdToDimTypeId.putString(
                        dimId.location().toString(),
                        dimTypeId.toString()
                );
            }

            return new DimSyncPacket(dimIdToDimTypeId);
        }

        public static FriendlyByteBuf createBuf(MinecraftServer server) {
            FriendlyByteBuf buf = PacketByteBufs.empty();
            DimLibNetworking.DimSyncPacket packet = DimLibNetworking.DimSyncPacket.createPacket(server);
            packet.write(buf);
            return buf;
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeNbt(dimIdToTypeIdTag);
        }

        @Override
        public PacketType<?> getType() {
            return TYPE;
        }

        public ImmutableMap<ResourceKey<Level>, ResourceKey<DimensionType>> toMap() {
            CompoundTag tag = dimIdToTypeIdTag();

            ImmutableMap.Builder<ResourceKey<Level>, ResourceKey<DimensionType>> builder =
                    new ImmutableMap.Builder<>();

            for (String key : tag.getAllKeys()) {
                ResourceKey<Level> dimId = ResourceKey.create(
                        Registries.DIMENSION,
                        new ResourceLocation(key)
                );
                String dimTypeId = tag.getString(key);
                ResourceKey<DimensionType> dimType = ResourceKey.create(
                        Registries.DIMENSION_TYPE,
                        new ResourceLocation(dimTypeId)
                );
                builder.put(dimId, dimType);
            }

            return builder.build();
        }

        @Environment(EnvType.CLIENT)
        public void handleOnNetworkingThread(ClientGamePacketListener listener) {
            LOGGER.info(
                    "Client received dimension info\n{}",
                    String.join("\n", dimIdToTypeIdTag.getAllKeys())
            );

            var dimIdToDimType = this.toMap();
            ClientDimensionInfo.accept(dimIdToDimType);
            ((IClientPacketListener) listener).ip_setLevels(dimIdToDimType.keySet());

            Minecraft.getInstance().execute(() -> DimensionAPI.CLIENT_DIMENSION_UPDATE_EVENT.invoker().run(
                    ClientDimensionInfo.getDimensionIds()
            ));
        }
    }
}
