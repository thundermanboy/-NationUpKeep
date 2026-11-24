package com.noah.nationupkeep;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.EventExecutor;
import java.util.function.Supplier;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.EventHandler;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class NationUpkeepPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private final Map<String, Nation> nations = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<UUID, String> membership = new HashMap<>();
    private long nextChargeAt;
    private BukkitTask upkeepTask;
    private Economy economy;
    private final Map<Long, SuspendedClaim> suspendedClaims = new HashMap<>();
    private Listener gpEventListener;
    private boolean gpHooksReady;
    private final Map<UUID, ArmorStand> nationNameplates = new HashMap<>();
    private BukkitTask nameplateTask;

    @Override public void onEnable() {
        saveDefaultConfig();
        loadData();
        loadSuspendedClaims();
        setupEconomy();
        registerGriefPreventionHooks();
        getServer().getPluginManager().registerEvents(this, this);
        reconcileSuspendedClaims();
        Objects.requireNonNull(getCommand("nation")).setExecutor(this);
        Objects.requireNonNull(getCommand("nation")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("nationadmin")).setExecutor(this);
        Objects.requireNonNull(getCommand("nationadmin")).setTabCompleter(this);
        if (nextChargeAt == 0) nextChargeAt = System.currentTimeMillis() + daysToMillis(getConfig().getLong("upkeep.first-charge-delay-days", 30));
        saveData();
        scheduleNextCharge();
        cleanupOrphanNameplates();
        for (Player online : Bukkit.getOnlinePlayers()) refreshNationNameplate(online);
        nameplateTask = Bukkit.getScheduler().runTaskTimer(this, this::refreshAllNationNameplates, 40L, 40L);
        getLogger().info("NationUpkeep 0.6.2 enabled. Vault economy: " + (economy != null) + "; GriefPrevention scanning: " + (Bukkit.getPluginManager().isPluginEnabled("GriefPrevention") ? "available" : "not installed"));
    }

    @Override public void onDisable() { if (upkeepTask != null) upkeepTask.cancel(); if (nameplateTask != null) nameplateTask.cancel(); removeAllNationNameplates(); saveData(); saveSuspendedClaims(); }

    private long daysToMillis(long days) { return Math.max(1, days) * 86_400_000L; }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', "&6[Nations] &f" + s); }
    private void msg(CommandSender s, String m) { s.sendMessage(color(m)); }
    private Nation nationOf(UUID id) { String n = membership.get(id); return n == null ? null : nations.get(n); }
    private boolean rank(Nation n, UUID id, String... roles) { String r = n.role(id); return r != null && Arrays.asList(roles).contains(r); }
    private boolean playerOnly(CommandSender s) { if (s instanceof Player) return false; msg(s, "This command can only be used in-game."); return true; }
    private boolean hasRank(Player p, Nation n, String... roles) { if (rank(n, p.getUniqueId(), roles)) return true; msg(p, "You do not have a high enough nation role for that."); return false; }
    private void broadcast(Nation n, String message) { for (UUID id : n.members().keySet()) { Player p = Bukkit.getPlayer(id); if (p != null) msg(p, message); } }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (command.getName().equalsIgnoreCase("nationadmin")) return admin(sender, a);
        if (a.length == 0) { help(sender); return true; }
        if (playerOnly(sender)) return true;
        Player p = (Player) sender;
        String sub = a[0].toLowerCase(Locale.ROOT);
        Nation n = nationOf(p.getUniqueId());
        switch (sub) {
            case "create" -> {
                if (a.length < 2) { msg(p, "Usage: /nation create <name>"); return true; }
                if (n != null) { msg(p, "You are already in a nation."); return true; }
                String name = a[1];
                if (!name.matches("[A-Za-z0-9_-]{3,20}")) { msg(p, "Name must be 3-20 characters: letters, numbers, _ or -."); return true; }
                if (nations.containsKey(name)) { msg(p, "That nation already exists."); return true; }
                Nation created = new Nation(name, p.getUniqueId()); nations.put(name, created); membership.put(p.getUniqueId(), name); saveData(); refreshNationNameplate(p); msg(p, "Created nation " + name + ". You are the Owner.");
            }
            case "invite" -> {
                if (a.length < 2 || n == null) { msg(p, "Usage: /nation invite <online-player>"); return true; }
                if (!hasRank(p, n, "OWNER", "CO_OWNER", "ADMIN", "OFFICER")) return true;
                Player target = Bukkit.getPlayerExact(a[1]);
                if (target == null) { msg(p, "That player must be online."); return true; }
                if (membership.containsKey(target.getUniqueId())) { msg(p, "That player already belongs to a nation."); return true; }
                n.invites().add(target.getUniqueId()); saveData(); msg(target, "You were invited to " + n.name() + ". Type /nation accept " + n.name()); msg(p, "Invitation sent to " + target.getName() + ".");
            }
            case "accept" -> {
                if (a.length < 2) { msg(p, "Usage: /nation accept <name>"); return true; }
                Nation invited = nations.get(a[1]);
                if (invited == null || !invited.invites().remove(p.getUniqueId())) { msg(p, "You don't have an invitation to that nation."); return true; }
                if (n != null) { invited.invites().add(p.getUniqueId()); msg(p, "Leave your current nation first."); return true; }
                invited.members().put(p.getUniqueId(), "MEMBER"); membership.put(p.getUniqueId(), invited.name()); saveData(); refreshNationNameplate(p); broadcast(invited, p.getName() + " joined the nation.");
            }
            case "leave" -> {
                if (n == null) { msg(p, "You are not in a nation."); return true; }
                if (rank(n, p.getUniqueId(), "OWNER")) { msg(p, "The Owner cannot leave. Transfer ownership or disband the nation first."); return true; }
                restoreClaimsOwnedBy(p.getUniqueId());
                n.members().remove(p.getUniqueId()); membership.remove(p.getUniqueId()); saveData(); refreshNationNameplate(p); broadcast(n, p.getName() + " left the nation.");
            }
            case "info" -> {
                if (n == null && a.length > 1) n = nations.get(a[1]);
                if (n == null) { msg(p, "You are not in a nation. Use /nation info <name> to view one."); return true; }
                msg(p, "Nation: &e" + n.name()); msg(p, "Members: &e" + n.members().size() + " &7| Bank: &e" + money(n.bank()));
                msg(p, "Claim blocks recorded: &e" + n.claimedBlocks() + " &7| Upkeep: " + (n.paid() ? "&aPAID" : "&cUNPAID"));
                msg(p, "Allies: &b" + (n.allies().isEmpty() ? "None" : String.join(", ", n.allies())));
                msg(p, "Enemies: &c" + (n.enemies().isEmpty() ? "None" : String.join(", ", n.enemies())));
            }
            case "bank" -> { if (n == null) msg(p, "You are not in a nation."); else msg(p, n.name() + " bank: &e" + money(n.bank())); }
            case "members" -> {
                if (n == null) { msg(p, "You are not in a nation."); return true; }
                msg(p, "Members of &e" + n.name() + "&f:");
                n.members().entrySet().stream()
                        .sorted(Comparator.comparingInt(entry -> roleOrder(entry.getValue())))
                        .forEach(entry -> {
                            String memberName = Optional.ofNullable(Bukkit.getOfflinePlayer(entry.getKey()).getName()).orElse(entry.getKey().toString().substring(0, 8));
                            Player online = Bukkit.getPlayer(entry.getKey());
                            msg(p, (online == null ? "&7" : "&a") + memberName + " &8- &e" + entry.getValue() + (online == null ? " &7(offline)" : " &a(online)"));
                        });
            }
            case "kick" -> {
                if (n == null || a.length < 2) { msg(p, "Usage: /nation kick <online-member>"); return true; }
                if (!hasRank(p, n, "OWNER", "CO_OWNER", "ADMIN")) return true;
                Player target = Bukkit.getPlayerExact(a[1]);
                if (target == null || !n.has(target.getUniqueId())) { msg(p, "That player must be an online member of your nation."); return true; }
                String actorRole = n.role(p.getUniqueId());
                String targetRole = n.role(target.getUniqueId());
                if (target.getUniqueId().equals(n.owner()) || "OWNER".equals(targetRole)) { msg(p, "The nation Owner cannot be kicked. Transfer ownership first."); return true; }
                if (roleOrder(targetRole) <= roleOrder(actorRole)) { msg(p, "You cannot kick a member with an equal or higher role."); return true; }
                restoreClaimsOwnedBy(target.getUniqueId());
                n.members().remove(target.getUniqueId());
                membership.remove(target.getUniqueId());
                saveData();
                refreshNationNameplate(target);
                msg(target, "You were removed from nation " + n.name() + ". Your personal GriefPrevention claim permissions were restored where snapshots existed.");
                broadcast(n, target.getName() + " was removed from the nation by " + p.getName() + ".");
            }
            case "deposit", "withdraw" -> {
                if (n == null) { msg(p, "You are not in a nation."); return true; }
                if (a.length < 2) { msg(p, "Usage: /nation " + sub + " <amount>"); return true; }
                if (economy == null) { msg(p, "Vault and a Vault-compatible economy plugin are required. No money was changed."); return true; }
                double amount; try { amount = Double.parseDouble(a[1]); } catch (NumberFormatException ex) { msg(p, "Enter a valid amount."); return true; }
                if (!Double.isFinite(amount) || amount <= 0 || amount > 1_000_000_000) { msg(p, "Amount must be greater than zero and no more than 1,000,000,000."); return true; }
                if (sub.equals("deposit")) {
                    EconomyResponse response = economy.withdrawPlayer(p, amount);
                    if (!response.transactionSuccess()) { msg(p, "Deposit failed: " + response.errorMessage); return true; }
                    n.bank(n.bank() + amount);
                    boolean settled = settleOverdueUpkeep(n);
                    saveData();
                    msg(p, "Deposited " + money(amount) + " into " + n.name() + "'s bank.");
                    if (settled) broadcast(n, "Your nation paid its overdue upkeep. Suspended claim permissions are being restored.");
                } else {
                    if (!hasRank(p, n, "OWNER", "CO_OWNER", "ADMIN")) return true;
                    if (n.bank() + 0.000001 < amount) { msg(p, "The nation bank does not have enough money."); return true; }
                    EconomyResponse response = economy.depositPlayer(p, amount);
                    if (!response.transactionSuccess()) { msg(p, "Withdrawal failed: " + response.errorMessage); return true; }
                    n.bank(n.bank() - amount); saveData(); msg(p, "Withdrew " + money(amount) + " from " + n.name() + "'s bank.");
                }
            }
            case "promote" -> {
                if (n == null || a.length < 3) { msg(p, "Usage: /nation promote <online-player> <CO_OWNER|ADMIN|OFFICER|MEMBER>"); return true; }
                if (!hasRank(p, n, "OWNER", "CO_OWNER")) return true;
                Player target = Bukkit.getPlayerExact(a[1]); String role = a[2].toUpperCase(Locale.ROOT);
                if (target == null || !n.has(target.getUniqueId()) || !Set.of("CO_OWNER", "ADMIN", "OFFICER", "MEMBER").contains(role)) { msg(p, "Invalid member or role."); return true; }
                if (rank(n, target.getUniqueId(), "OWNER")) { msg(p, "The Owner role cannot be changed here."); return true; }
                n.members().put(target.getUniqueId(), role); saveData(); broadcast(n, target.getName() + " is now " + role + ".");
            }
            case "transfer" -> {
                if (n == null || a.length < 2) { msg(p, "Usage: /nation transfer <online-member>"); return true; }
                if (!hasRank(p, n, "OWNER")) return true;
                Player target = Bukkit.getPlayerExact(a[1]);
                if (target == null || !n.has(target.getUniqueId()) || target.equals(p)) { msg(p, "Choose another online member of your nation."); return true; }
                n.members().put(p.getUniqueId(), "CO_OWNER"); n.members().put(target.getUniqueId(), "OWNER"); n.owner(target.getUniqueId()); saveData(); broadcast(n, target.getName() + " is now the nation Owner.");
            }
            case "disband" -> {
                if (n == null) { msg(p, "You are not in a nation."); return true; }
                if (!hasRank(p, n, "OWNER")) return true;
                String name = n.name();
                restoreClaimsForNation(name);
                for (UUID id : n.members().keySet()) { membership.remove(id); Player online = Bukkit.getPlayer(id); if (online != null) refreshNationNameplate(online); }
                for (Nation other : nations.values()) { other.allies().remove(name); other.enemies().remove(name); }
                nations.remove(name); saveData(); msg(p, "Nation " + name + " has been disbanded.");
            }
            case "ally", "enemy", "neutral" -> {
                if (n == null || a.length < 2) { msg(p, "Usage: /nation " + sub + " <nation>"); return true; }
                if (!hasRank(p, n, "OWNER", "CO_OWNER", "ADMIN")) return true;
                Nation other = nations.get(a[1]);
                if (other == null || other == n) { msg(p, "That nation does not exist or is your own."); return true; }
                setRelation(n, other, sub); saveData();
                String state = sub.equals("neutral") ? "neutral with" : (sub.equals("ally") ? "allied with" : "at war with");
                broadcast(n, "Your nation is now " + state + " " + other.name() + "."); broadcast(other, "Nation " + n.name() + " has declared your nations' relationship: " + sub + ".");
            }
            default -> help(p);
        }
        return true;
    }

    @EventHandler public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(this, () -> refreshNationNameplate(event.getPlayer()));
    }

    @EventHandler public void onPlayerQuit(PlayerQuitEvent event) {
        removeNationNameplate(event.getPlayer().getUniqueId());
    }

    @EventHandler public void onPlayerRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> refreshNationNameplate(event.getPlayer()), 2L);
    }

    @EventHandler public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Bukkit.getScheduler().runTask(this, () -> refreshNationNameplate(event.getPlayer()));
    }

    private void refreshAllNationNameplates() {
        if (!getConfig().getBoolean("nameplates.enabled", true)) {
            removeAllNationNameplates();
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) refreshNationNameplate(player);
    }

    private void refreshNationNameplate(Player player) {
        if (!getConfig().getBoolean("nameplates.enabled", true)) {
            removeNationNameplate(player.getUniqueId());
            return;
        }
        Nation nation = nationOf(player.getUniqueId());
        if (nation == null) {
            removeNationNameplate(player.getUniqueId());
            return;
        }
        String format = getConfig().getString("nameplates.format", "&6&l{name}");
        String label = ChatColor.translateAlternateColorCodes('&', format.replace("{name}", nation.name()));
        ArmorStand existing = nationNameplates.get(player.getUniqueId());
        if (existing != null && existing.isValid() && player.getPassengers().contains(existing)) {
            existing.setCustomName(label);
            existing.setCustomNameVisible(true);
            return;
        }
        removeNationNameplate(player.getUniqueId());
        try {
            ArmorStand stand = player.getWorld().spawn(player.getLocation(), ArmorStand.class, armorStand -> {
                armorStand.setVisible(false);
                armorStand.setInvisible(true);
                armorStand.setMarker(true);
                armorStand.setGravity(false);
                armorStand.setSilent(true);
                armorStand.setInvulnerable(true);
                armorStand.setCollidable(false);
                armorStand.setCustomName(label);
                armorStand.setCustomNameVisible(true);
                armorStand.addScoreboardTag("nationupkeep_nameplate");
            });
            if (player.addPassenger(stand)) nationNameplates.put(player.getUniqueId(), stand);
            else stand.remove();
        } catch (RuntimeException ex) {
            getLogger().warning("Could not create nation nameplate for " + player.getName() + ": " + ex.getMessage());
        }
    }

    private void removeNationNameplate(UUID playerId) {
        ArmorStand stand = nationNameplates.remove(playerId);
        if (stand != null && stand.isValid()) stand.remove();
    }

    private void removeAllNationNameplates() {
        for (ArmorStand stand : new ArrayList<>(nationNameplates.values())) if (stand != null && stand.isValid()) stand.remove();
        nationNameplates.clear();
    }

    private void cleanupOrphanNameplates() {
        for (var world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof ArmorStand stand && stand.getScoreboardTags().contains("nationupkeep_nameplate")) stand.remove();
            }
        }
    }

    private int roleOrder(String role) {
        return switch (role == null ? "MEMBER" : role) {
            case "OWNER" -> 0;
            case "CO_OWNER" -> 1;
            case "ADMIN" -> 2;
            case "OFFICER" -> 3;
            default -> 4;
        };
    }

    private void setRelation(Nation a, Nation b, String relation) {
        a.allies().remove(b.name()); a.enemies().remove(b.name()); b.allies().remove(a.name()); b.enemies().remove(a.name());
        if (relation.equals("ally")) { a.allies().add(b.name()); b.allies().add(a.name()); }
        if (relation.equals("enemy")) { a.enemies().add(b.name()); b.enemies().add(a.name()); }
    }

    private boolean admin(CommandSender s, String[] a) {
        if (!s.hasPermission("nationupkeep.admin")) { msg(s, "No permission."); return true; }
        if (a.length == 0) { msg(s, "/nationadmin scanclaims | blocks <nation> <number> | charge | restoreclaims | status | reload"); return true; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "scanclaims" -> {
                if (!scanGriefPreventionClaims()) msg(s, "Could not scan claims. Check that GriefPrevention is installed and compatible; see console for details.");
                else { saveData(); msg(s, "GriefPrevention claims scanned and nation land totals updated."); }
            }
            case "blocks" -> {
                if (a.length < 3) { msg(s, "Usage: /nationadmin blocks <nation> <claimed-blocks>"); return true; }
                Nation n = nations.get(a[1]); long blocks; try { blocks = Long.parseLong(a[2]); } catch (NumberFormatException ex) { msg(s, "Enter a whole number."); return true; }
                if (n == null || blocks < 0) { msg(s, "Unknown nation or invalid number."); return true; }
                n.claimedBlocks(blocks); saveData(); msg(s, "Recorded " + blocks + " claimed blocks for " + n.name() + ". A later GriefPrevention scan can replace this value.");
            }
            case "charge" -> { if (chargeAllNations()) msg(s, "Upkeep check completed. The next scheduled date was reset."); else msg(s, "Upkeep was not charged because the GriefPrevention claim scan failed. No bank balances were changed."); }
            case "restoreclaims" -> { int restored = restoreAllSuspendedClaims(); msg(s, "Restored " + restored + " suspended claim(s) where the saved records were available."); }
            case "status" -> { msg(s, "Nations: " + nations.size() + " | Suspended claims: " + suspendedClaims.size() + " | Vault: " + (economy != null) + " | GriefPrevention: " + Bukkit.getPluginManager().isPluginEnabled("GriefPrevention")); }
            case "reload" -> { reloadConfig(); scheduleNextCharge(); msg(s, "Configuration reloaded."); }
            default -> msg(s, "/nationadmin scanclaims | blocks <nation> <number> | charge | restoreclaims | status | reload");
        }
        return true;
    }

    private void setupEconomy() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) { economy = null; return; }
        RegisteredServiceProvider<Economy> registration = getServer().getServicesManager().getRegistration(Economy.class);
        economy = registration == null ? null : registration.getProvider();
    }

    @EventHandler
    public void onEconomyServiceRegistered(ServiceRegisterEvent event) {
        if (event.getProvider().getService().equals(Economy.class)) {
            setupEconomy();
            getLogger().info("Vault economy provider registered: " + (economy != null));
        }
    }

    /** Reads GriefPrevention's public datastore/claim getters reflectively to avoid hard-linking
     * this plugin to a particular GP major version. Claims owned by any nation member count. */
    private boolean scanGriefPreventionClaims() {
        if (!Bukkit.getPluginManager().isPluginEnabled("GriefPrevention")) return false;
        try {
            Class<?> gpClass = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
            Object gp = gpClass.getField("instance").get(null);
            Object store = gpClass.getField("dataStore").get(gp);
            Object claimsObject = store.getClass().getMethod("getClaims").invoke(store);
            if (!(claimsObject instanceof Iterable<?> claims)) return false;
            Map<String, Long> totals = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Nation nation : nations.values()) totals.put(nation.name(), 0L);
            for (Object claim : claims) {
                UUID owner = (UUID) claim.getClass().getMethod("getOwnerID").invoke(claim);
                if (owner == null) continue;
                String nationName = membership.get(owner);
                if (nationName == null || !totals.containsKey(nationName)) continue;
                Object lesser = claim.getClass().getMethod("getLesserBoundaryCorner").invoke(claim);
                Object greater = claim.getClass().getMethod("getGreaterBoundaryCorner").invoke(claim);
                int minX = (int) lesser.getClass().getMethod("getBlockX").invoke(lesser);
                int minZ = (int) lesser.getClass().getMethod("getBlockZ").invoke(lesser);
                int maxX = (int) greater.getClass().getMethod("getBlockX").invoke(greater);
                int maxZ = (int) greater.getClass().getMethod("getBlockZ").invoke(greater);
                long area = (Math.abs((long) maxX - minX) + 1L) * (Math.abs((long) maxZ - minZ) + 1L);
                totals.merge(nationName, area, Long::sum);
            }
            for (Nation nation : nations.values()) nation.claimedBlocks(totals.getOrDefault(nation.name(), 0L));
            getLogger().info("Updated claimed-land totals from GriefPrevention.");
            return true;
        } catch (ReflectiveOperationException | ClassCastException ex) {
            getLogger().warning("GriefPrevention scan failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return false;
        }
    }

    /** If a nation is already unpaid, use a new deposit to settle its current bill immediately. */
    private boolean settleOverdueUpkeep(Nation nation) {
        if (nation.paid() || !getConfig().getBoolean("upkeep.enabled", true)) return false;
        double due = upkeepDue(nation);
        if (due <= 0 || nation.bank() + 0.00001 < due) return false;
        nation.bank(Math.max(0, nation.bank() - due));
        nation.paid(true);
        restoreClaimsForNation(nation.name());
        getLogger().info("Nation '" + nation.name() + "' settled overdue upkeep of " + money(due) + " after a bank deposit.");
        return true;
    }

    private double upkeepDue(Nation nation) {
        double rate = getConfig().getDouble("upkeep.cost-per-100-blocks", 1.0);
        if (!Double.isFinite(rate) || rate < 0) rate = 1.0;
        return Math.ceil(nation.claimedBlocks() / 100.0 * rate * 100.0) / 100.0;
    }

    private boolean chargeAllNations() {
        boolean gpEnabled = Bukkit.getPluginManager().isPluginEnabled("GriefPrevention");
        if (getConfig().getBoolean("claims.auto-scan-before-upkeep", true)) {
            if (!gpEnabled || !scanGriefPreventionClaims()) {
                getLogger().severe("Upkeep aborted: GriefPrevention is missing or its claim scan failed. No nation banks were charged; retrying in 24 hours.");
                nextChargeAt = System.currentTimeMillis() + daysToMillis(1);
                saveData(); scheduleNextCharge();
                return false;
            }
        }
        if (!getConfig().getBoolean("upkeep.enabled", true)) {
            for (Nation n : nations.values()) n.paid(true);
            restoreAllSuspendedClaims();
        } else {
            for (Nation n : nations.values()) {
                double due = upkeepDue(n);
                boolean wasPaid = n.paid();
                if (due <= 0) {
                    n.paid(true);
                    if (!wasPaid) restoreClaimsForNation(n.name());
                    continue;
                }
                if (n.bank() + 0.00001 >= due) {
                    n.bank(n.bank() - due);
                    n.paid(true);
                    if (!wasPaid) restoreClaimsForNation(n.name());
                } else {
                    n.paid(false);
                    if (gpEnabled && gpHooksReady && getConfig().getBoolean("claims.suspend-protection-when-unpaid", true)) {
                        suspendNationClaims(n);
                    } else if (gpEnabled) {
                        getLogger().severe("Nation '" + n.name() + "' is unpaid, but automatic claim suspension is disabled or the GP permission hook failed. Claims were left unchanged.");
                    }
                    getLogger().warning("Nation '" + n.name() + "' could not pay " + money(due) + ".");
                }
            }
        }
        nextChargeAt = System.currentTimeMillis() + daysToMillis(getConfig().getLong("upkeep.charge-interval-days", 30));
        saveData(); saveSuspendedClaims(); scheduleNextCharge();
        return true;
    }


    /** Registers GriefPrevention's permission-check event without linking against a specific GP jar. */
    @SuppressWarnings("unchecked")
    private void registerGriefPreventionHooks() {
        if (!Bukkit.getPluginManager().isPluginEnabled("GriefPrevention")) return;
        try {
            Class<?> raw = Class.forName("me.ryanhamshire.GriefPrevention.events.ClaimPermissionCheckEvent", true,
                    Bukkit.getPluginManager().getPlugin("GriefPrevention").getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(raw)) throw new IllegalStateException("GP permission event is not a Bukkit Event");
            Class<? extends Event> eventClass = (Class<? extends Event>) raw;
            gpEventListener = new Listener() {};
            EventExecutor executor = (listener, event) -> enforceSuspendedClaimRules(event);
            Bukkit.getPluginManager().registerEvent(eventClass, gpEventListener, EventPriority.HIGHEST, executor, this, true);
            // A claim transfer would detach the claim from the nation whose saved trust list we must restore.
            try {
                Class<?> transferRaw = Class.forName("me.ryanhamshire.GriefPrevention.events.ClaimTransferEvent", true,
                        Bukkit.getPluginManager().getPlugin("GriefPrevention").getClass().getClassLoader());
                if (Event.class.isAssignableFrom(transferRaw)) {
                    Class<? extends Event> transferClass = (Class<? extends Event>) transferRaw;
                    EventExecutor transferExecutor = (listener, event) -> {
                        try {
                            Object claim = event.getClass().getMethod("getClaim").invoke(event);
                            long id = claimId(claim);
                            if (suspendedClaims.containsKey(id)) {
                                event.getClass().getMethod("setCancelled", boolean.class).invoke(event, true);
                                getLogger().warning("Blocked transfer of suspended GriefPrevention claim " + id + " until its nation pays upkeep.");
                            }
                        } catch (ReflectiveOperationException ex) {
                            getLogger().warning("Could not inspect GriefPrevention claim transfer event: " + ex.getMessage());
                        }
                    };
                    Bukkit.getPluginManager().registerEvent(transferClass, gpEventListener, EventPriority.HIGHEST, transferExecutor, this, true);
                }
            } catch (ClassNotFoundException ignored) {
                getLogger().warning("This GriefPrevention build has no ClaimTransferEvent; transfer protection is unavailable.");
            }
            gpHooksReady = true;
            getLogger().info("Registered GriefPrevention claim-permission and transfer hooks.");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            getLogger().severe("Could not register GriefPrevention permission hook. Claims will NOT be automatically suspended: " + ex.getMessage());
        }
    }

    private void enforceSuspendedClaimRules(Event event) {
        try {
            Object claim = event.getClass().getMethod("getClaim").invoke(event);
            Object idValue = claim.getClass().getMethod("getID").invoke(claim);
            if (!(idValue instanceof Number number) || !suspendedClaims.containsKey(number.longValue())) return;
            Object permission = event.getClass().getMethod("getRequiredPermission").invoke(event);
            // While public Build/Container/Access permissions make unpaid land unprotected,
            // keep the trust list immutable so nobody can overwrite the saved permissions.
            if ("Manage".equals(String.valueOf(permission)) || "Edit".equals(String.valueOf(permission))) {
                event.getClass().getMethod("setDenialReason", Supplier.class).invoke(event,
                        (Supplier<String>) () -> "This claim's nation has unpaid upkeep. Claim permissions are temporarily locked.");
            }
        } catch (ReflectiveOperationException ex) {
            getLogger().warning("Could not inspect a GriefPrevention permission event: " + ex.getMessage());
        }
    }

    private Object gpDataStore() throws ReflectiveOperationException {
        Class<?> gpClass = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
        Object gp = gpClass.getField("instance").get(null);
        return gpClass.getField("dataStore").get(gp);
    }


    private long claimId(Object claim) throws ReflectiveOperationException {
        Object value = claim.getClass().getMethod("getID").invoke(claim);
        return ((Number) value).longValue();
    }

    private List<Object> allGpClaims() throws ReflectiveOperationException {
        Object store = gpDataStore();
        Object claims = store.getClass().getMethod("getClaims").invoke(store);
        if (!(claims instanceof Iterable<?> iterable)) throw new IllegalStateException("GP claim list is not iterable");
        List<Object> result = new ArrayList<>();
        for (Object claim : iterable) collectClaimTree(claim, result);
        return result;
    }

    private void collectClaimTree(Object claim, List<Object> result) throws ReflectiveOperationException {
        result.add(claim); // parent first; children are processed afterwards
        Object children = claim.getClass().getField("children").get(claim);
        if (children instanceof Iterable<?> iterable) for (Object child : iterable) collectClaimTree(child, result);
    }

    private Map<String, String> readClaimPermissions(Object claim) throws ReflectiveOperationException {
        ArrayList<String> builders = new ArrayList<>(), containers = new ArrayList<>(), accessors = new ArrayList<>(), managers = new ArrayList<>();
        claim.getClass().getMethod("getPermissions", ArrayList.class, ArrayList.class, ArrayList.class, ArrayList.class)
                .invoke(claim, builders, containers, accessors, managers);
        Set<String> playerIds = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        playerIds.addAll(builders); playerIds.addAll(containers); playerIds.addAll(accessors); playerIds.addAll(managers);
        Map<String, String> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        // Ask GP for each exact enum value. Its grouped getPermissions() output can combine
        // legacy Inventory and Container permissions into the same list.
        for (String id : playerIds) {
            Object permission = claim.getClass().getMethod("getPermission", String.class).invoke(claim, id);
            if (permission != null) result.put(id, String.valueOf(permission));
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setClaimPermission(Object claim, String playerId, String level) throws ReflectiveOperationException {
        Class<? extends Enum> permissionClass = (Class<? extends Enum>) Class.forName("me.ryanhamshire.GriefPrevention.ClaimPermission");
        Object value = Enum.valueOf(permissionClass, level);
        claim.getClass().getMethod("setPermission", String.class, permissionClass).invoke(claim, playerId, value);
    }

    private void clearClaimPermissions(Object claim) throws ReflectiveOperationException {
        Map<String, String> current = readClaimPermissions(claim);
        for (String playerId : current.keySet()) claim.getClass().getMethod("dropPermission", String.class).invoke(claim, playerId);
    }

    private void saveGpClaim(Object claim) throws ReflectiveOperationException {
        Object store = gpDataStore();
        store.getClass().getMethod("saveClaim", claim.getClass()).invoke(store, claim);
    }

    private void suspendNationClaims(Nation nation) {
        if (!Bukkit.getPluginManager().isPluginEnabled("GriefPrevention") || !gpHooksReady) return;
        try {
            List<Object> nationClaims = new ArrayList<>();
            for (Object claim : allGpClaims()) {
                UUID owner = (UUID) claim.getClass().getMethod("getOwnerID").invoke(claim);
                if (owner != null && nation.has(owner)) nationClaims.add(claim); // includes subdivisions; excludes admin claims
            }
            // Capture EVERY parent and subdivision before changing any permissions. GP's dropPermission
            // cascades from a parent into its children, so snapshotting one-by-one while mutating is unsafe.
            for (Object claim : nationClaims) {
                long id = claimId(claim);
                if (!suspendedClaims.containsKey(id)) {
                    UUID owner = (UUID) claim.getClass().getMethod("getOwnerID").invoke(claim);
                    suspendedClaims.put(id, new SuspendedClaim(nation.name(), owner, readClaimPermissions(claim)));
                }
            }
            if (!saveSuspendedClaims()) throw new IllegalStateException("Could not persist permission snapshots; no claim permissions were changed");
            // Parent-first application is intentional: GP's dropPermission also clears child permissions.
            for (Object claim : nationClaims) applyPublicPermissions(claim);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            getLogger().severe("Could not safely suspend claims for nation " + nation.name() + ": " + ex.getMessage());
        }
    }

    private void applyPublicPermissions(Object claim) throws ReflectiveOperationException {
        clearClaimPermissions(claim);
        setClaimPermission(claim, "public", "Build");
        setClaimPermission(claim, "public", "Container");
        setClaimPermission(claim, "public", "Access");
        saveGpClaim(claim);
    }

    private void restoreClaimsForNation(String nationName) {
        restoreSuspendedClaims(entry -> entry.nation().equalsIgnoreCase(nationName));
    }

    private void restoreClaimsOwnedBy(UUID owner) {
        restoreSuspendedClaims(entry -> entry.owner().equals(owner));
    }

    private int restoreAllSuspendedClaims() { return restoreSuspendedClaims(entry -> true); }

    private int restoreSuspendedClaims(java.util.function.Predicate<SuspendedClaim> filter) {
        if (!Bukkit.getPluginManager().isPluginEnabled("GriefPrevention")) return 0;
        int restored = 0;
        try {
            Object store = gpDataStore();
            List<Map.Entry<Long, SuspendedClaim>> selected = suspendedClaims.entrySet().stream()
                    .filter(entry -> filter.test(entry.getValue()))
                    .sorted(Comparator.comparingInt(entry -> claimDepthForId(store, entry.getKey())))
                    .toList();
            // Parent first: restoring a parent calls dropPermission recursively, so restore children AFTER parents.
            for (Map.Entry<Long, SuspendedClaim> entry : selected) {
                Object claim = store.getClass().getMethod("getClaim", long.class).invoke(store, entry.getKey());
                if (claim != null) {
                    clearClaimPermissions(claim);
                    for (Map.Entry<String, String> permission : entry.getValue().permissions().entrySet())
                        setClaimPermission(claim, permission.getKey(), permission.getValue());
                    saveGpClaim(claim);
                    restored++;
                }
                suspendedClaims.remove(entry.getKey()); // deleted claims have nothing to restore
            }
            saveSuspendedClaims();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            getLogger().severe("Claim restoration stopped. Some snapshots remain saved for recovery: " + ex.getMessage());
        }
        return restored;
    }

    private int claimDepthForId(Object store, long id) {
        if (store == null) return Integer.MAX_VALUE;
        try {
            Object claim = store.getClass().getMethod("getClaim", long.class).invoke(store, id);
            int depth = 0;
            while (claim != null) {
                Object parent = claim.getClass().getField("parent").get(claim);
                if (parent == null) break;
                depth++; claim = parent;
            }
            return depth;
        } catch (ReflectiveOperationException ex) { return Integer.MAX_VALUE; }
    }

    private void loadSuspendedClaims() {
        File file = new File(getDataFolder(), "suspended-claims.yml");
        if (!file.exists()) return;
        var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("claims");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                long id = Long.parseLong(key);
                ConfigurationSection c = root.getConfigurationSection(key);
                if (c == null) continue;
                String nation = c.getString("nation", "");
                UUID owner = UUID.fromString(c.getString("owner", ""));
                ConfigurationSection perms = c.getConfigurationSection("permissions");
                Map<String, String> original = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                if (perms != null) for (String playerId : perms.getKeys(false)) original.put(playerId, perms.getString(playerId, ""));
                if (!nation.isBlank()) suspendedClaims.put(id, new SuspendedClaim(nation, owner, original));
            } catch (Exception ex) { getLogger().warning("Ignoring invalid suspended-claim snapshot " + key); }
        }
    }

    private boolean saveSuspendedClaims() {
        var y = new org.bukkit.configuration.file.YamlConfiguration();
        for (Map.Entry<Long, SuspendedClaim> entry : suspendedClaims.entrySet()) {
            String path = "claims." + entry.getKey() + ".";
            y.set(path + "nation", entry.getValue().nation());
            y.set(path + "owner", entry.getValue().owner().toString());
            for (Map.Entry<String, String> permission : entry.getValue().permissions().entrySet())
                y.set(path + "permissions." + permission.getKey(), permission.getValue());
        }
        try { if (!getDataFolder().exists()) getDataFolder().mkdirs(); y.save(new File(getDataFolder(), "suspended-claims.yml")); }
        catch (IOException ex) { getLogger().severe("Could not save suspended-claims.yml: " + ex.getMessage()); return false; }
        return true;
    }

    private void reconcileSuspendedClaims() {
        if (!Bukkit.getPluginManager().isPluginEnabled("GriefPrevention")) return;
        if (!gpHooksReady) {
            getLogger().severe("GriefPrevention hooks are unavailable; restoring saved claim permissions to fail safely.");
            restoreAllSuspendedClaims();
            return;
        }
        if (!getConfig().getBoolean("claims.suspend-protection-when-unpaid", true)) {
            restoreAllSuspendedClaims();
            return;
        }
        // Restore paid/orphaned claims as a batch (parent first), then reapply public access
        // to unpaid claims parent-first so subdivision-specific restrictions are handled correctly.
        restoreSuspendedClaims(snapshot -> {
            Nation nation = nations.get(snapshot.nation());
            return nation == null || nation.paid() || !nation.has(snapshot.owner());
        });
        List<Map.Entry<Long, SuspendedClaim>> unpaid = suspendedClaims.entrySet().stream()
                .filter(entry -> {
                    Nation nation = nations.get(entry.getValue().nation());
                    return nation != null && !nation.paid() && nation.has(entry.getValue().owner());
                })
                .sorted(Comparator.comparingInt(entry -> claimDepthForId(safeDataStore(), entry.getKey())))
                .toList();
        for (Map.Entry<Long, SuspendedClaim> entry : unpaid) applyPublicSuspension(entry.getKey());
    }

    private Object safeDataStore() {
        try { return gpDataStore(); }
        catch (ReflectiveOperationException ex) { return null; }
    }

    private void applyPublicSuspension(long id) {
        try {
            Object store = gpDataStore();
            Object claim = store.getClass().getMethod("getClaim", long.class).invoke(store, id);
            if (claim == null) { suspendedClaims.remove(id); saveSuspendedClaims(); return; }
            applyPublicPermissions(claim);
        } catch (ReflectiveOperationException ex) { getLogger().severe("Could not reapply suspension for claim " + id + ": " + ex.getMessage()); }
    }

    private void scheduleNextCharge() {
        if (upkeepTask != null) upkeepTask.cancel();
        long delay = Math.max(1L, (nextChargeAt - System.currentTimeMillis()) / 50L);
        upkeepTask = Bukkit.getScheduler().runTaskLater(this, this::chargeAllNations, delay);
    }

    private String money(double d) { return getConfig().getString("upkeep.currency-symbol", "$") + String.format(Locale.US, "%.2f", d); }
    private void help(CommandSender s) { msg(s, "Commands: create, invite, accept, leave, info, members, bank, deposit, withdraw, promote, kick, transfer, disband, ally, enemy, neutral"); }

    private void loadData() {
        File file = new File(getDataFolder(), "nations.yml");
        if (!file.exists()) return;
        var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        nextChargeAt = y.getLong("next-charge-at", 0);
        ConfigurationSection root = y.getConfigurationSection("nations"); if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection c = root.getConfigurationSection(key); if (c == null) continue;
            UUID owner; try { owner = UUID.fromString(c.getString("owner", "")); } catch (Exception e) { continue; }
            Nation n = new Nation(key, owner); n.bank(c.getDouble("bank", 0)); n.claimedBlocks(c.getLong("claimed-blocks", 0)); n.paid(c.getBoolean("paid", true));
            ConfigurationSection m = c.getConfigurationSection("members");
            if (m != null) for (String id : m.getKeys(false)) try { UUID u = UUID.fromString(id); n.members().put(u, m.getString(id, "MEMBER")); membership.put(u, key); } catch (Exception ignored) { }
            for (String id : c.getStringList("invites")) try { n.invites().add(UUID.fromString(id)); } catch (Exception ignored) { }
            n.allies().addAll(c.getStringList("allies")); n.enemies().addAll(c.getStringList("enemies"));
            nations.put(key, n);
        }
        // Remove stale diplomacy references if a nation was deleted while the server was offline.
        for (Nation n : nations.values()) { n.allies().removeIf(name -> !nations.containsKey(name)); n.enemies().removeIf(name -> !nations.containsKey(name)); }
    }

    private void saveData() {
        var y = new org.bukkit.configuration.file.YamlConfiguration(); y.set("next-charge-at", nextChargeAt);
        for (Nation n : nations.values()) {
            String p = "nations." + n.name() + "."; y.set(p + "owner", n.owner().toString()); y.set(p + "bank", n.bank()); y.set(p + "claimed-blocks", n.claimedBlocks()); y.set(p + "paid", n.paid());
            for (var e : n.members().entrySet()) y.set(p + "members." + e.getKey(), e.getValue());
            y.set(p + "invites", n.invites().stream().map(UUID::toString).toList()); y.set(p + "allies", new ArrayList<>(n.allies())); y.set(p + "enemies", new ArrayList<>(n.enemies()));
        }
        try { if (!getDataFolder().exists()) getDataFolder().mkdirs(); y.save(new File(getDataFolder(), "nations.yml")); }
        catch (IOException e) { getLogger().severe("Could not save nations.yml: " + e.getMessage()); }
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] args) {
        if (args.length == 1) return c.getName().equalsIgnoreCase("nation") ? List.of("create", "invite", "accept", "leave", "info", "members", "bank", "deposit", "withdraw", "promote", "kick", "transfer", "disband", "ally", "enemy", "neutral") : List.of("scanclaims", "blocks", "charge", "restoreclaims", "status", "reload");
        if (args.length == 2 && c.getName().equalsIgnoreCase("nation") && Set.of("kick", "promote", "transfer").contains(args[0].toLowerCase(Locale.ROOT)) && s instanceof Player player) {
            Nation nation = nationOf(player.getUniqueId());
            if (nation == null) return Collections.emptyList();
            return nation.members().keySet().stream().map(Bukkit::getPlayer).filter(Objects::nonNull).map(Player::getName).toList();
        }
        if (args.length == 2 && c.getName().equalsIgnoreCase("nation") && Set.of("info", "accept", "ally", "enemy", "neutral").contains(args[0].toLowerCase(Locale.ROOT))) return new ArrayList<>(nations.keySet());
        return Collections.emptyList();
    }

    private record SuspendedClaim(String nation, UUID owner, Map<String, String> permissions) {}

}
