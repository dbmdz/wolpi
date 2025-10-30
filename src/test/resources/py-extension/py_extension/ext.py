import re
import time

import java

COLOR_PAT = re.compile(r"^#(?P<red>[0-9a-fA-F]{2})(?P<green>[0-9a-fA-F]{2})(?P<blue>[0-9a-fA-F]{2})$")

VipsOption = java.type("app.photofox.vipsffm.VipsOption")
VipsSize = java.type("app.photofox.vipsffm.enums.VipsSize")

class TestExtension:
  def info(self):
    System = java.type("java.lang.System")
    # CHANGE THIS LINE FOR TESTS
    return {
      "name": "Test Python Extension",
      "apiVersion": 1,
      "description": "A test extension written in Python to test hook calling."
    }

  def cleanup(self):
    pass

  def authorize(self, identifier: str, headers: dict, client_ip: str) -> bool:
    if wolpi.config() is None:
      return True
    wolpi_cfg = dict(wolpi.config())
    if (allowed_ids := wolpi_cfg.get("allowedIds")) is not None:
      return identifier in allowed_ids
    elif (forbidden_ids := wolpi_cfg.get("forbiddenIds")) is not None:
      return identifier not in forbidden_ids
    elif (allowed_ips := wolpi_cfg.get("allowedIps")) is not None:
      return client_ip in allowed_ips
    elif (required_headers := wolpi_cfg.get("requiredHeaders")) is not None:
      return all(v in (headers.get(k) or []) for k, v in required_headers.items())
    return True

  def resolve(self, identifier, etag, last_modified):
    if wolpi.config() is None:
      return None
    wolpi_cfg = wolpi.config()
    prefix = wolpi_cfg.get("prefix")
    if prefix:
      if not identifier.startswith(prefix):
        return None
      identifier = identifier[len(prefix):]

    if identifier == "should-take-a-long-time-over-http":
      time.sleep(10)  # Simulate a long operation
      return None

    return {
      "url": f"https://example.com/resource/{identifier}",
    }

  def augment_info_json(self, identifier: str, info_json: dict, iiif_version: int) -> dict:
    return {
      **info_json,
      "augmentedFromPython": f"{identifier}-{iiif_version}"
    }

  def pre_process_image(self, image, identifier: str, image_info, image_request):
    if not identifier.startswith("watermarked:"):
      return None
    cfg = wolpi.config() or {}
    if watermark_color := cfg.get("watermarkColor"):
      match = COLOR_PAT.match(watermark_color)
      if match:
        red = int(match.group("red"), 16)
        green = int(match.group("green"), 16)
        blue = int(match.group("blue"), 16)
      else:
        red, green, blue = 1.0, 0, 0
      image.drawRect([red, green, blue], 0, 0, 100, 100, VipsOption.Boolean("fill", True))
      return image
    else:
      return None

  def pre_scale(self, image, identifier: str, image_info, image_request):
    width, height = image_request.sizeSpec().split(",")
    return image.thumbnailImage(int(width), VipsOption.Int("height", int(height)), VipsOption.Enum("size", VipsSize.SIZE_FORCE))

def entry():
  return TestExtension()
