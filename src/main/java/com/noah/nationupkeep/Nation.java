package com.noah.nationupkeep;

import java.util.*;

public final class Nation {
    private final String name;
    private UUID owner;
    private final Map<UUID, String> members = new HashMap<>();
    private final Set<UUID> invites = new HashSet<>();
    private final Set<String> allies = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final Set<String> enemies = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private double bank;
    private long claimedBlocks;
    private boolean paid = true;

    public Nation(String name, UUID owner) { this.name = name; this.owner = owner; members.put(owner, "OWNER"); }
    public String name() { return name; }
    public UUID owner() { return owner; }
    public void owner(UUID id) { owner = id; }
    public Map<UUID,String> members() { return members; }
    public Set<UUID> invites() { return invites; }
    public Set<String> allies() { return allies; }
    public Set<String> enemies() { return enemies; }
    public double bank() { return bank; }
    public void bank(double value) { bank = Math.max(0, value); }
    public long claimedBlocks() { return claimedBlocks; }
    public void claimedBlocks(long value) { claimedBlocks = Math.max(0, value); }
    public boolean paid() { return paid; }
    public void paid(boolean value) { paid = value; }
    public String role(UUID id) { return members.get(id); }
    public boolean has(UUID id) { return members.containsKey(id); }
}
