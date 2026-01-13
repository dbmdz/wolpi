// Grayscale 1x1 JPEG with a grey pixel at 190 brightness
const TEST_JPG = new Uint8Array([
  0xff, 0xd8, 0xff, 0xdb, 0x00, 0x43, 0x00, 0x03, 0x02, 0x02, 0x02, 0x02, 0x02, 0x03, 0x02, 0x02,
  0x02, 0x03, 0x03, 0x03, 0x03, 0x04, 0x06, 0x04, 0x04, 0x04, 0x04, 0x04, 0x08, 0x06, 0x06, 0x05,
  0x06, 0x09, 0x08, 0x0a, 0x0a, 0x09, 0x08, 0x09, 0x09, 0x0a, 0x0c, 0x0f, 0x0c, 0x0a, 0x0b, 0x0e,
  0x0b, 0x09, 0x09, 0x0d, 0x11, 0x0d, 0x0e, 0x0f, 0x10, 0x10, 0x11, 0x10, 0x0a, 0x0c, 0x12, 0x13,
  0x12, 0x10, 0x13, 0x0f, 0x10, 0x10, 0x10, 0xff, 0xc9, 0x00, 0x0b, 0x08, 0x00, 0x01, 0x00, 0x01,
  0x01, 0x01, 0x11, 0x00, 0xff, 0xcc, 0x00, 0x06, 0x00, 0x10, 0x10, 0x05, 0xff, 0xda, 0x00, 0x08,
  0x01, 0x01, 0x00, 0x00, 0x3f, 0x00, 0xd2, 0xcf, 0x20, 0xff, 0xd9
]);
const COLOR_PAT = /^#(?<red>[0-9a-fA-F]{2})(?<green>[0-9a-fA-F]{2})(?<blue>[0-9a-fA-F]{2})$/;

const VipsOption = Java.type("app.photofox.vipsffm.VipsOption");
const VipsSize = Java.type("app.photofox.vipsffm.enums.VipsSize")
const VTarget = Java.type("app.photofox.vipsffm.VTarget");
const Thread = Java.type("java.lang.Thread");
const StandardOpenOption = Java.type('java.nio.file.StandardOpenOption');
const ByteBuffer = Java.type('java.nio.ByteBuffer');
const Path = Java.type('java.nio.file.Path');
const Files = Java.type('java.nio.file.Files');

function raiseFromIdentifier(identifier) {
  if (identifier === 'js-raise-http-400') {
    throw { "status": 400, message: "HTTP 400 from js" }
  } else if (identifier === "js-raise-http-418") {
    throw { "status": 418, message: "HTTP 418 from js" }
  } else if (identifier === "js-raise") {
    throw new Error("Generic error from js");
  }
}

function appendToFile(path, data) {
  const javaPath = Path.of(path);
  const chan = Files.newByteChannel(javaPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  try {
    const buf = ByteBuffer.wrap(data);
    chan.write(buf);
  } finally {
    chan.close();
  }
}

function logHookCall(hookName) {
  if (!wolpi.config?.logHooks) {
    return;
  }
  const entry = `${new Date().toISOString()} [${Thread.currentThread().getName()}] ${hookName}\n`;
  appendToFile(wolpi.config.logHooks, new TextEncoder().encode(entry));
}

export default {
  info() {
    logHookCall("info");
    const System = Java.type("java.lang.System")
    // CHANGE THIS LINE FOR TESTS
    return {
      name: "JavaScript Test Extension",
      apiVersion: 1,
      description: "A JavaScript extension for testing hook calling"
    };
  },

  authorize(identifier, headers, clientIp) {
    logHookCall("authorize");
    raiseFromIdentifier(identifier);
    if (!wolpi.config) {
      return true;
    }
    const {
      allowedIds,
      forbiddenIds,
      allowedIps,
      requiredHeaders
    } = wolpi.config;
    if (allowedIds) {
      return allowedIds.find(id => id === identifier) !== undefined;
    } else if (forbiddenIds) {
      return forbiddenIds.find(id => id === identifier) === undefined;
    } else if (allowedIps) {
      return allowedIps.find(ip => ip === clientIp) !== undefined;
    } else if (requiredHeaders) {
      for (const [key, value] of Object.entries(requiredHeaders)) {
        if (headers[key]?.find(v => v === value) === undefined) {
          return false;
        }
      }
      return true;
    }
    return true;
  },

  resolve(identifier, eTag, lastModified) {
    logHookCall("resolve");
    raiseFromIdentifier(identifier);
    if (eTag === "not-modified") {
      return { notModified: true };
    }
    if (!wolpi.config) {
      return null;
    }
    const { prefix, resolvingType } = wolpi.config;
    if (prefix && identifier.indexOf(prefix) !== 0) {
      return null;
    }
    const resolved = identifier.substring(prefix ? prefix.length : 0);
    let cacheInfo = undefined;
    if (identifier.indexOf(".withCacheInfo") >= 0) {
      cacheInfo = { eTag: "js-extension-etag", lastModified: new Date("2023-01-01T00:00:00Z") };
    }
    let imageInfo = undefined;
    if (identifier.indexOf(".withImageInfo") >= 0) {
      imageInfo = { nativeSize: { width: 1, height: 1 }, sizes: [], tileSizes: [] };
    }
    if (resolvingType === "FILESYSTEM") {
      return { path: `/tmp/images/${resolved}.jp2`, cacheInfo, imageInfo };
    } else if (resolvingType === "BINARY") {
      return { rawData: TEST_JPG, cacheInfo, imageInfo };
    } else if (resolvingType === "CUSTOM") {
      return {
        cacheInfo,
        imageInfo,
        _position: 0,
        onRead(length) {
          return TEST_JPG.slice(
              this._position,
              Math.min(TEST_JPG.length, this._position + length)
          );
        },
        onSeek(offset, whence) {
          if (whence === 0) { // SEEK_SET
            this._position = offset;
          } else if (whence === 1) { // SEEK_CUR
            this._position += offset;
          } else if (whence === 2) { // SEEK_END
            this._position = TEST_JPG.length + offset;
          }
          if (this._position < 0) {
            this._position = 0;
          } else if (this._position > TEST_JPG.length) {
            this._position = TEST_JPG.length;
          }
          return this._position;
        }
      }
    }
  },

  augmentInfoJson(identifier, infoJson, iiifVersion) {
    logHookCall("augmentInfoJson");
    return {
      ...infoJson,
      augmentedFromJS: `${identifier}-${iiifVersion}`
    };
  },

  preProcessImage(image, identifier, imageInfo, imageRequest) {
    logHookCall("preProcessImage");
    if (!identifier.startsWith("watermarked:")) {
      return null;
    }
    const { watermarkColor } = wolpi.config ?? {};
    if (!watermarkColor) {
      return null;
    }
    const match = COLOR_PAT.exec(watermarkColor);
    let color = [1.0, 0, 0];
    if (match) {
      color = [
        parseInt(match.groups.red, 16),
        parseInt(match.groups.green, 16),
        parseInt(match.groups.blue, 16)
      ];
    }
    image.drawRect(color, 0, 100, 100, 100, VipsOption.Boolean("fill", true));
    return image;
  },

  preFormat(image, identifier, imageInfo, imageRequest) {
    logHookCall("preFormat");
    if (imageRequest.formatSpec === "hdr-png") {
      const target = VTarget.newToMemory(wolpi.vipsArena);
      image.writeToTarget(target, ".png");
      const data = target.getBlob().asArenaScopedByteBuffer();
      return {
        data,
        contentType: "image/png",
        extraHeaders: {
          "X-Wolpi-Base-Uri": [wolpi.baseUri]
        }
      }
    } else if (imageRequest.formatSpec === "xyz") {
      return {
        data: new Uint8Array([0x78, 0x79, 0x7a]),
        contentType: "image/vnd.xyz",
        extraHeaders: {
          "X-Wolpi-Encoding-Source": [identifier]
        }
      };
    }
    return null;
  },

  preScale(image, identifier, imageInfo, imageRequest) {
    logHookCall("preScale");
    if (!imageRequest.sizeSpec.startsWith("custom")) {
      return null;
    }
    return image.thumbnailImage(50, VipsOption.Int("height", 50), VipsOption.Enum("size", VipsSize.SIZE_FORCE));
  },

  setup() {
    logHookCall("setup");
  },

  cleanup() {
    logHookCall("cleanup");
  },

  destroy() {
    logHookCall("destroy");
  }
}