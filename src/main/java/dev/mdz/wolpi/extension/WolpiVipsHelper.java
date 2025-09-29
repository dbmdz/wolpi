package dev.mdz.wolpi.extension;

import app.photofox.vipsffm.VEnum;
import app.photofox.vipsffm.VImage;
import app.photofox.vipsffm.VipsHelper;
import app.photofox.vipsffm.enums.VipsBandFormat;
import app.photofox.vipsffm.enums.VipsInterpretation;
import java.util.Arrays;

/// Small wrapper around [VipsHelper] to provide more idiomatic access to some of its methods
/// that don't require raw pointers. Intended for use by extensions or our own shim code, which
/// don't have access to [java.lang.foreign.MemorySegment].
public class WolpiVipsHelper {
  public static int image_get_bands(VImage img) {
    return VipsHelper.image_get_bands(img.getUnsafeStructAddress());
  }

  public static VipsBandFormat image_get_band_format(VImage img) {
    return mapToEnum(
        VipsHelper.image_get_format(img.getUnsafeStructAddress()),
        VipsBandFormat.class
    );
  }

  public static VipsInterpretation image_get_interpretation(VImage img) {
    return mapToEnum(
        VipsHelper.image_get_interpretation(img.getUnsafeStructAddress()),
        VipsInterpretation.class
    );
  }

  private static <T extends VEnum> T mapToEnum(int rawVal, Class<T> enumClass) {
    return Arrays.stream(enumClass.getEnumConstants())
        .filter(v -> ((T) v).getRawValue() == rawVal)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Unknown enum value: " + rawVal));
  }
}
