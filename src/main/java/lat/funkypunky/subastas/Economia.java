package lat.funkypunky.subastas;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Puente con Vault (VaultUnlocked se registra como "Vault"). La moneda real la maneja Essentials.
 * Se busca la economía la primera vez que se usa: así no importa en qué orden arrancan los plugins.
 */
final class Economia {

    private Economy eco;

    private Economy eco() {
        if (eco == null) {
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null) eco = rsp.getProvider();
        }
        return eco;
    }

    boolean disponible() {
        return eco() != null;
    }

    boolean tiene(OfflinePlayer p, double cantidad) {
        return cantidad <= 0 || (eco() != null && eco.has(p, cantidad));
    }

    double saldo(OfflinePlayer p) {
        return eco() == null ? 0 : eco.getBalance(p);
    }

    /** Cobra. Devuelve true solo si Vault confirma que se cobró. */
    boolean retirar(OfflinePlayer p, double cantidad) {
        if (cantidad <= 0) return true;
        if (eco() == null) return false;
        EconomyResponse r = eco.withdrawPlayer(p, cantidad);
        return r != null && r.transactionSuccess();
    }

    boolean depositar(OfflinePlayer p, double cantidad) {
        if (cantidad <= 0) return true;
        if (eco() == null) return false;
        EconomyResponse r = eco.depositPlayer(p, cantidad);
        return r != null && r.transactionSuccess();
    }
}
