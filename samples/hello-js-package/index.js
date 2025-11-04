import fetchSync from 'wolpi:fetch';

const VALIDATION_ID = '5976299c-b668-11f0-ab84-047bcbb8eed4';

export default {
  info: () => ({
    apiVersion: 1,
    name: 'internet-archive-resolver-js',
    description: 'Resolves images from the Internet Archive.'
  }),
  cleanup: () => {
    // No cleanup needed in this example, since we don't keep any state between
    // hook calls
  },
  resolve: (identifier, eTag, lastModified) => {
    if (identifier.startsWith(VALIDATION_ID)) {
      return;
    }
    const log = wolpi.logger;
    const metadataUrl = `https://archive.org/metadata/${encodeURIComponent(identifier)}`;
    log.info(`Trying to resolve identifier ${identifier} from Internet Archive via ${metadataUrl}`);
    const headers = {};
    if (eTag) {
      headers['If-None-Match'] = eTag;
    }
    if (lastModified) {
      headers['If-Modified-Since'] = lastModified;
    }
    const response = fetchSync(metadataUrl, { headers });
    log.info(`Received response with status ${response.status} from Internet Archive for identifier ${identifier}`);
    if (response.status === 304) {
      return { notModified: true };
    }
    if (!response.ok) {
      return
    }
    const data = response.json();
    if (!data.metadata || data.metadata.mediatype !== 'image') {
      return;
    }
    let bestFile = null;
    for (const file of data.files) {
      if (file.source !== 'original') {
        continue;
      }
      if (file.format === 'JPEG 2000') {
        bestFile = file;
        break;
      }
      if (file.format === 'JPEG') {
        bestFile = file;
      }
    }
    if (!bestFile) {
      return;
    }
    log.info(`Resolved identifier ${identifier} to file ${bestFile.name} from ${data.d1}/${data.dir} in Internet Archive`);
    return {
      url: `https://${data.d1}${data.dir}/${bestFile.name}`
    };
  }
}
