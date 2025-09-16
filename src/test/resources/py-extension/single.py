import sys
from pathlib import Path
from datetime import datetime

sys.path.insert(0, str(Path(__file__).parent.absolute()))

import py_extension

ext = py_extension.wolpi_extension()

def info():
    return ext.info()

def cleanup():
    return ext.cleanup()

def authorize(identifier: str, headers: dict, client_ip: str) -> bool:
    return ext.authorize(identifier, headers, client_ip)

def resolve(identifier: str, etag: str | None, last_modified: datetime | None) -> str | None:
    return ext.resolve(identifier, etag, last_modified)