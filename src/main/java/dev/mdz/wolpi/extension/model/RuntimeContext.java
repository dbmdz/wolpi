package dev.mdz.wolpi.extension.model;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/// Runtime context of an extension, contains all the state that is bound to the lifetime of a
/// single request.
///
/// @param lang            The GraalVM Polyglot language the extension is implemented in
/// @param langContext     The GraalVM Polyglot context the extension runs in
/// @param wolpiContext    The Wolpi-specific context object, available in the global scope of the
///                        extension to interact with Wolpi
/// @param extensionObject The main extension object, containing the extension hooks as members
public record RuntimeContext(
    Language lang,
    Context langContext,
    ExtensionGuestContext wolpiContext,
    Value extensionObject) {}
