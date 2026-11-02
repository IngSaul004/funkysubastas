package lat.funkypunky.subastas;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Utilidades de texto: colores &, dinero, tiempo y nombres de objetos. */
final class Texto {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private Texto() {
    }

    /** Texto con códigos & a Component, sin la cursiva que Minecraft pone en los lores. */
    static Component c(String s) {
        return LEGACY.deserialize(s == null ? "" : s).decoration(TextDecoration.ITALIC, false);
    }

    /** 1234.5 -> "$1,234.5" (mismo formato que Essentials). */
    static String dinero(double v) {
        DecimalFormat f = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        return "$" + f.format(v);
    }

    /** Milisegundos -> "1d 4h", "3h 20m" o "15m". */
    static String tiempo(long ms) {
        if (ms <= 0) return "0m";
        long min = ms / 60_000L;
        long h = min / 60;
        long d = h / 24;
        if (d > 0) return d + "d " + (h % 24) + "h";
        if (h > 0) return h + "h " + (min % 60) + "m";
        return Math.max(1, min) + "m";
    }

    /** Nombre para mostrar: el personalizado si tiene; si no, el material ("diamond sword"). Incluye la cantidad. */
    static String nombre(ItemStack it) {
        String base = nombreSimple(it);
        return it.getAmount() > 1 ? it.getAmount() + "x " + base : base;
    }

    static String nombreSimple(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m != null && m.hasDisplayName() && m.displayName() != null) {
            return PlainTextComponentSerializer.plainText().serialize(m.displayName());
        }
        return it.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
