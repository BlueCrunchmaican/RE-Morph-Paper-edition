package dev.morphreborn.client.mixin;

import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ModelPart.class)
public interface ModelPartAccessor {
	@Accessor("cubes")
	List<ModelPart.Cube> morph$cubes();

	@Accessor("children")
	Map<String, ModelPart> morph$children();

	@Accessor("initialPose")
	PartPose morph$initialPose();
}
