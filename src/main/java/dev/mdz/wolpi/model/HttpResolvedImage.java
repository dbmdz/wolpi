package dev.mdz.wolpi.model;

import java.net.URI;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record HttpResolvedImage(URI url, @Nullable Map<String, String> headers) implements ResolvedImage {}
