package dev.mdz.iiif.wolpi.model.extensions;

/**
 * Information about the extension, to be returned by the info() hook.
 *
 * @param apiVersion      Wolpi Extension API version the extension is programmed against. Will be
 *                        used to ensure backwards compatibility in case of future changes to the
 *                        API.
 * @param name            Human-readable name of the extension.
 * @param description     Short description of the extension.
 */
public record ExtensionInfo(
    String apiVersion,
    String name,
    String description) {

}
