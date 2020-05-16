package org.bukkitplugin.npcsteamclaim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.StructureType;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkitplugin.claim.claimable.Claim;
import org.bukkitplugin.claim.claimable.Claimable;
import org.bukkitplugin.claim.claimable.ProtectedClaim;
import org.bukkitplugin.claim.owner.EntityOwner;
import org.bukkitplugin.claim.owner.Owner;
import org.bukkitplugin.claim.owner.TeamOwner;
import org.bukkitplugin.claim.rule.ClaimRule;
import org.bukkitplugin.claim.rule.RuleTarget;
import org.bukkitutils.BukkitPlugin;

public class NPCsTeamClaimPlugin extends BukkitPlugin implements Listener {
	
	public static NPCsTeamClaimPlugin plugin;
	
	public NPCsTeamClaimPlugin() {
		plugin = this;
	}
	
	
	public class Structure {
		public final StructureType type;
		public final int radius;
		public final Class<?>[] entityTypes;
		public final String[] names;
		public final Map<ClaimRule, Boolean> claimRules;
		
		public Structure(StructureType type, int radius, List<String> entityTypes, List<String> names, Map<ClaimRule, Boolean> claimRules) {
			this.type = type;
			this.radius = radius;
			
			int length = entityTypes.size();
			Class<?>[] entities = new Class<?>[length];
			for (int i = 0; i < length; i++) {
				try {
					entities[i] = Class.forName(entityTypes.get(i));
				} catch (ClassNotFoundException e) {
					getLogger().warning(entityTypes.get(i) + " is not a valid entity type.");
				}
			}
			this.entityTypes = entities;
			
			this.names = names.toArray(new String[names.size()]);
			
			this.claimRules = claimRules;
		}
	}
	public static final List<Structure> structures = new ArrayList<Structure>();
	
	public void onEnable() {
		Bukkit.getPluginManager().registerEvents(this, this);
		
		saveDefaultConfig();
		
		Set<String> list = StructureType.getStructureTypes().keySet();
		for (String key : getConfig().getKeys(false)) {
			if (list.contains(key.toLowerCase())) {
				try {
					Map<ClaimRule, Boolean> claimRules = new HashMap<ClaimRule, Boolean>();
					if (getConfig().contains(key + ".claim_rules"))
						for (String rule : getConfig().getConfigurationSection(key + ".claim_rules").getKeys(false))
							claimRules.put(ClaimRule.valueOf(rule), getConfig().getBoolean(key + ".claim_rules." + rule));
					structures.add(new Structure(
						StructureType.getStructureTypes().get(key.toLowerCase()),
						getConfig().getInt(key + ".radius"),
						getConfig().getStringList(key + ".entity_types"),
						getConfig().getStringList(key + ".names"),
						claimRules
					));
				} catch (Exception e) {
					getLogger().warning("Structure type " + key + " is malformed.");
				}
			} else getLogger().warning("Structure type " + key + " does not exist.");
		}
	}
	
	@EventHandler
	public void onEntityDeath(EntityDeathEvent e) {
		Entity entity = e.getEntity();
		Team team = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(new EntityOwner(entity).getEntry());
		if (team != null && team.getEntries().size() <= 1) team.unregister();
	}
	
	@EventHandler
	public void onSpawn(CreatureSpawnEvent e) {
		Entity entity = e.getEntity();
		Location location = e.getLocation();
		for (Structure structure : structures) {
			for (Class<?> type : structure.entityTypes) {
				if (type.isInstance(entity)) {
					Claimable claimable = Claimable.get(location.getChunk());
					if (claimable instanceof ProtectedClaim) {
						Owner owner = ((Claim) claimable).getOwner();
						if (owner instanceof TeamOwner) {
							Location loc = location.getWorld().locateNearestStructure(location, structure.type, structure.radius, false);
							if (loc != null) {
								Claimable center = Claimable.get(loc.getChunk());
								if (center instanceof Claim && ((Claim) center).getOwner().equals(owner))
									((TeamOwner) owner).getTeam().addEntry(new EntityOwner(entity).getEntry());
							}
						}
					}
					break;
				}
			}
		}
	}
	
	@EventHandler
	public void onLoad(ChunkLoadEvent e) {
		if (e.isNewChunk()) {
			Chunk chunk = e.getChunk();
			for (Structure structure : structures)
				Bukkit.getScheduler().scheduleSyncDelayedTask(this, () -> {
					World world = chunk.getWorld();
					Location location = world.locateNearestStructure(chunk.getBlock(0, 0, 0).getLocation(), structure.type, 1, false);
					if (location != null && location.getChunk().equals(chunk) && !(Claimable.get(chunk) instanceof Claim)) {
						Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
						
						Random rand = new Random();
						String name = structure.names[rand.nextInt(structure.names.length)];
						String id = name.replaceAll("[^A-Za-z0-9 ]", "").replaceAll(" ", "_");
						if (name.replaceAll(" ", "").equals("")) {
							name = "Place";
							id = name;
						}
						
						if (id.length() > 10) id = id.substring(0, 10);
						Team team = null;
						try {
							team = scoreboard.registerNewTeam(id);
						} catch (IllegalArgumentException e1) {
							int i = 2;
							while (scoreboard.getTeam(id + i) != null) {
								i++;
							}
							team = scoreboard.registerNewTeam(id + i);
						}
						team.setDisplayName(name);
						TeamOwner teamOwner = new TeamOwner(team);
						
						int x = chunk.getX();
						int z = chunk.getZ();
						for (int i = -structure.radius; i <= structure.radius; i++) {
							for (int j = -structure.radius; j <= structure.radius; j++) {
								if (Math.sqrt(i*i + j*j) <= structure.radius) {
									Chunk c = world.getChunkAt(x+i, z+j);
									c.load(true);
									Claimable claim = Claimable.get(c);
									if (!(claim instanceof Claim)) {
										claim.protect(teamOwner);
										ProtectedClaim protectedClaim = (ProtectedClaim) Claimable.get(c);
										for (Entry<ClaimRule, Boolean> entry : structure.claimRules.entrySet())
											protectedClaim.setClaimRuleValue(entry.getKey(), RuleTarget.NEUTRALS, entry.getValue());
										for (Entity entity : c.getEntities()) {
											for (Class<?> type : structure.entityTypes) {
												if (type.isInstance(entity)) {
													String entry = new EntityOwner(entity).getEntry();
													if (scoreboard.getEntryTeam(entry) == null) team.addEntry(entry);
													break;
												}
											}
										}
									}
								}
							}
						}
					}
				}, 20l);
		}
	}
	
}