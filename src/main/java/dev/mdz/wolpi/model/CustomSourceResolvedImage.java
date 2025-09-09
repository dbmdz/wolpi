package dev.mdz.wolpi.model;

import app.photofox.vipsffm.VCustomSource;

/// An image that was resolved to a custom vips source implementation.
public record CustomSourceResolvedImage(VCustomSource source) implements ResolvedImage {}
