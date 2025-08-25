package dev.mdz.iiif.wolpi.model.params;

public record ImageRequest(
    String identifier,
    IIIFVersion version,
    String cropSpec,
    String sizeSpec,
    String rotationSpec,
    String qualitySpec,
    String formatSpec) {

  ///  Construct the IIIF Image API request path for this request
  public String toRequestPath() {
    return "%s/%s/%s/%s/%s/%s.%s".formatted(
        version.name().toLowerCase(), identifier, cropSpec, sizeSpec, rotationSpec, qualitySpec,
        formatSpec);
  }
}
