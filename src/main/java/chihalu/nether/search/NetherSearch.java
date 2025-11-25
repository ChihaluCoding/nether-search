package chihalu.nether.search;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.datafixers.util.Pair;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.structure.NetherFortressGenerator;
import net.minecraft.structure.PoolStructurePiece;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.structure.pool.LegacySinglePoolElement;
import net.minecraft.structure.pool.SinglePoolElement;
import net.minecraft.structure.pool.StructurePoolElement;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.structure.Structure;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class NetherSearch implements ModInitializer {
	public static final String MOD_ID = "nether-search";

	private static final int CHEST_GLOW_RADIUS = 96;
	private static final int MIN_CHEST_RADIUS = 16;
	private static final int MAX_CHEST_RADIUS = 192;
	private static final int DEFAULT_GLOW_SECONDS = 60;
	private static final int MAX_GLOW_SECONDS = 60 * 10;
	private static final List<ArmorStandEntity> ACTIVE_MARKERS = new ArrayList<>();
	private static boolean boundingBoxWarningIssued = false;
	private static final Map<String, Set<Long>> FOUND_STRUCTURE_CHUNKS = new HashMap<>();
	private static final Map<GlowKey, ArmorStandEntity> ACTIVE_GLOW_MARKERS = new HashMap<>();
	private static final String GLOW_MARKER_TAG = "nether_search:glow_marker";
	private static boolean needsMarkerRefresh = true;
	private static long glowExpireTick = -1;
	private static RegistryKey<World> glowWorldKey = null;
	// ピグリン要塞の種別テキストキー（翻訳用）を定数化
	private static final String BASTION_TYPE_TREASURE_KEY = "structure_type.bastion.treasure";
	private static final String BASTION_TYPE_BRIDGE_KEY = "structure_type.bastion.bridge";
	private static final String BASTION_TYPE_HOUSING_KEY = "structure_type.bastion.housing";
	private static final String BASTION_TYPE_HOGLIN_KEY = "structure_type.bastion.hoglin";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Loading Nether Search command helpers");

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				registerCommands(dispatcher, "ns")
		);

		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (world instanceof ServerWorld serverWorld) {
				handleChestBreak(serverWorld, pos);
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> clearGlowMarkers(server));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				server.execute(() -> {
					if (server.getPlayerManager().getPlayerList().isEmpty()) {
						clearGlowMarkers(server);
					}
				}));
		ServerLifecycleEvents.SERVER_STARTING.register(NetherSearch::resetGlowState);
		ServerLifecycleEvents.SERVER_STOPPING.register(NetherSearch::resetGlowState);
		ServerLifecycleEvents.SERVER_STOPPED.register(NetherSearch::resetGlowState);
		ServerTickEvents.END_WORLD_TICK.register(NetherSearch::handleGlowCleanup);
	}

	private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, String literalName) {
		dispatcher.register(
				CommandManager.literal(literalName)
						.requires(source -> true)
						.then(CommandManager.literal("search")
								.then(CommandManager.literal("new")
										.then(CommandManager.literal("fortress")
												.executes(ctx -> executeLocateList(ctx.getSource(), "fortress", 1, true))
												.then(CommandManager.argument("count", IntegerArgumentType.integer(1, 20))
														.executes(ctx -> executeLocateList(ctx.getSource(), "fortress", IntegerArgumentType.getInteger(ctx, "count"), true))))
										.then(CommandManager.literal("bastion_remnant")
												.executes(ctx -> executeLocateList(ctx.getSource(), "bastion_remnant", 1, true))
												.then(CommandManager.argument("count", IntegerArgumentType.integer(1, 20))
														.executes(ctx -> executeLocateList(ctx.getSource(), "bastion_remnant", IntegerArgumentType.getInteger(ctx, "count"), true)))))
								.then(CommandManager.literal("fortress")
										.executes(ctx -> executeLocateList(ctx.getSource(), "fortress", 1, false))
										.then(CommandManager.argument("count", IntegerArgumentType.integer(1, 20))
												.executes(ctx -> executeLocateList(ctx.getSource(), "fortress", IntegerArgumentType.getInteger(ctx, "count"), false))))
								.then(CommandManager.literal("bastion_remnant")
										.executes(ctx -> executeLocateList(ctx.getSource(), "bastion_remnant", 1, false))
										.then(CommandManager.argument("count", IntegerArgumentType.integer(1, 20))
												.executes(ctx -> executeLocateList(ctx.getSource(), "bastion_remnant", IntegerArgumentType.getInteger(ctx, "count"), false)))))
						.then(CommandManager.literal("chest")
								.executes(ctx -> executeChestCount(ctx.getSource(), CHEST_GLOW_RADIUS))
								.then(CommandManager.argument("range", IntegerArgumentType.integer(MIN_CHEST_RADIUS))
										.executes(ctx -> executeChestCount(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "range")))))
						.then(CommandManager.literal("glowing_chest")
								.executes(ctx -> executeGlowChests(ctx.getSource(), CHEST_GLOW_RADIUS, DEFAULT_GLOW_SECONDS))
								.then(CommandManager.argument("range", IntegerArgumentType.integer(MIN_CHEST_RADIUS))
										.executes(ctx -> executeGlowChests(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "range"), DEFAULT_GLOW_SECONDS))
										.then(CommandManager.argument("duration_seconds", IntegerArgumentType.integer(1, MAX_GLOW_SECONDS))
												.executes(ctx -> executeGlowChests(
														ctx.getSource(),
														IntegerArgumentType.getInteger(ctx, "range"),
														IntegerArgumentType.getInteger(ctx, "duration_seconds"))))))
						.then(CommandManager.literal("exp")
								.executes(ctx -> showCommandUsage(ctx.getSource())))
		);
	}

private static int executeLocateList(ServerCommandSource source, String structureId, int count, boolean newOnly) {
		try {
			ServerWorld world = source.getWorld();
			Identifier id = Identifier.ofVanilla(structureId);
			RegistryWrapper.WrapperLookup registryLookup = source.getRegistryManager();
			RegistryEntryLookup<Structure> structureLookup = registryLookup.getOrThrow(RegistryKeys.STRUCTURE);
			RegistryKey<Structure> key = RegistryKey.of(RegistryKeys.STRUCTURE, id);
			Optional<RegistryEntry.Reference<Structure>> structureEntry = structureLookup.getOptional(key);
			if (structureEntry.isEmpty()) {
				source.sendError(message("structure_not_found", structureId));
				return 0;
			}

			RegistryEntryList<Structure> targetList = RegistryEntryList.of(structureEntry.get());
			BlockPos originPos = BlockPos.ofFloored(source.getPosition());
			Vec3d originVec = source.getPosition();

			StructureAccessor accessor = world.getStructureAccessor();
			StructureStart currentStart = accessor.getStructureContaining(originPos, targetList);
			BlockBox currentBox = extractBoundingBox(currentStart);
			ChunkPos currentChunk = extractChunkPos(currentStart);

			int[] offsets = {0, 512, -512, 1024, -1024, 1536, -1536, 2048, -2048, 2560, -2560, 3072, -3072, 4096, -4096};
			Set<ChunkPos> seen = new HashSet<>();
			List<StructureResult> found = new ArrayList<>();

			for (int dx : offsets) {
				for (int dz : offsets) {
					if (found.size() >= count) {
						break;
					}
					BlockPos searchPos = originPos.add(dx, 0, dz);
					int maxOffset = Math.max(Math.abs(dx), Math.abs(dz));
					int chunkRadius = Math.max(128, (maxOffset >> 4) + 128);
					Pair<BlockPos, RegistryEntry<Structure>> result = world.getChunkManager()
							.getChunkGenerator()
							.locateStructure(world, targetList, searchPos, chunkRadius, false);
					if (result == null) {
						continue;
					}
					BlockPos located = result.getFirst();
					ChunkPos chunkPos = new ChunkPos(located);
					if (!seen.add(chunkPos)) {
						continue;
					}
					if (newOnly && isStructureKnown(structureId, chunkPos)) {
						continue;
					}

					// 種類情報付きで座標を補正
					StructureLocation resolvedLocation = resolveStructureCenter(world, chunkPos, targetList, structureId);
					BlockPos candidatePos = resolvedLocation != null ? resolvedLocation.pos() : located;
					String structureTypeKey = resolvedLocation != null ? resolvedLocation.structureTypeKey() : null;

					if (currentChunk != null && currentChunk.equals(chunkPos)) {
						continue;
					}
					if (currentBox != null && currentBox.contains(candidatePos)) {
						continue;
					}

					StructureStart locatedStart = accessor.getStructureContaining(candidatePos, targetList);
					BlockBox locatedBox = extractBoundingBox(locatedStart);
					ChunkPos locatedChunk = extractChunkPos(locatedStart);

					boolean sameStructure = false;
					if (currentBox != null && locatedBox != null) {
						sameStructure = currentBox.equals(locatedBox);
					} else if (currentChunk != null && locatedChunk != null) {
						sameStructure = currentChunk.equals(locatedChunk);
					}

					if (sameStructure) {
						continue;
					}

					ChunkPos resultChunk = locatedChunk != null ? locatedChunk : new ChunkPos(candidatePos);
					found.add(new StructureResult(candidatePos, resultChunk, structureTypeKey));
					markStructureKnown(structureId, chunkPos);
				}
			}

			if (found.isEmpty()) {
				source.sendFeedback(() -> message("no_structures_found"), false);
				return 0;
			}

			found.sort(Comparator.comparingDouble(result -> originVec.squaredDistanceTo(Vec3d.ofCenter(result.pos()))));
			Text structureName = getStructureDisplayName(structureId);
			MutableText header = message("structure_list_header", found.size(), structureName);
			source.sendFeedback(() -> header.formatted(Formatting.LIGHT_PURPLE), false);
			int index = 1;
			for (StructureResult result : found) {
				BlockPos pos = result.pos();
				double distance = Math.sqrt(originVec.squaredDistanceTo(Vec3d.ofCenter(pos)));
				MutableText distanceText = message("structure_distance", String.format("%.1f", distance)).formatted(Formatting.GRAY);
				// 座標をクリックするとテレポートコマンドを即座にコピーできるようにイベント付きテキストを構築
				final String teleportCommand = "/tp " + pos.getX() + " ~ " + pos.getZ();
				final MutableText hoverHint = message("tp_clipboard_hint", teleportCommand).formatted(Formatting.GRAY);
				MutableText coordinateText = Text.literal(pos.getX() + " / " + pos.getZ()).formatted(Formatting.YELLOW)
						.styled(style -> style
								.withClickEvent(new ClickEvent.CopyToClipboard(teleportCommand))
								.withHoverEvent(new HoverEvent.ShowText(hoverHint)));
				MutableText displayLine = Text.empty()
						.append(Text.literal("[" + index + "] ").formatted(Formatting.GREEN))
						.append(coordinateText)
						.append(distanceText);
				if (result.structureTypeKey() != null) {
					// 種別ラベルを距離の後ろに追加
					MutableText typeName = message(result.structureTypeKey()).formatted(Formatting.AQUA);
					MutableText typeLabel = message("structure_type_label", typeName).formatted(Formatting.GRAY);
					displayLine = displayLine.append(typeLabel);
				}
				final Text finalDisplayLine = displayLine;
				source.sendFeedback(() -> finalDisplayLine, false);
				index++;
			}
			return found.size();
		} catch (Exception e) {
			LOGGER.error("構造物検索中にエラー", e);
			source.sendError(message("structure_search_error", e.getClass().getSimpleName()));
			return 0;
		}
	}

	private static BlockBox extractBoundingBox(StructureStart start) {
		if (start == null) {
			return null;
		}
		try {
			return start.getBoundingBox();
		} catch (IllegalStateException e) {
			if (!boundingBoxWarningIssued) {
				LOGGER.warn("構造物の境界ボックス取得に失敗しました: {}", e.getMessage());
				boundingBoxWarningIssued = true;
			}
			return null;
		}
	}

	private static ChunkPos extractChunkPos(StructureStart start) {
		if (start == null) {
			return null;
		}
		try {
			return start.getPos();
		} catch (Exception e) {
			return null;
		}
	}

private static int executeGlowChests(ServerCommandSource source, int requestedRadius, int durationSeconds) {
	int radius = validateRadius(source, requestedRadius);
	if (radius < 0) {
		return 0;
	}
	int glowSeconds = validateGlowDuration(source, durationSeconds);
	if (glowSeconds < 0) {
		return 0;
	}
	ServerWorld world = source.getWorld();
	BlockPos center = BlockPos.ofFloored(source.getPosition());
	clearGlowMarkers(world.getServer());
	int count = createGlowMarkers(world, center, radius);
	if (count <= 0) {
		source.sendFeedback(() -> message("glow_none"), false);
		return 0;
	}
	glowWorldKey = world.getRegistryKey();
	glowExpireTick = world.getTime() + glowSeconds * 20L;
	final int finalGlowSeconds = glowSeconds;
	MutableText durationText = formatDurationText(finalGlowSeconds);
	source.sendFeedback(() -> message("glow_started", count, durationText).formatted(Formatting.YELLOW), false);
	return count;
}

private static int validateRadius(ServerCommandSource source, int radius) {
	if (radius > MAX_CHEST_RADIUS) {
		source.sendError(message("radius_too_large").formatted(Formatting.RED));
		return -1;
	}
	if (radius < MIN_CHEST_RADIUS) {
		return MIN_CHEST_RADIUS;
	}
	return radius;
}

private static int validateGlowDuration(ServerCommandSource source, int seconds) {
	if (seconds < 1) {
		source.sendError(message("duration_too_short").formatted(Formatting.RED));
		return -1;
	}
	if (seconds > MAX_GLOW_SECONDS) {
		source.sendError(message("duration_too_long").formatted(Formatting.RED));
		return -1;
	}
	return seconds;
}

private static MutableText message(String key, Object... args) {
	return Text.translatable("message.nether_search." + key, args);
}

private static MutableText formatDurationText(int seconds) {
	int minutes = seconds / 60;
	int remain = seconds % 60;
	if (minutes > 0) {
		if (remain == 0) {
			return message("duration.minutes", minutes);
		}
		return message("duration.minutes_seconds", minutes, remain);
	}
	return message("duration.seconds", seconds);
}

private static List<BlockPos> findNearbyChests(ServerWorld world, BlockPos center, int radius) {
	int chunkRadius = Math.max(1, (radius / 16) + 1);
	int centerChunkX = center.getX() >> 4;
	int centerChunkZ = center.getZ() >> 4;
	ServerChunkManager chunkManager = world.getChunkManager();

	List<BlockPos> targets = new ArrayList<>();
	for (int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
		for (int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
			WorldChunk chunk = chunkManager.getWorldChunk(chunkX, chunkZ);
			if (chunk == null) {
				continue;
			}
			for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
				if (blockEntity instanceof ChestBlockEntity && blockEntity.getPos().isWithinDistance(center, radius)) {
					targets.add(blockEntity.getPos());
				}
			}
		}
	}
	return targets;
}

private static int createGlowMarkers(ServerWorld world, BlockPos center, int radius) {
	List<BlockPos> targets = findNearbyChests(world, center, radius);
	if (targets.isEmpty()) {
		return 0;
	}
	for (BlockPos pos : targets) {
			ArmorStandEntity marker = new ArmorStandEntity(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
			marker.setInvisible(true);
			marker.setNoGravity(true);
			marker.setInvulnerable(true);
			marker.setGlowing(true);
			marker.setSilent(true);
			marker.setCustomNameVisible(false);
			marker.addCommandTag(GLOW_MARKER_TAG);
			if (world.spawnEntity(marker)) {
				registerMarker(world, marker);
			}
	}
	needsMarkerRefresh = false;
	return targets.size();
}

	private static void clearGlowMarkers(MinecraftServer server) {
		clearGlowMarkersInternal(server);
	}

	private static void clearGlowMarkersInternal(MinecraftServer server) {
		boolean hadMarkers = !ACTIVE_MARKERS.isEmpty();
		RegistryKey<World> targetWorld = glowWorldKey;
		for (ArmorStandEntity entity : ACTIVE_MARKERS) {
			if (entity != null && entity.isAlive()) {
				entity.discard();
			}
		}
		ACTIVE_MARKERS.clear();
		ACTIVE_GLOW_MARKERS.clear();
		glowExpireTick = -1;
		glowWorldKey = null;
		if (hadMarkers && targetWorld != null && server != null) {
			ServerWorld world = server.getWorld(targetWorld);
			if (world != null) {
				Text notice = message("glow_cleared").formatted(Formatting.YELLOW);
				for (ServerPlayerEntity player : world.getPlayers()) {
					player.sendMessage(notice, false);
				}
			}
		}
	}

	private static Text getStructureDisplayName(String id) {
		return switch (id) {
			case "fortress" -> message("structure_name.fortress");
			case "bastion_remnant" -> message("structure_name.bastion_remnant");
			default -> Text.literal(id.replace('_', ' '));
		};
	}

	private static boolean isStructureKnown(String id, ChunkPos chunkPos) {
		Set<Long> set = FOUND_STRUCTURE_CHUNKS.get(id);
		return set != null && set.contains(chunkPos.toLong());
	}

	private static void markStructureKnown(String id, ChunkPos chunkPos) {
		FOUND_STRUCTURE_CHUNKS.computeIfAbsent(id, key -> new HashSet<>()).add(chunkPos.toLong());
	}

// 生成された構造物の中心と種別を解決
private static StructureLocation resolveStructureCenter(ServerWorld world, ChunkPos chunkPos, RegistryEntryList<Structure> targetList, String structureId) {
	LongSet forcedChunks = world.getForcedChunks();
	long chunkLong = ChunkPos.toLong(chunkPos.x, chunkPos.z);
	boolean alreadyForced = forcedChunks.contains(chunkLong);
	if (!alreadyForced) {
			world.setChunkForced(chunkPos.x, chunkPos.z, true);
		}
		try {
			world.getChunkManager().getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true);
			int probeX = chunkPos.getStartX() + 8;
			int probeZ = chunkPos.getStartZ() + 8;
			int probeY = world.getBottomY() + 64;
			BlockPos probe = new BlockPos(probeX, probeY, probeZ);
			StructureStart start = world.getStructureAccessor().getStructureContaining(probe, targetList);
			if (start == null) {
				return null;
			}

			StructureLocation specialLocation = null;
			if ("fortress".equals(structureId)) {
				BlockPos special = findBridgeCrossing(start.getChildren());
				if (special != null) {
					specialLocation = new StructureLocation(special, null);
				}
			} else if ("bastion_remnant".equals(structureId)) {
				specialLocation = findBastionDetails(start.getChildren());
			}
			if (specialLocation != null) {
				return specialLocation;
			}

			BlockBox box = extractBoundingBox(start);
			if (box == null) {
				return null;
			}
			return new StructureLocation(getPieceCenter(box), null);
		} finally {
			if (!alreadyForced) {
				world.setChunkForced(chunkPos.x, chunkPos.z, false);
			}
		}
	}

	private static BlockPos findBridgeCrossing(List<StructurePiece> pieces) {
		BlockPos fallback = null;
		for (StructurePiece piece : pieces) {
			BlockBox pieceBox = piece.getBoundingBox();
			if (pieceBox == null) {
				continue;
			}
			BlockPos center = getPieceCenter(pieceBox);

			if (piece instanceof NetherFortressGenerator.BridgeCrossing
					|| piece instanceof NetherFortressGenerator.CorridorCrossing
					|| piece instanceof NetherFortressGenerator.BridgeSmallCrossing) {
				return center;
			}
			if (fallback == null && piece instanceof NetherFortressGenerator.Bridge) {
				fallback = center;
			}
		}
		return fallback;
	}

	// ピグリン要塞の種別と位置を解決
	private static StructureLocation findBastionDetails(List<StructurePiece> pieces) {
		BlockPos fallbackPos = null;
		String fallbackType = null;
		for (StructurePiece piece : pieces) {
			if (!(piece instanceof PoolStructurePiece poolPiece)) {
				continue;
			}
			Identifier id = getPoolPieceId(poolPiece.getPoolElement());
			if (id == null) {
				continue;
			}
			String idString = id.toString();
			BlockBox box = piece.getBoundingBox();
			if (box == null) {
				continue;
			}
			String detectedType = detectBastionType(idString);
			if (detectedType == null) {
				continue;
			}
			BlockPos center = getPieceCenter(box);
			if (BASTION_TYPE_TREASURE_KEY.equals(detectedType)) {
				return new StructureLocation(center, detectedType);
			}
			if (fallbackPos == null) {
				fallbackPos = center;
				fallbackType = detectedType;
			}
		}
		if (fallbackPos != null) {
			return new StructureLocation(fallbackPos, fallbackType);
		}
		return null;
	}

	// ピースID文字列からピグリン要塞の種別キーを抽出
	private static String detectBastionType(String idString) {
		String lowered = idString.toLowerCase(Locale.ROOT);
		if (lowered.contains("treasure")) {
			return BASTION_TYPE_TREASURE_KEY;
		}
		if (lowered.contains("hoglin") || lowered.contains("stable")) {
			return BASTION_TYPE_HOGLIN_KEY;
		}
		if (lowered.contains("housing") || lowered.contains("units")) {
			return BASTION_TYPE_HOUSING_KEY;
		}
		if (lowered.contains("bridge") || lowered.contains("entrance") || lowered.contains("rampart")) {
			return BASTION_TYPE_BRIDGE_KEY;
		}
		return null;
	}

	private static Identifier getPoolPieceId(StructurePoolElement element) {
		if (element instanceof SinglePoolElement single) {
			return single.getIdOrThrow();
		}
		if (element instanceof LegacySinglePoolElement legacy) {
			return legacy.getIdOrThrow();
		}
		return null;
	}

	private static BlockPos getPieceCenter(BlockBox pieceBox) {
		return new BlockPos((pieceBox.getMinX() + pieceBox.getMaxX()) / 2,
				(pieceBox.getMinY() + pieceBox.getMaxY()) / 2,
				(pieceBox.getMinZ() + pieceBox.getMaxZ()) / 2);
	}
	private static void discardMarkerEntity(ArmorStandEntity marker) {
		if (marker != null && marker.isAlive()) {
			marker.discard();
		}
	}

	private static void handleChestBreak(ServerWorld world, BlockPos pos) {
		GlowKey key = new GlowKey(world.getRegistryKey(), pos.toImmutable());
		ArmorStandEntity marker = ACTIVE_GLOW_MARKERS.remove(key);
		if (marker != null) {
			discardMarkerEntity(marker);
			ACTIVE_MARKERS.remove(marker);
			return;
		}
		Iterator<Map.Entry<GlowKey, ArmorStandEntity>> iterator = ACTIVE_GLOW_MARKERS.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<GlowKey, ArmorStandEntity> entry = iterator.next();
			GlowKey otherKey = entry.getKey();
			if (otherKey.worldKey.equals(key.worldKey) && otherKey.pos().equals(key.pos())) {
				discardMarkerEntity(entry.getValue());
				ACTIVE_MARKERS.remove(entry.getValue());
				iterator.remove();
				break;
			}
		}
	}

private static void resetGlowState(MinecraftServer server) {
		clearGlowMarkers(server);
	needsMarkerRefresh = true;
}

	private static void registerMarker(ServerWorld world, ArmorStandEntity marker) {
		GlowKey key = new GlowKey(world.getRegistryKey(), marker.getBlockPos().toImmutable());
		if (!ACTIVE_GLOW_MARKERS.containsKey(key)) {
			ACTIVE_GLOW_MARKERS.put(key, marker);
		}
		if (!ACTIVE_MARKERS.contains(marker)) {
			ACTIVE_MARKERS.add(marker);
		}
	}

	private static void refreshTrackedMarkers(ServerWorld world) {
		WorldBorder border = world.getWorldBorder();
		double minX = border.getBoundWest();
		double maxX = border.getBoundEast();
		double minZ = border.getBoundNorth();
		double maxZ = border.getBoundSouth();
		double minY = world.getBottomY();
		double maxY = minY + 512;

		Box box = new Box(minX, minY, minZ, maxX, maxY, maxZ);
		List<ArmorStandEntity> markers = world.getEntitiesByClass(ArmorStandEntity.class, box,
				entity -> entity.getCommandTags().contains(GLOW_MARKER_TAG));
		for (ArmorStandEntity marker : markers) {
			registerMarker(world, marker);
		}
		needsMarkerRefresh = false;
	}

	private static void handleGlowCleanup(ServerWorld world) {
		if (needsMarkerRefresh && world.getRegistryKey() == World.NETHER) {
			refreshTrackedMarkers(world);
		}
		if (glowWorldKey != null && glowWorldKey.equals(world.getRegistryKey())
				&& glowExpireTick > 0 && world.getTime() >= glowExpireTick) {
			clearGlowMarkers(world.getServer());
			return;
		}
		if (glowWorldKey != null && glowWorldKey.equals(world.getRegistryKey())
				&& world.getPlayers().isEmpty()) {
			clearGlowMarkers(world.getServer());
			return;
		}
		Iterator<Map.Entry<GlowKey, ArmorStandEntity>> iterator = ACTIVE_GLOW_MARKERS.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<GlowKey, ArmorStandEntity> entry = iterator.next();
			GlowKey key = entry.getKey();
			if (!key.worldKey.equals(world.getRegistryKey())) {
				continue;
			}
			BlockEntity blockEntity = world.getBlockEntity(key.pos());
			if (!(blockEntity instanceof ChestBlockEntity)) {
				ArmorStandEntity marker = entry.getValue();
				discardMarkerEntity(marker);
				ACTIVE_MARKERS.remove(marker);
				iterator.remove();
			}
		}
	}

	private static int executeChestCount(ServerCommandSource source, int requestedRadius) {
		try {
			int radius = validateRadius(source, requestedRadius);
			if (radius < 0) {
				return 0;
			}
			ServerWorld world = source.getWorld();
			BlockPos center = BlockPos.ofFloored(source.getPosition());
			List<BlockPos> targets = findNearbyChests(world, center, radius);
			int count = targets.size();
			if (count == 0) {
				source.sendFeedback(() -> message("chest_none_nearby"), false);
			} else {
				final int total = count;
				source.sendFeedback(() -> message("chest_count", total), false);
			}
			return count;
		} catch (Exception e) {
			LOGGER.error("チェスト数の調査中にエラー", e);
			source.sendError(message("chest_error", e.getClass().getSimpleName()));
			return 0;
		}
	}

private static int showCommandUsage(ServerCommandSource source) {

		source.sendFeedback(() -> message("command_list_title").formatted(Formatting.LIGHT_PURPLE), false);
		source.sendFeedback(() -> message("command_search").formatted(Formatting.YELLOW), false);
		source.sendFeedback(() -> message("command_search_new").formatted(Formatting.YELLOW), false);
		source.sendFeedback(() -> message("command_chest").formatted(Formatting.YELLOW), false);
		source.sendFeedback(() -> message("command_glowing_chest").formatted(Formatting.YELLOW), false);
		source.sendFeedback(Text::empty, false);
		source.sendFeedback(() -> message("command_hint_search").formatted(Formatting.RED), false);
		source.sendFeedback(() -> message("command_hint_glowing").formatted(Formatting.RED), false);

		return 1;

	}
// 構造物の座標と種別ラベルを保持
private record StructureResult(BlockPos pos, ChunkPos chunkPos, String structureTypeKey) {}

// 座標解決時の補助レコード
private record StructureLocation(BlockPos pos, String structureTypeKey) {}

	private record GlowKey(RegistryKey<World> worldKey, BlockPos pos) {}
}
