import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createPublicKey, verify } from 'node:crypto'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import test, { after, before } from 'node:test'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const port = 18000 + (process.pid % 1000)
const base = `http://127.0.0.1:${port}`
const token = 'demo-admin'
let child
let dataDir
let publicKey

async function request(path, options = {}) {
  return fetch(`${base}${path}`, options)
}

async function waitForServer() {
  for (let i = 0; i < 50; i++) {
    try {
      const response = await request('/api/health')
      if (response.ok) return
    } catch {
      // server is still starting
    }
    await new Promise((resolve) => setTimeout(resolve, 100))
  }
  throw new Error('server did not start')
}

before(async () => {
  dataDir = await mkdtemp(join(tmpdir(), 'offline-web-api-'))
  publicKey = createPublicKey(await readFile(join(root, 'keys', 'demo-public.pem')))
  child = spawn(process.execPath, ['index.mjs'], {
    cwd: root,
    env: { ...process.env, PORT: String(port), DATA_DIR: dataDir },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  child.stdout.on('data', () => {})
  child.stderr.on('data', () => {})
  await waitForServer()
})

after(async () => {
  child?.kill('SIGTERM')
  if (dataDir) await rm(dataDir, { recursive: true, force: true })
})

test('serves a signed seeded release', async () => {
  const packages = await (await request('/api/v1/apps/demo/packages')).json()
  assert.equal(packages.length, 1)
  assert.equal(packages[0].packageId, 'shop')
  assert.equal(packages[0].version, 1)

  const response = await request('/api/v1/apps/demo/packages/shop/release')
  assert.equal(response.status, 200)
  const body = await response.json()
  const release = Buffer.from(body.release, 'base64')
  const signature = Buffer.from(body.signature, 'base64')
  assert.equal(verify('sha256', release, publicKey, signature), true)

  const parsed = JSON.parse(release.toString())
  assert.equal(parsed.packageId, 'shop')
  assert.equal(parsed.routes[0].basePath, '/site/')
  assert.equal(parsed.files.length, 5)
})

test('supports ETag and byte ranges', async () => {
  const first = await request('/api/v1/apps/demo/packages/shop/release')
  const etag = first.headers.get('etag')
  const second = await request('/api/v1/apps/demo/packages/shop/release', {
    headers: { 'if-none-match': etag },
  })
  assert.equal(second.status, 304)

  const range = await request('/api/v1/downloads/shop/1/archive.zip', {
    headers: { range: 'bytes=0-9' },
  })
  assert.equal(range.status, 206)
  assert.equal((await range.arrayBuffer()).byteLength, 10)
})

test('publishes, revokes and rolls back', async () => {
  const deploy = await request('/admin/api/packages/shop/deploy-sample', {
    method: 'POST',
    headers: { 'x-admin-token': token, 'content-type': 'application/json' },
    body: JSON.stringify({ versionName: 'api-test-2' }),
  })
  assert.equal(deploy.status, 200)
  const deployed = await deploy.json()
  assert.equal(deployed.version, 2)

  const revoke = await request('/admin/api/releases/shop/2/revoke', {
    method: 'POST',
    headers: { 'x-admin-token': token },
  })
  assert.equal(revoke.status, 200)

  const revoked = await request('/api/v1/apps/demo/packages/shop/release')
  assert.equal(revoked.status, 410)

  const rollback = await request('/admin/api/releases/shop/1/rollback', {
    method: 'POST',
    headers: { 'x-admin-token': token, 'content-type': 'application/json' },
    body: JSON.stringify({}),
  })
  assert.equal(rollback.status, 200)
  const rolled = await rollback.json()
  assert.equal(rolled.version, 3)

  const active = await request('/api/v1/apps/demo/packages/shop/release')
  assert.equal(active.status, 200)
})
