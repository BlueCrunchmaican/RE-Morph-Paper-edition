package dev.morphreborn.client.arm.model;

import dev.morphreborn.client.arm.ArmGuiOverride;
import dev.morphreborn.client.arm.ArmProtocol;
import dev.morphreborn.client.arm.ArmStacks;
import dev.morphreborn.client.arm.geom.ArmDraw;
import dev.morphreborn.client.arm.geom.ArmGeometry;
import dev.morphreborn.client.arm.geom.FormGeometry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The arm's item model: what {@code ItemModelResolver.appendItemLayers} builds for an arm stack instead of the model
 * its item_model names (ArmHooks.itemLayers, D16). This is the one place every item look passes through, in every
 * view: first and third person, the GUI, the paper doll, the inventory preview, item frames. So the arm is posed by
 * all of vanilla's own transforms around it, and the vanilla tool it is made from (an iron sword for the katana) is
 * never drawn: once a stack is recognised as an arm, vanilla's model is skipped, even if building the arm fails.
 * <p>
 * The one layer it makes: the context's item transform ({@link ArmTransforms}), grip space G as its local transform in
 * the hands, the form's GUI fit everywhere else (turned and scaled when a screen set an {@link ArmGuiOverride} for this
 * icon), lit like a block in slots (3D shading), animated (so the GUI never caches a frame of it), and
 * {@link ArmFormRenderer} with this draw's {@link ArmDraw} as its argument.
 */
public final class ArmFormItemModel {
	/** The mod's logger by name (MorphClient.LOGGER is the same one), so this class loads without the client. */
	private static final Logger LOG = LoggerFactory.getLogger("RE:Morph");
	/** The model's identity in the GUI's item atlas (with the form and tier after it). */
	private static final Object IDENTITY = new Object();
	/** Each form's rest-pose extents (G px), worked out once: the item's bounding box. */
	private static final Map<String, Supplier<Vector3fc[]>> EXTENTS = new ConcurrentHashMap<>();
	private static boolean failed;

	private ArmFormItemModel() {}

	/**
	 * ArmHooks.itemLayers: fills {@code out} for an arm stack and returns true (vanilla's model is skipped); false for
	 * every other stack, cheaply.
	 */
	public static boolean append(ItemStackRenderState out, ItemStack stack, ItemModelResolver resolver, ItemDisplayContext ctx, @Nullable Level level,
			@Nullable ItemOwner owner, int seed) {
		ArmStacks.Recognized arm = ArmStacks.recognize(stack, owner, ctx);
		if (arm == null) return false;
		try {
			update(out, arm, stack, ctx, owner);
		} catch (RuntimeException e) {
			// never fall back to the vanilla tool: an arm that can't be built shows nothing this frame
			if (!failed) {
				failed = true;
				LOG.error("Arm item model failed for {}; later failures aren't logged", arm.form(), e);
			}
		}
		return true;
	}

	private static void update(ItemStackRenderState out, ArmStacks.Recognized arm, ItemStack stack, ItemDisplayContext ctx, @Nullable ItemOwner owner) {
		FormGeometry geometry = ArmGeometry.get(arm.form());
		boolean hand = ArmTransforms.inHand(ctx);
		ArmGuiOverride.Override pose = hand ? null : ArmGuiOverride.take();
		ArmDraw draw = ArmDrawFactory.forItem(arm, stack, ctx, owner);
		out.appendModelIdentityElement(IDENTITY);
		out.appendModelIdentityElement(arm);
		out.setAnimated();
		if (pose != null && pose.scale() > 1F) out.setOversizedInGui(true);
		ItemStackRenderState.LayerRenderState layer = out.newLayer();
		layer.setUsesBlockLight(true);
		// the trident's raised-to-throw pose is its own display block, used while the use animation runs (bowPull counts the use)
		boolean throwing = ArmProtocol.TRIDENT.equals(arm.form()) && draw.bowPull() > 0F;
		layer.setItemTransform(ArmTransforms.forForm(ctx, arm.form(), geometry.bowTransforms(), throwing));
		Matrix4f local = new Matrix4f();
		if (hand) {
			ArmTransforms.handFrame(ctx, arm.form(), geometry.bowTransforms(), throwing, local);
		} else if (pose != null) {
			ArmTransforms.guiTurned(geometry.guiScale(), geometry.guiGripPx(), pose.yawDeg(), pose.pitchDeg(), pose.scale(), local);
		} else {
			ArmTransforms.gui(geometry.guiScale(), geometry.guiGripPx(), local);
		}
		layer.setLocalTransform(local);
		layer.setExtents(extents(arm.form(), geometry));
		ArmFormRenderer renderer = pose == null ? ArmFormRenderer.INSTANCE : ArmFormRenderer.of(pose.tintArgb(), pose.fullBright());
		layer.setupSpecialModel(renderer, draw);
	}

	private static Supplier<Vector3fc[]> extents(String form, FormGeometry geometry) {
		return EXTENTS.computeIfAbsent(form, f -> {
			List<Vector3fc> points = new ArrayList<>();
			geometry.extents(v -> points.add(new Vector3f(v)));
			Vector3fc[] array = points.toArray(new Vector3fc[0]);
			return () -> array;
		});
	}
}
