package dev.mdz.wolpi.model;

import app.photofox.vipsffm.VSource;
import java.lang.foreign.Arena;
import java.util.function.Function;

/// An image that was resolved to a custom vips source implementation.
public record CustomSourceResolvedImage(Function<Arena, VSource> sourceSupplier) implements ResolvedImage {}
