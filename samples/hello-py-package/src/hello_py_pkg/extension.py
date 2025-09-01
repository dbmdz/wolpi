import requests

HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_9_3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/35.0.1916.47 Safari/537.36'
}

def info():
    return {
        'apiVersion': 1,
        'name': 'wikimedia-resolver',
        'description': 'Resolves images from Wikimedia Commons.'
    }

def resolve(identifier):
    api_url = f"https://commons.wikimedia.org/w/api.php?action=query&titles=File:{identifier}&prop=imageinfo&iiprop=url&format=json&formatversion=2"
    resp = requests.get(api_url, headers=HEADERS)
    if resp.status_code != 200:
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
        'resolve': resolve
    }
