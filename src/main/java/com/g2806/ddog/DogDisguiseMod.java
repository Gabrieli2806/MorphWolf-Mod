package com.g2806.ddog;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.world.GameMode;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.scoreboard.Team;
import net.minecraft.scoreboard.Scoreboard;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DogDisguiseMod implements ModInitializer {
	private static final String DISGUISE_TEAM_NAME = "ddog_nocollision";

	// Disguise tracking
	private static final Map<UUID, Boolean> disguisedPlayers = new HashMap<>();
	private static final Map<UUID, WolfEntity> disguiseEntities = new HashMap<>();
	private static final Map<UUID, Team> playerOriginalTeams = new HashMap<>();
	private static final Map<UUID, UUID> wolfToPlayerMap = new HashMap<>();

	@Override
	public void onInitialize() {
		// Register commands
		CommandRegistrationCallback.EVENT.register(this::registerCommands);

		// Register events
		registerEvents();

		System.out.println("Dog Disguise Mod initialized!");
	}

	// ==================== COMMAND REGISTRATION ====================

	private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, Object registryAccess, Object environment) {
		dispatcher.register(CommandManager.literal("disguise")
				.executes(this::executeDisguise));

		dispatcher.register(CommandManager.literal("undisguise")
				.executes(this::executeUndisguise));

		// Register dogname command with optional name argument
		dispatcher.register(CommandManager.literal("dogname")
				.executes(this::executeDognameEmpty) // No arguments - clear name
				.then(CommandManager.argument("name", StringArgumentType.greedyString())
					.executes(this::executeDognameWithName))); // With name argument
	}

	private int executeDisguise(CommandContext<ServerCommandSource> context) {
		try {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();

			if (isDisguised(player)) {
				player.sendMessage(Text.literal("You are already disguised!")
						.formatted(Formatting.GRAY), false);
				return 0;
			}

			disguisePlayer(player);
			player.sendMessage(Text.literal("You are now disguised as a tamed dog! Woof! Crouch to sit!")
					.formatted(Formatting.GREEN), false);

			return 1;
		} catch (Exception e) {
			context.getSource().sendMessage(Text.literal("This command can only be used by players!")
					.formatted(Formatting.RED));
			return 0;
		}
	}

	private int executeUndisguise(CommandContext<ServerCommandSource> context) {
		try {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();

			if (!isDisguised(player)) {
				player.sendMessage(Text.literal("You are not disguised!")
						.formatted(Formatting.RED), false);
				return 0;
			}

			undisguisePlayer(player);
			player.sendMessage(Text.literal("You are no longer disguised.")
					.formatted(Formatting.YELLOW), false);

			return 1;
		} catch (Exception e) {
			context.getSource().sendMessage(Text.literal("This command can only be used by players!")
					.formatted(Formatting.GRAY));
			return 0;
		}
	}

	private int executeDognameEmpty(CommandContext<ServerCommandSource> context) {
		try {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();

			if (!isDisguised(player)) {
				player.sendMessage(Text.literal("You must be disguised to change your dog's name!")
						.formatted(Formatting.RED), false);
				return 0;
			}

			WolfEntity wolf = disguiseEntities.get(player.getUuid());
			if (wolf != null) {
				wolf.setCustomName(null);
				wolf.setCustomNameVisible(false);
				player.sendMessage(Text.literal("Your dog's name has been cleared.")
						.formatted(Formatting.GREEN), false);
			}

			return 1;
		} catch (Exception e) {
			context.getSource().sendMessage(Text.literal("This command can only be used by players!")
					.formatted(Formatting.RED));
			return 0;
		}
	}

	private int executeDognameWithName(CommandContext<ServerCommandSource> context) {
		try {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();

			if (!isDisguised(player)) {
				player.sendMessage(Text.literal("You must be disguised to change your dog's name!")
						.formatted(Formatting.RED), false);
				return 0;
			}

			String newName = StringArgumentType.getString(context, "name");
			WolfEntity wolf = disguiseEntities.get(player.getUuid());
			
			if (wolf != null) {
				if (newName.trim().isEmpty()) {
					// If empty string provided, clear the name
					wolf.setCustomName(null);
					wolf.setCustomNameVisible(false);
					player.sendMessage(Text.literal("Your dog's name has been cleared.")
							.formatted(Formatting.GREEN), false);
				} else {
					// Set the new name
					wolf.setCustomName(Text.literal(newName));
					wolf.setCustomNameVisible(true);
					player.sendMessage(Text.literal("Your dog's name has been set to: ")
							.formatted(Formatting.GREEN)
							.append(Text.literal(newName).formatted(Formatting.WHITE)), false);
				}
			}

			return 1;
		} catch (Exception e) {
			context.getSource().sendMessage(Text.literal("This command can only be used by players!")
					.formatted(Formatting.RED));
			return 0;
		}
	}

	// ==================== COLLISION MANAGEMENT ====================

	private static void disablePlayerCollision(ServerPlayerEntity player) {
		Scoreboard scoreboard = player.getScoreboard();
		UUID playerId = player.getUuid();

		// Store player's original team
		Team originalTeam = player.getScoreboardTeam();
		playerOriginalTeams.put(playerId, originalTeam);

		// Get or create no-collision team
		Team noCollisionTeam = scoreboard.getTeam(DISGUISE_TEAM_NAME);
		if (noCollisionTeam == null) {
			noCollisionTeam = scoreboard.addTeam(DISGUISE_TEAM_NAME);
			noCollisionTeam.setCollisionRule(Team.CollisionRule.NEVER);
			noCollisionTeam.setShowFriendlyInvisibles(false);
		}

		// Add player to no-collision team
		scoreboard.addScoreHolderToTeam(player.getNameForScoreboard(), noCollisionTeam);
	}

	private static void enablePlayerCollision(ServerPlayerEntity player) {
		Scoreboard scoreboard = player.getScoreboard();
		UUID playerId = player.getUuid();

		// Remove player from no-collision team
		Team noCollisionTeam = scoreboard.getTeam(DISGUISE_TEAM_NAME);
		if (noCollisionTeam != null) {
			scoreboard.removeScoreHolderFromTeam(player.getNameForScoreboard(), noCollisionTeam);
		}

		// Restore original team if they had one
		Team originalTeam = playerOriginalTeams.get(playerId);
		if (originalTeam != null) {
			scoreboard.addScoreHolderToTeam(player.getNameForScoreboard(), originalTeam);
		}

		// Clean up stored team reference
		playerOriginalTeams.remove(playerId);
	}

	// ==================== DISGUISE MANAGEMENT ====================

	public static void disguisePlayer(ServerPlayerEntity player) {
		UUID playerId = player.getUuid();
		ServerWorld world = player.getServerWorld();

		// Create a wolf entity at player's position
		WolfEntity wolf = new WolfEntity(EntityType.WOLF, world);
		wolf.setPosition(player.getX(), player.getY(), player.getZ());
		wolf.setYaw(player.getYaw());
		wolf.setPitch(player.getPitch());
		wolf.setCustomName(player.getDisplayName());
		wolf.setCustomNameVisible(false);

		// Tame the wolf and set the player as owner
		wolf.setTamed(true, true);
		wolf.setOwner(player);

		// Sync health with player (or make immune if creative)
		syncWolfHealthWithPlayer(wolf, player);

		// Mark player as disguised
		disguisedPlayers.put(playerId, true);
		disguiseEntities.put(playerId, wolf);
		wolfToPlayerMap.put(wolf.getUuid(), playerId);

		// Make player invisible to others
		player.setInvisible(true);

		// Disable player collision
		disablePlayerCollision(player);

		// Spawn wolf for all other players
		world.spawnEntity(wolf);

		// Notify other players about the disguise
		PlayerLookup.world(world).forEach(otherPlayer -> {
			// In a full implementation, you would send custom packets here
			// For now, the wolf entity synchronization handles multiplayer
			System.out.println("Player " + player.getName().getString() + " disguised for " + otherPlayer.getName().getString());
		});
	}

	public static void undisguisePlayer(ServerPlayerEntity player) {
		UUID playerId = player.getUuid();

		// Remove disguise
		disguisedPlayers.remove(playerId);

		// Make player visible again
		player.setInvisible(false);

		// Re-enable player collision
		enablePlayerCollision(player);

		// Remove wolf entity
		WolfEntity wolf = disguiseEntities.get(playerId);
		if (wolf != null) {
			wolfToPlayerMap.remove(wolf.getUuid());
			wolf.discard();
			disguiseEntities.remove(playerId);
		}

		// Notify other players about undisguise
		PlayerLookup.world(player.getServerWorld()).forEach(otherPlayer -> {
			// In a full implementation, you would send custom packets here
			// The wolf entity removal is automatically synchronized
			System.out.println("Player " + player.getName().getString() + " undisguised for " + otherPlayer.getName().getString());
		});
	}

	public static boolean isDisguised(ServerPlayerEntity player) {
		return disguisedPlayers.getOrDefault(player.getUuid(), false);
	}

	public static void updateDisguisePosition(ServerPlayerEntity player) {
		if (!isDisguised(player)) return;

		WolfEntity wolf = disguiseEntities.get(player.getUuid());
		if (wolf != null) {
			// Update position
			wolf.setPosition(player.getX(), player.getY(), player.getZ());

			// Enhanced look direction synchronization
			syncWolfLookDirection(wolf, player);

			// Handle sitting when player is crouching
			wolf.setSitting(player.isSneaking());

			// Sync health with player
			syncWolfHealthWithPlayer(wolf, player);
		}
	}

	// ==================== ENHANCED LOOK DIRECTION SYNC ====================

	private static void syncWolfLookDirection(WolfEntity wolf, ServerPlayerEntity player) {
		// Get player's exact look direction
		float playerYaw = player.getYaw();
		float playerPitch = player.getPitch();

		// Set wolf's body rotation to match player's yaw
		wolf.setYaw(playerYaw);
		wolf.setPitch(playerPitch);

		// Also set the head yaw to match exactly
		wolf.setHeadYaw(playerYaw);

		// Set body yaw for smoother rotation
		wolf.setBodyYaw(playerYaw);

		// For very precise head tracking, we can also set previous values
		// to ensure smooth interpolation on the client side
		wolf.prevYaw = playerYaw;
		wolf.prevPitch = playerPitch;
		wolf.prevHeadYaw = playerYaw;
		wolf.prevBodyYaw = playerYaw;

		// Clamp pitch to reasonable values for a wolf (they can't look straight up/down like players)
		float clampedPitch = MathHelper.clamp(playerPitch, -45.0f, 45.0f);
		wolf.setPitch(clampedPitch);
		wolf.prevPitch = clampedPitch;

		// Update the wolf's velocity to match player movement for even better sync
		wolf.setVelocity(player.getVelocity());
	}

	public static void cleanupPlayer(ServerPlayerEntity player) {
		UUID playerId = player.getUuid();

		// Clean up disguise state
		disguisedPlayers.remove(playerId);

		// Clean up wolf entity
		WolfEntity wolf = disguiseEntities.get(playerId);
		if (wolf != null) {
			wolfToPlayerMap.remove(wolf.getUuid());
			wolf.discard();
			disguiseEntities.remove(playerId);
		}

		// Re-enable collision if player was disguised
		if (playerOriginalTeams.containsKey(playerId)) {
			enablePlayerCollision(player);
		}
	}

	// ==================== HEALTH SYNCHRONIZATION ====================

	private static void syncWolfHealthWithPlayer(WolfEntity wolf, ServerPlayerEntity player) {
		// If player is in creative mode, make wolf immune to damage
		if (player.interactionManager.getGameMode() == GameMode.CREATIVE) {
			wolf.setHealth(wolf.getMaxHealth());
			wolf.setInvulnerable(true);
		} else {
			wolf.setInvulnerable(false);
			// Sync wolf's health percentage with player's health percentage
			float playerHealthPercent = player.getHealth() / player.getMaxHealth();
			float wolfTargetHealth = wolf.getMaxHealth() * playerHealthPercent;
			wolf.setHealth(wolfTargetHealth);
		}
	}

	private static void handleWolfDamage(WolfEntity wolf, DamageSource damageSource, float damageAmount) {
		UUID wolfId = wolf.getUuid();
		UUID playerId = wolfToPlayerMap.get(wolfId);

		if (playerId != null) {
			var server = wolf.getServer();
			if (server != null) {
				var playerManager = server.getPlayerManager();
				if (playerManager != null) {
					ServerPlayerEntity player = playerManager.getPlayer(playerId);
					if (player != null && isDisguised(player)) {
						// Don't damage wolf if player is in creative
						if (player.interactionManager.getGameMode() == GameMode.CREATIVE) {
							return;
						}

						// Calculate damage proportion based on wolf's max health vs player's max health
						float damagePercent = damageAmount / wolf.getMaxHealth();
						float playerDamage = player.getMaxHealth() * damagePercent;

						// Damage the player instead of the wolf
						player.damage(player.getServerWorld(), damageSource, playerDamage);

						// Sync health after damage
						syncWolfHealthWithPlayer(wolf, player);
					}
				}
			}
		}
	}

	private static void handlePlayerDeath(ServerPlayerEntity player) {
		if (isDisguised(player)) {
			WolfEntity wolf = disguiseEntities.get(player.getUuid());
			if (wolf != null) {
				// Kill the wolf when player dies
				wolf.damage((ServerWorld) wolf.getWorld(), wolf.getDamageSources().generic(), Float.MAX_VALUE);
			}
		}
	}

	// ==================== EVENT HANDLING ====================

	private void registerEvents() {
		// Handle player respawn/disconnect
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (!alive) {
				cleanupPlayer(oldPlayer);
			}
		});

		// Handle player disconnect - FIXED: Clean up wolf when player leaves
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> cleanupPlayer(handler.player));

		// Handle game mode changes to maintain disguise
		ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
			// This event fires when player data is copied (including game mode changes)
			if (alive && isDisguised(oldPlayer)) {
				var server = newPlayer.getServer();
				if (server != null) {
					// Small delay to ensure the game mode change is fully processed
					server.execute(() -> {
						if (isDisguised(newPlayer)) {
							// Reapply invisibility after game mode change
							newPlayer.setInvisible(true);
							// Sync wolf health with new game mode
							WolfEntity wolf = disguiseEntities.get(newPlayer.getUuid());
							if (wolf != null) {
								syncWolfHealthWithPlayer(wolf, newPlayer);
							}
						}
					});
				}
			}
		});

		// Handle wolf damage
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, damageSource, damageAmount) -> {
			if (entity instanceof WolfEntity wolf) {
				UUID wolfId = wolf.getUuid();
				UUID playerId = wolfToPlayerMap.get(wolfId);

				if (playerId != null) {
					var server = wolf.getServer();
					if (server != null) {
						var playerManager = server.getPlayerManager();
						if (playerManager != null) {
							ServerPlayerEntity player = playerManager.getPlayer(playerId);
							if (player != null && isDisguised(player)) {
								// Handle damage to disguised wolf
								handleWolfDamage(wolf, damageSource, damageAmount);
								return false; // Cancel wolf damage
							}
						}
					}
				}
			}
			return true; // Allow normal damage
		});

		// Handle player death
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof ServerPlayerEntity player) {
				handlePlayerDeath(player);
			}
		});

		// Handle server tick for position updates and maintaining disguise
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			var playerManager = server.getPlayerManager();
			if (playerManager != null) {
				for (ServerPlayerEntity player : playerManager.getPlayerList()) {
					if (isDisguised(player)) {
						updateDisguisePosition(player);

						// Ensure player stays invisible (fixes game mode change bug)
						if (!player.isInvisible()) {
							player.setInvisible(true);
						}
					}
				}
			}
		});
	}
}
