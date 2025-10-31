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

def augment_info_json(identifier: str, info_json: dict, iiif_version: int) -> dict:
    return ext.augment_info_json(identifier, info_json, iiif_version)

def pre_process_image(image, identifier: str, image_info, iiif_request):
    return ext.pre_process_image(image, identifier, image_info, iiif_request)

def pre_scale(image, identifier: str, image_info, iiif_request):
    return ext.pre_scale(image, identifier, image_info, iiif_request)

def pre_crop(image, identifier: str, image_info, iiif_request):
    return ext.pre_crop(image, identifier, image_info, iiif_request)

def pre_rotate(image, identifier: str, image_info, iiif_request):
    return ext.pre_rotate(image, identifier, image_info, iiif_request)

def pre_format(image, identifier: str, image_info, iiif_request):
    return ext.pre_format(image, identifier, image_info, iiif_request)
