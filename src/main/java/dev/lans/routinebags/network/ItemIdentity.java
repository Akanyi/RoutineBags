package dev.lans.routinebags.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.item.ItemStack;

public final class ItemIdentity {
    public static final int HASH_SIZE = 32;

    public static byte[] hash(ItemStack stack, HolderLookup.Provider registries) {
        ItemStack single = stack.copyWithCount(1);
        return ItemStack.CODEC.encodeStart(
                registries.createSerializationContext(NbtOps.INSTANCE), single
        ).result().filter(CompoundTag.class::isInstance).map(CompoundTag.class::cast).map(tag -> {
            NbtUtils.addCurrentDataVersion(tag);
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                NbtIo.writeCompressed(tag, output);
                return MessageDigest.getInstance("SHA-256").digest(output.toByteArray());
            } catch (IOException | NoSuchAlgorithmException exception) {
                return new byte[0];
            }
        }).orElseGet(() -> new byte[0]);
    }

    private ItemIdentity() {
    }
}
