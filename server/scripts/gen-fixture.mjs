import AdmZip from 'adm-zip'
import { createHash, createPrivateKey, sign } from 'node:crypto'
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const root = join(here, '..', '..')
const fixtures = join(root, 'fixtures')
const site = join(fixtures, 'site')
const privateKey = createPrivateKey(readFileSync(join(root, 'server', 'keys', 'demo-private.pem')))
const publicKey = readFileSync(join(root, 'server', 'keys', 'demo-public.b64'), 'utf8').trim()

function walk(dir, base = dir, result = []) {
  for (const name of readdirSync(dir).sort()) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) walk(full, base, result)
    else result.push(relative(base, full).split(sep).join('/'))
  }
  return result
}

const paths = walk(site)
const zip = new AdmZip()
for (const path of paths) {
  zip.addFile(path, readFileSync(join(site, path)))
}
const zipPath = join(fixtures, 'site.zip')
zip.writeZip(zipPath)
const archive = readFileSync(zipPath)

const files = paths.map((path) => {
  const data = readFileSync(join(site, path))
  return {
    path,
    size: data.length,
    sha256: createHash('sha256').update(data).digest('hex'),
  }
})

const release = {
  schemaVersion: 1,
  keyId: 'demo-2026-01',
  appKey: 'fixture',
  packageId: 'fixture',
  version: 1,
  versionName: 'fixture-1',
  createdAt: 1790000000000,
  expiresAt: null,
  minClientVersion: 1,
  entryUrl: 'http://fixture.local/app/',
  archive: {
    url: 'site.zip',
    size: archive.length,
    sha256: createHash('sha256').update(archive).digest('hex'),
  },
  routes: [{ origin: 'http://fixture.local', basePath: '/app/', spaFallback: true }],
  files,
}

const releaseBytes = Buffer.from(JSON.stringify(release, null, 2))
const signature = sign('sha256', releaseBytes, privateKey).toString('base64')
const etag = `"${createHash('sha256').update(releaseBytes).digest('hex')}"`

writeFileSync(join(fixtures, 'release.json'), releaseBytes)
writeFileSync(join(fixtures, 'release.sig'), signature)
writeFileSync(join(fixtures, 'public-key.b64'), publicKey)
writeFileSync(
  join(fixtures, 'release-response.json'),
  JSON.stringify(
    {
      etag,
      release: releaseBytes.toString('base64'),
      signature,
    },
    null,
    2,
  ),
)

console.log(`wrote ${fixtures}/release.json, site.zip, release.sig`)
