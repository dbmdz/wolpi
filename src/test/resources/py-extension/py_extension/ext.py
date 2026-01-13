import datetime
import re
import time

import java

import wolpi
from wolpi.errors import HttpStatusError

COLOR_PAT = re.compile(r"^#(?P<red>[0-9a-fA-F]{2})(?P<green>[0-9a-fA-F]{2})(?P<blue>[0-9a-fA-F]{2})$")

VipsOption = java.type("app.photofox.vipsffm.VipsOption")
VipsSize = java.type("app.photofox.vipsffm.enums.VipsSize")
VipsBandFormat = java.type("app.photofox.vipsffm.enums.VipsBandFormat")
Thread = java.type("java.lang.Thread")

def log_hook_call(hook_name: str):
  if not wolpi.config or not wolpi.config.get("logHooks"):
    return
  with open(wolpi.config["logHooks"], "a") as f:
    f.write(f"{datetime.datetime.now().isoformat()} [{Thread.currentThread().getName()}] {hook_name}\n")

class TestExtension:
  def info(self):
    log_hook_call("info")
    System = java.type("java.lang.System")
    # CHANGE THIS LINE FOR TESTS
    return {
      "name": "Test Python Extension",
      "apiVersion": 1,
      "description": "A test extension written in Python to test hook calling."
    }

  def _raise_from_id(self, identifier: str):
    if identifier == "py-raise-http-400":
      raise HttpStatusError(
          message="HTTP 400 from py", status=400,
          details={"error": "Bad Request"})
    if identifier == "py-raise-http-418":
      raise HttpStatusError(
          message="HTTP 418 from py", status=418,
          details={"error": "I'm a teapot"})
    if identifier == "py-raise":
      raise Exception("Generic exception from py")

  def setup(self):
    log_hook_call("setup")

  def cleanup(self):
    log_hook_call("cleanup")

  def destroy(self):
    log_hook_call("destroy")

  def authorize(self, identifier: str, headers: dict, client_ip: str) -> bool:
    log_hook_call("authorize")
    self._raise_from_id(identifier)
    if wolpi.config is None:
      return True
    wolpi_cfg = dict(wolpi.config)
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
    log_hook_call("resolve")
    self._raise_from_id(identifier)
    if wolpi.config is None:
      return None
    wolpi_cfg = wolpi.config
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
    log_hook_call("augmentInfoJson")
    self._raise_from_id(identifier)
    return {
      **info_json,
      "augmentedFromPython": f"{identifier}-{iiif_version}"
    }

  def pre_process_image(self, image, identifier: str, image_info, image_request):
    log_hook_call("preProcessImage")
    self._raise_from_id(identifier)
    if not identifier.startswith("watermarked:"):
      return None
    cfg = wolpi.config or {}
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
    log_hook_call("preScale")
    wolpi.logger.debug(f"py: pre_scale called with id: {identifier} and spec: {image_request.sizeSpec}")
    self._raise_from_id(identifier)
    if not image_request.sizeSpec.startswith("custom:"):
      return None
    scale_spec = image_request.sizeSpec.replace("custom:", "")
    dimensions = wolpi.imageRequestParser.parseSize(image_request.version, scale_spec, image_info.nativeSize)
    return image.thumbnailImage(dimensions.width, VipsOption.Int("height", dimensions.height), VipsOption.Enum("size", VipsSize.SIZE_FORCE))

  def pre_crop(self, image, identifier: str, image_info, image_request):
    log_hook_call("preCrop")
    self._raise_from_id(identifier)
    if not image_request.cropSpec.startswith("custom"):
      return None
    x, y, width, height = image_request.cropSpec.replace("custom:", "").split(",")
    return image.extractArea(int(x), int(y), int(width), int(height))

  def pre_rotate(self, image, identifier: str, image_info, image_request):
    log_hook_call("preRotate")
    self._raise_from_id(identifier)
    if not image_request.rotationSpec.startswith("custom"):
      return None
    exif_orientation = image.getInt("exif-ifd0-Orientation")
    rotated = image
    if exif_orientation == 8:
      rotated = image.rotate(90.0)
    elif exif_orientation == 3:
      rotated = image.rotate(180.0)
    elif exif_orientation == 6:
      rotated = image.rotate(270.0)
    return rotated

  def pre_quality(self, image, identifier: str, image_info, image_request):
    log_hook_call("preQuality")
    self._raise_from_id(identifier)
    if not image_request.qualitySpec.startswith("custom"):
      return None
    return image.cast(VipsBandFormat.FORMAT_UCHAR).invert()

  def pre_format(self, image, identifier: str, image_info, image_request):
    log_hook_call("preFormat")
    self._raise_from_id(identifier)
    if image_request.formatSpec != "pixl":
      return None
    print("Converting image to PIXL format")
    return {
      "data": b'PIXLDATA',
      "contentType": "image/vnd.pixl",
      "extraHeaders": {
        "X-Wolpi-Encoding-Source": [identifier]
      }
    }

def entry():
  return TestExtension()
