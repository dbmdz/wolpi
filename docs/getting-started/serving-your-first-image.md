# Serving your first image

The easiest way to run Wolpi is to use the official container image:

```bash
docker run -p 8080:8080 -v <path_to_your_images>:/app/images ghcr.io/dbmdz/wolpi:latest
```

By default, the container serves images from the `/app/images` directory and resolves them according to their path inside of it,, e.g. an image located  at `<path_to_your_images>/foo/bar/baz.jpg` on the host machine will be available at http://localhost:8080/v3/foo/bar/baz.jpg/info.json (for info.json) and e.g. http://localhost:8080/v3/foo/bar/baz.jpg/full/max/0/default.jpg (for an image request)

Here is an example with the logo image from this documentation, assuming you've checked out the repository to `/home/user/src/wolpi`:

```
docker run -p 8080:8080 -v /home/user/src/wolpi/docs/img:/app/images ghcr.io/dbmdz/wolpi:latest
```

```
❯ curl -s localhost:8080/v3/wolpi.png/info.json
{
    "@context": "http://iiif.io/api/image/3/context.json",
    "id": "http://localhost:8080/v3/wolpi.png",
    "type": "ImageService3",
    "protocol": "http://iiif.io/api/image",
    "profile": "level2",
    "width": 1024,
    "height": 1024,
    "sizes": [
        {
            "type": "Size",
            "width": 1024,
            "height": 1024
        }
    ],
    "preferredFormats": [
        "jpg",
        "webp",
        "png"
    ],
    "extraFeatures": [
        "mirroring",
        "rotationArbitrary",
        "bitonal",
        "profileLinkHeader",
        "canonicalLinkHeader"
    ],
    "extraFormats": [
        "tif",
        "gif",
        "jp2",
        "webp"
    ],
    "extraQualities": [
        "bitonal"
    ]
}
```

Navigate to http://localhost:8080/v3/wolpi.png/full/max/0/default.webp in your browser to see the image. You can also try out different parameters, e.g. http://localhost:8080/v3/wolpi.png/full/!200,200/0/default.jpg for a thumbnail.