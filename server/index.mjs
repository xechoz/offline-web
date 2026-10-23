import express from 'express'
import * as cheerio from 'cheerio'
import archiver from 'archiver'
import AdmZip from 'adm-zip'
import { createHash, createPrivateKey, sign as cryptoSign } from 'node:crypto'
import {
  createWriteStream,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs'
import { mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { dirname, join, posix, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomUUID } from 'node:crypto'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ROOT = __dirname
const DATA_DIR = process.env.DATA_DIR || join(ROOT, 'data')
const ARCHIVE_DIR = join(DATA_DIR, 'archives')
const RELEASE_DIR = join(DATA_DIR, 'releases')
const WORK_DIR = join(DATA_DIR, 'work')
const SAMPLE_DIR = join(ROOT, 'sample-web')
const KEYS_DIR = join(ROOT, 'keys')
const STORE_PATH = join(DATA_DIR, 'store.json')
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || 'demo-admin'
const PORT = Number(process.env.PORT || 8080)
const KEY_ID = 'demo-2026-01'
const MAX_FILES = 10_000
const MAX_BYTES = 200 * 1024 * 1024
const MAX_SNAPSHOT_FILES = 400
const MAX_SNAPSHOT_BYTES = 50 * 1024 * 1024

const PRIVATE_KEY = createPrivateKey(readFileSync(join(KEYS_DIR, 'demo-private.pem')))
const PUBLIC_KEY_B64 = readFileSync(join(KEYS_DIR, 'demo-public.b64'), 'utf8').trim()

let store

function ensureDirs() {
  for (const dir of [DATA_DIR, ARCHIVE_DIR, RELEASE_DIR, WORK_DIR]) {
    mkdirSync(dir, { recursive: true })
  }
  if (existsSync(WORK_DIR)) {
    for (const entry of readdirSync(WORK_DIR)) rmSync(join(WORK_DIR, entry), { recursive: true, force: true })
  }
}

function defaultStore() {
  return {
    apps: {
      demo: { name: 'Offline Web Demo', keyId: KEY_ID, publicKey: PUBLIC_KEY_B64 },
    },
    packages: {
      shop: {
        appKey: 'demo',
        name: 'Shop',
        entryUrl: 'http://10.0.2.2:8080/site/',
        spaFallback: true,
        nextVersion: 1,
        activeVersion: null,
        createdAt: Date.now(),
      },
    },
    releases: { shop: [] },
  }
}

function loadStore() {
  if (!existsSync(STORE_PATH)) return defaultStore()
  const parsed = JSON.parse(readFileSync(STORE_PATH, 'utf8'))
  parsed.apps ||= {}
  parsed.packages ||= {}
  parsed.releases ||= {}
  return parsed
}

function saveStore() {
  mkdirSync(DATA_DIR, { recursive: true })
  const tmp = `${STORE_PATH}.tmp`
  writeFileSync(tmp, JSON.stringify(store, null, 2))
  renameSync(tmp, STORE_PATH)
}

function sha256(buffer) {
  return createHash('sha256').update(buffer).digest('hex')
}

function shortHash(value) {
  return createHash('sha1').update(value).digest('hex').slice(0, 8)
}

function safeId(value) {
  return typeof value === 'string' && /^[A-Za-z0-9._-]{1,64}$/.test(value)
}

function safeRelativePath(value) {
  if (typeof value !== 'string' || !value || value.length > 512) return false
  if (value.startsWith('/') || value.includes('\\') || value.includes('\0')) return false
  return value.split('/').every((part) => part && part !== '.' && part !== '..')
}

function normalizeEntryUrl(raw) {
  const url = new URL(raw)
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error('entryUrl must use http or https')
  }
  return url.href
}

function routesFor(pkg) {
  const url = new URL(pkg.entryUrl)
  let base = url.pathname || '/'
  if (!base.endsWith('/')) {
    const last = base.slice(base.lastIndexOf('/') + 1)
    base = last.includes('.') ? base.slice(0, base.lastIndexOf('/') + 1) : `${base}/`
  }
  return [{ origin: url.origin, basePath: base, spaFallback: Boolean(pkg.spaFallback) }]
}

function walkFiles(dir, base = dir, result = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === '.DS_Store') continue
    const full = join(dir, entry.name)
    if (entry.isSymbolicLink()) continue
    if (entry.isDirectory()) walkFiles(full, base, result)
    else if (entry.isFile()) result.push(relative(base, full).split(sep).join('/'))
  }
  return result
}

function zipDirectory(dir, target) {
  return new Promise((resolve, reject) => {
    mkdirSync(dirname(target), { recursive: true })
    const output = createWriteStream(target)
    const archive = archiver('zip', { zlib: { level: 9 } })
    output.on('close', resolve)
    output.on('error', reject)
    archive.on('error', reject)
    archive.pipe(output)
    archive.directory(dir, false)
    archive.finalize()
  })
}

async function deployDirectory(packageId, sourceDir, options = {}) {
  const pkg = store.packages[packageId]
  if (!pkg) throw new Error(`unknown package: ${packageId}`)
  if (options.entryUrl) pkg.entryUrl = normalizeEntryUrl(options.entryUrl)

  const paths = walkFiles(sourceDir).sort()
  if (paths.length === 0) throw new Error('package contains no files')
  if (paths.length > MAX_FILES) throw new Error('too many files')

  const files = []
  let total = 0
  for (const path of paths) {
    if (!safeRelativePath(path)) throw new Error(`unsafe path: ${path}`)
    const buffer = readFileSync(join(sourceDir, path))
    total += buffer.length
    if (total > MAX_BYTES) throw new Error('package is too large')
    files.push({ path, size: buffer.length, sha256: sha256(buffer) })
  }

  const version = pkg.nextVersion
  const archivePath = join(ARCHIVE_DIR, packageId, `${version}.zip`)
  await zipDirectory(sourceDir, archivePath)
  const archiveSize = statSync(archivePath).size
  if (archiveSize > MAX_BYTES) throw new Error('archive is too large')

  const release = {
    schemaVersion: 1,
    keyId: KEY_ID,
    appKey: pkg.appKey,
    packageId,
    version,
    versionName: options.versionName || `1.0.${version}`,
    createdAt: Date.now(),
    expiresAt: options.expiresAt ? Number(options.expiresAt) : null,
    minClientVersion: 1,
    entryUrl: pkg.entryUrl,
    archive: {
      url: `api/v1/downloads/${packageId}/${version}/archive.zip`,
      size: archiveSize,
      sha256: sha256(readFileSync(archivePath)),
    },
    routes: routesFor(pkg),
    files,
  }

  const releaseBytes = Buffer.from(JSON.stringify(release, null, 2))
  const signature = cryptoSign('sha256', releaseBytes, PRIVATE_KEY).toString('base64')
  const releaseDir = join(RELEASE_DIR, packageId, String(version))
  mkdirSync(releaseDir, { recursive: true })
  writeFileSync(join(releaseDir, 'release.json'), releaseBytes)
  writeFileSync(join(releaseDir, 'release.sig'), signature)

  for (const record of store.releases[packageId] || []) {
    if (record.status === 'active') record.status = 'superseded'
  }

  store.releases[packageId] ||= []
  store.releases[packageId].push({
    version,
    versionName: release.versionName,
    createdAt: release.createdAt,
    expiresAt: release.expiresAt,
    size: archiveSize,
    sha256: release.archive.sha256,
    fileCount: files.length,
    status: 'active',
  })
  pkg.nextVersion = version + 1
  pkg.activeVersion = version
  saveStore()

  return {
    packageId,
    version,
    versionName: release.versionName,
    fileCount: files.length,
    size: archiveSize,
    skipped: options.skipped || [],
  }
}

function writeLocal(baseDir, localPath, data) {
  const target = join(baseDir, localPath)
  const rootPath = join(baseDir)
  if (!target.startsWith(`${rootPath}${sep}`)) throw new Error(`unsafe local path: ${localPath}`)
  mkdirSync(dirname(target), { recursive: true })
  writeFileSync(target, data)
}

function relativeAsset(fromLocal, toLocal) {
  const rel = posix.relative(posix.dirname(fromLocal), toLocal)
  return rel || posix.basename(toLocal)
}

async function fetchBuffer(url) {
  const response = await fetch(url, { signal: AbortSignal.timeout(30_000) })
  if (!response.ok) throw new Error(`fetch ${url} failed: ${response.status}`)
  return Buffer.from(await response.arrayBuffer())
}

function replaceAsync(text, regex, replacer) {
  const matches = []
  text.replace(regex, (...args) => {
    matches.push(args)
    return args[0]
  })
  return Promise.all(matches.map((args) => replacer(...args))).then((values) => {
    let index = 0
    return text.replace(regex, () => values[index++])
  })
}

function createSnapshotContext(dir, baseUrl, basePath) {
  return {
    dir,
    baseUrl,
    basePath,
    origin: new URL(baseUrl).origin,
    assets: new Map(),
    used: new Set(['index.html']),
    skipped: [],
    count: 0,
    bytes: 0,
  }
}

function localPathFor(url, context) {
  let path
  try {
    path = decodeURIComponent(url.pathname)
  } catch {
    return null
  }
  if (!path.startsWith(context.basePath)) return null
  path = path.slice(context.basePath.length)
  if (!path || path.endsWith('/')) path = `${path}index.html`
  if (!safeRelativePath(path)) return null
  if (url.search) {
    const extension = posix.extname(path)
    path = `${path.slice(0, path.length - extension.length)}__q_${shortHash(url.search)}${extension}`
  }
  if (context.used.has(path)) {
    const extension = posix.extname(path)
    path = `${path.slice(0, path.length - extension.length)}__${shortHash(url.href)}${extension}`
  }
  return path
}

async function downloadAsset(raw, context) {
  if (!raw || raw.startsWith('data:') || raw.startsWith('blob:') || raw.startsWith('#')) return null
  if (raw.startsWith('mailto:') || raw.startsWith('javascript:') || raw.startsWith('about:')) return null

  let url
  try {
    url = new URL(raw, context.baseUrl)
  } catch {
    return null
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return null
  if (url.origin !== context.origin) {
    context.skipped.push(url.href)
    return null
  }
  const key = url.href
  if (context.assets.has(key)) return context.assets.get(key)

  const local = localPathFor(url, context)
  if (!local) {
    context.skipped.push(url.href)
    return null
  }
  context.assets.set(key, local)
  context.used.add(local)

  try {
    const response = await fetch(url.href, { signal: AbortSignal.timeout(30_000) })
    if (!response.ok) throw new Error(`fetch asset ${url.href} failed: ${response.status}`)
    const buffer = Buffer.from(await response.arrayBuffer())
    context.count += 1
    context.bytes += buffer.length
    if (context.count > MAX_SNAPSHOT_FILES) throw new Error('snapshot has too many assets')
    if (context.bytes > MAX_SNAPSHOT_BYTES) throw new Error('snapshot is too large')

    const contentType = response.headers.get('content-type') || ''
    if (url.pathname.endsWith('.css') || contentType.includes('text/css')) {
      const css = await rewriteCss(buffer.toString('utf8'), local, url.href, context)
      writeLocal(context.dir, local, css)
    } else {
      writeLocal(context.dir, local, buffer)
    }
    return local
  } catch (error) {
    context.assets.delete(key)
    context.used.delete(local)
    throw error
  }
}

async function rewriteCss(css, cssLocal, cssUrl, context) {
  const urlPattern = /url\(\s*(['"]?)([^'")]+)\1\s*\)/gi
  let output = await replaceAsync(css, urlPattern, async (match, _quote, raw) => {
    const value = raw.trim()
    if (!value || value.startsWith('data:') || value.startsWith('#') || value.startsWith('blob:')) return match
    try {
      const local = await downloadAsset(value, context)
      return local ? `url("${relativeAsset(cssLocal, local)}")` : match
    } catch {
      return match
    }
  })

  const importPattern = /@import\s+(['"])([^'"]+)\1/gi
  output = await replaceAsync(output, importPattern, async (match, _quote, raw) => {
    try {
      const local = await downloadAsset(raw, context)
      return local ? `@import "${relativeAsset(cssLocal, local)}"` : match
    } catch {
      return match
    }
  })
  return output
}

async function snapshotSite(sourceUrl) {
  const entryResponse = await fetch(sourceUrl, { signal: AbortSignal.timeout(30_000) })
  if (!entryResponse.ok) throw new Error(`fetch entry failed: ${entryResponse.status}`)
  const contentType = entryResponse.headers.get('content-type') || ''
  if (!contentType.includes('text/html')) throw new Error('entry URL is not HTML')

  const finalUrl = new URL(entryResponse.url)
  const html = await entryResponse.text()
  let basePath = finalUrl.pathname
  if (!basePath.endsWith('/')) {
    const last = basePath.slice(basePath.lastIndexOf('/') + 1)
    basePath = last.includes('.') ? basePath.slice(0, basePath.lastIndexOf('/') + 1) : `${basePath}/`
  }
  if (!basePath) basePath = '/'

  const dir = join(WORK_DIR, `snapshot-${randomUUID()}`)
  mkdirSync(dir, { recursive: true })
  const context = createSnapshotContext(dir, finalUrl.href, basePath)

  const $ = cheerio.load(html)
  $('base').remove()

  const rewriteAttr = async (selector, attr) => {
    for (const node of $(selector).toArray()) {
      const element = $(node)
      const raw = element.attr(attr)
      if (!raw) continue
      try {
        const local = await downloadAsset(raw, context)
        if (local) {
          element.attr(attr, relativeAsset('index.html', local))
          if (attr === 'href') element.removeAttr('integrity')
        }
      } catch (error) {
        console.warn(error.message)
      }
    }
  }

  await rewriteAttr('script[src]', 'src')
  await rewriteAttr('img[src]', 'src')
  await rewriteAttr('source[src]', 'src')
  await rewriteAttr('video[src]', 'src')
  await rewriteAttr('audio[src]', 'src')
  await rewriteAttr('video[poster]', 'poster')

  for (const node of $('link[href]').toArray()) {
    const element = $(node)
    const rel = (element.attr('rel') || '').toLowerCase()
    if (!/(stylesheet|icon|manifest|preload|modulepreload|apple-touch-icon)/.test(rel)) continue
    try {
      const local = await downloadAsset(element.attr('href'), context)
      if (local) {
        element.attr('href', relativeAsset('index.html', local))
        element.removeAttr('integrity')
      }
    } catch (error) {
      console.warn(error.message)
    }
  }

  for (const node of $('[srcset]').toArray()) {
    const element = $(node)
    const candidates = (element.attr('srcset') || '').split(',').map((item) => item.trim()).filter(Boolean)
    const rewritten = []
    for (const candidate of candidates) {
      const [raw, ...descriptor] = candidate.split(/\s+/)
      try {
        const local = await downloadAsset(raw, context)
        rewritten.push(local ? `${relativeAsset('index.html', local)}${descriptor.length ? ` ${descriptor.join(' ')}` : ''}` : candidate)
      } catch {
        rewritten.push(candidate)
      }
    }
    element.attr('srcset', rewritten.join(', '))
  }

  for (const node of $('[style]').toArray()) {
    const element = $(node)
    const css = element.attr('style') || ''
    element.attr('style', await rewriteCss(css, 'index.html', finalUrl.href, context))
  }

  for (const node of $('style').toArray()) {
    const element = $(node)
    element.text(await rewriteCss(element.text(), 'index.html', finalUrl.href, context))
  }

  writeLocal(dir, 'index.html', $.html())
  return { dir, skipped: [...new Set(context.skipped)] }
}

async function deployFromUrl(packageId, sourceUrl, options = {}) {
  const snapshot = await snapshotSite(sourceUrl)
  try {
    return await deployDirectory(packageId, snapshot.dir, {
      ...options,
      skipped: snapshot.skipped,
    })
  } finally {
    await rm(snapshot.dir, { recursive: true, force: true })
  }
}

function extractZipBuffer(buffer, destination) {
  const zip = new AdmZip(buffer)
  let total = 0
  let count = 0
  for (const entry of zip.getEntries()) {
    if (entry.isDirectory) continue
    if (entry.entryName.startsWith('__MACOSX/') || entry.entryName.endsWith('.DS_Store')) continue
    if (!safeRelativePath(entry.entryName)) throw new Error(`unsafe zip path: ${entry.entryName}`)
    const data = entry.getData()
    total += data.length
    count += 1
    if (count > MAX_FILES) throw new Error('too many files in zip')
    if (total > MAX_BYTES) throw new Error('zip is too large')
    writeLocal(destination, entry.entryName, data)
  }
  if (count === 0) throw new Error('zip contains no files')
}

async function deployZip(packageId, buffer, options = {}) {
  const dir = join(WORK_DIR, `upload-${randomUUID()}`)
  mkdirSync(dir, { recursive: true })
  try {
    extractZipBuffer(buffer, dir)
    return await deployDirectory(packageId, dir, options)
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
}

function releasePaths(packageId, version) {
  const dir = join(RELEASE_DIR, packageId, String(version))
  return {
    json: join(dir, 'release.json'),
    sig: join(dir, 'release.sig'),
    archive: join(ARCHIVE_DIR, packageId, `${version}.zip`),
  }
}

function activeRelease(packageId) {
  const pkg = store.packages[packageId]
  if (!pkg || pkg.activeVersion == null) return null
  return (store.releases[packageId] || []).find((release) => release.version === pkg.activeVersion) || null
}

function adminOnly(req, res, next) {
  if (req.get('x-admin-token') !== ADMIN_TOKEN) {
    return res.status(401).json({ error: 'unauthorized' })
  }
  next()
}

async function main() {
  ensureDirs()
  store = loadStore()
  saveStore()

  const app = express()
  app.use(express.json({ limit: '2mb' }))

  app.get('/admin', (_req, res) => res.sendFile(join(ROOT, 'admin.html')))
  app.get('/admin/api/state', adminOnly, (_req, res) => {
    res.json({ apps: store.apps, packages: store.packages, releases: store.releases })
  })

  app.post('/admin/api/apps', adminOnly, (req, res) => {
    const { appKey, name } = req.body || {}
    if (!safeId(appKey)) return res.status(400).json({ error: 'invalid appKey' })
    if (store.apps[appKey]) return res.status(409).json({ error: 'app already exists' })
    store.apps[appKey] = {
      name: name || appKey,
      keyId: KEY_ID,
      publicKey: PUBLIC_KEY_B64,
    }
    saveStore()
    res.json(store.apps[appKey])
  })

  app.post('/admin/api/apps/:appKey/packages', adminOnly, (req, res) => {
    const { appKey } = req.params
    const { packageId, name, entryUrl, spaFallback } = req.body || {}
    if (!store.apps[appKey]) return res.status(404).json({ error: 'unknown app' })
    if (!safeId(packageId)) return res.status(400).json({ error: 'invalid packageId' })
    if (store.packages[packageId]) return res.status(409).json({ error: 'package already exists' })
    let normalized
    try {
      normalized = normalizeEntryUrl(entryUrl)
    } catch (error) {
      return res.status(400).json({ error: error.message })
    }
    store.packages[packageId] = {
      appKey,
      name: name || packageId,
      entryUrl: normalized,
      spaFallback: Boolean(spaFallback),
      nextVersion: 1,
      activeVersion: null,
      createdAt: Date.now(),
    }
    store.releases[packageId] = []
    saveStore()
    res.json(store.packages[packageId])
  })

  app.patch('/admin/api/packages/:id', adminOnly, (req, res) => {
    const pkg = store.packages[req.params.id]
    if (!pkg) return res.status(404).json({ error: 'unknown package' })
    const { name, entryUrl, spaFallback } = req.body || {}
    if (name !== undefined) pkg.name = name
    if (spaFallback !== undefined) pkg.spaFallback = Boolean(spaFallback)
    if (entryUrl !== undefined) {
      try {
        pkg.entryUrl = normalizeEntryUrl(entryUrl)
      } catch (error) {
        return res.status(400).json({ error: error.message })
      }
    }
    saveStore()
    res.json(pkg)
  })

  app.post('/admin/api/packages/:id/deploy', adminOnly, async (req, res, next) => {
    try {
      const packageId = req.params.id
      const { sourceUrl, entryUrl, expiresAt, versionName } = req.body || {}
      if (!sourceUrl) return res.status(400).json({ error: 'sourceUrl is required' })
      const result = await deployFromUrl(packageId, sourceUrl, {
        entryUrl,
        expiresAt,
        versionName,
      })
      res.json(result)
    } catch (error) {
      next(error)
    }
  })

  app.post('/admin/api/packages/:id/deploy-sample', adminOnly, async (req, res, next) => {
    try {
      const result = await deployDirectory(req.params.id, SAMPLE_DIR, {
        versionName: req.body?.versionName,
      })
      res.json(result)
    } catch (error) {
      next(error)
    }
  })

  app.post(
    '/admin/api/packages/:id/deploy-zip',
    adminOnly,
    express.raw({ type: ['application/zip', 'application/octet-stream'], limit: '200mb' }),
    async (req, res, next) => {
      try {
        if (!Buffer.isBuffer(req.body)) {
          return res.status(400).json({ error: 'expected application/zip body' })
        }
        const result = await deployZip(req.params.id, req.body, {
          expiresAt: req.query.expiresAt,
          versionName: req.query.versionName,
        })
        res.json(result)
      } catch (error) {
        next(error)
      }
    },
  )

  app.post('/admin/api/releases/:pkg/:ver/revoke', adminOnly, (req, res) => {
    const { pkg: packageId, ver } = req.params
    const record = (store.releases[packageId] || []).find((item) => item.version === Number(ver))
    if (!record) return res.status(404).json({ error: 'unknown release' })
    record.status = 'revoked'
    const pkg = store.packages[packageId]
    if (pkg?.activeVersion === Number(ver)) pkg.activeVersion = null
    saveStore()
    res.json(record)
  })

  app.post('/admin/api/releases/:pkg/:ver/rollback', adminOnly, async (req, res, next) => {
    try {
      const { pkg: packageId, ver } = req.params
      const paths = releasePaths(packageId, Number(ver))
      if (!existsSync(paths.archive)) return res.status(404).json({ error: 'archive not found' })
      const dir = join(WORK_DIR, `rollback-${randomUUID()}`)
      mkdirSync(dir, { recursive: true })
      try {
        extractZipBuffer(readFileSync(paths.archive), dir)
        const result = await deployDirectory(packageId, dir, {
          versionName: req.body?.versionName || `rollback of ${ver}`,
        })
        res.json(result)
      } finally {
        await rm(dir, { recursive: true, force: true })
      }
    } catch (error) {
      next(error)
    }
  })

  app.get('/api/v1/apps/:appKey/packages', (req, res) => {
    const { appKey } = req.params
    const packages = Object.entries(store.packages)
      .filter(([, pkg]) => pkg.appKey === appKey)
      .map(([packageId, pkg]) => {
        const release = (store.releases[packageId] || []).find(
          (item) => item.version === pkg.activeVersion,
        )
        return {
          packageId,
          name: pkg.name,
          version: release?.version ?? null,
          versionName: release?.versionName || '',
          expiresAt: release?.expiresAt ?? null,
          size: release?.size ?? null,
        }
      })
    res.json(packages)
  })

  app.get('/api/v1/apps/:appKey/packages/:id/release', (req, res) => {
    const { appKey, id: packageId } = req.params
    const pkg = store.packages[packageId]
    if (!pkg || pkg.appKey !== appKey) return res.status(404).json({ error: 'package not found' })

    const record = activeRelease(packageId)
    if (!record) {
      const revoked = (store.releases[packageId] || [])
        .filter((item) => item.status === 'revoked')
        .sort((a, b) => b.version - a.version)[0]
      if (revoked) return res.status(410).json({ error: 'release revoked' })
      return res.status(404).json({ error: 'no active release' })
    }
    if (record.status === 'revoked') return res.status(410).json({ error: 'release revoked' })

    const paths = releasePaths(packageId, record.version)
    if (!existsSync(paths.json) || !existsSync(paths.sig)) {
      return res.status(404).json({ error: 'release files missing' })
    }
    const releaseBytes = readFileSync(paths.json)
    const etag = `"${sha256(releaseBytes)}"`
    if (req.get('if-none-match') === etag) {
      res.set('ETag', etag)
      res.set('Cache-Control', 'no-store')
      return res.status(304).end()
    }

    res.set('ETag', etag)
    res.set('Cache-Control', 'no-store')
    res.json({
      etag,
      release: releaseBytes.toString('base64'),
      signature: readFileSync(paths.sig, 'utf8').trim(),
    })
  })

  app.get('/api/v1/downloads/:pkg/:ver/archive.zip', (req, res) => {
    const { pkg: packageId, ver } = req.params
    const path = releasePaths(packageId, Number(ver)).archive
    if (!existsSync(path)) return res.status(404).json({ error: 'archive not found' })
    res.set('Cache-Control', 'no-store')
    res.sendFile(path)
  })

  app.get('/api/health', (_req, res) => res.json({ ok: true }))
  app.use('/site', express.static(SAMPLE_DIR, { extensions: ['html'] }))

  app.use((error, _req, res, _next) => {
    console.error(error)
    res.status(500).json({ error: error.message || 'internal error' })
  })

  const seededReleases = store.releases.shop || []
  if (seededReleases.length === 0 && existsSync(SAMPLE_DIR)) {
    await deployDirectory('shop', SAMPLE_DIR, { versionName: '1.0.0' })
    console.log('seeded shop v1 from sample-web')
  }

  app.listen(PORT, '0.0.0.0', () => {
    console.log(`offline-web demo server on http://127.0.0.1:${PORT}`)
    console.log(`admin token: ${ADMIN_TOKEN}`)
    console.log(`emulator URL: http://10.0.2.2:${PORT}/site/`)
  })
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
