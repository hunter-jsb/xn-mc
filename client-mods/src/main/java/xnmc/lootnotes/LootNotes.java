package xnmc.lootnotes;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import xnmc.*;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** Read-only observations of server-transmitted data. Never opens containers or requests hidden inventories. */
public final class LootNotes {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern ATTRIBUTION = Pattern.compile("(?i)\\b(crafted|looted|found|discovered|opened)\\s+by\\b");
    private static final int MAX_TAG = 16_384;
    private static ClientLevel observedWorld;
    private record PacketNote(BlockPos pos, String observedAt, String tag, boolean inventoryField) {}
    private static final Map<BlockPos, PacketNote> packets = new LinkedHashMap<>() {
        protected boolean removeEldestEntry(Map.Entry<BlockPos, PacketNote> eldest) { return size() > 2048; }
    };
    private static String last = "read-only item and chest packet inspector";

    public static void init() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> { packets.clear(); observedWorld = null; });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(literal("lootnotes")
                .executes(c -> { say("hand = held item tags; chest = an already-open chest; nearby = received chest packet metadata."); return 1; })
                .then(literal("hand").executes(c -> inspectHand()))
                .then(literal("chest").executes(c -> inspectChest()))
                .then(literal("nearby").executes(c -> inspectNearby()))));
    }
    public static Feature feature() {
        return new Feature("Loot Notes", () -> last, s -> s.row(
                FeatureScreen.act("Held item", LootNotes::inspectHand), FeatureScreen.act("Open chest", LootNotes::inspectChest),
                FeatureScreen.act("Nearby packets", LootNotes::inspectNearby)));
    }
    public static void chunkPacket(ClientboundLevelChunkWithLightPacket packet) {
        packet.chunkData().forEachBlockEntityTag(packet.x(), packet.z(), LootNotes::blockEntityPacket);
    }
    public static void blockEntityPacket(BlockPos pos, BlockEntityType<?> type, CompoundTag tag) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (observedWorld != mc.level) { packets.clear(); observedWorld = mc.level; }
        if (type != BlockEntityTypes.CHEST && type != BlockEntityTypes.TRAPPED_CHEST && type != BlockEntityTypes.BARREL) return;
        String raw = tag == null || tag.isEmpty() ? "" : tag.toString();
        boolean inventory = tag != null && (tag.contains("Items") || tag.contains("items"));
        packets.remove(pos);
        packets.put(pos.immutable(), new PacketNote(pos.immutable(), Instant.now().toString(), bounded(raw), inventory));
    }
    private static String bounded(String text) { return text.length() <= MAX_TAG ? text : text.substring(0, MAX_TAG) + " [TRUNCATED]"; }
    static boolean attributionText(String line) { return ATTRIBUTION.matcher(line.replaceAll("§.", "")).find(); }
    private static JsonObject item(ItemStack stack, int slot) {
        JsonObject j = new JsonObject();
        j.addProperty("slot", slot);
        j.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        j.addProperty("count", stack.getCount());
        j.addProperty("name", stack.getHoverName().getString());
        JsonArray lore = new JsonArray(), tags = new JsonArray();
        ItemLore l = stack.get(DataComponents.LORE);
        if (l != null) for (var line : l.lines()) {
            lore.add(line.getString());
            if (attributionText(line.getString())) tags.add(line.getString());
        }
        j.add("lore", lore); j.add("attributionTextObserved", tags);
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) j.addProperty("customDataSnbt", bounded(data.copyTag().toString()));
        return j;
    }
    public static int inspectHand() {
        var p = Minecraft.getInstance().player;
        if (p == null || p.getMainHandItem().isEmpty()) { say("hold the tagged item in your main hand first."); return 0; }
        JsonArray items = new JsonArray(); items.add(item(p.getMainHandItem(), p.getInventory().getSelectedSlot()));
        return save("held-item", items, "Item provenance is not evidence of who opened a chest.");
    }
    public static int inspectChest() {
        var p = Minecraft.getInstance().player;
        if (p == null || !(p.containerMenu instanceof ChestMenu menu)) { say("manually open a chest first; I don't open one for you."); return 0; }
        JsonArray items = new JsonArray();
        for (int i = 0; i < menu.getRowCount() * 9; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (!s.isEmpty()) items.add(item(s, i));
        }
        return save("already-open-container", items, "Observed server-sent menu slots, not a verified chest location or a history of its visitors. Empty slots do not prove prior looting.");
    }
    public static int inspectNearby() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;
        if (observedWorld != mc.level) { packets.clear(); observedWorld = mc.level; }
        JsonArray notes = new JsonArray();
        var sorted = packets.values().stream().filter(n -> n.pos.distSqr(mc.player.blockPosition()) <= 256 * 256
                && mc.level.getChunkSource().hasChunk(Math.floorDiv(n.pos.getX(),16), Math.floorDiv(n.pos.getZ(),16)))
                .sorted(Comparator.comparingDouble(n -> n.pos.distSqr(mc.player.blockPosition()))).limit(128).toList();
        int exposed = 0;
        for (PacketNote n : sorted) {
            JsonObject j = new JsonObject();
            j.addProperty("x", n.pos.getX()); j.addProperty("y", n.pos.getY()); j.addProperty("z", n.pos.getZ());
            j.addProperty("observedAt", n.observedAt);
            j.addProperty("receivedTagSnbt", n.tag);
            j.addProperty("inventoryFieldReceived", n.inventoryField);
            if (!n.tag.isEmpty()) exposed++;
            notes.add(j);
        }
        say(sorted.size() + " recent chest/barrel packet samples nearby; " + exposed + " contained nonempty extra tags. No tag means no metadata observed, not unlooted.");
        return save("nearby-chest-packets", notes, "Only data already sent by the server. No remote inventory queries; no looter attribution inferred.");
    }
    private static int save(String source, JsonArray observations, String limitation) {
        Minecraft mc = Minecraft.getInstance();
        JsonObject report = new JsonObject();
        report.addProperty("observedAt", Instant.now().toString()); report.addProperty("source", source);
        report.addProperty("limitation", limitation);
        if (mc.getCurrentServer() != null) report.addProperty("server", mc.getCurrentServer().ip);
        if (mc.level != null) report.addProperty("dimension", mc.level.dimension().identifier().toString());
        if (mc.player != null) report.addProperty("observerPosition", mc.player.blockPosition().toShortString());
        report.add("observations", observations);
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("xnmc/loot-notes");
            Files.createDirectories(dir);
            Path file = dir.resolve(source + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0,8) + ".json");
            Files.writeString(file, JSON.toJson(report), StandardOpenOption.CREATE_NEW);
            last = observations.size() + " observations saved locally";
            say(last + ": " + file);
            if (source.equals("held-item") && observations.size() == 1)
                say("Observed tag text: " + observations.get(0).getAsJsonObject().get("attributionTextObserved"));
            return 1;
        } catch (IOException e) { say("couldn't save observations: " + e.getMessage()); return 0; }
    }
    private static void say(String text) { Xnmc.say("Loot Notes", ChatFormatting.AQUA, text); }
}
