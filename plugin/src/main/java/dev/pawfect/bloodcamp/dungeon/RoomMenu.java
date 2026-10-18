package dev.pawfect.bloodcamp.dungeon;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

final class RoomMenu implements Listener {
    private static final TextColor TITLE = TextColor.color(0xF2C14E);
    private static final TextColor LABEL = TextColor.color(0x9AA0A6);
    private static final TextColor VALUE = TextColor.color(0xF1F3F4);
    private static final TextColor DIM = TextColor.color(0x5F6368);
    private static final TextColor GOOD = TextColor.color(0x7BD88F);
    private static final TextColor BAD = TextColor.color(0xF2766B);

    private record Category(String pool, String name, Material icon, int slot) {}

    private static final List<Category> CATEGORIES = List.of(
        new Category("1x1", "1x1", Material.STONE_BRICKS, 10),
        new Category("1x2", "1x2", Material.CHISELED_STONE_BRICKS, 11),
        new Category("1x3", "1x3", Material.CRACKED_STONE_BRICKS, 12),
        new Category("1x4", "1x4", Material.MOSSY_STONE_BRICKS, 13),
        new Category("2x2", "2x2", Material.POLISHED_ANDESITE, 14),
        new Category("l-shape", "L-Shape", Material.DEEPSLATE_TILES, 15),
        new Category("puzzle", "Puzzle", Material.CHISELED_BOOKSHELF, 16),
        new Category("trap", "Trap", Material.TRIPWIRE_HOOK, 21),
        new Category("miniboss", "Miniboss", Material.WITHER_SKELETON_SKULL, 22),
        new Category("special", "Special", Material.REDSTONE_BLOCK, 23));

    private static final int[] ROOM_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43};
    private static final int CLOSE = 40;
    private static final int PREV = 45;
    private static final int BACK = 49;
    private static final int NEXT = 53;

    private static final class Holder implements InventoryHolder {
        final Category category;
        final int page;
        final Map<Integer, RoomLibrary.Entry> rooms = new HashMap<>();
        Inventory inventory;

        Holder(Category category, int page) {
            this.category = category;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final JavaPlugin plugin;
    private final RoomLibrary library;
    private final RoomViewer viewer;

    RoomMenu(JavaPlugin plugin, RoomLibrary library, RoomViewer viewer) {
        this.plugin = plugin;
        this.library = library;
        this.viewer = viewer;
    }

    void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void open(Player player) {
        Holder holder = new Holder(null, 0);
        holder.inventory = Bukkit.createInventory(holder, 45, Component.text("Room Selector", NamedTextColor.DARK_GRAY));
        Inventory inv = holder.inventory;
        frame(inv);
        inv.setItem(4, item(Material.FILLED_MAP, Component.text("Room Selector", TITLE, TextDecoration.BOLD), List.of(
            Component.empty(),
            Component.text("Pick any room and practice it", LABEL),
            Component.text("on its own, with your kit,", LABEL),
            Component.text("speed, secrets and levers.", LABEL))));
        for (Category c : CATEGORIES) {
            int count = library.pool(c.pool()).size();
            inv.setItem(c.slot(), item(c.icon(), Component.text(c.name(), VALUE, TextDecoration.BOLD), List.of(
                Component.empty(),
                Component.text(count + (count == 1 ? " room" : " rooms"), LABEL),
                Component.empty(),
                action("Click to browse"))));
        }
        inv.setItem(CLOSE, item(Material.BARRIER, Component.text("Close", BAD), List.of()));
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
    }

    private void openCategory(Player player, Category category, int page) {
        List<RoomLibrary.Entry> rooms = library.pool(category.pool()).stream()
            .sorted((a, b) -> a.name().compareToIgnoreCase(b.name())).toList();
        int pages = Math.max(1, (rooms.size() + ROOM_SLOTS.length - 1) / ROOM_SLOTS.length);
        int p = Math.max(0, Math.min(page, pages - 1));
        Holder holder = new Holder(category, p);
        String title = category.name() + (pages > 1 ? "  " + (p + 1) + "/" + pages : "");
        holder.inventory = Bukkit.createInventory(holder, 54, Component.text("Rooms · " + title, NamedTextColor.DARK_GRAY));
        Inventory inv = holder.inventory;
        frame(inv);
        for (int i = 0; i < ROOM_SLOTS.length; i++) {
            int index = p * ROOM_SLOTS.length + i;
            if (index >= rooms.size()) break;
            RoomLibrary.Entry entry = rooms.get(index);
            holder.rooms.put(ROOM_SLOTS[i], entry);
            inv.setItem(ROOM_SLOTS[i], roomItem(category, entry));
        }
        if (p > 0) inv.setItem(PREV, item(Material.ARROW, Component.text("Previous Page", VALUE), List.of(Component.text("Page " + p, DIM))));
        if (p < pages - 1) inv.setItem(NEXT, item(Material.ARROW, Component.text("Next Page", VALUE), List.of(Component.text("Page " + (p + 2), DIM))));
        inv.setItem(BACK, item(Material.OAK_DOOR, Component.text("Back", LABEL), List.of()));
        player.openInventory(inv);
    }

    private ItemStack roomItem(Category category, RoomLibrary.Entry entry) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(category.name(), DIM));
        lore.add(Component.empty());
        RoomLibrary.RoomSecrets secrets = library.secrets(entry.id());
        lore.add(Component.text("Secrets: ", LABEL).append(Component.text(secrets == null ? "none" : String.valueOf(secrets.count()), VALUE)));
        lore.add(Component.empty());
        lore.add(action("Click to practice"));
        return item(category.icon(), Component.text(entry.name(), VALUE, TextDecoration.BOLD), lore);
    }

    private static void frame(Inventory inv) {
        ItemStack border = filler(Material.BLACK_STAINED_GLASS_PANE);
        ItemStack inner = filler(Material.GRAY_STAINED_GLASS_PANE);
        int rows = inv.getSize() / 9;
        for (int i = 0; i < inv.getSize(); i++) {
            int row = i / 9, col = i % 9;
            boolean edge = row == 0 || row == rows - 1 || col == 0 || col == 8;
            inv.setItem(i, edge ? border : inner);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        if (holder.category == null) {
            if (slot == CLOSE) {
                player.closeInventory();
                return;
            }
            for (Category c : CATEGORIES) {
                if (c.slot() != slot) continue;
                click(player);
                openCategory(player, c, 0);
                return;
            }
            return;
        }
        switch (slot) {
            case PREV -> {
                click(player);
                openCategory(player, holder.category, holder.page - 1);
            }
            case NEXT -> {
                click(player);
                openCategory(player, holder.category, holder.page + 1);
            }
            case BACK -> {
                click(player);
                open(player);
            }
            default -> {
                RoomLibrary.Entry entry = holder.rooms.get(slot);
                if (entry == null) return;
                if (viewer.isBuilding()) {
                    player.sendMessage(Component.text("Another room is building, try again in a second.", BAD));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
                    return;
                }
                player.closeInventory();
                player.sendMessage(Component.text("Building " + entry.name() + "...", LABEL));
                viewer.practice(player, holder.category.pool(), entry);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    private static void click(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
    }

    private static Component action(String text) {
        return Component.text("▸ ", TITLE).append(Component.text(text, TITLE));
    }

    private static ItemStack filler(Material material) {
        ItemStack stack = new ItemStack(material);
        stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return stack;
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        meta.lore(lore.stream().map(c -> c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
        stack.setItemMeta(meta);
        return stack;
    }
}
