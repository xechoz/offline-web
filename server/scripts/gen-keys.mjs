import { generateKeyPairSync } from 'node:crypto'
import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = dirname(fileURLToPath(import.meta.url))
const keys = join(root, '..', 'keys')
mkdirSync(keys, { recursive: true })

const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
const privatePem = privateKey.export({ type: 'pkcs8', format: 'pem' })
const publicPem = publicKey.export({ type: 'spki', format: 'pem' })
const publicDerBase64 = publicKey.export({ type: 'spki', format: 'der' }).toString('base64')

writeFileSync(join(keys, 'demo-private.pem'), privatePem)
writeFileSync(join(keys, 'demo-public.pem'), publicPem)
writeFileSync(join(keys, 'demo-public.b64'), publicDerBase64)
console.log(publicDerBase64)
