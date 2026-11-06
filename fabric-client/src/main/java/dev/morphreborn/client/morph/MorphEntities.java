package dev.morphreborn.client.morph;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import dev.morphreborn.client.MorphClient;
import dev.morphreborn.client.net.MorphRef;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.level.storage.TagValueInput;
import org.jspecify.annotations.Nullable;

/**
 * Client-only entity instances that stand in for morphed players (the original's MorphState.getEntityInstance).
 * They are never added to the level; ids are negative so they can't collide with real entities.
 */
public final class MorphEntities {
	private static final AtomicInteger NEXT_ID = new AtomicInteger(-70_000_000);

	private final Map<UUID, Map<String, LivingEntity>> world = new HashMap<>();
	private final Map<String, LivingEntity> gui = new HashMap<>();
	private final Set<String> broken = new HashSet<>();
	private @Nullable ClientLevel level;

	/** Instance used to render {@code ref} for player {@code owner} in the world. One per player per appearance. */
	public @Nullable LivingEntity forWorld(UUID owner, MorphRef ref) {
		ClientLevel lvl = currentLevel();
		if (lvl == null) return null;
		return world.computeIfAbsent(owner, k -> new HashMap<>()).computeIfAbsent(ref.appearanceKey(), k -> create(lvl, ref));
	}

	/** Shared, static instance for selector previews. */
	public @Nullable LivingEntity forGui(MorphRef ref) {
		ClientLevel lvl = currentLevel();
		if (lvl == null) return null;
		return gui.computeIfAbsent(ref.appearanceKey(), k -> create(lvl, ref));
	}

	/** Drop instances for appearances a player no longer uses. */
	public void retain(UUID owner, @Nullable MorphRef a, @Nullable MorphRef b) {
		Map<String, LivingEntity> map = world.get(owner);
		if (map == null) return;
		map.keySet().removeIf(k -> (a == null || !k.equals(a.appearanceKey())) && (b == null || !k.equals(b.appearanceKey())));
		if (map.isEmpty()) world.remove(owner);
	}

	public void forget(UUID owner) {
		world.remove(owner);
	}

	public void clear() {
		world.clear();
		gui.clear();
		level = null;
	}

	private @Nullable ClientLevel currentLevel() {
		ClientLevel now = Minecraft.getInstance().level;
		if (now != level) {
			world.clear();
			gui.clear();
			level = now;
		}
		return now;
	}

	private LivingEntity create(ClientLevel level, MorphRef ref) {
		LivingEntity created = null;
		try {
			created = ref.isPlayer() ? createPlayer(level, ref) : createMob(level, ref);
		} catch (Exception | LinkageError e) {
			if (broken.add(ref.appearanceKey())) MorphClient.LOGGER.error("Could not create morph entity {} {}", ref.entityType(), ref.snbt(), e);
		}
		if (created == null) {
			// Same fallback as the original: an obviously wrong pig instead of an invisible player.
			created = EntityTypes.PIG.create(level, new EntitySpawnRequest(EntitySpawnReason.LOAD, true));
			if (created != null) created.setCustomName(Component.literal("Invalid Morph Pig"));
		}
		created.setId(NEXT_ID.getAndDecrement());
		created.setSilent(true);
		return created;
	}

	private static @Nullable LivingEntity createMob(ClientLevel level, MorphRef ref) throws Exception {
		Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(ref.entityType()));
		if (type.isEmpty()) return null;
		// Copy: 26.3's SNBT parser returns one shared instance for every "{}" (and "[]"), so editing a parse result in
		// place leaks into every later parse.
		CompoundTag tag = ref.snbt().isEmpty() ? new CompoundTag() : TagParser.parseCompoundFully(ref.snbt()).copy();
		tag.putString("id", ref.entityType());
		Entity entity = type.get().create(level, new EntitySpawnRequest(EntitySpawnReason.LOAD, true));
		if (!(entity instanceof LivingEntity living)) return null;
		ProblemReporter.Collector problems = new ProblemReporter.Collector();
		living.load(TagValueInput.create(problems, level.registryAccess(), tag));
		if (!problems.isEmpty()) MorphClient.LOGGER.warn("Problems loading morph {} {}: {}", ref.entityType(), ref.snbt(), problems.getReport());
		living.setNoGravity(true);
		neverConvert(living);
		return living;
	}

	/**
	 * Piglins, piglin brutes and hoglins outside the Nether are "converting" into zombified mobs, and vanilla draws that
	 * as a constant left-right shake of the body (LivingEntityRenderer.isShaking). A morph never converts; NoAI would
	 * also stop the shake but freezes other mobs' animations (dragon wings, copper golems), so mark it immune instead.
	 */
	private static void neverConvert(LivingEntity living) {
		if (living instanceof AbstractPiglin piglin) piglin.setImmuneToZombification(true);
		else if (living instanceof Hoglin hoglin) hoglin.setImmuneToZombification(true);
	}

	private static LivingEntity createPlayer(ClientLevel level, MorphRef ref) {
		Minecraft mc = Minecraft.getInstance();
		MorphRef.Skin skin = ref.skin();
		UUID id = skin != null ? skin.profileId() : mc.player != null ? mc.player.getUUID() : UUID.randomUUID();
		String name = skin != null ? skin.name() : "Player";
		PropertyMap properties = new PropertyMap(ImmutableMultimap.of());
		if (skin != null && !skin.textureValue().isEmpty()) {
			Property textures = skin.textureSignature().isEmpty() ? new Property("textures", skin.textureValue())
					: new Property("textures", skin.textureValue(), skin.textureSignature());
			properties = new PropertyMap(ImmutableMultimap.of("textures", textures));
		}
		return new MorphPlayer(level, new GameProfile(id, name, properties));
	}

	/** A player body for player-type morphs that shows the right skin even if that player is offline. */
	public static final class MorphPlayer extends RemotePlayer {
		private final @Nullable Supplier<PlayerSkin> lookup;

		MorphPlayer(ClientLevel level, GameProfile profile) {
			super(level, profile);
			this.lookup = profile.properties().isEmpty() ? null : Minecraft.getInstance().getSkinManager().createLookup(profile, false);
			this.getEntityData().set(net.minecraft.world.entity.Avatar.DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7F);
		}

		@Override
		public PlayerSkin getSkin() {
			PlayerInfo info = Minecraft.getInstance().getConnection() == null ? null : Minecraft.getInstance().getConnection().getPlayerInfo(getUUID());
			if (info != null) return info.getSkin();
			if (lookup != null) return lookup.get();
			return super.getSkin();
		}

		@Override
		public boolean isModelPartShown(PlayerModelPart part) {
			return true;
		}
	}
}
