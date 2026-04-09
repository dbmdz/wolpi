from urllib.parse import quote_plus

import requests

from wolpi import ExtensionInfo, HttpImageSource, SourceNotModified, logger

VALIDATION_ID = '5976299c-b668-11f0-ab84-047bcbb8eed4'
HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_9_3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/35.0.1916.47 Safari/537.36'
}

def info() -> ExtensionInfo:
    return {
        'apiVersion': 1,
        'name': 'wikimedia-resolver',
        'description': 'Resolves images from Wikimedia Commons.'
    }

def resolve(identifier: str, etag: str | None, last_modified: str | None) -> HttpImageSource | SourceNotModified | None:
    if identifier.startswith(VALIDATION_ID):
        return
    api_url = f"https://commons.wikimedia.org/w/api.php?action=query&titles=File:{quote_plus(identifier)}&prop=imageinfo&iiprop=url&format=json&formatversion=2"
    headers = {**HEADERS}
    logger.info(f"Trying to resolve identifier {identifier} from Wikimedia")
    if etag:
        headers['If-None-Match'] = etag
    if last_modified:
        headers['If-Modified-Since'] = last_modified
    resp = requests.get(api_url, headers=headers)
    logger.debug(f"Wikimedia response status code: {resp.status_code}")
    if resp.status_code == 304:
        return {'notModified': True}
    elif resp.status_code != 200:
        return
    data = resp.json()
    if 'query' not in data or 'pages' not in data['query'] or not len(data['query']['pages']):
        return
    page = data['query']['pages'][0]
    if 'imageinfo' not in page or not len(page['imageinfo']):
        return
    image_info = page['imageinfo'][0]
    if 'url' not in image_info:
        return
    return {
        'url': image_info['url'],
        'headers': HEADERS
    }

def extension():
    return {
        'info': info,
        'resolve': resolve,
        'cleanup': lambda: None  # No cleanup necessary
    }
