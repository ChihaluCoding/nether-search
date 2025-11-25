package chihalu.nether.search;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.datafixers.util.Pair;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
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
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.NetherFortressGenerator;
import net.minecraft.structure.PoolStructurePiece;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.structure.pool.LegacySinglePoolElement;
import net.minecraft.structure.pool.SinglePoolElement;
import net.minecraft.structure.pool.StructurePoolElement;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class NetherSearch implements ModInitializer {
	public static final String MOD_ID = "nether-search";

	private static final int CHEST_GLOW_RADIUS = 96;
	private static final int MIN_CHEST_RADIUS = 16;
	private static final int MAX_CHEST_RADIUS = 192;
	private static final long GLOW_DURATION_TICKS = 20L * 60 * 10;
	private static final List<ArmorStandEntity> ACTIVE_MARKERS = new ArrayList<>();
	private static boolean boundingBoxWarningIssued = false;
	private static final Map<String, Set<Long>> FOUND_STRUCTURE_CHUNKS = new HashMap<>();
	private static final Map<GlowKey, ArmorStandEntity> ACTIVE_GLOW_MARKERS = new HashMap<>();
	private static final String GLOW_MARKER_TAG = "nether_search:glow_marker";
	private static boolean needsMarkerRefresh = true;
	private static long glowExpireTick = -1;
	private static RegistryKey<World> glowWorldKey = null;

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Loading Nether Search command helpers");

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				registerCommands(dispatcher, "/seed")
		);

		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (world instanceof ServerWorld serverWorld) {
				handleChestBreak(serverWorld, pos);
			}
		});
		ServerLifecycleEvents.SERVER_STARTING.register(server -> resetGlowState());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> resetGlowState());
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
								.executes(ctx -> executeGlowChests(ctx.getSource(), CHEST_GLOW_RADIUS))
								.then(CommandManager.argument("range", IntegerArgumentType.integer(MIN_CHEST_RADIUS))
										.executes(ctx -> executeGlowChests(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "range")))))
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
				source.sendError(Text.literal("構造物が見つかりません: " + structureId));
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
			List<BlockPos> found = new ArrayList<>();

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

					BlockPos refinedPos = resolveStructureCenter(world, chunkPos, targetList, structureId);
					BlockPos candidatePos = refinedPos != null ? refinedPos : located;

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

					found.add(candidatePos);
					markStructureKnown(structureId, chunkPos);
				}
			}

			if (found.isEmpty()) {
				source.sendFeedback(() -> Text.literal("範囲内で新しい構造物は見つかりませんでした"), false);
				return 0;
			}

			found.sort(Comparator.comparingDouble(pos -> originVec.squaredDistanceTo(Vec3d.ofCenter(pos))));
			String header = found.size() + "個の" + getStructureDisplayName(structureId) + "が見つかりました";
			source.sendFeedback(() -> Text.literal(header).formatted(Formatting.LIGHT_PURPLE), false);
			int index = 1;
			for (BlockPos pos : found) {
				double distance = Math.sqrt(originVec.squaredDistanceTo(Vec3d.ofCenter(pos)));
				Text line = Text.empty()
						.append(Text.literal("[" + index + "] ").formatted(Formatting.GREEN))
						.append(Text.literal(pos.getX() + " / " + pos.getZ()).formatted(Formatting.YELLOW))
						.append(Text.literal(" (距離: " + String.format("%.1f", distance) + ")"));
				source.sendFeedback(() -> line, false);
				index++;
			}
			return found.size();
		} catch (Exception e) {
			LOGGER.error("構造物検索中にエラー", e);
			source.sendError(Text.literal("検索中にエラーが発生しました: " + e.getClass().getSimpleName()));
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

private static int executeGlowChests(ServerCommandSource source, int requestedRadius) {
	int radius = validateRadius(source, requestedRadius);
	if (radius < 0) {
			return 0;
		}
		ServerWorld world = source.getWorld();
		BlockPos center = BlockPos.ofFloored(source.getPosition());
		clearGlowMarkers();
		int count = createGlowMarkers(world, center, radius);
		if (count <= 0) {
			source.sendFeedback(() -> Text.literal("周囲にチェストは見つかりませんでした"), false);
			return 0;
		}
		glowWorldKey = world.getRegistryKey();
		glowExpireTick = world.getTime() + GLOW_DURATION_TICKS;
	source.sendFeedback(() -> Text.literal("チェスト " + count + "個を発光させました（10分後に自動停止）"), false);
	return count;
}

private static int validateRadius(ServerCommandSource source, int radius) {
	if (radius > MAX_CHEST_RADIUS) {
		source.sendError(Text.literal("※範囲上限を超えています※").formatted(Formatting.RED));
		return -1;
	}
	if (radius < MIN_CHEST_RADIUS) {
		return MIN_CHEST_RADIUS;
	}
	return radius;
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


	private static void clearGlowMarkers() {
		for (ArmorStandEntity entity : ACTIVE_MARKERS) {
			if (entity != null && entity.isAlive()) {
				entity.discard();
			}
		}
		ACTIVE_MARKERS.clear();
		ACTIVE_GLOW_MARKERS.clear();
		glowExpireTick = -1;
		glowWorldKey = null;
	}

	private static String getStructureDisplayName(String id) {
		return switch (id) {
			case "fortress" -> "ネザー要塞";
			case "bastion_remnant" -> "ピグリン要塞";
			default -> id.replace('_', ' ');
		};
	}

	private static boolean isStructureKnown(String id, ChunkPos chunkPos) {
		Set<Long> set = FOUND_STRUCTURE_CHUNKS.get(id);
		return set != null && set.contains(chunkPos.toLong());
	}

	private static void markStructureKnown(String id, ChunkPos chunkPos) {
		FOUND_STRUCTURE_CHUNKS.computeIfAbsent(id, key -> new HashSet<>()).add(chunkPos.toLong());
	}

private static BlockPos resolveStructureCenter(ServerWorld world, ChunkPos chunkPos, RegistryEntryList<Structure> targetList, String structureId) {
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

			BlockPos special = null;
			if ("fortress".equals(structureId)) {
				special = findBridgeCrossing(start.getChildren());
			} else if ("bastion_remnant".equals(structureId)) {
				special = findBastionTreasure(start.getChildren());
			}
			if (special != null) {
				return special;
			}

			BlockBox box = extractBoundingBox(start);
			if (box == null) {
				return null;
			}
			return getPieceCenter(box);
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

	private static BlockPos findBastionTreasure(List<StructurePiece> pieces) {
		BlockPos fallback = null;
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
			if (idString.contains("treasure")) {
				return getPieceCenter(box);
			}
			if (fallback == null && (idString.contains("bridge") || idString.contains("entrance"))) {
				fallback = getPieceCenter(box);
			}
		}
		return fallback;
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

	private static void handleChestBreak(ServerWorld world, BlockPos pos) {
		GlowKey key = new GlowKey(world.getRegistryKey(), pos.toImmutable());
		ArmorStandEntity marker = ACTIVE_GLOW_MARKERS.remove(key);
		if (marker != null) {
			marker.discard();
			ACTIVE_MARKERS.remove(marker);
		}
	}

private static void resetGlowState() {
		clearGlowMarkers();
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
			clearGlowMarkers();
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
				if (marker != null && marker.isAlive()) {
					marker.discard();
				}
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
				source.sendFeedback(() -> Text.literal("周囲にチェストは見つかりませんでした"), false);
			} else {
				final int total = count;
				source.sendFeedback(() -> Text.literal("要塞周辺のチェスト数: " + total + "個"), false);
			}
			return count;
		} catch (Exception e) {
			LOGGER.error("チェスト数の調査中にエラー", e);
			source.sendError(Text.literal("チェスト数の調査中にエラーが発生しました: " + e.getClass().getSimpleName()));
			return 0;
		}
	}



	private static int showCommandUsage(ServerCommandSource source) {
		source.sendFeedback(() -> Text.literal("�R�}���h�ꗗ").formatted(Formatting.LIGHT_PURPLE), false);
		source.sendFeedback(() -> Text.literal("//seed search <���������v�ǂ̎��> <�T����>").formatted(Formatting.YELLOW), false);
		source.sendFeedback(() -> Text.literal("//seed search new <���������v�ǂ̎��> <�T����>").formatted(Formatting.YELLOW), false);
		source.sendFeedback(() -> Text.literal("//seed chest <�͈̓u���b�N��>").formatted(Formatting.YELLOW), false);
		source.sendFeedback(() -> Text.literal("//seed glowing_chest <�͈̓u���b�N��>").formatted(Formatting.YELLOW), false);

		source.sendFeedback(() -> Text.literal("��search�̒T�����͎w�肵�Ȃ��Ă��f�t�H���g��1�T���܂���").formatted(Formatting.RED), false);
		return 1;
	}

	private record GlowKey(RegistryKey<World> worldKey, BlockPos pos) {}
}
