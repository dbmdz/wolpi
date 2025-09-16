package dev.mdz.wolpi.model;

/// Empty marker implementation of ResolvedImage to indicate that the source has not been modified.
///
/// @param notModified Always true, only present to make the record non-empty and suitable for
///                    automatic mapping from Graal polyglot interop.
public record SourceNotModified(boolean notModified) implements ResolvedImage {}
