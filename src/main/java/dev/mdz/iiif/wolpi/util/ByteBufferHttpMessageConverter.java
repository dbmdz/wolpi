package dev.mdz.iiif.wolpi.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

/// [HttpMessageConverter] that allows writing [ByteBuffer]s directly to the HTTP response body.
public class ByteBufferHttpMessageConverter extends AbstractHttpMessageConverter<ByteBuffer> {

  public ByteBufferHttpMessageConverter() {
    super(MediaType.ALL);
  }

  @Override
  public boolean canRead(Class<?> clazz, @Nullable MediaType mediaType) {
    return false;
  }

  @Override
  protected boolean supports(Class<?> clazz) {
    return ByteBuffer.class.isAssignableFrom(clazz);
  }

  @Override
  protected ByteBuffer readInternal(
      Class<? extends ByteBuffer> clazz, HttpInputMessage inputMessage)
      throws IOException, HttpMessageNotReadableException {
    // Not implemented for writing-only converter.
    throw new UnsupportedOperationException();
  }

  @Override
  protected void writeInternal(ByteBuffer byteBuffer, HttpOutputMessage outputMessage)
      throws IOException, HttpMessageNotWritableException {
    final OutputStream outputStream = outputMessage.getBody();
    try (WritableByteChannel channel = Channels.newChannel(outputStream)) {
      channel.write(byteBuffer);
    }
  }
}
