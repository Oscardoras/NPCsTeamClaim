package org.bukkitplugin.npcsteamclaim;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

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
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.permissions.Permission;
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
import org.bukkitutils.command.v1_15_V1.Argument;
import org.bukkitutils.command.v1_15_V1.CommandRegister;
import org.bukkitutils.command.v1_15_V1.CommandRegister.CommandExecutorType;
import org.bukkitutils.command.v1_15_V1.CustomArgument;
import org.bukkitutils.command.v1_15_V1.LiteralArgument;
import org.bukkitutils.io.ConfigurationFile;
import org.bukkitutils.io.TranslatableMessage;

public class NPCsTeamClaimPlugin extends BukkitPlugin implements Listener {
	
	public static NPCsTeamClaimPlugin plugin;
	
	public NPCsTeamClaimPlugin() {
		plugin = this;
	}
	
	
	public class Structure {
		public final String name;
		public final StructureType type;
		public final int radius;
		public final Class<?>[] entityTypes;
		public final String[] names;
		public final Map<ClaimRule, Boolean> claimRules;
		
		public Structure(String name, StructureType type, int radius, List<String> entityTypes, List<String> names, Map<ClaimRule, Boolean> claimRules) {
			this.name = name;
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
	public static final Map<String, Structure> structures = new HashMap<String, Structure>();
	
	@Override
	public void onLoad() {
		LinkedHashMap<String, Argument<?>> arguments = new LinkedHashMap<>();
		arguments.put("claim", new LiteralArgument("claim").withPermission(new Permission("npcsteamclaim.command.teamclaimstructure")));
		arguments.put("structure", new LiteralArgument("structure").withPermission(new Permission("npcsteamclaim.command.teamclaimstructure")));
		arguments.put("type", new CustomArgument<Structure>() {
			protected Structure parse(String arg, SuggestedCommand cmd) throws Exception {
				Structure structure = structures.get(arg);
				if (structure == null) throw getCustomException(new TranslatableMessage(plugin, "structure.does_not_exist", arg).getMessage(cmd.getLanguage()));
				else return structure;
			}
		}.withSuggestionsProvider((cmd) -> {
			return structures.keySet();
		}));
		CommandRegister.register("t", arguments, new Permission("npcsteamclaim.command.teamclaimstructure"), CommandExecutorType.ENTITY, (cmd) -> {
			Team team = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(cmd.getExecutor().getName());
			claimPlace(cmd.getLocation().getChunk(), (Structure) cmd.getArg(0), team);
			cmd.sendMessage(new TranslatableMessage(this, "structure.claimed"));
			return 1;
		});
	}
	
	@Override
	public void onEnable() {
		Bukkit.getPluginManager().registerEvents(this, this);
		
		saveDefaultConfig();
		
		for (String key : getConfig().getKeys(false)) {
			try {
				Map<ClaimRule, Boolean> claimRules = new HashMap<ClaimRule, Boolean>();
				if (getConfig().contains(key + ".claim_rules"))
					for (String rule : getConfig().getConfigurationSection(key + ".claim_rules").getKeys(false))
						claimRules.put(ClaimRule.valueOf(rule), getConfig().getBoolean(key + ".claim_rules." + rule));
					
				String name = key.toLowerCase();
				structures.put(name, new Structure(
					name,
					StructureType.getStructureTypes().get(name),
					getConfig().getInt(key + ".radius"),
					getConfig().getStringList(key + ".entity_types"),
					getConfig().getStringList(key + ".names"),
					claimRules
				));
			} catch (Exception e) {
				getLogger().warning("Structure type " + key + " is malformed.");
			}
		}
	}
	
	@EventHandler
	public void onTarget(EntityTargetEvent e) {
		Entity target = e.getTarget();
		if (target != null) {
			Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
			Team entityTeam = scoreboard.getEntryTeam(e.getEntity().getName());
			if (entityTeam != null) {
				Team tagetTeam = scoreboard.getEntryTeam(target.getName());
				if (entityTeam.equals(tagetTeam)) e.setCancelled(true);
			}
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
		Chunk chunk = location.getChunk();
		Claimable claimable = Claimable.get(chunk);
		if (claimable instanceof ProtectedClaim) {
			Owner owner = ((Claim) claimable).getOwner();
			if (owner instanceof TeamOwner) {
				ConfigurationFile config = StructureClaim.getConfig(location.getWorld());
				if (config.contains(chunk.getX() + "." + chunk.getZ() + ".structure")) {
					Structure structure = structures.get(config.getString(chunk.getX() + "." + chunk.getZ() + ".structure"));
					if (structure != null) {
						for (Class<?> type : structure.entityTypes) {
							if (type.isInstance(entity)) {
								((TeamOwner) owner).getTeam().addEntry(new EntityOwner(entity).getEntry());
								break;
							}
						}
					}
				}
			}
		}
	}
	
	@EventHandler
	public void onLoad(ChunkLoadEvent e) {
		if (e.isNewChunk()) {
			Chunk chunk = e.getChunk();
			for (Structure structure : structures.values()) if (structure.type != null) {
				Bukkit.getScheduler().scheduleSyncDelayedTask(this, () -> {
					World world = chunk.getWorld();
					Location location = world.locateNearestStructure(chunk.getBlock(0, 0, 0).getLocation(), structure.type, 1, false);
					if (location != null && location.getChunk().equals(chunk) && !(Claimable.get(chunk) instanceof Claim))
						claimPlace(chunk, structure, null);
				}, 20l);
			}
		}
	}
	
	public void claimPlace(Chunk center, Structure structure, Team team) {
		Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
		
		Random rand = new Random();
		String name = structure.names[rand.nextInt(structure.names.length)];
		String id = name.replaceAll("[^A-Za-z0-9 ]", "").replaceAll(" ", "_");
		if (name.replaceAll(" ", "").equals("")) {
			name = "Place";
			id = name;
		}
		
		if (team == null) {
			if (id.length() > 10) id = id.substring(0, 10);
			if (scoreboard.getTeam(id) == null) team = scoreboard.registerNewTeam(id);
			else {
				int i = 2;
				for (;scoreboard.getTeam(id + i) != null; i++);
				team = scoreboard.registerNewTeam(id + i);
			}
			team.setDisplayName(name);
		}
		TeamOwner teamOwner = new TeamOwner(team);
		
		World world = center.getWorld();
		int x = center.getX();
		int z = center.getZ();
		ConfigurationFile config = StructureClaim.getConfig(world);
		for (int i = -structure.radius; i <= structure.radius; i++) {
			for (int j = -structure.radius; j <= structure.radius; j++) {
				if (Math.sqrt(i*i + j*j) <= structure.radius) {
					Chunk c = world.getChunkAt(x+i, z+j);
					c.load(true);
					Claimable claim = Claimable.get(c);
					if (!(claim instanceof Claim)) {
						claim.protect(teamOwner);
						config.set(x + "." + z + ".structure", structure.name);
						ProtectedClaim protectedClaim = (ProtectedClaim) Claimable.get(c);
						for (Entry<ClaimRule, Boolean> entry : structure.claimRules.entrySet())
							protectedClaim.setClaimRuleValue(entry.getKey(), RuleTarget.NEUTRALS, entry.getValue());
						for (Entity entity : c.getEntities()) {
							for (Class<?> type : structure.entityTypes) {
								if (type.isInstance(entity)) {
									String entry = entity.getName();
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
	
}

class StructureClaim extends Claimable {
	protected StructureClaim(Chunk chunk, ConfigurationFile config) {
		super(chunk, config);
	}
	public static ConfigurationFile getConfig(World world) {
		return Claimable.getConfig(world);
	}
}