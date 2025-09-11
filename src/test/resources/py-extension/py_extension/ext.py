import time
from datetime import datetime

class TestExtension:
  def info(self):
    return {
      "name": "Test Python Extension",
      "apiVersion": 1,
      "description": "A test extension written in Python to test hook calling."
    }

  def cleanup(self):
    pass

  def authorize(self, identifier: str, headers: dict, client_ip: str) -> bool:
    wolpi_cfg = wolpi.config()
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


def entry():
  return TestExtension()
