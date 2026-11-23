package xnmc.lootnotes;

public final class LootNotesTest {
    public static void main(String[] args) {
        for (String text : new String[]{"Crafted by samboyd", "§aLooted by§r player", "Found by: example", "Discovered by player", "Opened by someone"})
            if (!LootNotes.attributionText(text)) throw new AssertionError("Missed attribution text: " + text);
        for (String text : new String[]{"", "Crafted item", "Loot table", "player", "Uncrafted by nobody"})
            if (LootNotes.attributionText(text)) throw new AssertionError("False attribution text: " + text);
        System.out.println("10 item attribution text checks passed (no inference about chest ownership)");
    }
}
