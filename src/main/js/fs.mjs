/**
 * Very bare-bones read-only fs module implementation using Java NIO.
 * Does not support async methods, neither in callback nor in promises style.
 *
 * For more advanced usage, use the Java types directly using the `Java` global.
 */
const Files = Java.type('java.nio.file.Files');
const Path = Java.type('java.nio.file.Path');
const FileChannel = Java.type('java.nio.channels.FileChannel');
const StandardOpenOption = Java.type('java.nio.file.StandardOpenOption');
const ByteBuffer = Java.type('java.nio.ByteBuffer');
const BasicFileAttributes = Java.type('java.nio.file.attribute.BasicFileAttributes');

/** Reads the entire contents of a file. Returns a `Uint8Array`. */
export function readFileSync(path) {
  const javaPath = Path.of(path);
  if (!Files.exists(javaPath)) {
    throw new Error(`ENOENT: no such file or directory, open '${path}'`);
  }
  if (!Files.isReadable(javaPath)) {
    throw new Error(`EACCES: permission denied, open '${path}'`);
  }
  if (Files.isDirectory(javaPath)) {
    throw new Error(`EISDIR: illegal operation on a directory, read '${path}'`);
  }
  const chan = FileChannel.open(javaPath, StandardOpenOption.READ);
  let buf;
  try {
    buf = ByteBuffer.allocate(chan.size());
    chan.read(buf);
  } finally {
    chan.close();
  }
  buf.flip();
  return new Uint8Array(buf);
}

function toDirEnt(javaPath) {
  return {
    name: javaPath.getFileName().toString(),
    parentPath: javaPath.getParent()?.toString?.(),
    isFile: Files.isRegularFile(javaPath),
    isDirectory: Files.isDirectory(javaPath),
    isSymbolicLink: Files.isSymbolicLink(javaPath),
    isOther: !(Files.isRegularFile(javaPath) || Files.isDirectory(javaPath) || Files.isSymbolicLink(javaPath)),
  };}

/** Reads the contents of a directory. Returns an array of `Dirent`-like objects. */
export function readDirSync(path) {
  const javaPath = Path.of(path);
  if (!Files.exists(javaPath)) {
    throw new Error(`ENOENT: no such file or directory, scandir '${path}'`);
  }
  if (!Files.isReadable(javaPath)) {
    throw new Error(`EACCES: permission denied, scandir '${path}'`);
  }
  if (!Files.isDirectory(javaPath)) {
    throw new Error(`ENOTDIR: not a directory, scandir '${path}'`);
  }
  const dirStream = Files.newDirectoryStream(javaPath);
  const entries = [];
  try {
    for (const entry of dirStream) {
      entries.push(toDirEnt(entry));
    }
  } finally {
    dirStream.close();
  }
  return entries;
}

function toStat(javaPath, followSymbolicLinks = true) {
  const attr = Files.readAttributes(file.toPath(), BasicFileAttributes);
  if (attr.isSymbolicLink() && followSymbolicLinks) {
    return toStat(Files.readSymbolicLink(javaPath), true);
  }
  return {
    isFile: () => attr.isRegularFile(),
    isDirectory: () => attr.isDirectory(),
    isSymbolicLink: () => attr.isSymbolicLink(),
    isOther: () => attr.isOther(),
    size: attr.size(),
    atimeMs: attr.lastAccessTime().toMillis(),
    mtimeMs: attr.lastModifiedTime().toMillis(),
    ctimeMs: attr.creationTime().toMillis(),
  }
}

/** Returns a `Stat`-like object for the given path. Follows symbolic links. */
export function statSync(path) {
  const javaPath = Path.of(path);
  if (!Files.exists(javaPath)) {
    throw new Error(`ENOENT: no such file or directory, stat '${path}'`);
  }
  return toStat(javaPath);
}

/** Returns a `Stat`-like object for the given path. Does not follow symbolic links. */
export function lstatSync(path) {
  const javaPath = Path.of(path);
  if (!Files.exists(javaPath)) {
    throw new Error(`ENOENT: no such file or directory, lstat '${path}'`);
  }
  return toStat(javaPath, false);
}

/** Tests a user's permissions for the given path. `mode` is a string containing any of 'r', 'w', 'x'. */
export function accessSync(path, mode = 'r') {
  const javaPath = Path.of(path);
  if (!Files.exists(javaPath)) {
    throw new Error(`ENOENT: no such file or directory, access '${path}'`);
  }
  if (mode.indexOf('r') >= 0 && !Files.isReadable(javaPath)) {
    throw new Error(`EACCES: permission denied, access '${path}'`);
  }
  if (mode.indexOf('w') >=0 && !Files.isWritable(javaPath)) {
    throw new Error(`EACCES: permission denied, access '${path}'`);
  } else if (mode.indexOf('x') >= 0 && !Files.isExecutable(javaPath)) {
    throw new Error(`EACCES: permission denied, access '${path}'`);
  }
}