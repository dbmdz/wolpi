package dev.mdz.wolpi.iiif.exceptions;

/// Exception to indicate that a requested IIIF feature is not implemented in the current
/// configuration.
///
/// Currently, *only* used to indicate to clients that upscaling is not supported if they request
/// an image size larger than the original image.
public class NotImplementedException extends Exception {

  public NotImplementedException(String reason) {
    super(reason);
  }
}
