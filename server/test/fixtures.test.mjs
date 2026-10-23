import assert from 'node:assert/strict'
import { createHash, createPublicKey, verify } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const fixtures = join(root, 'fixtures')
const publicKey = createPublicKey(readFileSync(join(root, 'server', 'keys', 'demo-public.pem')))

test('fixture release signature verifies', () => {
  const bytes = readFileSync(join(fixtures, 'release.json'))
  const signature = Buffer.from(readFileSync(join(fixtures, 'release.sig'), 'utf8').trim(), 'base64')
  assert.equal(verify('sha256', bytes, publicKey, signature), true)
})

test('tampered fixture release is rejected', () => {
  const bytes = Buffer.from(readFileSync(join(fixtures, 'release.json')))
  bytes[bytes.length - 2] ^= 1
  const signature = Buffer.from(readFileSync(join(fixtures, 'release.sig'), 'utf8').trim(), 'base64')
  assert.equal(verify('sha256', bytes, publicKey, signature), false)
})

test('release response carries exact bytes', () => {
  const bytes = readFileSync(join(fixtures, 'release.json'))
  const response = JSON.parse(readFileSync(join(fixtures, 'release-response.json'), 'utf8'))
  assert.deepEqual(Buffer.from(response.release, 'base64'), bytes)
  assert.equal(response.etag, `"${createHash('sha256').update(bytes).digest('hex')}"`)
})

test('archive matches release hash and size', () => {
  const archive = readFileSync(join(fixtures, 'site.zip'))
  const release = JSON.parse(readFileSync(join(fixtures, 'release.json'), 'utf8'))
  assert.equal(archive.length, release.archive.size)
  assert.equal(createHash('sha256').update(archive).digest('hex'), release.archive.sha256)
})

test('fixture files match their hashes', () => {
  const release = JSON.parse(readFileSync(join(fixtures, 'release.json'), 'utf8'))
  for (const file of release.files) {
    const bytes = readFileSync(join(fixtures, 'site', file.path))
    assert.equal(bytes.length, file.size)
    assert.equal(createHash('sha256').update(bytes).digest('hex'), file.sha256)
  }
})
