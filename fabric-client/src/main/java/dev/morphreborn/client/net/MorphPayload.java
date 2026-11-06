package dev.morphreborn.client.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The single plugin-message payload. Its body is the raw {@link Wire} frame, so Paper sees an ordinary
 * {@code morphreborn:main} plugin message with exactly these bytes.
 */
public record MorphPayload(byte[] data) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<MorphPayload> TYPE = new CustomPacketPayload.Type<>(Identifier.parse(Wire.CHANNEL));

	public static final StreamCodec<ByteBuf, MorphPayload> CODEC = StreamCodec.of(
			(buf, payload) -> buf.writeBytes(payload.data),
			buf -> {
				byte[] bytes = new byte[buf.readableBytes()];
				buf.readBytes(bytes);
				return new MorphPayload(bytes);
			});

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
